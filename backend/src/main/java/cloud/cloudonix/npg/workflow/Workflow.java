package cloud.cloudonix.npg.workflow;

import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;

import java.time.LocalDateTime;

/** Snapshot of one np_workflows row. */
public record Workflow(long id, String uuid, String type, String role, String requestId, String phoneNumber,
                       String state, Long recipientOperatorId, Long donorOperatorId, Long originalOperatorId,
                       LocalDateTime requestedPortTime, LocalDateTime approvedPortTime, JsonObject context) {

    static Workflow from(Row row) {
        Object ctx = row.getValue("context_json");
        JsonObject context = ctx == null ? new JsonObject()
            : ctx instanceof JsonObject jo ? jo : new JsonObject(ctx.toString());
        return new Workflow(row.getLong("id"), row.getString("workflow_uuid"), row.getString("workflow_type"),
            row.getString("local_role"), row.getString("request_id"), row.getString("phone_number"),
            row.getString("current_state"), row.getLong("recipient_operator_id"), row.getLong("donor_operator_id"),
            row.getLong("original_operator_id"), row.getLocalDateTime("requested_port_time"),
            row.getLocalDateTime("approved_port_time"), context);
    }

    public Workflow withState(String newState) {
        return new Workflow(id, uuid, type, role, requestId, phoneNumber, newState, recipientOperatorId,
            donorOperatorId, originalOperatorId, requestedPortTime, approvedPortTime, context);
    }

    public StateMachines.Machine machine() {
        return StateMachines.of(type, role);
    }
}
