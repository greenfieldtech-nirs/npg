package cloud.cloudonix.npg.workflow.porting;

import cloud.cloudonix.npg.workflow.InboundMessage;
import cloud.cloudonix.npg.workflow.PublicationTracker;
import cloud.cloudonix.npg.workflow.Workflow;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;

/** Dispatch table from protocol events to {@link PortingService} steps (kept package-private there). */
public class PortingSteps {

    private final PortingService porting;

    public PortingSteps(PortingService porting) {
        this.porting = porting;
    }

    public PublicationTracker publication() {
        return porting.publication();
    }

    public Future<Void> onMessage(SqlClient tx, InboundMessage message) {
        return switch (message.messageType()) {
            // we are the donor
            case "Check" -> porting.onCheck(tx, message);
            case "Request" -> porting.onRequest(tx, message);
            case "Update" -> porting.onUpdate(tx, message);
            case "Cancel" -> porting.onCancel(tx, message);
            case "KD_update" -> porting.onKdUpdate(tx, message);
            case "Execute" -> porting.onExecute(tx, message);
            // we are the recipient
            case "Check_response" -> porting.onCheckResponse(tx, message);
            case "Request_response" -> porting.onRequestResponse(tx, message);
            case "Update_response" -> porting.onUpdateResponse(tx, message);
            case "Cancel_response" -> porting.onCancelResponse(tx, message);
            case "KD_update_response" -> porting.onKdUpdateResponse(tx, message);
            case "Execute_response" -> porting.onExecuteResponse(tx, message);
            default -> Future.failedFuture("Not a porting message: " + message.messageType());
        };
    }

    /** True when handled; false leaves the generic response-timeout escalation to the registry. */
    public Future<Boolean> onTimer(SqlClient tx, Workflow wf, String timerName, String action) {
        if (PortingService.EXECUTE_ACTION.equals(action)) {
            return porting.execute(tx, wf).map(true);
        }
        if ("PUBLICATION_RESPONSE_TIMEOUT".equals(timerName)) {
            return porting.publication().onTimeout(tx, wf, PublicationTracker.PUBLISH).map(true);
        }
        if ("CANCEL_PUBLISH_RESPONSE_TIMEOUT".equals(timerName)) {
            return porting.publication().onTimeout(tx, wf, PublicationTracker.CANCEL_PUBLISH).map(true);
        }
        return Future.succeededFuture(false);
    }
}
