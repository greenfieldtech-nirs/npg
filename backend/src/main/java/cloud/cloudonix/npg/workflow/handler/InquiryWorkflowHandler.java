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
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;

/**
 * Number ownership inquiry: ask a peer who serves a number (NOC action), or answer a peer from
 * our routing view. The answer carries the serving operator in {@code currentOperator} (element
 * name assumed; see docs/protocol/open-questions.md).
 */
public class InquiryWorkflowHandler implements WorkflowHandler {

    static final String PROCESS = "INQUIRY";

    private final WorkflowServices services;
    private final WorkflowSupport support;
    private final WorkflowEngine engine;

    public InquiryWorkflowHandler(WorkflowServices services) {
        this.services = services;
        this.support = new WorkflowSupport(services);
        this.engine = services.engine();
    }

    /** NOC: ask {@code operatorCode} who serves {@code phoneNumber}. */
    public Future<JsonObject> inquire(String phoneNumber, String operatorCode, String actor, String remoteIp) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return Future.failedFuture(BusinessRuleException.badRequest("phoneNumber is required"));
        }
        return services.pool().withTransaction(tx -> services.operators().local(tx)
            .compose(local -> services.operators().byCode(tx, operatorCode).compose(peer -> {
                if (peer == null || peer.id().equals(local.id())) {
                    return Future.failedFuture(BusinessRuleException.badRequest("Unknown operator: " + operatorCode));
                }
                return ProtocolIds.nextRequestId(tx, local.operatorCode(), peer.operatorCode())
                    .compose(requestId -> engine.create(tx, new WorkflowEngine.NewWorkflow(StateMachines.NUMBER_INQUIRY,
                        StateMachines.REQUESTER, peer.id(), requestId, phoneNumber, null, null, null, null, new JsonObject(), null), actor))
                    .compose(wf -> support.send(tx, wf, "Inquire_number", PROCESS, peer, null,
                            ProtocolBodies.numberBlock(null, phoneNumber, null))
                        .compose(messageId -> engine.recordEvent(tx, wf, "INQUIRY_SENT", messageId, null, actor))
                        .compose(v -> services.timers().start(tx, wf, "INQUIRY_RESPONSE_TIMEOUT", "RESPONSE_TIMEOUT"))
                        .compose(v -> new AuditEventRepository(tx).insertAuditEvent("USER", actor, "NUMBER_INQUIRY_SENT", "WORKFLOW",
                            wf.uuid(), new JsonObject().put("phoneNumber", phoneNumber).put("operator", operatorCode).encode(), remoteIp))
                        .map(v -> new JsonObject().put("workflowUuid", wf.uuid()).put("requestId", wf.requestId())));
            })));
    }

    @Override
    public Future<Void> handle(SqlConnection tx, Row inboxItem) {
        return InboundMessage.load(tx, services.xmlAudit(), services.catalog(), inboxItem)
            .compose(message -> switch (message.messageType()) {
                case "Inquire_number" -> answer(tx, message);
                case "Inquire_number_response" -> onAnswer(tx, message);
                default -> Future.failedFuture("Not an inquiry message: " + message.messageType());
            });
    }

    private Future<Void> answer(SqlClient tx, InboundMessage inquiry) {
        String phone = inquiry.body().getString("phoneNumber");
        return engine.create(tx, new WorkflowEngine.NewWorkflow(StateMachines.NUMBER_INQUIRY, StateMachines.RESPONDER,
                inquiry.senderOperatorId(), inquiry.requestId(), phone, null, null, null, null, new JsonObject(), null), "system")
            .compose(wf -> tx.preparedQuery("""
                    SELECT o.operator_code FROM np_numbers n JOIN np_operators o ON o.id = n.current_operator_id
                    WHERE n.phone_number = ?
                    """).execute(Tuple.of(phone))
                .compose(rows -> {
                    JsonObject body = rows.iterator().hasNext()
                        ? ProtocolBodies.approve(inquiry.trxNo(),
                            new JsonObject().put("currentOperator", rows.iterator().next().getString("operator_code")))
                        : ProtocolBodies.reject(inquiry.trxNo(), "REJECTED_NUMBER_NOT_FOUND", "No porting record for this number");
                    return support.respond(tx, wf, inquiry, "Inquire_number_response", PROCESS, body)
                        .compose(messageId -> engine.transition(tx, wf, "INQUIRY_ANSWERED", "INQUIRY_ANSWERED", inquiry.messageId(),
                            body.getJsonObject("positiveApproval"), "system"));
                }))
            .mapEmpty();
    }

    private Future<Void> onAnswer(SqlClient tx, InboundMessage response) {
        return support.workflowForResponse(tx, response, "Inquire_number").compose(wf -> {
            if (!"INQUIRY_SENT".equals(wf.state())) {
                return engine.recordEvent(tx, wf, "LATE_RESPONSE_IGNORED", response.messageId(), null, "system");
            }
            JsonObject result = response.approved()
                ? new JsonObject().put("found", true).put("currentOperator", response.body().getString("currentOperator"))
                : new JsonObject().put("found", false).mergeIn(WorkflowSupport.rejectionOf(response));
            return services.timers().cancel(tx, wf.id(), "INQUIRY_RESPONSE_TIMEOUT")
                .compose(v -> engine.mergeContext(tx, wf, new JsonObject().put("result", result)))
                .compose(updated -> engine.transition(tx, updated, "INQUIRY_COMPLETED", "INQUIRY_COMPLETED",
                    response.messageId(), result, "system"))
                .mapEmpty();
        });
    }
}
