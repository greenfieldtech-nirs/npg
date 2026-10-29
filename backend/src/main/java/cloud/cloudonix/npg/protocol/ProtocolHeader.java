package cloud.cloudonix.npg.protocol;

import io.vertx.core.json.JsonObject;

public record ProtocolHeader(
    String senderOperator,
    String receiverOperator,
    String messageType,
    String requestId,
    String transactionId,
    String correlationId,
    String timestamp,
    String protocolVersion
) {

    public JsonObject toJson() {
        return new JsonObject()
            .put("senderOperator", senderOperator)
            .put("receiverOperator", receiverOperator)
            .put("messageType", messageType)
            .put("requestId", requestId)
            .put("transactionId", transactionId)
            .put("correlationId", correlationId)
            .put("timestamp", timestamp)
            .put("protocolVersion", protocolVersion);
    }

    public String buildIdempotencyKey() {
        return String.join("|",
            "INBOUND",
            senderOperator != null ? senderOperator : "",
            receiverOperator != null ? receiverOperator : "",
            messageType != null ? messageType : "",
            requestId != null ? requestId : "",
            transactionId != null ? transactionId : "");
    }
}
