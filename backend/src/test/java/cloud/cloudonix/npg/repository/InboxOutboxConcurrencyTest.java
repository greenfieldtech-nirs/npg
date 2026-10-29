package cloud.cloudonix.npg.repository;

import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.support.TestDatabase;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(VertxExtension.class)
class InboxOutboxConcurrencyTest {

    private static Pool pool;
    private static SqlClient sqlClient;
    private static InboxRepository inboxRepository;
    private static OutboxRepository outboxRepository;
    private static MessageRepository messageRepository;

    @BeforeAll
    static void setUpClass(Vertx vertx, VertxTestContext ctx) {
        TestDatabase.start();

        System.setProperty("DB_HOST", TestDatabase.host());
        System.setProperty("DB_PORT", String.valueOf(TestDatabase.port()));
        System.setProperty("DB_NAME", TestDatabase.database());
        System.setProperty("DB_USER", TestDatabase.user());
        System.setProperty("DB_PASSWORD", TestDatabase.password());

        TestDatabase.migrate();

        DatabaseConfig.configure(vertx)
            .onComplete(ctx.succeeding(p -> {
                pool = p;
                sqlClient = p;
                inboxRepository = new InboxRepository(p);
                outboxRepository = new OutboxRepository(p);
                messageRepository = new MessageRepository(p);
                ctx.completeNow();
            }));
    }

    @AfterAll
    static void tearDownClass(VertxTestContext ctx) {
        Future<Void> close = (pool != null) ? pool.close().mapEmpty() : Future.succeededFuture();
        close.onComplete(v -> {
            TestDatabase.stop();
            ctx.completeNow();
        });
    }

    @BeforeEach
    void cleanTables(VertxTestContext ctx) {
        sqlClient.query("SET FOREIGN_KEY_CHECKS = 0").execute()
            .compose(v -> sqlClient.query("TRUNCATE TABLE np_inbox").execute())
            .compose(v -> sqlClient.query("TRUNCATE TABLE np_outbox").execute())
            .compose(v -> sqlClient.query("TRUNCATE TABLE np_messages").execute())
            .compose(v -> sqlClient.query("SET FOREIGN_KEY_CHECKS = 1").execute())
            .onComplete(ctx.succeedingThenComplete());
    }

    @Test
    void inboxClaimIsAtomicUnderConcurrentWorkers(VertxTestContext ctx) {
        List<Future> inserts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            inserts.add(messageRepository.insertMessage(
                UUID.randomUUID().toString(), "Check", "INBOUND", null, null,
                "REQ-" + idx, "TX-" + idx, null, "key-" + idx, null, null, "4.0"
            ).compose(msgId -> inboxRepository.createInboxItem(msgId, null, "CHECK", LocalDateTime.now(ZoneOffset.UTC), 100, 3)));
        }

        CompositeFuture.all(inserts)
            .compose(v -> {
                List<Future> claims = new ArrayList<>();
                AtomicInteger totalClaimed = new AtomicInteger();
                for (int i = 0; i < 5; i++) {
                    claims.add(inboxRepository.claimBatch("worker-" + i, 10, 60)
                        .onSuccess(rows -> totalClaimed.addAndGet(rows.size())));
                }
                return CompositeFuture.all(claims).map(ignored -> totalClaimed.get());
            })
            .onComplete(ctx.succeeding(total -> ctx.verify(() -> {
                assertEquals(10, total);
                ctx.completeNow();
            })));
    }

    @Test
    void outboxClaimIsAtomicUnderConcurrentWorkers(VertxTestContext ctx) {
        List<Future> inserts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            inserts.add(messageRepository.insertMessage(
                UUID.randomUUID().toString(), "Check", "OUTBOUND", null, null,
                "REQ-" + idx, "TX-" + idx, null, "out-key-" + idx, null, null, "4.0"
            ).compose(msgId -> outboxRepository.createOutboxItem(msgId, null, 1L, "CHECK", LocalDateTime.now(ZoneOffset.UTC), 100, 3)));
        }

        CompositeFuture.all(inserts)
            .compose(v -> {
                List<Future> claims = new ArrayList<>();
                AtomicInteger totalClaimed = new AtomicInteger();
                for (int i = 0; i < 5; i++) {
                    claims.add(outboxRepository.claimBatch("worker-" + i, 10, 60)
                        .onSuccess(rows -> totalClaimed.addAndGet(rows.size())));
                }
                return CompositeFuture.all(claims).map(ignored -> totalClaimed.get());
            })
            .onComplete(ctx.succeeding(total -> ctx.verify(() -> {
                assertEquals(10, total);
                ctx.completeNow();
            })));
    }
}
