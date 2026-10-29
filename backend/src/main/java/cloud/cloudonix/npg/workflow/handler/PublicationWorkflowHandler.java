package cloud.cloudonix.npg.workflow.handler;

import cloud.cloudonix.npg.number.NumberOwnershipService;
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
import io.vertx.sqlclient.SqlConnection;

/**
 * Publication messages.
 * <ul>
 *   <li>As publisher: Publish_response / Cancel_Publish_response feed the per-recipient tracking of
 *       the port-in or number return that published.</li>
 *   <li>As third party: a Publish tells us who serves a number now — we update our routing view and
 *       answer; Cancel_Publish rolls that back.</li>
 * </ul>
 */
public class PublicationWorkflowHandler implements WorkflowHandler {

    static final String PROCESS = "PORT";

    private final WorkflowServices services;
    private final WorkflowSupport support;
    private final PublicationTracker tracker;
    private final WorkflowEngine engine;

    public PublicationWorkflowHandler(WorkflowServices services, PublicationTracker tracker) {
        this.services = services;
        this.support = new WorkflowSupport(services);
        this.tracker = tracker;
        this.engine = services.engine();
    }

    @Override
    public Future<Void> handle(SqlConnection tx, Row inboxItem) {
        return InboundMessage.load(tx, services.xmlAudit(), services.catalog(), inboxItem)
            .compose(message -> switch (message.messageType()) {
                case "Publish_response" -> tracker.onResponse(tx, message, "Publish").mapEmpty();
                case "Cancel_Publish_response" -> tracker.onResponse(tx, message, "Cancel_Publish").mapEmpty();
                case "Publish" -> onPublish(tx, message);
                case "Cancel_Publish" -> onCancelPublish(tx, message);
                default -> Future.failedFuture("Not a publication message: " + message.messageType());
            });
    }

    /** Another operator announces that it now serves a number (port) or returned it (return). */
    private Future<Void> onPublish(SqlConnection tx, InboundMessage publish) {
        String phone = publish.body().getString("phoneNumber");
        if (phone == null) {
            return support.respond(tx, null, publish, "Publish_response", PROCESS,
                ProtocolBodies.reject(publish.trxNo(), "REJECTED_NUMBER_NOT_FOUND", "No number in the Publish")).mapEmpty();
        }
        return engine.findForUpdate(tx, publish.requestId(), StateMachines.PUBLICATION, StateMachines.OBSERVER,
                publish.senderOperatorId())
            .compose(existing -> {
                if (existing != null) {
                    return engine.recordEvent(tx, existing, "DUPLICATE_PUBLISH_IGNORED", publish.messageId(), null, "system");
                }
                boolean isReturn = "Return".equalsIgnoreCase(publish.body().getString("publishType"));
                // a port: the publisher now serves the number; a return: it goes back to its original operator
                String newOwnerCode = isReturn ? publish.body().getString("originalOperator") : null;
                return services.operators().byCode(tx, publish.body().getString("donor"))
                    .compose(donor -> services.operators().byCode(tx, newOwnerCode).compose(returnedTo -> {
                        Long newOwner = isReturn ? (returnedTo != null ? returnedTo.id() : null) : publish.senderOperatorId();
                        Long previous = isReturn ? publish.senderOperatorId() : (donor != null ? donor.id() : null);
                        return engine.create(tx, new WorkflowEngine.NewWorkflow(StateMachines.PUBLICATION,
                                StateMachines.OBSERVER, publish.senderOperatorId(), publish.requestId(), phone,
                                publish.senderOperatorId(), donor != null ? donor.id() : null, null, null,
                                new JsonObject().put("publishType", publish.body().getString("publishType"))
                                    .put("previousOperatorId", previous), null), "system")
                            .compose(wf -> newOwner == null
                                ? reject(tx, wf, publish, "Unknown operator in the publication")
                                : apply(tx, wf, publish, phone, newOwner, previous, isReturn));
                    }));
            });
    }

    private Future<Void> apply(SqlConnection tx, Workflow wf, InboundMessage publish, String phone, Long newOwner,
                               Long previous, boolean isReturn) {
        return NumberOwnershipService.transfer(tx, phone, numberType(publish), newOwner, previous,
                isReturn ? "RETURNED" : "PORTED", isReturn ? "RETURN_PUBLISHED" : "PORT_PUBLISHED", wf.id(), publish.messageId())
            .compose(v -> support.respond(tx, wf, publish, "Publish_response", PROCESS,
                ProtocolBodies.approve(publish.trxNo(), new JsonObject().put("routeDateTime", ProtocolBodies.localNow()))))
            .compose(messageId -> engine.transition(tx, wf, "PUBLICATION_APPLIED", "PUBLICATION_APPLIED", publish.messageId(),
                new JsonObject().put("newOperatorId", newOwner), "system"))
            .mapEmpty();
    }

    private Future<Void> reject(SqlConnection tx, Workflow wf, InboundMessage publish, String reason) {
        return support.respond(tx, wf, publish, "Publish_response", PROCESS,
                ProtocolBodies.reject(publish.trxNo(), "REJECTED_OTHER", reason))
            .compose(messageId -> engine.transition(tx, wf, "PUBLICATION_REJECTED", "PUBLICATION_REJECTED",
                publish.messageId(), new JsonObject().put("reason", reason), "system"))
            .mapEmpty();
    }

    /** The publisher withdraws an earlier publication: restore the previous routing. */
    private Future<Void> onCancelPublish(SqlConnection tx, InboundMessage cancel) {
        return engine.findForUpdate(tx, cancel.requestId(), StateMachines.PUBLICATION, StateMachines.OBSERVER,
                cancel.senderOperatorId())
            .compose(wf -> {
                if (wf == null || !"PUBLICATION_APPLIED".equals(wf.state())) {
                    return support.respond(tx, wf, cancel, "Cancel_Publish_response", PROCESS, ProtocolBodies.reject(
                        cancel.trxNo(), "REJECTED_OTHER", "No applied publication for this request")).mapEmpty();
                }
                Long previous = wf.context().getLong("previousOperatorId");
                return NumberOwnershipService.transfer(tx, wf.phoneNumber(), null, previous, "PORTED",
                        "PUBLICATION_CANCELLED", wf.id(), cancel.messageId())
                    .compose(v -> support.respond(tx, wf, cancel, "Cancel_Publish_response", PROCESS,
                        ProtocolBodies.approve(cancel.trxNo(), null)))
                    .compose(messageId -> engine.transition(tx, wf, "PUBLICATION_CANCELLED", "PUBLICATION_CANCELLED",
                        cancel.messageId(), null, "system"))
                    .mapEmpty();
            });
    }

    private static String numberType(InboundMessage message) {
        return switch (message.body().getString("networkType", "")) {
            case "F" -> "FIXED";
            case "M" -> "MOBILE";
            default -> message.body().containsKey("mobileNumberSingle") ? "MOBILE" : "FIXED";
        };
    }
}
