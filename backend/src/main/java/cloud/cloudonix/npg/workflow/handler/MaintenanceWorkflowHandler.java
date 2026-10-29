package cloud.cloudonix.npg.workflow.handler;

import cloud.cloudonix.npg.domain.Operator;
import cloud.cloudonix.npg.operator.OperatorStatusService;
import cloud.cloudonix.npg.protocol.ProtocolIds;
import cloud.cloudonix.npg.repository.AuditEventRepository;
import cloud.cloudonix.npg.workflow.BusinessRuleException;
import cloud.cloudonix.npg.workflow.InboundMessage;
import cloud.cloudonix.npg.workflow.StateMachines;
import cloud.cloudonix.npg.workflow.Workflow;
import cloud.cloudonix.npg.workflow.WorkflowEngine;
import cloud.cloudonix.npg.workflow.WorkflowServices;
import cloud.cloudonix.npg.workflow.WorkflowSupport;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;

import java.util.List;

/**
 * Planned maintenance (Down_system / Up_system; no business responses). A peer going down is
 * marked DOWN — the dispatcher then holds its messages — and an alert opens if it stays down past
 * MAINTENANCE_WINDOW_TIMEOUT. The NOC can announce our own maintenance to every peer.
 */
public class MaintenanceWorkflowHandler implements WorkflowHandler {

    static final String PROCESS = "MAINTENANCE";

    private final WorkflowServices services;
    private final WorkflowSupport support;
    private final WorkflowEngine engine;

    public MaintenanceWorkflowHandler(WorkflowServices services) {
        this.services = services;
        this.support = new WorkflowSupport(services);
        this.engine = services.engine();
    }

    @Override
    public Future<Void> handle(SqlConnection tx, Row inboxItem) {
        return InboundMessage.load(tx, services.xmlAudit(), services.catalog(), inboxItem)
            .compose(message -> switch (message.messageType()) {
                case "Down_system" -> onDown(tx, message);
                case "Up_system" -> onUp(tx, message);
                default -> Future.failedFuture("Not a maintenance message: " + message.messageType());
            });
    }

    private Future<Void> onDown(SqlClient tx, InboundMessage down) {
        String reason = down.body().getString("reason", "Down_system received");
        return OperatorStatusService.setAvailability(tx, down.senderOperatorId(), "DOWN", reason)
            .compose(v -> engine.create(tx, new WorkflowEngine.NewWorkflow(StateMachines.MAINTENANCE, StateMachines.OBSERVER,
                down.senderOperatorId(), down.requestId(), null, null, null, null, null,
                new JsonObject().put("reason", reason).put("expectedDuration", down.body().getString("expectedDuration")), null), "system"))
            .compose(wf -> services.timers().start(tx, wf, "MAINTENANCE_WINDOW_TIMEOUT", "MAINTENANCE_OVERDUE"))
            .mapEmpty();
    }

    private Future<Void> onUp(SqlClient tx, InboundMessage up) {
        return OperatorStatusService.setAvailability(tx, up.senderOperatorId(), "UP", up.body().getString("reason", "Up_system received"))
            .compose(v -> tx.preparedQuery("""
                    SELECT id FROM np_workflows WHERE workflow_type = 'MAINTENANCE' AND local_role = 'OBSERVER'
                      AND counterparty_operator_id = ? AND current_state IN ('OPERATOR_MARKED_DOWN', 'MANUAL_REVIEW')
                    ORDER BY id DESC FOR UPDATE
                    """).execute(Tuple.of(up.senderOperatorId())))
            .compose(rows -> {
                Future<Void> chain = Future.succeededFuture();
                for (Row row : rows) {
                    chain = chain.compose(v -> engine.lock(tx, row.getLong("id"))
                        .compose(wf -> services.timers().cancelAll(tx, wf.id())
                            .compose(c -> StateMachines.MANUAL_REVIEW.equals(wf.state())
                                ? engine.manualTransition(tx, wf, "OPERATOR_MARKED_UP", "Up_system received", "system", null)
                                : engine.transition(tx, wf, "OPERATOR_MARKED_UP", "OPERATOR_MARKED_UP", up.messageId(), null, "system")))
                        .mapEmpty());
                }
                return chain;
            });
    }

    /** NOC: announce our planned downtime (or recovery) to every peer. */
    public Future<JsonObject> announce(boolean down, String reason, String expectedDuration, String actor, String remoteIp) {
        if (reason == null || reason.isBlank()) {
            return Future.failedFuture(BusinessRuleException.badRequest("A reason is required"));
        }
        return services.pool().withTransaction(tx -> services.operators().local(tx).compose(local -> down
            ? ProtocolIds.nextRequestId(tx, local.operatorCode(), local.operatorCode())
                .compose(requestId -> engine.create(tx, new WorkflowEngine.NewWorkflow(StateMachines.MAINTENANCE,
                    StateMachines.ANNOUNCER, null, requestId, null, null, null, null, null,
                    new JsonObject().put("reason", reason), null), actor))
                .compose(wf -> broadcast(tx, wf, local, "Down_system", new JsonObject().put("reason", reason)
                    .put("expectedDuration", expectedDuration)).map(wf))
            : openAnnouncement(tx).compose(wf -> broadcast(tx, wf, local, "Up_system", new JsonObject().put("reason", reason))
                .compose(v -> engine.transition(tx, wf, "UP_ANNOUNCED", "UP_ANNOUNCED", null, null, actor))))
            .compose(wf -> new AuditEventRepository(tx).insertAuditEvent("USER", actor, down ? "MAINTENANCE_DOWN_ANNOUNCED"
                    : "MAINTENANCE_UP_ANNOUNCED", "WORKFLOW", wf.uuid(), new JsonObject().put("reason", reason).encode(), remoteIp)
                .map(v -> new JsonObject().put("workflowUuid", wf.uuid()))));
    }

    private Future<Workflow> openAnnouncement(SqlClient tx) {
        return tx.query("SELECT id FROM np_workflows WHERE workflow_type = 'MAINTENANCE' AND local_role = 'ANNOUNCER' "
                + "AND current_state = 'DOWN_ANNOUNCED' ORDER BY id DESC LIMIT 1").execute()
            .compose(rows -> rows.iterator().hasNext()
                ? engine.lock(tx, rows.iterator().next().getLong("id"))
                : Future.failedFuture(BusinessRuleException.conflict("No Down_system announcement is open")));
    }

    private Future<Void> broadcast(SqlClient tx, Workflow wf, Operator local, String type, JsonObject body) {
        return services.operators().peersExcept(tx, local.id()).compose((List<Operator> peers) -> {
            Future<Void> chain = Future.succeededFuture();
            for (Operator peer : peers) {
                chain = chain.compose(v -> support.send(tx, wf, type, PROCESS, peer, null, body).mapEmpty());
            }
            return chain;
        });
    }
}
