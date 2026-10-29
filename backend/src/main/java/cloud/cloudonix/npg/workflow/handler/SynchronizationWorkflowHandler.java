package cloud.cloudonix.npg.workflow.handler;

import cloud.cloudonix.npg.protocol.ProtocolIds;
import cloud.cloudonix.npg.repository.AuditEventRepository;
import cloud.cloudonix.npg.workflow.BusinessRuleException;
import cloud.cloudonix.npg.workflow.InboundMessage;
import cloud.cloudonix.npg.workflow.ProtocolBodies;
import cloud.cloudonix.npg.workflow.StateMachines;
import cloud.cloudonix.npg.workflow.WorkflowEngine;
import cloud.cloudonix.npg.workflow.WorkflowServices;
import cloud.cloudonix.npg.workflow.WorkflowSupport;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;

/**
 * Database synchronization. On request we export our porting view (numbers we know the serving
 * operator of) as a snapshot in MongoDB and answer with its reference; the transfer channel for
 * the file itself (FTP/SFTP?) is an open question (docs/protocol/open-questions.md #10). The NOC
 * can request a peer's snapshot the same way.
 */
public class SynchronizationWorkflowHandler implements WorkflowHandler {

    static final String PROCESS = "SYNC";
    static final String EXPORTS = "sync_exports";

    private final WorkflowServices services;
    private final WorkflowSupport support;
    private final WorkflowEngine engine;

    public SynchronizationWorkflowHandler(WorkflowServices services) {
        this.services = services;
        this.support = new WorkflowSupport(services);
        this.engine = services.engine();
    }

    /** NOC: ask a peer for a synchronization snapshot. */
    public Future<JsonObject> requestSync(String operatorCode, String syncType, String actor, String remoteIp) {
        return services.pool().withTransaction(tx -> services.operators().local(tx)
            .compose(local -> services.operators().byCode(tx, operatorCode).compose(peer -> {
                if (peer == null || peer.id().equals(local.id())) {
                    return Future.failedFuture(BusinessRuleException.badRequest("Unknown operator: " + operatorCode));
                }
                return ProtocolIds.nextRequestId(tx, local.operatorCode(), peer.operatorCode())
                    .compose(requestId -> engine.create(tx, new WorkflowEngine.NewWorkflow(StateMachines.DATA_SYNCHRONIZATION,
                        StateMachines.REQUESTER, peer.id(), requestId, null, null, null, null, null,
                        new JsonObject().put("syncType", syncType != null ? syncType : "FULL"), null), actor))
                    .compose(wf -> support.send(tx, wf, "DB_synch_request", PROCESS, peer, null,
                            new JsonObject().put("syncType", syncType != null ? syncType : "FULL"))
                        .compose(messageId -> services.timers().start(tx, wf, "SYNCH_RESPONSE_TIMEOUT", "RESPONSE_TIMEOUT"))
                        .compose(v -> new AuditEventRepository(tx).insertAuditEvent("USER", actor, "SYNC_REQUESTED", "WORKFLOW",
                            wf.uuid(), new JsonObject().put("operator", operatorCode).encode(), remoteIp))
                        .map(v -> new JsonObject().put("workflowUuid", wf.uuid()).put("requestId", wf.requestId())));
            })));
    }

    @Override
    public Future<Void> handle(SqlConnection tx, Row inboxItem) {
        return InboundMessage.load(tx, services.xmlAudit(), services.catalog(), inboxItem)
            .compose(message -> switch (message.messageType()) {
                case "DB_synch_request" -> answer(tx, message);
                case "DB_synch_response" -> onAnswer(tx, message);
                default -> Future.failedFuture("Not a synchronization message: " + message.messageType());
            });
    }

    private Future<Void> answer(SqlClient tx, InboundMessage request) {
        return engine.create(tx, new WorkflowEngine.NewWorkflow(StateMachines.DATA_SYNCHRONIZATION, StateMachines.RESPONDER,
                request.senderOperatorId(), request.requestId(), null, null, null, null, null, new JsonObject(), null), "system")
            .compose(wf -> tx.query("""
                    SELECT n.phone_number, n.number_type, c.operator_code AS current_operator, o.operator_code AS original_operator,
                           n.status, n.last_ported_at
                    FROM np_numbers n LEFT JOIN np_operators c ON c.id = n.current_operator_id
                    LEFT JOIN np_operators o ON o.id = n.original_operator_id ORDER BY n.phone_number
                    """).execute()
                .compose(rows -> {
                    JsonArray records = new JsonArray();
                    for (Row row : rows) {
                        records.add(new JsonObject().put("phoneNumber", row.getString("phone_number"))
                            .put("numberType", row.getString("number_type")).put("currentOperator", row.getString("current_operator"))
                            .put("originalOperator", row.getString("original_operator")).put("status", row.getString("status")));
                    }
                    JsonObject snapshot = new JsonObject().put("requestId", request.requestId()).put("records", records)
                        .put("createdAt", java.time.Instant.now().toString());
                    return services.xmlAudit().mongoClient().insert(EXPORTS, snapshot).map(id -> new Object[]{id, records.size()});
                })
                .compose(export -> support.respond(tx, wf, request, "DB_synch_response", PROCESS,
                        ProtocolBodies.approve(request.trxNo(), new JsonObject()
                            .put("fileReference", "npg-sync:" + export[0]).put("recordCount", export[1])))
                    .compose(messageId -> engine.transition(tx, wf, "SYNC_ANSWERED", "SYNC_ANSWERED", request.messageId(),
                        new JsonObject().put("fileReference", "npg-sync:" + export[0]).put("recordCount", export[1]), "system"))))
            .mapEmpty();
    }

    private Future<Void> onAnswer(SqlClient tx, InboundMessage response) {
        return support.workflowForResponse(tx, response, "DB_synch_request").compose(wf -> {
            if (!"SYNC_REQUESTED".equals(wf.state())) {
                return engine.recordEvent(tx, wf, "LATE_RESPONSE_IGNORED", response.messageId(), null, "system");
            }
            String target = response.approved() ? "SYNC_COMPLETED" : "SYNC_REJECTED";
            JsonObject details = response.approved()
                ? new JsonObject().put("fileReference", response.body().getString("fileReference"))
                : WorkflowSupport.rejectionOf(response);
            return services.timers().cancel(tx, wf.id(), "SYNCH_RESPONSE_TIMEOUT")
                .compose(v -> engine.mergeContext(tx, wf, new JsonObject().put("result", details)))
                .compose(updated -> engine.transition(tx, updated, target, target, response.messageId(), details, "system"))
                .mapEmpty();
        });
    }
}
