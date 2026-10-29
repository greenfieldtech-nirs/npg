package cloud.cloudonix.npg.repository;

import io.vertx.core.Future;
import io.vertx.mysqlclient.MySQLClient;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.UUID;

public class WorkflowRepository {

    private final SqlClient sqlClient;

    public WorkflowRepository(SqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    public Future<Long> createWorkflow(String workflowType, String requestId, String phoneNumber,
                                         Long recipientOperatorId, Long donorOperatorId, String currentState) {
        String workflowUuid = UUID.randomUUID().toString();
        return sqlClient.preparedQuery("""
                INSERT INTO np_workflows (workflow_uuid, workflow_type, request_id, phone_number,
                                          recipient_operator_id, donor_operator_id, current_state)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)
            .execute(Tuple.of(workflowUuid, workflowType, requestId, phoneNumber,
                recipientOperatorId, donorOperatorId, currentState))
            .map(rs -> rs.property(MySQLClient.LAST_INSERTED_ID));
    }

    public Future<RowSet<Row>> updateState(Long workflowId, String newState) {
        return sqlClient.preparedQuery("UPDATE np_workflows SET current_state = ?, updated_at = UTC_TIMESTAMP(6) WHERE id = ?")
            .execute(Tuple.of(newState, workflowId));
    }

    public Future<RowSet<Row>> findByUuid(String workflowUuid) {
        return sqlClient.preparedQuery("SELECT * FROM np_workflows WHERE workflow_uuid = ?")
            .execute(Tuple.of(workflowUuid));
    }
}
