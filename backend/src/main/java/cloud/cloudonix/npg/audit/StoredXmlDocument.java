package cloud.cloudonix.npg.audit;

import io.vertx.core.json.JsonObject;

import java.time.Instant;

public record StoredXmlDocument(
    String messageUuid,
    String workflowUuid,
    String direction,
    String messageType,
    String senderOperatorCode,
    String receiverOperatorCode,
    String requestId,
    String transactionId,
    String correlationId,
    String schemaVersion,
    String rawXml,
    String canonicalSha256,
    XmlValidationMetadata validation,
    XmlTransportMetadata transport,
    Instant createdAt
) {

    public JsonObject toJson() {
        return new JsonObject()
            .put("messageUuid", messageUuid)
            .put("workflowUuid", workflowUuid)
            .put("direction", direction)
            .put("messageType", messageType)
            .put("senderOperatorCode", senderOperatorCode)
            .put("receiverOperatorCode", receiverOperatorCode)
            .put("requestId", requestId)
            .put("transactionId", transactionId)
            .put("correlationId", correlationId)
            .put("schemaVersion", schemaVersion)
            .put("rawXml", rawXml)
            .put("canonicalSha256", canonicalSha256)
            .put("validation", validation.toJson())
            .put("transport", transport.toJson())
            .put("createdAt", createdAt.toString());
    }
}
