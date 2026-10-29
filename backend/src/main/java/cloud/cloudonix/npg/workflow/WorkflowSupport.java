package cloud.cloudonix.npg.workflow;

import cloud.cloudonix.npg.domain.Operator;
import cloud.cloudonix.npg.repository.ManualTaskRepository;
import cloud.cloudonix.npg.worker.PermanentProcessingException;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

/** Steps every workflow handler needs: sending, answering, correlating, escalating. */
public class WorkflowSupport {

    /** The outbound request an inbound response answers. */
    public record SentRequest(long messageId, Long workflowId, String messageType, Long receiverOperatorId) {
    }

    private final WorkflowServices services;

    public WorkflowSupport(WorkflowServices services) {
        this.services = services;
    }

    public WorkflowServices services() {
        return services;
    }

    /** Queues a message from this gateway on behalf of a workflow; returns the np_messages id. */
    public Future<Long> send(SqlClient tx, Workflow wf, String messageType, String processType, Operator to,
                             String correlationTrxNo, JsonObject body) {
        return services.operators().local(tx)
            .compose(local -> services.outbound().prepare(tx, wf != null ? wf.id() : null, messageType, processType,
                local, to, wf != null ? wf.requestId() : null, correlationTrxNo, body))
            .compose(prepared -> services.outbound().enqueue(tx, prepared))
            .map(queued -> queued.messageId());
    }

    /** Answers an inbound request: same REQUEST_ID, back to its sender, correlated by its TRX_NO. */
    public Future<Long> respond(SqlClient tx, Workflow wf, InboundMessage request, String responseType,
                                String processType, JsonObject body) {
        return services.operators().byId(tx, request.senderOperatorId())
            .compose(sender -> services.operators().local(tx)
                .compose(local -> services.outbound().prepare(tx, wf != null ? wf.id() : null, responseType,
                    processType, local, sender, request.requestId(), request.trxNo(), body))
                .compose(prepared -> services.outbound().enqueue(tx, prepared))
                .map(queued -> queued.messageId()));
    }

    /** Finds the request we sent that an inbound response refers to (requestTrxNo = our TRX_NO). */
    public Future<SentRequest> answeredRequest(SqlClient tx, InboundMessage response) {
        return tx.preparedQuery("""
                SELECT id, workflow_id, message_type, receiver_operator_id FROM np_messages
                WHERE direction = 'OUTBOUND' AND transaction_id = ?
                """)
            .execute(Tuple.of(response.correlationTrxNo()))
            .compose(rows -> {
                if (!rows.iterator().hasNext()) {
                    return Future.failedFuture(new PermanentProcessingException(response.messageType()
                        + " answers unknown request TRX " + response.correlationTrxNo()));
                }
                Row row = rows.iterator().next();
                return Future.succeededFuture(new SentRequest(row.getLong("id"), row.getLong("workflow_id"),
                    row.getString("message_type"), row.getLong("receiver_operator_id")));
            });
    }

    /** Locks the workflow a response belongs to, checking it answers the expected request type. */
    public Future<Workflow> workflowForResponse(SqlClient tx, InboundMessage response, String expectedRequestType) {
        return answeredRequest(tx, response).compose(request -> {
            // only the operator we sent the request to may answer it
            if (request.receiverOperatorId() == null || !request.receiverOperatorId().equals(response.senderOperatorId())) {
                return Future.failedFuture(new PermanentProcessingException(response.messageType() + " for TRX "
                    + response.correlationTrxNo() + " came from an operator the request was not sent to"));
            }
            if (!expectedRequestType.equals(request.messageType())) {
                return Future.failedFuture(new PermanentProcessingException(response.messageType()
                    + " answers a " + request.messageType() + ", expected " + expectedRequestType));
            }
            if (request.workflowId() == null) {
                return Future.failedFuture(new PermanentProcessingException(
                    "Request " + response.correlationTrxNo() + " has no workflow"));
            }
            return services.engine().lock(tx, request.workflowId());
        });
    }

    public Future<Void> task(SqlClient tx, Workflow wf, Long messageId, String taskType, String severity,
                             String description) {
        return new ManualTaskRepository(tx).open(wf != null ? wf.id() : null, messageId, taskType, severity, description)
            .mapEmpty();
    }

    /** Hands a workflow to the NOC: MANUAL_REVIEW plus a task explaining why. */
    public Future<Workflow> escalate(SqlClient tx, Workflow wf, Long messageId, String taskType, String description) {
        return services.engine().transition(tx, wf, StateMachines.MANUAL_REVIEW, taskType, messageId,
                new JsonObject().put("reason", description), "system")
            .compose(updated -> task(tx, wf, messageId, taskType, ManualTaskRepository.SEVERITY_HIGH, description)
                .map(updated));
    }

    /** Rejection code and text carried by a negative business response, for the workflow record. */
    public static JsonObject rejectionOf(InboundMessage response) {
        return new JsonObject()
            .put("rejectReasonCode", response.body().getString("rejectReasonCode", "UNSPECIFIED"))
            .put("rejectReasonText", response.body().getString("rejectReasonText"));
    }
}
