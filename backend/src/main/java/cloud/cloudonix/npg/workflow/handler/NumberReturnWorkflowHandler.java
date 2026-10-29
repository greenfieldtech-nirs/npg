package cloud.cloudonix.npg.workflow.handler;

import cloud.cloudonix.npg.number.NumberOwnershipService;
import cloud.cloudonix.npg.protocol.ProtocolIds;
import cloud.cloudonix.npg.repository.AuditEventRepository;
import cloud.cloudonix.npg.workflow.BusinessRuleException;
import cloud.cloudonix.npg.workflow.InboundMessage;
import cloud.cloudonix.npg.workflow.ProtocolBodies;
import cloud.cloudonix.npg.workflow.PublicationTracker;
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

/**
 * Number return (docs/05): a ported number whose subscriber left goes back to its original
 * operator after a cooling period, and the return is published to everyone else.
 * <ul>
 *   <li>Current operator (us): NOC disconnects the number → RETURN_COOLING_PERIOD timer → Return to
 *       the original operator → on approval the number is theirs again → Publish (publishType Return).</li>
 *   <li>Original operator (us): a Return for a number we originally owned is accepted and the number
 *       comes back to us.</li>
 * </ul>
 */
public class NumberReturnWorkflowHandler implements WorkflowHandler {

    static final String PROCESS = "RETURN";

    private final WorkflowServices services;
    private final WorkflowSupport support;
    private final PublicationTracker publication;
    private final WorkflowEngine engine;

    public NumberReturnWorkflowHandler(WorkflowServices services, PublicationTracker publication) {
        this.services = services;
        this.support = new WorkflowSupport(services);
        this.publication = publication;
        this.engine = services.engine();
    }

    /** NOC: the subscriber of a ported-in number disconnected; start returning it. */
    public Future<JsonObject> startReturn(String phoneNumber, String actor, String remoteIp) {
        return services.pool().withTransaction(tx -> services.operators().local(tx).compose(local -> tx.preparedQuery(
                "SELECT number_type, original_operator_id, current_operator_id FROM np_numbers WHERE phone_number = ? FOR UPDATE")
            .execute(Tuple.of(phoneNumber))
            .compose(rows -> {
                Row number = rows.iterator().hasNext() ? rows.iterator().next() : null;
                if (number == null || !local.id().equals(number.getLong("current_operator_id"))) {
                    return Future.failedFuture(BusinessRuleException.badRequest(phoneNumber + " is not a number we currently serve"));
                }
                Long original = number.getLong("original_operator_id");
                if (original == null || original.equals(local.id())) {
                    return Future.failedFuture(BusinessRuleException.badRequest(
                        phoneNumber + " was not ported in, so there is nobody to return it to"));
                }
                return services.operators().byId(tx, original).compose(originalOp -> ProtocolIds.nextRequestId(tx,
                        local.operatorCode(), originalOp.operatorCode()))
                    .compose(requestId -> engine.create(tx, new WorkflowEngine.NewWorkflow(StateMachines.NUMBER_RETURN,
                        StateMachines.CURRENT_OPERATOR, original, requestId, phoneNumber, null, null, original, null,
                        new JsonObject().put("numberType", number.getString("number_type")), null), actor))
                    .compose(wf -> engine.transition(tx, wf, "RETURN_COOLING_PERIOD", "NUMBER_DISCONNECTED", null, null, actor))
                    .compose(cooling -> services.timers().start(tx, cooling, "RETURN_COOLING_PERIOD", "RETURN_READY").map(cooling))
                    .compose(wf -> new AuditEventRepository(tx).insertAuditEvent("USER", actor, "NUMBER_RETURN_STARTED",
                            "WORKFLOW", wf.uuid(), new JsonObject().put("phoneNumber", phoneNumber).encode(), remoteIp)
                        .map(new JsonObject().put("workflowUuid", wf.uuid()).put("requestId", wf.requestId())));
            })));
    }

    @Override
    public Future<Void> handle(SqlConnection tx, Row inboxItem) {
        return InboundMessage.load(tx, services.xmlAudit(), services.catalog(), inboxItem)
            .compose(message -> switch (message.messageType()) {
                case "Return" -> onReturn(tx, message);
                case "Return_response" -> onReturnResponse(tx, message);
                default -> Future.failedFuture("Not a return message: " + message.messageType());
            });
    }

    @Override
    public Future<Boolean> onTimer(SqlConnection tx, Workflow wf, Row timer) {
        return switch (timer.getString("timer_name")) {
            case "RETURN_COOLING_PERIOD" -> sendReturn(tx, wf).map(true);
            case "PUBLICATION_RESPONSE_TIMEOUT" -> publication.onTimeout(tx, wf, PublicationTracker.PUBLISH).map(true);
            default -> Future.succeededFuture(false);
        };
    }

