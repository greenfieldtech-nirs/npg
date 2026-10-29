package cloud.cloudonix.npg.workflow.handler;

import cloud.cloudonix.npg.audit.XmlAuditService;
import cloud.cloudonix.npg.number.NumberOwnershipService;
import cloud.cloudonix.npg.protocol.ProtocolCatalogLoader;
import cloud.cloudonix.npg.workflow.WorkflowEngine;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;

import java.util.HashMap;
import java.util.Map;

public class WorkflowHandlerRegistry {

    private final Map<String, WorkflowHandler> handlers = new HashMap<>();

    public WorkflowHandlerRegistry(Pool pool, XmlAuditService xmlAuditService,
                                   ProtocolCatalogLoader catalogLoader, WorkflowEngine workflowEngine,
                                   NumberOwnershipService ownershipService) {
        handlers.put("PORTING", new PortingWorkflowHandler(pool, xmlAuditService, catalogLoader,
            workflowEngine, ownershipService));
        handlers.put("PUBLICATION", new PublicationWorkflowHandler());
        handlers.put("NUMBER_RETURN", new NumberReturnWorkflowHandler());
        handlers.put("NUMBER_INQUIRY", new InquiryWorkflowHandler());
        handlers.put("MAINTENANCE", new MaintenanceWorkflowHandler());
        handlers.put("DATA_SYNCHRONIZATION", new SynchronizationWorkflowHandler());
    }

    public Future<Void> handle(Row inboxItem) {
        String itemType = inboxItem.getString("item_type");
        String family = resolveFamily(itemType);
        WorkflowHandler handler = handlers.get(family);
        if (handler == null) {
            return Future.failedFuture("No handler for message family: " + family);
        }
        return handler.handle(inboxItem);
    }

    private String resolveFamily(String messageType) {
        return switch (messageType) {
            case "Check", "Check_response", "Request", "Request_response", "Update", "Update_response",
                 "Cancel", "Cancel_response", "KD_update", "KD_update_response",
                 "Execute", "Execute_response" -> "PORTING";
            case "Publish", "Publish_response", "Cancel_Publish", "Cancel_Publish_response" -> "PUBLICATION";
            case "Return", "Return_response" -> "NUMBER_RETURN";
            case "Inquire_number", "Inquire_number_response" -> "NUMBER_INQUIRY";
            case "Down_system", "Up_system" -> "MAINTENANCE";
            case "DB_synch_request", "DB_synch_response" -> "DATA_SYNCHRONIZATION";
            default -> "PORTING";
        };
    }
}
