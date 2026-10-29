package cloud.cloudonix.npg.api;

import cloud.cloudonix.npg.workflow.WorkflowEngine;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;

public class WorkflowApiHandler {

    private final Pool pool;
    private final WorkflowEngine workflowEngine;

    public WorkflowApiHandler(Pool pool) {
        this.pool = pool;
        this.workflowEngine = new WorkflowEngine(pool);
    }

    public Future<JsonArray> searchWorkflows(String phoneNumber, String state, int page, int size) {
        StringBuilder sql = new StringBuilder("SELECT * FROM np_workflows WHERE 1=1");
        Tuple params = Tuple.tuple();
        if (phoneNumber != null) {
            sql.append(" AND phone_number = ?");
            params.addValue(phoneNumber);
        }
        if (state != null) {
            sql.append(" AND current_state = ?");
            params.addValue(state);
        }
        sql.append(" ORDER BY updated_at DESC LIMIT ? OFFSET ?");
        params.addValue(size);
        params.addValue(page * size);
        return pool.preparedQuery(sql.toString())
            .execute(params)
            .map(RowJson::rowsToJsonArray);
    }

    public Future<JsonObject> getWorkflow(String workflowUuid) {
        return pool.preparedQuery("SELECT * FROM np_workflows WHERE workflow_uuid = ?")
            .execute(Tuple.of(workflowUuid))
            .map(rows -> rows.iterator().hasNext() ? RowJson.rowToJson(rows.iterator().next()) : null);
    }

    public Future<JsonArray> getWorkflowEvents(String workflowUuid) {
        return pool.preparedQuery("""
                SELECT e.* FROM np_workflow_events e
                JOIN np_workflows w ON e.workflow_id = w.id
                WHERE w.workflow_uuid = ?
                ORDER BY e.created_at DESC
                """)
            .execute(Tuple.of(workflowUuid))
            .map(RowJson::rowsToJsonArray);
    }

    public Future<Void> manualTransition(String workflowUuid, String toState, String reason, String actorId) {
        return pool.preparedQuery("SELECT id FROM np_workflows WHERE workflow_uuid = ?")
            .execute(Tuple.of(workflowUuid))
            .compose(rows -> {
                if (!rows.iterator().hasNext()) {
                    return Future.failedFuture("Workflow not found");
                }
                Long workflowId = rows.iterator().next().getLong("id");
                return workflowEngine.manualTransition(workflowId, toState, "MANUAL_TRANSITION",
                    new JsonObject().put("reason", reason), actorId);
            });
    }
}
