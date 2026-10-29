package cloud.cloudonix.npg.workflow;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.mysqlclient.MySQLClient;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

public class WorkflowEventService {

    private final SqlClient sqlClient;

    public WorkflowEventService(SqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    public Future<Long> recordEvent(Long workflowId, String eventType, String fromState, String toState,
                                     Long messageId, JsonObject details, String createdBy) {
        return sqlClient.preparedQuery("""
                INSERT INTO np_workflow_events (workflow_id, event_type, from_state, to_state, message_id, details_json, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)
            .execute(Tuple.of(workflowId, eventType, fromState, toState, messageId,
                details != null ? details.encode() : null, createdBy))
            .map(rs -> rs.property(MySQLClient.LAST_INSERTED_ID));
    }
}
