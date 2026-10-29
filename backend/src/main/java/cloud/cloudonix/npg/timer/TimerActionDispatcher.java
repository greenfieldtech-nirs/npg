package cloud.cloudonix.npg.timer;

import cloud.cloudonix.npg.workflow.WorkflowEngine;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;

public class TimerActionDispatcher {

    private final TimerService timerService;
    private final WorkflowEngine workflowEngine;

    public TimerActionDispatcher(TimerService timerService, WorkflowEngine workflowEngine) {
        this.timerService = timerService;
        this.workflowEngine = workflowEngine;
    }

    public Future<Void> dispatch(Row timerRow) {
        Long workflowId = timerRow.getLong("workflow_id");
        String timerType = timerRow.getString("timer_type");
        JsonObject action = actionJson(timerRow);

        // Idempotency: an illegal transition means the workflow already moved past this point.
        return workflowEngine.transition(workflowId, "MANUAL_REVIEW", timerType + "_FIRED",
                null, action, "system")
            .recover(err -> {
                if (err.getMessage() != null && err.getMessage().contains("Illegal transition")) {
                    return Future.succeededFuture();
                }
                return Future.failedFuture(err);
            })
            .mapEmpty();
    }

    private JsonObject actionJson(Row row) {
        Object value = row.getValue("action_json");
        if (value == null) {
            return new JsonObject();
        }
        if (value instanceof JsonObject jo) {
            return jo;
        }
        return new JsonObject(value.toString());
    }
}
