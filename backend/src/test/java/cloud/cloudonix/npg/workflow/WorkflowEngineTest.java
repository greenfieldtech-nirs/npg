package cloud.cloudonix.npg.workflow;

import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.protocol.ProtocolCatalogLoader;
import cloud.cloudonix.npg.support.TestDatabase;
import cloud.cloudonix.npg.timer.TimerActionDispatcher;
import cloud.cloudonix.npg.timer.TimerScheduler;
import cloud.cloudonix.npg.timer.TimerService;
import cloud.cloudonix.npg.workflow.handler.WorkflowHandlerRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class WorkflowEngineTest {

    private static Pool pool;
    private static final WorkflowEngine engine = new WorkflowEngine();
    private static TimerScheduler scheduler;
    private static TimerActionDispatcher timerDispatcher;

    @BeforeAll
    static void setUpClass(Vertx vertx, VertxTestContext ctx) {
        TestDatabase.start();
        System.setProperty("DB_HOST", TestDatabase.host());
        System.setProperty("DB_PORT", String.valueOf(TestDatabase.port()));
        System.setProperty("DB_NAME", TestDatabase.database());
        System.setProperty("DB_USER", TestDatabase.user());
        System.setProperty("DB_PASSWORD", TestDatabase.password());
        TestDatabase.migrate();
        DatabaseConfig.configure(vertx).onComplete(ctx.succeeding(p -> {
            pool = p;
            ProtocolCatalogLoader catalog = new ProtocolCatalogLoader(vertx, "../docs/protocol");
            scheduler = new TimerScheduler(catalog);
            WorkflowServices services = new WorkflowServices(p, engine, scheduler, null, null, catalog, null,
                new OperatorDirectory());
            timerDispatcher = new TimerActionDispatcher(engine, new WorkflowHandlerRegistry(services));
            ctx.completeNow();
        }));
    }

    @AfterAll
    static void tearDownClass(VertxTestContext ctx) {
        Future<Void> close = pool != null ? pool.close().mapEmpty() : Future.succeededFuture();
        close.onComplete(v -> {
            TestDatabase.stop();
            ctx.completeNow();
        });
    }

    @BeforeEach
    void clean(VertxTestContext ctx) {
        pool.query("DELETE FROM np_manual_tasks").execute()
            .compose(v -> pool.query("DELETE FROM np_timers").execute())
            .compose(v -> pool.query("DELETE FROM np_workflow_events").execute())
            .compose(v -> pool.query("DELETE FROM np_audit_events").execute())
            .compose(v -> pool.query("DELETE FROM np_workflows").execute())
            .onComplete(ctx.succeedingThenComplete());
    }

    // ---------------------------------------------------------------- state machines

    @Test
    void everyMachineIsConsistent() {
        for (StateMachines.Machine m : StateMachines.all()) {
            assertTrue(m.states().contains(m.initial()), m.type() + "/" + m.role());
            for (String terminal : m.states()) {
                if (m.isTerminal(terminal)) {
                    assertFalse(m.allows(terminal, StateMachines.MANUAL_REVIEW), "terminal " + terminal + " cannot escalate");
                }
            }
            assertFalse(m.allows(StateMachines.MANUAL_REVIEW, m.initial()), "only manual transitions leave MANUAL_REVIEW");
        }
    }

    @Test
    void recipientAndDonorAreDifferentMachines() {
        StateMachines.Machine recipient = StateMachines.of("PORTING", "RECIPIENT");
        StateMachines.Machine donor = StateMachines.of("PORTING", "DONOR");
        assertTrue(recipient.allows("PORT_SCHEDULED", "EXECUTION_SENT"));
        assertFalse(donor.allows("PORT_SCHEDULED", "EXECUTION_SENT"));
        assertTrue(donor.allows("PORT_SCHEDULED", "COMPLETED"));
        assertEquals("CHECK_RECEIVED", donor.initial());
        assertTrue(recipient.allows("CHECK_SENT", "MANUAL_REVIEW"));
        assertTrue(recipient.allows("CHECK_SENT", "FAILED"));
    }

    // ---------------------------------------------------------------- engine

    @Test
    void createStartsInTheInitialStateWithAnEvent(VertxTestContext ctx) {
        pool.withTransaction(tx -> create(tx, "PORTING", "RECIPIENT"))
            .compose(wf -> {
                ctx.verify(() -> {
                    assertEquals("NEW", wf.state());
                    assertEquals("RECIPIENT", wf.role());
                    assertEquals("FIXED", wf.context().getString("numberType"));
                });
                return pool.preparedQuery("SELECT event_type, to_state FROM np_workflow_events WHERE workflow_id = ?")
                    .execute(Tuple.of(wf.id()));
            })
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                Row event = rows.iterator().next();
                assertEquals("WORKFLOW_CREATED", event.getString("event_type"));
                assertEquals("NEW", event.getString("to_state"));
                ctx.completeNow();
            })));
    }

    @Test
    void legalTransitionUpdatesStateAndHistory(VertxTestContext ctx) {
        pool.withTransaction(tx -> create(tx, "PORTING", "RECIPIENT")
                .compose(wf -> engine.transition(tx, wf, "CHECK_SENT", "CHECK_SENT", null, null, "test")))
            .compose(wf -> pool.preparedQuery("SELECT current_state FROM np_workflows WHERE id = ?").execute(Tuple.of(wf.id())))
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertEquals("CHECK_SENT", rows.iterator().next().getString("current_state"));
                ctx.completeNow();
            })));
    }

    @Test
    void illegalTransitionIsRejectedAndRolledBack(VertxTestContext ctx) {
        String requestId = "REQ-" + UUID.randomUUID();
        pool.withTransaction(tx -> engine.create(tx, spec(requestId, "PORTING", "RECIPIENT"), "test")
                .compose(wf -> engine.transition(tx, wf, "EXECUTION_SENT", "X", null, null, "test")))
            .onComplete(ctx.failing(err -> ctx.verify(() -> {
                assertInstanceOf(WorkflowEngine.IllegalTransitionException.class, err);
                pool.preparedQuery("SELECT COUNT(*) AS c FROM np_workflows WHERE request_id = ?").execute(Tuple.of(requestId))
                    .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                        assertEquals(0L, rows.iterator().next().getLong("c"), "the whole transaction rolled back");
                        ctx.completeNow();
                    })));
            })));
    }

    @Test
    void manualTransitionNeedsAReasonAndAKnownState(VertxTestContext ctx) {
        pool.withTransaction(tx -> create(tx, "PORTING", "RECIPIENT"))
            .compose(wf -> pool.withTransaction(tx -> engine.manualTransition(tx, wf, "CHECK_SENT", " ", "noc", null))
                .transform(ar -> {
                    ctx.verify(() -> assertTrue(ar.failed(), "blank reason must fail"));
                    return pool.withTransaction(tx -> engine.manualTransition(tx, wf, "NOT_A_STATE", "typo", "noc", null));
                })
                .transform(ar -> {
                    ctx.verify(() -> assertInstanceOf(WorkflowEngine.IllegalTransitionException.class, ar.cause()));
                    return pool.withTransaction(tx -> engine.manualTransition(tx, wf, "CANCELLED", "customer withdrew", "noc", "10.0.0.9"));
                }))
            .compose(wf -> pool.preparedQuery("""
                    SELECT w.current_state, w.cancelled_at IS NOT NULL AS stamped,
                           (SELECT COUNT(*) FROM np_audit_events a WHERE a.entity_id = w.workflow_uuid
                              AND a.action = 'WORKFLOW_MANUAL_TRANSITION' AND a.actor_id = 'noc') AS audits
                    FROM np_workflows w WHERE w.id = ?""").execute(Tuple.of(wf.id())))
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                Row row = rows.iterator().next();
                assertEquals("CANCELLED", row.getString("current_state"));
                assertEquals(1, row.getInteger("stamped"));
                assertEquals(1L, row.getLong("audits"));
                ctx.completeNow();
            })));
    }

    @Test
    void contextMergesAndColumnsAreWhitelisted(VertxTestContext ctx) {
        pool.withTransaction(tx -> create(tx, "PORTING", "RECIPIENT")
                .compose(wf -> engine.mergeContext(tx, wf, new JsonObject().put("identification", "123")))
                .compose(wf -> engine.update(tx, wf, java.util.Map.of("current_state", "COMPLETED"))
                    .transform(ar -> {
                        ctx.verify(() -> assertTrue(ar.failed(), "state may only change through transitions"));
                        return engine.lock(tx, wf.id());
                    })))
            .onComplete(ctx.succeeding(wf -> ctx.verify(() -> {
                assertEquals("123", wf.context().getString("identification"));
                assertEquals("FIXED", wf.context().getString("numberType"));
                assertEquals("NEW", wf.state());
                ctx.completeNow();
            })));
    }

    // ---------------------------------------------------------------- timers

    @Test
    void scheduledTimerRecordsTheGuardedStateAndCanBeCancelled(VertxTestContext ctx) {
        pool.withTransaction(tx -> create(tx, "PORTING", "RECIPIENT")
                .compose(wf -> engine.transition(tx, wf, "CHECK_SENT", "CHECK_SENT", null, null, "test"))
                .compose(wf -> scheduler.start(tx, wf, "CHECK_RESPONSE_TIMEOUT", "RESPONSE_TIMEOUT").map(wf)))
            .compose(wf -> pool.preparedQuery("SELECT action_json, due_at > UTC_TIMESTAMP(6) AS later FROM np_timers WHERE workflow_id = ?")
                .execute(Tuple.of(wf.id()))
                .compose(rows -> {
                    ctx.verify(() -> {
                        Row row = rows.iterator().next();
                        assertEquals(1, row.getInteger("later"));
                        assertEquals("CHECK_SENT", new JsonObject(row.getValue("action_json").toString()).getString("expectedState"));
                    });
                    return pool.withTransaction(tx -> scheduler.cancel(tx, wf.id(), "CHECK_RESPONSE_TIMEOUT"));
                })
                .compose(v -> pool.preparedQuery("SELECT status FROM np_timers WHERE workflow_id = ?").execute(Tuple.of(wf.id()))))
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertEquals("CANCELLED", rows.iterator().next().getString("status"));
                ctx.completeNow();
            })));
    }

    @Test
    void expiredResponseTimerEscalatesToManualReviewWithTask(VertxTestContext ctx) {
        fireTimerFor("CHECK_SENT", "CHECK_SENT")
            .compose(wfId -> pool.preparedQuery("""
                    SELECT w.current_state, (SELECT COUNT(*) FROM np_manual_tasks t WHERE t.workflow_id = w.id
                      AND t.task_type = 'RESPONSE_TIMEOUT') AS tasks FROM np_workflows w WHERE w.id = ?""")
                .execute(Tuple.of(wfId)))
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertEquals("MANUAL_REVIEW", rows.iterator().next().getString("current_state"));
                assertEquals(1L, rows.iterator().next().getLong("tasks"));
                ctx.completeNow();
            })));
    }

    @Test
    void timerForAStateTheWorkflowAlreadyLeftIsANoOp(VertxTestContext ctx) {
        // guarded CHECK_SENT, but the response already moved the workflow to CHECK_APPROVED
        fireTimerFor("CHECK_SENT", "CHECK_APPROVED")
            .compose(wfId -> pool.preparedQuery("""
                    SELECT w.current_state, (SELECT COUNT(*) FROM np_manual_tasks t WHERE t.workflow_id = w.id) AS tasks
                    FROM np_workflows w WHERE w.id = ?""").execute(Tuple.of(wfId)))
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertEquals("CHECK_APPROVED", rows.iterator().next().getString("current_state"));
                assertEquals(0L, rows.iterator().next().getLong("tasks"));
                ctx.completeNow();
            })));
    }

    /** Workflow guarded at {@code guarded}, moved to {@code actual}, timer fired; returns the workflow id. */
    private Future<Long> fireTimerFor(String guarded, String actual) {
        return pool.withTransaction(tx -> create(tx, "PORTING", "RECIPIENT")
                .compose(wf -> engine.transition(tx, wf, "CHECK_SENT", "CHECK_SENT", null, null, "test"))
                .compose(wf -> scheduler.startAt(tx, wf, "CHECK_RESPONSE_TIMEOUT", "RESPONSE_TIMEOUT",
                    java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(1)).map(wf))
                .compose(wf -> guarded.equals(actual) ? Future.succeededFuture(wf)
                    : engine.transition(tx, wf, actual, actual, null, null, "test")))
            .compose(wf -> new TimerService(pool).claimDueTimers("w", 10, 60)
                .compose(rows -> pool.withTransaction(tx -> timerDispatcher.dispatch(tx, rows.iterator().next())))
                .map(wf.id()));
    }

    private Future<Workflow> create(io.vertx.sqlclient.SqlClient tx, String type, String role) {
        return engine.create(tx, spec("REQ-" + UUID.randomUUID(), type, role), "test");
    }

    private static WorkflowEngine.NewWorkflow spec(String requestId, String type, String role) {
        return new WorkflowEngine.NewWorkflow(type, role, null, requestId, "0501234567", null, null, null, null,
            new JsonObject().put("numberType", "FIXED"), null);
    }
}
