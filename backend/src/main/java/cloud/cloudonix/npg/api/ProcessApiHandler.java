package cloud.cloudonix.npg.api;

import cloud.cloudonix.npg.workflow.PublicationTracker;
import cloud.cloudonix.npg.workflow.WorkflowServices;
import cloud.cloudonix.npg.workflow.WorkflowSupport;
import cloud.cloudonix.npg.workflow.handler.InquiryWorkflowHandler;
import cloud.cloudonix.npg.workflow.handler.MaintenanceWorkflowHandler;
import cloud.cloudonix.npg.workflow.handler.NumberReturnWorkflowHandler;
import cloud.cloudonix.npg.workflow.handler.SynchronizationWorkflowHandler;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/** NOC-initiated protocol processes besides porting: return, inquiry, maintenance, synchronization. */
public class ProcessApiHandler {

    private final NumberReturnWorkflowHandler returns;
    private final InquiryWorkflowHandler inquiries;
    private final MaintenanceWorkflowHandler maintenance;
    private final SynchronizationWorkflowHandler sync;

    public ProcessApiHandler(WorkflowServices services) {
        this.returns = new NumberReturnWorkflowHandler(services, new PublicationTracker(new WorkflowSupport(services)));
        this.inquiries = new InquiryWorkflowHandler(services);
        this.maintenance = new MaintenanceWorkflowHandler(services);
        this.sync = new SynchronizationWorkflowHandler(services);
    }

    public Future<JsonObject> startReturn(String phoneNumber, String actor, String remoteIp) {
        return returns.startReturn(phoneNumber, actor, remoteIp);
    }

    public Future<JsonObject> inquire(JsonObject body, String actor, String remoteIp) {
        return inquiries.inquire(body.getString("phoneNumber"), body.getString("operatorCode"), actor, remoteIp);
    }

    public Future<JsonObject> announce(boolean down, JsonObject body, String actor, String remoteIp) {
        return maintenance.announce(down, body.getString("reason"), body.getString("expectedDuration"), actor, remoteIp);
    }

    public Future<JsonObject> requestSync(JsonObject body, String actor, String remoteIp) {
        return sync.requestSync(body.getString("operatorCode"), body.getString("syncType"), actor, remoteIp);
    }
}
