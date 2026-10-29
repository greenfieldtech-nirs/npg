package cloud.cloudonix.npg.workflow;

import io.vertx.core.json.JsonObject;

public record WorkflowCommand(String commandType, Long workflowId, String actor, String reason, JsonObject payload) {
}
