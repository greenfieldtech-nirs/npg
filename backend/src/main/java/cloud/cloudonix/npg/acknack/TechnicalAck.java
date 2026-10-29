package cloud.cloudonix.npg.acknack;

import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.UUID;

public record TechnicalAck(TechnicalAckStatus status, String ackId, String correlationId,
                           String originalTransactionId, NackReason nackReason, Instant timestamp) {

    public static TechnicalAck ack(String correlationId, String originalTransactionId) {
        return new TechnicalAck(TechnicalAckStatus.ACK, UUID.randomUUID().toString(),
            correlationId, originalTransactionId, null, Instant.now());
    }

    public static TechnicalAck nack(String correlationId, String originalTransactionId, NackReason reason) {
        return new TechnicalAck(TechnicalAckStatus.NACK, UUID.randomUUID().toString(),
            correlationId, originalTransactionId, reason, Instant.now());
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject()
            .put("status", status.name())
            .put("ackId", ackId)
            .put("correlationId", correlationId)
            .put("originalTransactionId", originalTransactionId)
            .put("timestamp", timestamp.toString());
        if (nackReason != null) {
            json.put("nackReason", new JsonObject()
                .put("code", nackReason.code())
                .put("field", nackReason.field())
                .put("message", nackReason.message())
                .put("layer", nackReason.layer())
                .put("retrySafe", nackReason.retrySafe()));
        }
        return json;
    }

    public String toXml() {
        StringBuilder sb = new StringBuilder();
        sb.append("\u003cAckNack\u003e");
        sb.append("\u003cStatus\u003e").append(status.name()).append("\u003c/Status\u003e");
        sb.append("\u003cAckId\u003e").append(ackId).append("\u003c/AckId\u003e");
        sb.append("\u003cCorrelationId\u003e").append(correlationId).append("\u003c/CorrelationId\u003e");
        sb.append("\u003cOriginalTransactionId\u003e").append(originalTransactionId).append("\u003c/OriginalTransactionId\u003e");
        sb.append("\u003cTimestamp\u003e").append(timestamp.toString()).append("\u003c/Timestamp\u003e");
        if (nackReason != null) {
            sb.append("\u003cNackReason\u003e");
            sb.append("\u003cCode\u003e").append(escapeXml(nackReason.code())).append("\u003c/Code\u003e");
            sb.append("\u003cMessage\u003e").append(escapeXml(nackReason.message())).append("\u003c/Message\u003e");
            sb.append("\u003c/NackReason\u003e");
        }
        sb.append("\u003c/AckNack\u003e");
        return sb.toString();
    }

    private String escapeXml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }
}
