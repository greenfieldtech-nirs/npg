package cloud.cloudonix.npg.acknack;

import cloud.cloudonix.npg.protocol.ProtocolHeader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AckNackBuilderTest {

    @Test
    void ackHasStatusAck() {
        ProtocolHeader header = new ProtocolHeader("OP01", "OP02", "Check", "REQ-1", "TX-1", null, null, "4.0");
        TechnicalAck ack = AckNackBuilder.ack(header);
        assertEquals(TechnicalAckStatus.ACK, ack.status());
        assertEquals("TX-1", ack.originalTransactionId());
    }

    @Test
    void nackIncludesReason() {
        ProtocolHeader header = new ProtocolHeader("OP01", "OP02", "Check", "REQ-1", "TX-1", null, null, "4.0");
        TechnicalAck nack = AckNackBuilder.nack(header, "NACK_INVALID_XML", null, "Malformed", "TRANSPORT", false);
        assertEquals(TechnicalAckStatus.NACK, nack.status());
        assertNotNull(nack.nackReason());
        assertEquals("NACK_INVALID_XML", nack.nackReason().code());
    }
}