    private Future<Void> sendReturn(SqlClient tx, Workflow wf) {
        return services.operators().byId(tx, wf.originalOperatorId())
            .compose(original -> support.send(tx, wf, "Return", PROCESS, original, null,
                ProtocolBodies.numberBlock(wf.context().getString("numberType"), wf.phoneNumber(), null)))
            .compose(messageId -> engine.transition(tx, wf, "RETURN_SENT", "RETURN_SENT", messageId, null, "system"))
            .compose(sent -> services.timers().start(tx, sent, "RETURN_RESPONSE_TIMEOUT", "RESPONSE_TIMEOUT"))
            .mapEmpty();
    }

    private Future<Void> onReturnResponse(SqlClient tx, InboundMessage response) {
        return support.workflowForResponse(tx, response, "Return").compose(wf -> {
            if (!"RETURN_SENT".equals(wf.state())) {
                return engine.recordEvent(tx, wf, "LATE_RESPONSE_IGNORED", response.messageId(), null, "system");
            }
            Future<Void> stop = services.timers().cancel(tx, wf.id(), "RETURN_RESPONSE_TIMEOUT");
            if (!response.approved()) {
                return stop.compose(v -> engine.transition(tx, wf, "RETURN_REJECTED", "RETURN_REJECTED", response.messageId(),
                    WorkflowSupport.rejectionOf(response), "system")).mapEmpty();
            }
            return stop
                .compose(v -> engine.transition(tx, wf, "RETURN_APPROVED", "RETURN_APPROVED", response.messageId(), null, "system"))
                .compose(approved -> NumberOwnershipService.transfer(tx, wf.phoneNumber(), wf.context().getString("numberType"),
                        wf.originalOperatorId(), "RETURNED", "RETURN", wf.id(), response.messageId())
                    .compose(o -> publishReturn(tx, approved)));
        });
    }

    private Future<Void> publishReturn(SqlClient tx, Workflow wf) {
        return services.operators().local(tx).compose(local -> services.operators().byId(tx, wf.originalOperatorId())
            .compose(original -> services.operators().peersExcept(tx, local.id(), original.id())
                .compose(recipients -> {
                    JsonObject body = new JsonObject()
                        .put("donor", local.operatorCode())
                        .put("originalOperator", original.operatorCode())
                        .put("connectDateTime", ProtocolBodies.now())
                        .put("publishType", "Return")
                        .mergeIn(ProtocolBodies.numberBlock(wf.context().getString("numberType"), wf.phoneNumber(), null));
                    return publication.publish(tx, wf, PublicationTracker.PUBLISH, "Publish", PROCESS, recipients, body)
                        .compose(count -> engine.transition(tx, wf, "RETURN_PUBLISH_SENT", "RETURN_PUBLISH_SENT", null,
                                new JsonObject().put("recipients", count), "system")
                            .compose(sent -> count == 0 ? publication.outcomeAfterPublish(tx, sent)
                                : services.timers().start(tx, sent, "PUBLICATION_RESPONSE_TIMEOUT", "PUBLICATION_TIMEOUT").map(sent)));
                })))
            .mapEmpty();
    }

    /** We are the original operator: take the number back if it really was ours. */
    private Future<Void> onReturn(SqlClient tx, InboundMessage ret) {
        String phone = ret.body().getString("phoneNumber");
        return services.operators().local(tx).compose(local -> engine.create(tx, new WorkflowEngine.NewWorkflow(
                StateMachines.NUMBER_RETURN, StateMachines.ORIGINAL_OPERATOR, ret.senderOperatorId(), ret.requestId(), phone,
                null, null, local.id(), null, new JsonObject(), null), "system")
            .compose(wf -> tx.preparedQuery("SELECT original_operator_id, current_operator_id, number_type FROM np_numbers WHERE phone_number = ?")
                .execute(Tuple.of(phone))
                .compose(rows -> {
                    Row number = rows.iterator().hasNext() ? rows.iterator().next() : null;
                    boolean ours = number != null && local.id().equals(number.getLong("original_operator_id"))
                        && ret.senderOperatorId().equals(number.getLong("current_operator_id"));
                    if (!ours) {
                        return support.respond(tx, wf, ret, "Return_response", PROCESS, ProtocolBodies.reject(ret.trxNo(),
                                "REJECTED_NUMBER_NOT_FOUND", "The number was not ported out from this operator to the sender"))
                            .compose(m -> engine.transition(tx, wf, "RETURN_REJECTED", "RETURN_REJECTED", ret.messageId(), null, "system"))
                            .mapEmpty();
                    }
                    return NumberOwnershipService.transfer(tx, phone, number.getString("number_type"), local.id(), "RETURNED",
                            "RETURN", wf.id(), ret.messageId())
                        .compose(v -> support.respond(tx, wf, ret, "Return_response", PROCESS, ProtocolBodies.approve(ret.trxNo(), null)))
                        .compose(m -> engine.transition(tx, wf, "RETURN_ACCEPTED", "RETURN_ACCEPTED", ret.messageId(), null, "system"))
                        .mapEmpty();
                })));
    }
}
