package cloud.cloudonix.npg.api;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;

public class DashboardApiHandler {

    private final Pool pool;

    public DashboardApiHandler(Pool pool) {
        this.pool = pool;
    }

    public Future<JsonObject> getDashboard() {
        return pool.query("""
                SELECT
                  (SELECT COUNT(*) FROM np_workflows WHERE current_state NOT IN ('COMPLETED', 'CANCELLED', 'FAILED')) AS activeWorkflows,
                  (SELECT COUNT(*) FROM np_inbox WHERE status IN ('PENDING', 'CLAIMED', 'PROCESSING')) AS inboxPending,
                  (SELECT COUNT(*) FROM np_outbox WHERE status IN ('PENDING', 'CLAIMED', 'SENDING')) AS outboxPending,
                  (SELECT COUNT(*) FROM np_manual_tasks WHERE status = 'OPEN') AS openManualTasks,
                  (SELECT COUNT(*) FROM np_workflows WHERE current_state = 'FAILED') AS failedWorkflows,
                  (SELECT COUNT(*) FROM np_operators WHERE availability_status = 'DOWN') AS operatorsDown,
                  (SELECT COUNT(*) FROM np_timers WHERE status = 'PENDING' AND due_at <= UTC_TIMESTAMP(6)) AS timersDue
                """
            )
            .execute()
            .map(rows -> {
                Row row = rows.iterator().next();
                // COUNT(*) returns Long in MySQL — getInteger would throw
                return new JsonObject()
                    .put("activeWorkflows", row.getLong("activeWorkflows"))
                    .put("inboxPending", row.getLong("inboxPending"))
                    .put("outboxPending", row.getLong("outboxPending"))
                    .put("openManualTasks", row.getLong("openManualTasks"))
                    .put("failedWorkflows", row.getLong("failedWorkflows"))
                    .put("operatorsDown", row.getLong("operatorsDown"))
                    .put("timersDue", row.getLong("timersDue"));
            });
    }
}
