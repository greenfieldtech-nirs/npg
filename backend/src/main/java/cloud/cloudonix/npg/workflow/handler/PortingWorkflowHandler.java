package cloud.cloudonix.npg.workflow.handler;

import cloud.cloudonix.npg.audit.XmlAuditService;
import cloud.cloudonix.npg.number.NumberOwnershipService;
import cloud.cloudonix.npg.protocol.ProtocolCatalogLoader;
import cloud.cloudonix.npg.workflow.WorkflowEngine;
import cloud.cloudonix.npg.xml.SecureXmlParser;
import cloud.cloudonix.npg.xml.XmlParseResult;
import io.vertx.core.Future;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.util.Map;

public class PortingWorkflowHandler implements WorkflowHandler {

    private static final Logger log = LoggerFactory.getLogger(PortingWorkflowHandler.class);

    private final Pool pool;
    private final XmlAuditService xmlAuditService;
    private final ProtocolCatalogLoader catalogLoader;
    private final WorkflowEngine workflowEngine;
    private final NumberOwnershipService ownershipService;

    public PortingWorkflowHandler(Pool pool, XmlAuditService xmlAuditService,
                                  ProtocolCatalogLoader catalogLoader, WorkflowEngine workflowEngine,
                                  NumberOwnershipService ownershipService) {
        this.pool = pool;
        this.xmlAuditService = xmlAuditService;
        this.catalogLoader = catalogLoader;
        this.workflowEngine = workflowEngine;
        this.ownershipService = ownershipService;
    }

    @Override
    public Future<Void> handle(Row inboxItem) {
        String itemType = inboxItem.getString("item_type");
        return switch (itemType) {
            case "Check" -> handleCheck(inboxItem);
            case "Check_response" -> handleCheckResponse(inboxItem);
            case "Request" -> handleRequest(inboxItem);
            case "Request_response" -> handleRequestResponse(inboxItem);
            default -> Future.succeededFuture();
        };
    }

    private Future<Void> handleCheck(Row inboxItem) {
        Long messageId = inboxItem.getLong("message_id");
        return pool.preparedQuery("SELECT message_uuid, request_id, transaction_id FROM np_messages WHERE id = ?")
            .execute(Tuple.of(messageId))
            .compose(rows -> {
                if (!rows.iterator().hasNext()) {
                    log.warn("Check inbox item references missing message: " + messageId);
                    return Future.succeededFuture();
                }
                Row message = rows.iterator().next();
                String messageUuid = message.getString("message_uuid");
                String requestId = message.getString("request_id");
                return xmlAuditService.findByMessageUuid(messageUuid)
                    .compose(doc -> {
                        if (doc == null) {
                            log.warn("No raw XML audit doc for message_uuid " + messageUuid);
                            return Future.succeededFuture();
                        }
                        XmlParseResult parsed = new SecureXmlParser().parse(doc.getString("rawXml"));
                        if (!parsed.success()) {
                            // Already NACKed upstream; nothing to process.
                            log.warn("Stored XML failed to parse for message_uuid " + messageUuid
                                + ": " + parsed.errorMessage());
                            return Future.succeededFuture();
                        }
                        JsonObject bodyFields = catalogLoader
                            .extractBody(parsed.document(), "Check", Map.of())
                            .toJson();
                        String phoneNumber = bodyFields.getString("phoneNumber");
                        if (phoneNumber == null || phoneNumber.isBlank()) {
                            log.warn("Check message has no phone number, message_uuid " + messageUuid);
                            return Future.succeededFuture();
                        }
                        // networkType: F = fixed/landline, M = mobile (trace-observed protocol values)
                        String numberType = switch (String.valueOf(bodyFields.getString("networkType", ""))) {
                            case "F" -> "FIXED";
                            case "M" -> "MOBILE";
                            default -> "UNKNOWN";
                        };
                        return workflowEngine.createWorkflow("PORTING", requestId, phoneNumber, null, null, "NEW")
                            .compose(workflowId -> workflowEngine.transition(workflowId, "CHECK_SENT",
                                    "CHECK_RECEIVED", messageId,
                                    new JsonObject().put("messageType", "Check"), "system")
                                .compose(v -> ownershipService.updateCurrentOperator(phoneNumber, null,
                                    "PORT_CHECK", workflowId, messageId, numberType)));
                    });
            })
            .onFailure(err -> log.error("Failed to process Check inbox item " + inboxItem.getLong("id"), err));
    }

    private Future<Void> handleCheckResponse(Row inboxItem) {
        return Future.succeededFuture();
    }

    private Future<Void> handleRequest(Row inboxItem) {
        return Future.succeededFuture();
    }

    private Future<Void> handleRequestResponse(Row inboxItem) {
        return Future.succeededFuture();
    }
}
