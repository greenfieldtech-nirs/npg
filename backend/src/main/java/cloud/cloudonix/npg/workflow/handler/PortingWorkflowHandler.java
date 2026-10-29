package cloud.cloudonix.npg.workflow.handler;

import cloud.cloudonix.npg.workflow.InboundMessage;
import cloud.cloudonix.npg.workflow.PublicationTracker;
import cloud.cloudonix.npg.workflow.Workflow;
import cloud.cloudonix.npg.workflow.WorkflowServices;
import cloud.cloudonix.npg.workflow.porting.PortingService;
import cloud.cloudonix.npg.workflow.porting.PortingSteps;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;

/** Routes porting messages and timers to {@link PortingService}. */
public class PortingWorkflowHandler implements WorkflowHandler {

    private final WorkflowServices services;
    private final PortingSteps steps;

    public PortingWorkflowHandler(WorkflowServices services, PortingService porting) {
        this.services = services;
        this.steps = new PortingSteps(porting);
    }

    @Override
    public Future<Void> handle(SqlConnection tx, Row inboxItem) {
        return InboundMessage.load(tx, services.xmlAudit(), services.catalog(), inboxItem)
            .compose(message -> steps.onMessage(tx, message));
    }

    @Override
    public Future<Boolean> onTimer(SqlConnection tx, Workflow workflow, Row timer) {
        Object raw = timer.getValue("action_json");
        JsonObject action = raw == null ? new JsonObject() : raw instanceof JsonObject jo ? jo : new JsonObject(raw.toString());
        return steps.onTimer(tx, workflow, timer.getString("timer_name"), action.getString("action"));
    }

    PublicationTracker publication() {
        return steps.publication();
    }
}
