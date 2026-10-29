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
        this.workflowEngine = new WorkflowEngine();
    }

    public Future<JsonArray> searchWorkflows(String phoneNumber, String state, int page, int size) {
        return searchWorkflows(new JsonObject().put("phoneNumber", phoneNumber).put("state", state), page, size);
    }

    /** Filters: phoneNumber, state, requestId, workflowType, localRole, operatorCode (counterparty). */
    public Future<JsonArray> searchWorkflows(JsonObject filters, int page, int size) {
        if (size < 1 || size > 500 || page < 0) {
            return Future.failedFuture(new ApiError(400, "page must be >= 0 and size between 1 and 500"));
        }
        StringBuilder sql = new StringBuilder("""
            SELECT w.*, c.operator_code AS counterparty_code FROM np_workflows w
            LEFT JOIN np_operators c ON c.id = w.counterparty_operator_id WHERE 1=1""");
        Tuple params = Tuple.tuple();
        filter(sql, params, " AND w.phone_number = ?", filters.getString("phoneNumber"));
        filter(sql, params, " AND w.current_state = ?", filters.getString("state"));
        filter(sql, params, " AND w.request_id = ?", filters.getString("requestId"));
        filter(sql, params, " AND w.workflow_type = ?", filters.getString("workflowType"));
        filter(sql, params, " AND w.local_role = ?", filters.getString("localRole"));
        filter(sql, params, " AND c.operator_code = ?", filters.getString("operatorCode"));
        sql.append(" ORDER BY w.updated_at DESC LIMIT ? OFFSET ?");
        params.addValue(size);
        params.addValue(page * size);
        return pool.preparedQuery(sql.toString())
            .execute(params)
            .map(RowJson::rowsToJsonArray);
    }

    private static void filter(StringBuilder sql, Tuple params, String clause, String value) {
        if (value != null && !value.isBlank()) {
            sql.append(clause);
            params.addValue(value.trim());
        }
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

    public Future<Void> manualTransition(String workflowUuid, String toState, String reason, String actorId,
                                         String remoteIp) {
        if (toState == null || toState.isBlank()) {
            return Future.failedFuture(new ApiError(400, "toState is required"));
        }
        if (reason == null || reason.isBlank()) {
            return Future.failedFuture(new ApiError(400, "A reason is required for manual transitions"));
        }
        return pool.withTransaction(tx -> workflowEngine.lockByUuid(tx, workflowUuid)
                .compose(wf -> workflowEngine.manualTransition(tx, wf, toState, reason, actorId, remoteIp)))
            .<Void>mapEmpty()
            .recover(err -> Future.failedFuture(
                err instanceof WorkflowEngine.WorkflowNotFoundException ? new ApiError(404, "Workflow not found")
                    : err instanceof WorkflowEngine.IllegalTransitionException ? new ApiError(400, err.getMessage())
                    : err));
    }
}
