package cloud.cloudonix.npg.timer;

import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.support.TestDatabase;
import cloud.cloudonix.npg.workflow.WorkflowEngine;
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

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(VertxExtension.class)
class TimerServiceTest {

    private static Pool pool;
    private static SqlClient sqlClient;
    private static TimerService timerService;
    private static WorkflowEngine workflowEngine;
    private static TimerActionDispatcher dispatcher;
    private static TimerProcessor processor;

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
                timerService = new TimerService(p);
                workflowEngine = new WorkflowEngine();
                cloud.cloudonix.npg.protocol.ProtocolCatalogLoader catalog =
                    new cloud.cloudonix.npg.protocol.ProtocolCatalogLoader(vertx, "../docs/protocol");
                cloud.cloudonix.npg.workflow.WorkflowServices services = new cloud.cloudonix.npg.workflow.WorkflowServices(
                    p, workflowEngine, new TimerScheduler(catalog), null, null, catalog, null,
                    new cloud.cloudonix.npg.workflow.OperatorDirectory());
                dispatcher = new TimerActionDispatcher(workflowEngine,
                    new cloud.cloudonix.npg.workflow.handler.WorkflowHandlerRegistry(services));
                processor = new TimerProcessor(pool, dispatcher::dispatch);
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
            .compose(v -> sqlClient.query("TRUNCATE TABLE np_timers").execute())
            .compose(v -> sqlClient.query("TRUNCATE TABLE np_workflow_events").execute())
            .compose(v -> sqlClient.query("TRUNCATE TABLE np_manual_tasks").execute())
            .compose(v -> sqlClient.query("TRUNCATE TABLE np_workflows").execute())
            .compose(v -> sqlClient.query("SET FOREIGN_KEY_CHECKS = 1").execute())
            .onComplete(ctx.succeedingThenComplete());
    }

    private Future<Long> createWorkflow() {
        return pool.withTransaction(tx -> workflowEngine.create(tx, new WorkflowEngine.NewWorkflow("PORTING", "RECIPIENT", null,
                "req-timer-" + java.util.UUID.randomUUID(), "0501234567", 1L, 2L, null, null, null, null), "test"))
            .map(cloud.cloudonix.npg.workflow.Workflow::id);
    }

    @Test
    void dueTimerIsClaimed(VertxTestContext ctx) {
        createWorkflow()
            .compose(wfId -> timerService.createTimer(wfId, null, "T1", "KD_CHECK",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1), new JsonObject()))
            .compose(id -> timerService.claimDueTimers("worker-1", 10, 60))
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertEquals(1, rows.size());
                ctx.completeNow();
            })));
    }

    @Test
    void futureTimerIsNotClaimed(VertxTestContext ctx) {
        createWorkflow()
            .compose(wfId -> timerService.createTimer(wfId, null, "T1", "KD_CHECK",
                LocalDateTime.now(ZoneOffset.UTC).plusHours(1), new JsonObject()))
            .compose(id -> timerService.claimDueTimers("worker-1", 10, 60))
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertEquals(0, rows.size());
                ctx.completeNow();
            })));
    }

    @Test
    void timerFireIsIdempotent(VertxTestContext ctx) {
        createWorkflow()
            .compose(wfId -> timerService.createTimer(wfId, null, "T1", "KD_CHECK",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1), new JsonObject().put("expectedState", "NEW")))
            .compose(id -> timerService.claimDueTimers("worker-1", 10, 60)
                .compose(rows -> processor.fireOne(rows.iterator().next()))
                .compose(v -> sqlClient.preparedQuery("SELECT status FROM np_timers WHERE id = ?")
                    .execute(Tuple.of(id)))
                .compose(rows -> {
                    assertEquals("SUCCEEDED", rows.iterator().next().getString("status"));
                    // Second dispatch of the same row must no-op safely (recover path).
                    return sqlClient.preparedQuery("SELECT * FROM np_timers WHERE id = ?")
                        .execute(Tuple.of(id))
                        .compose(fresh -> processor.fireOne(fresh.iterator().next()))
                        .compose(v -> sqlClient.preparedQuery("SELECT status FROM np_timers WHERE id = ?")
                            .execute(Tuple.of(id)))
                        .compose(r2 -> {
                            assertEquals("SUCCEEDED", r2.iterator().next().getString("status"));
                            return Future.succeededFuture();
                        });
                }))
            .onComplete(ctx.succeedingThenComplete());
    }
}
