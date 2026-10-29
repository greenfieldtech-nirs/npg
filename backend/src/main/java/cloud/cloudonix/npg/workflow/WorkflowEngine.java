package cloud.cloudonix.npg.workflow;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.mysqlclient.MySQLClient;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WorkflowEngine {

    private final Pool pool;
    private final WorkflowEventService eventService;
    private final Set<StateTransition> allowedTransitions = ConcurrentHashMap.newKeySet();

    public WorkflowEngine(Pool pool) {
        this.pool = pool;
        this.eventService = new WorkflowEventService(pool);
        loadDefaultTransitions();
    }

    private void loadDefaultTransitions() {
        allowedTransitions.add(new StateTransition("PORTING", "NEW", "CHECK_SENT", "Check"));
        allowedTransitions.add(new StateTransition("PORTING", "CHECK_SENT", "CHECK_APPROVED", "Check_response"));
        allowedTransitions.add(new StateTransition("PORTING", "CHECK_SENT", "CHECK_REJECTED", "Check_response"));
        allowedTransitions.add(new StateTransition("PORTING", "CHECK_APPROVED", "REQUEST_SENT", "Request"));
        allowedTransitions.add(new StateTransition("PORTING", "REQUEST_SENT", "REQUEST_APPROVED", "Request_response"));
        allowedTransitions.add(new StateTransition("PORTING", "REQUEST_SENT", "REQUEST_REJECTED", "Request_response"));
        allowedTransitions.add(new StateTransition("PORTING", "REQUEST_APPROVED", "PORT_SCHEDULED", "KD_update_response"));
        allowedTransitions.add(new StateTransition("PORTING", "PORT_SCHEDULED", "UPDATE_SENT", "Update"));
        allowedTransitions.add(new StateTransition("PORTING", "UPDATE_SENT", "PORT_SCHEDULED", "Update_response"));
        allowedTransitions.add(new StateTransition("PORTING", "PORT_SCHEDULED", "CANCEL_SENT", "Cancel"));
        allowedTransitions.add(new StateTransition("PORTING", "CANCEL_SENT", "CANCELLED", "Cancel_response"));
        allowedTransitions.add(new StateTransition("PORTING", "PORT_SCHEDULED", "EXECUTION_SENT", "Execute"));
        allowedTransitions.add(new StateTransition("PORTING", "EXECUTION_SENT", "EXECUTION_CONFIRMED", "Execute_response"));
        allowedTransitions.add(new StateTransition("PORTING", "EXECUTION_CONFIRMED", "PUBLISH_PENDING", "Publish"));
        allowedTransitions.add(new StateTransition("PORTING", "PUBLISH_PENDING", "PUBLISH_SENT", "Publish"));
        allowedTransitions.add(new StateTransition("PORTING", "PUBLISH_SENT", "PUBLISHED", "Publish_response"));
        allowedTransitions.add(new StateTransition("PORTING", "PUBLISHED", "COMPLETED", "auto"));
    }

    public Future<Long> createWorkflow(String workflowType, String requestId, String phoneNumber,
                                         Long recipientOperatorId, Long donorOperatorId, String initialState) {
        String workflowUuid = UUID.randomUUID().toString();
        return pool.preparedQuery("""
                INSERT INTO np_workflows (workflow_uuid, workflow_type, request_id, phone_number,
                                          recipient_operator_id, donor_operator_id, current_state)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)
            .execute(Tuple.of(workflowUuid, workflowType, requestId, phoneNumber,
                recipientOperatorId, donorOperatorId, initialState))
            .map(rs -> rs.property(MySQLClient.LAST_INSERTED_ID))
            .compose(id -> eventService.recordEvent(id, "WORKFLOW_CREATED", null, initialState, null,
                    new JsonObject().put("requestId", requestId).put("phoneNumber", phoneNumber), "system")
                .map(v -> id));
    }

    public Future<Void> transition(Long workflowId, String toState, String eventType,
                                    Long messageId, JsonObject details, String createdBy) {
        return pool.withTransaction(tx -> tx.preparedQuery("SELECT current_state, workflow_type FROM np_workflows WHERE id = ? FOR UPDATE")
            .execute(Tuple.of(workflowId))
            .compose(rows -> {
                if (!rows.iterator().hasNext()) {
                    return Future.failedFuture("Workflow not found: " + workflowId);
                }
                Row row = rows.iterator().next();
                String fromState = row.getString("current_state");
                String workflowType = row.getString("workflow_type");
                String messageType = details != null ? details.getString("messageType") : "auto";

                if (!isTransitionAllowed(workflowType, fromState, toState, messageType)) {
                    return Future.failedFuture("Illegal transition from " + fromState + " to " + toState +
                        " for workflow type " + workflowType);
                }

                return tx.preparedQuery("UPDATE np_workflows SET current_state = ?, updated_at = UTC_TIMESTAMP(6) WHERE id = ?")
                    .execute(Tuple.of(toState, workflowId))
                    .compose(u -> new WorkflowEventService(tx).recordEvent(workflowId, eventType, fromState, toState,
                        messageId, details, createdBy));
            }))
            .mapEmpty();
    }

    /**
     * Manual operator override via the management API. Skips state-machine validation
     * (per docs/05_WORKFLOW_STATE_MACHINES.md manual transitions are overrides) but still
     * records a workflow event with the mandatory reason.
     */
    public Future<Void> manualTransition(Long workflowId, String toState, String eventType,
                                         JsonObject details, String createdBy) {
        return pool.withTransaction(tx -> tx.preparedQuery("SELECT current_state FROM np_workflows WHERE id = ? FOR UPDATE")
            .execute(Tuple.of(workflowId))
            .compose(rows -> {
                if (!rows.iterator().hasNext()) {
                    return Future.failedFuture("Workflow not found: " + workflowId);
                }
                String fromState = rows.iterator().next().getString("current_state");
                return tx.preparedQuery("UPDATE np_workflows SET current_state = ?, updated_at = UTC_TIMESTAMP(6) WHERE id = ?")
                    .execute(Tuple.of(toState, workflowId))
                    .compose(u -> new WorkflowEventService(tx).recordEvent(workflowId, eventType, fromState, toState,
                        null, details, createdBy));
            }))
            .mapEmpty();
    }

    private boolean isTransitionAllowed(String workflowType, String fromState, String toState, String messageType) {
        if (allowedTransitions.contains(new StateTransition(workflowType, fromState, toState, messageType))) {
            return true;
        }
        return allowedTransitions.contains(new StateTransition(workflowType, fromState, toState, null))
            || allowedTransitions.contains(new StateTransition(workflowType, fromState, toState, "auto"));
    }
}
