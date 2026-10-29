package cloud.cloudonix.npg.outbound;

import cloud.cloudonix.npg.audit.XmlAuditService;
import cloud.cloudonix.npg.audit.XmlTransportMetadata;
import cloud.cloudonix.npg.repository.MessageRepository;
import cloud.cloudonix.npg.repository.OutboxRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public class OutboxWriter {

    private final Pool pool;
    private final XmlAuditService xmlAuditService;
    private final OutboxRepository outboxRepository;
    private final MessageRepository messageRepository;

    public OutboxWriter(Pool pool, XmlAuditService xmlAuditService) {
        this.pool = pool;
        this.xmlAuditService = xmlAuditService;
        this.outboxRepository = new OutboxRepository(pool);
        this.messageRepository = new MessageRepository(pool);
    }

    public Future<Long> writeOutbound(Long workflowId, Long destinationOperatorId, String messageType,
                                      String senderOperatorCode, String receiverOperatorCode,
                                      String requestId, String transactionId, String correlationId,
                                      String schemaVersion, JsonObject body) {
        String messageUuid = UUID.randomUUID().toString();
        OutboundMessageBuilder builder = new OutboundMessageBuilder();
        JsonObject header = new JsonObject()
            .put("senderOperator", senderOperatorCode)
            .put("receiverOperator", receiverOperatorCode)
            .put("messageType", messageType)
            .put("requestId", requestId)
            .put("transactionId", transactionId)
            .put("correlationId", correlationId)
            .put("timestamp", LocalDateTime.now(ZoneOffset.UTC).toString())
            .put("protocolVersion", schemaVersion);
        String rawXml = builder.build(messageType, header, body);

        return xmlAuditService.storeOutboundRawXml(messageUuid, null, messageType, senderOperatorCode,
                receiverOperatorCode, requestId, transactionId, correlationId, schemaVersion, rawXml,
                new XmlTransportMetadata("HTTPS", null, null, new JsonObject()))
            .compose(mongoDocId -> pool.withTransaction(tx -> {
                MessageRepository txMessageRepo = new MessageRepository(tx);
                OutboxRepository txOutboxRepo = new OutboxRepository(tx);
                String idempotencyKey = String.join("|", "OUTBOUND", senderOperatorCode, receiverOperatorCode,
                    messageType, String.valueOf(workflowId), transactionId);
                return txMessageRepo.insertMessage(messageUuid, messageType, "OUTBOUND", null, destinationOperatorId,
                        requestId, transactionId, correlationId, idempotencyKey, mongoDocId,
                        XmlAuditService.sha256(rawXml), schemaVersion)
                    .compose(messageId -> txOutboxRepo.createOutboxItem(messageId, workflowId, destinationOperatorId,
                            messageType, LocalDateTime.now(ZoneOffset.UTC), 100, 3));
            }));
    }
}
