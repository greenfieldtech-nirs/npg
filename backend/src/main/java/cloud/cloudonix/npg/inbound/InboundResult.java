package cloud.cloudonix.npg.inbound;

import cloud.cloudonix.npg.acknack.TechnicalAck;

public record InboundResult(TechnicalAck ack, Long messageId, Long inboxId) {
}
