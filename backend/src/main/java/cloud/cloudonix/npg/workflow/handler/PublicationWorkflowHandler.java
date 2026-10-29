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
import io.vertx.sqlclient.Tuple;

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

    /**
     * Another operator announces that it now serves a number (port) or returned it (return).
     * Applied only if it agrees with what we recorded: a peer cannot redirect a number by simply
     * claiming it — the previous owner always comes from our database, under a row lock.
     */
    private Future<Void> onPublish(SqlConnection tx, InboundMessage publish) {
        String phone = publish.body().getString("phoneNumber");
        if (phone == null) {
            return support.respond(tx, null, publish, "Publish_response", PROCESS,
                ProtocolBodies.reject(publish.trxNo(), "REJECTED_NUMBER_NOT_FOUND", "No number in the Publish")).mapEmpty();
        }
        return engine.findForUpdate(tx, publish.requestId(), StateMachines.PUBLICATION, StateMachines.OBSERVER,
                publish.senderOperatorId())
            .compose(existing -> existing != null
                ? engine.recordEvent(tx, existing, "DUPLICATE_PUBLISH_IGNORED", publish.messageId(), null, "system")
                : Future.all(services.operators().local(tx), services.operators().byCode(tx, publish.body().getString("donor")),
                        services.operators().byCode(tx, publish.body().getString("originalOperator")),
                        NumberOwnershipService.lockOwnership(tx, phone))
                    .compose(all -> {
                        cloud.cloudonix.npg.domain.Operator local = all.resultAt(0);
                        cloud.cloudonix.npg.domain.Operator donor = all.resultAt(1);
                        cloud.cloudonix.npg.domain.Operator original = all.resultAt(2);
                        NumberOwnershipService.Ownership recorded = all.resultAt(3);
                        boolean isReturn = "Return".equalsIgnoreCase(publish.body().getString("publishType"));
                        return engine.create(tx, new WorkflowEngine.NewWorkflow(StateMachines.PUBLICATION, StateMachines.OBSERVER,
                                publish.senderOperatorId(), publish.requestId(), phone, publish.senderOperatorId(),
                                donor != null ? donor.id() : null, null, null,
                                new JsonObject().put("publishType", publish.body().getString("publishType")), null), "system")
                            .compose(wf -> isReturn
                                ? applyReturn(tx, wf, publish, phone, local, original, recorded)
                                : applyPort(tx, wf, publish, phone, local, donor, recorded));
                    }));
    }

    private Future<Void> applyPort(SqlConnection tx, Workflow wf, InboundMessage publish, String phone,
                                   cloud.cloudonix.npg.domain.Operator local, cloud.cloudonix.npg.domain.Operator donor,
                                   NumberOwnershipService.Ownership recorded) {
        Long publisher = publish.senderOperatorId();
        Long owner = recorded != null ? recorded.currentOperatorId() : null;
        if (owner != null && owner.equals(local.id())) {
            // a number we still serve: a completed port-out to this publisher would already have moved it
            return refuse(tx, wf, publish, "This operator still serves the number; no completed port-out to the publisher");
        }
        if (owner != null && !owner.equals(publisher) && (donor == null || !owner.equals(donor.id()))) {
            return refuse(tx, wf, publish, "The publication must name the operator recorded as serving the number as donor");
        }
        if (owner == null && donor != null && donor.id().equals(local.id())) {
            // our native numbers are not in np_numbers: only a port-out we completed to the publisher counts
            return completedPortOutTo(tx, phone, publisher).compose(completed -> completed
                ? apply(tx, wf, publish, phone, publisher, local.id(), false)
                : refuse(tx, wf, publish, "Names this operator as donor, but no port-out of the number to the publisher was completed"));
        }
        // a first publication of another operator's number: not verifiable from a third party's view
        // (no number-range data); accepted from the authenticated publisher, previous owner as named
        Long previous = owner != null ? owner : (donor != null ? donor.id() : null);
        return apply(tx, wf, publish, phone, publisher, previous, false);
    }

    private Future<Boolean> completedPortOutTo(SqlConnection tx, String phone, Long publisher) {
        return tx.preparedQuery("""
                SELECT COUNT(*) AS c FROM np_workflows WHERE workflow_type = 'PORTING' AND local_role = 'DONOR'
                  AND phone_number = ? AND counterparty_operator_id = ? AND current_state = 'COMPLETED'
                """).execute(Tuple.of(phone, publisher))
            .map(rows -> rows.iterator().next().getLong("c") > 0);
    }

    /** Returns are verified strictly: the number must be recorded with matching current and original operators. */
    private Future<Void> applyReturn(SqlConnection tx, Workflow wf, InboundMessage publish, String phone,
                                     cloud.cloudonix.npg.domain.Operator local, cloud.cloudonix.npg.domain.Operator original,
                                     NumberOwnershipService.Ownership recorded) {
        if (original == null) {
            return refuse(tx, wf, publish, "Unknown original operator in the return publication");
        }
        if (original.id().equals(local.id())) {
            return refuse(tx, wf, publish, "Returns to this operator arrive as a Return message, not a Publish");
        }
        if (recorded == null || !publish.senderOperatorId().equals(recorded.currentOperatorId())) {
            return refuse(tx, wf, publish, "Only the operator recorded as serving the number can publish its return");
        }
        if (!original.id().equals(recorded.originalOperatorId())) {
            return refuse(tx, wf, publish, "The named original operator does not match our records");
        }
        return apply(tx, wf, publish, phone, original.id(), publish.senderOperatorId(), true);
    }

    private Future<Void> apply(SqlConnection tx, Workflow wf, InboundMessage publish, String phone, Long newOwner,
                               Long previous, boolean isReturn) {
        return engine.mergeContext(tx, wf, new JsonObject().put("previousOperatorId", previous))
            .compose(updated -> NumberOwnershipService.transfer(tx, phone, numberType(publish), newOwner, previous,
                    isReturn ? "RETURNED" : "PORTED", isReturn ? "RETURN_PUBLISHED" : "PORT_PUBLISHED", wf.id(), publish.messageId())
                .compose(v -> support.respond(tx, updated, publish, "Publish_response", PROCESS,
                    ProtocolBodies.approve(publish.trxNo(), new JsonObject().put("routeDateTime", ProtocolBodies.localNow()))))
                .compose(messageId -> engine.transition(tx, updated, "PUBLICATION_APPLIED", "PUBLICATION_APPLIED",
                    publish.messageId(), new JsonObject().put("newOperatorId", newOwner), "system")))
            .mapEmpty();
    }

    /** Refuses a publication that contradicts our records, and tells the NOC. */
    private Future<Void> refuse(SqlConnection tx, Workflow wf, InboundMessage publish, String reason) {
        return support.respond(tx, wf, publish, "Publish_response", PROCESS,
                ProtocolBodies.reject(publish.trxNo(), "REJECTED_OTHER", reason))
            .compose(messageId -> engine.transition(tx, wf, "PUBLICATION_REJECTED", "PUBLICATION_REJECTED",
                publish.messageId(), new JsonObject().put("reason", reason), "system"))
            .compose(rejected -> support.task(tx, rejected, publish.messageId(), "PUBLICATION_CONFLICT",
                cloud.cloudonix.npg.repository.ManualTaskRepository.SEVERITY_HIGH,
                "Refused a Publish for " + wf.phoneNumber() + ": " + reason + ". Verify with the publisher."))
            .mapEmpty();
    }

    /** The publisher withdraws an earlier publication: restore the routing we recorded before it. */
    private Future<Void> onCancelPublish(SqlConnection tx, InboundMessage cancel) {
        return engine.findForUpdate(tx, cancel.requestId(), StateMachines.PUBLICATION, StateMachines.OBSERVER,
                cancel.senderOperatorId())
            .compose(wf -> {
                if (wf == null || !"PUBLICATION_APPLIED".equals(wf.state())) {
                    return rejectCancel(tx, wf, cancel, "No applied publication for this request");
                }
                return NumberOwnershipService.lockOwnership(tx, wf.phoneNumber()).compose(recorded -> {
                    Long publishedOwner = wf.context().getLong("newOperatorId", cancel.senderOperatorId());
                    if (recorded == null || recorded.currentOperatorId() == null
                        || !recorded.currentOperatorId().equals(cancel.senderOperatorId())) {
                        return rejectCancel(tx, wf, cancel, "The number has moved on since this publication");
                    }
                    Long previous = wf.context().getLong("previousOperatorId");
                    return NumberOwnershipService.transfer(tx, wf.phoneNumber(), null, previous, "PORTED",
                            "PUBLICATION_CANCELLED", wf.id(), cancel.messageId())
                        .compose(v -> support.respond(tx, wf, cancel, "Cancel_Publish_response", PROCESS,
                            ProtocolBodies.approve(cancel.trxNo(), null)))
                        .compose(messageId -> engine.transition(tx, wf, "PUBLICATION_CANCELLED", "PUBLICATION_CANCELLED",
                            cancel.messageId(), new JsonObject().put("restoredOperatorId", previous)
                                .put("withdrawnOperatorId", publishedOwner), "system"))
                        .mapEmpty();
                });
            });
    }

    private Future<Void> rejectCancel(SqlConnection tx, Workflow wf, InboundMessage cancel, String reason) {
        return support.respond(tx, wf, cancel, "Cancel_Publish_response", PROCESS,
            ProtocolBodies.reject(cancel.trxNo(), "REJECTED_OTHER", reason)).mapEmpty();
    }

    private static String numberType(InboundMessage message) {
        return switch (message.body().getString("networkType", "")) {
            case "F" -> "FIXED";
            case "M" -> "MOBILE";
            default -> message.body().containsKey("mobileNumberSingle") ? "MOBILE" : "FIXED";
        };
    }
}
