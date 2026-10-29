package cloud.cloudonix.npg.timer;

import cloud.cloudonix.npg.workflow.WorkflowEngine;
import cloud.cloudonix.npg.workflow.handler.WorkflowHandlerRegistry;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;

/**
 * Runs a fired timer's action. The workflow is locked first; if it already left the state the
 * timer guards (the response arrived, the port was cancelled...) the timer is a no-op. That check
 * is what makes a timer that fires twice after a crash safe.
 */
public class TimerActionDispatcher {

    private final WorkflowEngine engine;
    private final WorkflowHandlerRegistry registry;

    public TimerActionDispatcher(WorkflowEngine engine, WorkflowHandlerRegistry registry) {
        this.engine = engine;
        this.registry = registry;
    }

    public Future<Void> dispatch(SqlConnection tx, Row timerRow) {
        Long workflowId = timerRow.getLong("workflow_id");
        if (workflowId == null) {
            return Future.succeededFuture();
        }
        String expectedState = actionJson(timerRow).getString("expectedState");
        return engine.lock(tx, workflowId).compose(wf -> {
            if (expectedState != null && !expectedState.equals(wf.state())) {
                return Future.succeededFuture(); // the workflow moved on: nothing to do
            }
            return registry.onTimer(tx, wf, timerRow);
        });
    }

    static JsonObject actionJson(Row row) {
        Object value = row.getValue("action_json");
        if (value == null) {
            return new JsonObject();
        }
        return value instanceof JsonObject jo ? jo : new JsonObject(value.toString());
    }
}
