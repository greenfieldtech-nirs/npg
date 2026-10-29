package cloud.cloudonix.npg.workflow;

import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.support.TestDatabase;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
class WorkflowEngineTest {

    private static Pool pool;
    private static SqlClient sqlClient;
    private static WorkflowEngine engine;

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
                engine = new WorkflowEngine(p);
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
            .compose(v -> sqlClient.query("TRUNCATE TABLE np_workflow_events").execute())
            .compose(v -> sqlClient.query("TRUNCATE TABLE np_workflows").execute())
            .compose(v -> sqlClient.query("SET FOREIGN_KEY_CHECKS = 1").execute())
            .onComplete(ctx.succeedingThenComplete());
    }

    @Test
    void createWorkflowSetsInitialState(VertxTestContext ctx) {
        engine.createWorkflow("PORTING", "req-1", "0501234567", 1L, 2L, "NEW")
            .compose(id -> {
                assertNotNull(id);
                return sqlClient.preparedQuery("SELECT current_state FROM np_workflows WHERE id = ?")
                    .execute(Tuple.of(id))
                    .compose(rows -> {
                        assertEquals("NEW", rows.iterator().next().getString("current_state"));
                        return sqlClient.preparedQuery(
                                "SELECT event_type FROM np_workflow_events WHERE workflow_id = ?")
                            .execute(Tuple.of(id));
                    })
                    .compose(events -> {
                        assertTrue(events.iterator().hasNext());
                        assertEquals("WORKFLOW_CREATED", events.iterator().next().getString("event_type"));
                        return Future.succeededFuture();
                    });
            })
            .onComplete(ctx.succeedingThenComplete());
    }

    @Test
    void validTransitionSucceeds(VertxTestContext ctx) {
        engine.createWorkflow("PORTING", "req-2", "0501234567", 1L, 2L, "NEW")
            .compose(id -> engine.transition(id, "CHECK_SENT", "Check", null,
                    new JsonObject().put("messageType", "Check"), "test")
                .compose(v -> sqlClient.preparedQuery("SELECT current_state FROM np_workflows WHERE id = ?")
                    .execute(Tuple.of(id)))
                .compose(rows -> {
                    assertEquals("CHECK_SENT", rows.iterator().next().getString("current_state"));
                    return Future.succeededFuture();
                }))
            .onComplete(ctx.succeedingThenComplete());
    }

    @Test
    void illegalTransitionFails(VertxTestContext ctx) {
        engine.createWorkflow("PORTING", "req-3", "0501234567", 1L, 2L, "NEW")
            .compose(id -> engine.transition(id, "EXECUTION_SENT", "Execute", null,
                new JsonObject().put("messageType", "Execute"), "test"))
            .onComplete(ctx.failing(err -> ctx.verify(() -> {
                assertTrue(err.getMessage().contains("Illegal transition"));
                ctx.completeNow();
            })));
    }
}
