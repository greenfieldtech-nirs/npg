package cloud.cloudonix.npg.workflow;

import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.number.NumberOwnershipService;
import cloud.cloudonix.npg.support.TestDatabase;
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

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class PortingWorkflowTest {

    private static Pool pool;
    private static WorkflowEngine engine;
    private static NumberOwnershipService ownershipService;

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
                engine = new WorkflowEngine(p);
                ownershipService = new NumberOwnershipService(p);
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
            .compose(v -> pool.query("TRUNCATE TABLE np_numbers").execute())
            .compose(v -> pool.query("TRUNCATE TABLE np_number_ownership_history").execute())
            .compose(v -> pool.query("SET FOREIGN_KEY_CHECKS = 1").execute())
            .onComplete(ctx.succeedingThenComplete());
    }

    @Test
    void ownershipUpdateCreatesNumberAndHistory(VertxTestContext ctx) {
        ownershipService.updateCurrentOperator("972501234567", 2L, "PORT_APPROVED", 1L, 1L)
            .compose(v -> ownershipService.findCurrentOperator("972501234567"))
            .compose(rows -> {
                assertTrue(rows.iterator().hasNext());
                assertEquals(2L, rows.iterator().next().getLong("current_operator_id"));
                return pool.query("SELECT COUNT(*) AS c FROM np_number_ownership_history WHERE phone_number = '972501234567'")
                    .execute();
            })
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertEquals(1L, rows.iterator().next().getLong("c"));
                ctx.completeNow();
            })));
    }

    @Test
    void ownershipUpdateTracksPreviousOperator(VertxTestContext ctx) {
        ownershipService.updateCurrentOperator("972501234568", 2L, "PORT_APPROVED", 1L, 1L)
            .compose(v -> ownershipService.updateCurrentOperator("972501234568", 3L, "PORT_AGAIN", 2L, 2L))
            .compose(v -> ownershipService.findCurrentOperator("972501234568"))
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertTrue(rows.iterator().hasNext());
                var row = rows.iterator().next();
                assertEquals(3L, row.getLong("current_operator_id"));
                assertEquals(2L, row.getLong("previous_operator_id"));
                ctx.completeNow();
            })));
    }
}
