package cloud.cloudonix.npg.chaos;

import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.repository.InboxRepository;
import cloud.cloudonix.npg.repository.MessageRepository;
import cloud.cloudonix.npg.support.TestDatabase;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
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
class QueueChaosTest {

    private static Pool pool;
    private static InboxRepository inboxRepository;
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
                inboxRepository = new InboxRepository(p);
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
        pool.query("SET FOREIGN_KEY_CHECKS = 0").execute()
            .compose(v -> pool.query("TRUNCATE TABLE np_inbox").execute())
            .compose(v -> pool.query("TRUNCATE TABLE np_messages").execute())
            .compose(v -> pool.query("SET FOREIGN_KEY_CHECKS = 1").execute())
            .onComplete(ctx.succeedingThenComplete());
    }

    private Future<?> insertInboxItems(int count) {
        List<Future> inserts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final int idx = i;
            inserts.add(messageRepository.insertMessage(
                UUID.randomUUID().toString(), "Check", "INBOUND", null, null,
                "REQ-" + idx, "TX-" + idx, null, "chaos-key-" + idx, null, null, "4.0"
            ).compose(msgId -> inboxRepository.createInboxItem(msgId, null, "CHECK", LocalDateTime.now(ZoneOffset.UTC), 100, 3)));
        }
        return CompositeFuture.all(inserts);
    }

    @Test
    void staleLockRecoveryAllowsReclaim(VertxTestContext ctx) {
        // ponytail: lockSeconds = -1 makes locked_until already in the past, simulating a crashed
        // worker whose lock expired; recovery worker must reclaim all 5 items.
        insertInboxItems(5)
            .compose(v -> inboxRepository.claimBatch("worker-crash", 10, -1))
            .compose(claimed -> {
                assertEquals(5, claimed.size());
                return inboxRepository.claimBatch("worker-recovery", 10, 60);
            })
            .onComplete(ctx.succeeding(recovered -> ctx.verify(() -> {
                assertEquals(5, recovered.size());
                ctx.completeNow();
            })));
    }

    @Test
    void duplicateClaimsDoNotDuplicate(VertxTestContext ctx) {
        insertInboxItems(10)
            .compose(v -> {
                List<Future> claims = new ArrayList<>();
                AtomicInteger totalClaimed = new AtomicInteger();
                for (int i = 0; i < 5; i++) {
                    claims.add(inboxRepository.claimBatch("chaos-worker-" + i, 10, 60)
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
