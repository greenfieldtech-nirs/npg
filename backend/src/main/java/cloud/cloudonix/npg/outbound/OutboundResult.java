package cloud.cloudonix.npg.outbound;

public record OutboundResult(boolean success, String ackStatus, String nackCode,
                             String errorCode, String errorMessage, boolean transportError) {

    public static OutboundResult ack() {
        return new OutboundResult(true, "ACK", null, null, null, false);
    }

    public static OutboundResult nack(String code, String message) {
        return new OutboundResult(true, "NACK", code, null, message, false);
    }

    public static OutboundResult transportError(String code, String message) {
        return new OutboundResult(false, null, null, code, message, true);
    }
}
