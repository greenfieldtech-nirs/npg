package cloud.cloudonix.npg.workflow.handler;

import cloud.cloudonix.npg.repository.ManualTaskRepository;
import cloud.cloudonix.npg.worker.PermanentProcessingException;
import cloud.cloudonix.npg.workflow.StateMachines;
import cloud.cloudonix.npg.workflow.Workflow;
import cloud.cloudonix.npg.workflow.WorkflowServices;
import cloud.cloudonix.npg.workflow.porting.PortOutPolicy;
import cloud.cloudonix.npg.workflow.porting.PortingService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;

import java.util.HashMap;
import java.util.Map;

/** Routes inbound messages by message family and timers by workflow type. */
public class WorkflowHandlerRegistry {

    private final WorkflowServices services;
    private final Map<String, WorkflowHandler> handlers = new HashMap<>();

    public WorkflowHandlerRegistry(WorkflowServices services) {
        this.services = services;
        PortingWorkflowHandler porting = new PortingWorkflowHandler(services,
            new PortingService(services, PortOutPolicy.fromEnvironment()));
        handlers.put(StateMachines.PORTING, porting);
        handlers.put(StateMachines.PUBLICATION, new PublicationWorkflowHandler(services, porting.publication()));
        handlers.put(StateMachines.NUMBER_RETURN, new NumberReturnWorkflowHandler(services, porting.publication()));
        handlers.put(StateMachines.NUMBER_INQUIRY, new InquiryWorkflowHandler(services));
        handlers.put(StateMachines.MAINTENANCE, new MaintenanceWorkflowHandler(services));
        handlers.put(StateMachines.DATA_SYNCHRONIZATION, new SynchronizationWorkflowHandler(services));
    }

    public Future<Void> handle(SqlConnection tx, Row inboxItem) {
        String itemType = inboxItem.getString("item_type");
        String family = familyOf(itemType);
        WorkflowHandler handler = family != null ? handlers.get(family) : null;
        if (handler == null) {
            return Future.failedFuture(new PermanentProcessingException("No handler for message type: " + itemType));
        }
        return handler.handle(tx, inboxItem);
    }

    /** Fires a timer for a locked workflow that is still in the state the timer guards. */
    public Future<Void> onTimer(SqlConnection tx, Workflow workflow, Row timer) {
        WorkflowHandler handler = handlers.get(workflow.type());
        Future<Boolean> handled = handler != null ? handler.onTimer(tx, workflow, timer) : Future.succeededFuture(false);
        return handled.compose(done -> done ? Future.succeededFuture() : escalateTimeout(tx, workflow, timer));
    }

    /** Generic response timeout: stop and hand the workflow to the NOC (timer matrix: retry_or_manual_review). */
    private Future<Void> escalateTimeout(SqlConnection tx, Workflow workflow, Row timer) {
        String timerName = timer.getString("timer_name");
        JsonObject details = new JsonObject().put("timer", timerName).put("stateWhenExpired", workflow.state());
        return services.engine().transition(tx, workflow, StateMachines.MANUAL_REVIEW, "TIMER_EXPIRED", null, details, "system")
            .compose(wf -> new ManualTaskRepository(tx).open(workflow.id(), null, "RESPONSE_TIMEOUT",
                ManualTaskRepository.SEVERITY_MEDIUM,
                timerName + " expired while " + workflow.type() + " " + workflow.requestId() + " was in "
                    + workflow.state() + ". Contact the peer operator, then resume the workflow manually."))
            .mapEmpty();
    }

    static String familyOf(String messageType) {
        return switch (messageType) {
            case "Check", "Check_response", "Request", "Request_response", "Update", "Update_response",
                 "Cancel", "Cancel_response", "KD_update", "KD_update_response",
                 "Execute", "Execute_response" -> StateMachines.PORTING;
            case "Publish", "Publish_response", "Cancel_Publish", "Cancel_Publish_response" -> StateMachines.PUBLICATION;
            case "Return", "Return_response" -> StateMachines.NUMBER_RETURN;
            case "Inquire_number", "Inquire_number_response" -> StateMachines.NUMBER_INQUIRY;
            case "Down_system", "Up_system" -> StateMachines.MAINTENANCE;
            case "DB_synch_request", "DB_synch_response" -> StateMachines.DATA_SYNCHRONIZATION;
            default -> null;
        };
    }
}
