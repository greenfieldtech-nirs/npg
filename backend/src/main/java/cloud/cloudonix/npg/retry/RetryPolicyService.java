package cloud.cloudonix.npg.retry;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public class RetryPolicyService {

    public RetryDecision decide(String messageType, String workflowState, int attempts, int maxAttempts,
                                String ackStatus, String businessResponseStatus, boolean operatorDown,
                                String errorCode, boolean unknownDelivery) {
        if (unknownDelivery) {
            return new RetryDecision(RetryDecision.RetryDecisionType.UNKNOWN_DELIVERY_MANUAL_REVIEW,
                null, "Unknown delivery after dispatcher crash");
        }
        if (operatorDown) {
            return new RetryDecision(RetryDecision.RetryDecisionType.WAIT_FOR_OPERATOR_UP,
                LocalDateTime.now(ZoneOffset.UTC).plus(Duration.ofMinutes(5)), "Operator is marked down");
        }
        if ("NACK".equals(ackStatus)) {
            return new RetryDecision(RetryDecision.RetryDecisionType.DO_NOT_RETRY_FINAL,
                null, "Remote operator returned NACK");
        }
        if ("REJECTED".equals(businessResponseStatus)) {
            return new RetryDecision(RetryDecision.RetryDecisionType.DO_NOT_RETRY_FINAL,
                null, "Business response was rejection");
        }
        if (attempts >= maxAttempts) {
            return new RetryDecision(RetryDecision.RetryDecisionType.MANUAL_REVIEW,
                null, "Max retry attempts exceeded");
        }
        return new RetryDecision(RetryDecision.RetryDecisionType.RETRY_LATER,
            LocalDateTime.now(ZoneOffset.UTC).plusSeconds((long) Math.pow(2, attempts) * 10),
            "Retry with exponential backoff");
    }
}
