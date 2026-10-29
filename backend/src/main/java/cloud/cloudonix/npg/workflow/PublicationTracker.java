package cloud.cloudonix.npg.workflow;

import cloud.cloudonix.npg.domain.Operator;
import cloud.cloudonix.npg.repository.ManualTaskRepository;
import cloud.cloudonix.npg.worker.PermanentProcessingException;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.List;

/**
 * Multi-recipient publication (docs/05 "Publication State"): one message per operator, tracked per
 * recipient in np_publication_recipients, aggregated into the owning workflow's state. Used for
 * Publish after a port-in, for the Publish that announces a number return, and for Cancel_Publish.
 */
public class PublicationTracker {

    public static final String PUBLISH = "PUBLISH";
    public static final String CANCEL_PUBLISH = "CANCEL_PUBLISH";
    static final String TIMER = "PUBLICATION_RESPONSE_TIMEOUT";
    static final String CANCEL_TIMER = "CANCEL_PUBLISH_RESPONSE_TIMEOUT";

    private final WorkflowSupport support;

    public PublicationTracker(WorkflowSupport support) {
        this.support = support;
    }

    /** Sends {@code messageType} to every recipient and records them; returns how many were sent. */
    public Future<Integer> publish(SqlClient tx, Workflow wf, String purpose, String messageType, String processType,
                                   List<Operator> recipients, JsonObject body) {
        Future<Void> chain = Future.succeededFuture();
        for (Operator recipient : recipients) {
            chain = chain.compose(v -> support.send(tx, wf, messageType, processType, recipient, null, body)
                .compose(messageId -> tx.preparedQuery("""
                        INSERT INTO np_publication_recipients (workflow_id, message_id, operator_id, purpose, status, sent_at)
                        VALUES (?, ?, ?, ?, 'SENT', UTC_TIMESTAMP(6))
                        ON DUPLICATE KEY UPDATE message_id = VALUES(message_id), status = 'SENT',
                                                response_status = NULL, sent_at = UTC_TIMESTAMP(6),
                                                attempts = attempts + 1
                        """).execute(Tuple.of(wf.id(), messageId, recipient.id(), purpose)))
                .mapEmpty());
        }
        return chain.map(recipients.size());
    }

    /** Operators that approved this workflow's publication (the audience of a Cancel_Publish). */
    public Future<List<Long>> approvedRecipients(SqlClient tx, Workflow wf) {
        return tx.preparedQuery("""
                SELECT operator_id FROM np_publication_recipients
                WHERE workflow_id = ? AND purpose = 'PUBLISH' AND status = 'RESPONDED_APPROVED'
                """)
            .execute(Tuple.of(wf.id()))
            .map(rows -> {
                List<Long> ids = new java.util.ArrayList<>();
                rows.forEach(r -> ids.add(r.getLong("operator_id")));
                return ids;
            });
    }

    /**
     * Records one recipient's response and, once every recipient has answered, moves the workflow
     * to its outcome. Returns the (possibly transitioned) workflow.
     */
    public Future<Workflow> onResponse(SqlClient tx, InboundMessage response, String expectedRequestType) {
        return support.workflowForResponse(tx, response, expectedRequestType).compose(wf -> {
            String status = response.approved() ? "RESPONDED_APPROVED" : "RESPONDED_REJECTED";
            return support.answeredRequest(tx, response)
                .compose(request -> tx.preparedQuery("""
                        UPDATE np_publication_recipients
                        SET status = ?, response_status = ?, response_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6)
                        WHERE message_id = ? AND status IN ('SENT', 'TIMEOUT')
                        """).execute(Tuple.of(status, response.approved() ? "APPROVED" : "REJECTED", request.messageId())))
                .compose(updated -> updated.rowCount() == 0
                    ? Future.failedFuture(new PermanentProcessingException("No open publication recipient for "
                        + response.correlationTrxNo()))
                    : Future.succeededFuture())
                .compose(v -> settle(tx, wf, purposeOf(expectedRequestType), false));
        });
    }

