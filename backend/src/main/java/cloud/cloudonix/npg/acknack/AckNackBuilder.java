package cloud.cloudonix.npg.acknack;

import cloud.cloudonix.npg.protocol.ProtocolHeader;

public class AckNackBuilder {

    public static TechnicalAck ack(ProtocolHeader header) {
        return TechnicalAck.ack(header.correlationId(), header.transactionId());
    }

    public static TechnicalAck nack(ProtocolHeader header, String code, String field, String message, String layer, boolean retrySafe) {
        return TechnicalAck.nack(
            header != null ? header.correlationId() : null,
            header != null ? header.transactionId() : null,
            new NackReason(code, field, message, layer, retrySafe)
        );
    }
}
