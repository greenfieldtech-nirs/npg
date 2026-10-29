package cloud.cloudonix.npg.retry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyServiceTest {

    private final RetryPolicyService service = new RetryPolicyService();

    @Test
    void nackIsFinal() {
        RetryDecision decision = service.decide("Check", "CHECK_SENT", 1, 3, "NACK", null, false, null, false);
        assertEquals(RetryDecision.RetryDecisionType.DO_NOT_RETRY_FINAL, decision.type());
    }

    @Test
    void businessRejectionIsFinal() {
        RetryDecision decision = service.decide("Check", "CHECK_SENT", 1, 3, "ACK", "REJECTED", false, null, false);
        assertEquals(RetryDecision.RetryDecisionType.DO_NOT_RETRY_FINAL, decision.type());
    }

    @Test
    void timeoutRetriesWithBackoff() {
        RetryDecision decision = service.decide("Check", "CHECK_SENT", 1, 3, null, null, false, "TIMEOUT", false);
        assertEquals(RetryDecision.RetryDecisionType.RETRY_LATER, decision.type());
        assertNotNull(decision.nextDueAt());
    }

    @Test
    void maxAttemptsEscalates() {
        RetryDecision decision = service.decide("Check", "CHECK_SENT", 3, 3, null, null, false, "TIMEOUT", false);
        assertEquals(RetryDecision.RetryDecisionType.MANUAL_REVIEW, decision.type());
    }

    @Test
    void operatorDownWaits() {
        RetryDecision decision = service.decide("Check", "CHECK_SENT", 1, 3, null, null, true, null, false);
        assertEquals(RetryDecision.RetryDecisionType.WAIT_FOR_OPERATOR_UP, decision.type());
    }

    @Test
    void unknownDeliveryGoesToManualReview() {
        RetryDecision decision = service.decide("Check", "CHECK_SENT", 1, 3, null, null, false, null, true);
        assertEquals(RetryDecision.RetryDecisionType.UNKNOWN_DELIVERY_MANUAL_REVIEW, decision.type());
    }
}