    /** The response deadline passed: unanswered recipients time out and the workflow settles. */
    public Future<Workflow> onTimeout(SqlClient tx, Workflow wf, String purpose) {
        return tx.preparedQuery("""
                UPDATE np_publication_recipients SET status = 'TIMEOUT', updated_at = UTC_TIMESTAMP(6)
                WHERE workflow_id = ? AND purpose = ? AND status = 'SENT'
                """).execute(Tuple.of(wf.id(), purpose))
            .compose(v -> settle(tx, wf, purpose, true));
    }

    private static String purposeOf(String requestType) {
        return "Cancel_Publish".equals(requestType) ? CANCEL_PUBLISH : PUBLISH;
    }

    /** Counts recipients and applies the outcome when nothing is pending any more. */
    private Future<Workflow> settle(SqlClient tx, Workflow wf, String purpose, boolean deadlinePassed) {
        return tx.preparedQuery("""
                SELECT SUM(status = 'SENT') AS pending, SUM(status = 'RESPONDED_APPROVED') AS approved,
                       SUM(status IN ('RESPONDED_REJECTED', 'TIMEOUT')) AS failed, COUNT(*) AS total
                FROM np_publication_recipients WHERE workflow_id = ? AND purpose = ?
                """).execute(Tuple.of(wf.id(), purpose))
            .compose(rows -> {
                Row counts = rows.iterator().next();
                long pending = counts.getLong("pending") != null ? counts.getLong("pending") : 0;
                long approved = counts.getLong("approved") != null ? counts.getLong("approved") : 0;
                long failed = counts.getLong("failed") != null ? counts.getLong("failed") : 0;
                if (pending > 0 && !deadlinePassed) {
                    return Future.succeededFuture(wf);
                }
                return outcome(tx, wf, purpose, approved, failed);
            });
    }

    /** Nobody to publish to: the round is trivially complete. */
    public Future<Workflow> outcomeAfterPublish(SqlClient tx, Workflow wf) {
        return outcome(tx, wf, PUBLISH, 0, 0);
    }

    public Future<Workflow> outcomeAfterCancel(SqlClient tx, Workflow wf) {
        return outcome(tx, wf, CANCEL_PUBLISH, 0, 0);
    }

    /** All answered: the workflow-type specific end state of a publication round. */
    Future<Workflow> outcome(SqlClient tx, Workflow wf, String purpose, long approved, long failed) {
        WorkflowEngine engine = support.services().engine();
        JsonObject details = new JsonObject().put("approved", approved).put("failed", failed);
        String timer = CANCEL_PUBLISH.equals(purpose) ? CANCEL_TIMER : TIMER;
        Future<Void> stopTimer = support.services().timers().cancel(tx, wf.id(), timer);
        if (CANCEL_PUBLISH.equals(purpose)) {
            return stopTimer.compose(v -> engine.transition(tx, wf, "PUBLICATION_CANCELLED", "PUBLICATION_CANCELLED",
                    null, details, "system"))
                .compose(done -> failed == 0 ? Future.succeededFuture(done)
                    : support.task(tx, done, null, "PUBLICATION_CANCEL_INCOMPLETE", ManualTaskRepository.SEVERITY_MEDIUM,
                        failed + " operator(s) rejected or did not answer Cancel_Publish for " + wf.phoneNumber()
                            + "; confirm their routing manually.").map(done));
        }
        String success = StateMachines.NUMBER_RETURN.equals(wf.type()) ? "RETURNED" : "PUBLISHED";
        if (failed == 0) {
            return stopTimer
                .compose(v -> engine.transition(tx, wf, success, success, null, details, "system"))
                .compose(published -> StateMachines.PORTING.equals(wf.type())
                    ? engine.transition(tx, published, "COMPLETED", "PORTING_COMPLETED", null, null, "system")
                    : Future.succeededFuture(published));
        }
        String partial = approved > 0 ? "PARTIALLY_PUBLISHED" : "PUBLISH_FAILED";
        if (partial.equals(wf.state())) {
            return stopTimer.map(wf);
        }
        return stopTimer
            .compose(v -> engine.transition(tx, wf, partial, partial, null, details, "system"))
            .compose(updated -> support.task(tx, updated, null, "PUBLICATION_INCOMPLETE", ManualTaskRepository.SEVERITY_HIGH,
                    failed + " operator(s) rejected or did not answer the publication of " + wf.phoneNumber()
                        + ". Coordinate with them, then complete the workflow manually.")
                .map(updated));
    }
}
