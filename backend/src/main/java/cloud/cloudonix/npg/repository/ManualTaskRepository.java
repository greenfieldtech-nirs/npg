package cloud.cloudonix.npg.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.UUID;

/**
 * Manual operator tasks — the durable hand-off from automation to the NOC. Anything the system
 * cannot resolve safely on its own (unknown delivery, exhausted retries, poison messages, business
 * decisions) opens a task here instead of failing silently.
 */
public class ManualTaskRepository {

    public static final String SEVERITY_LOW = "LOW";
    public static final String SEVERITY_MEDIUM = "MEDIUM";
    public static final String SEVERITY_HIGH = "HIGH";

    private final SqlClient sqlClient;

    public ManualTaskRepository(SqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    /** Opens a task and returns its uuid. */
    public Future<String> open(Long workflowId, Long messageId, String taskType, String severity, String description) {
        String taskUuid = UUID.randomUUID().toString();
        return sqlClient.preparedQuery("""
                INSERT INTO np_manual_tasks (task_uuid, workflow_id, message_id, task_type, severity, description)
                VALUES (?, ?, ?, ?, ?, ?)
                """)
            .execute(Tuple.of(taskUuid, workflowId, messageId, taskType, severity, description))
            .map(rs -> taskUuid);
    }
}
