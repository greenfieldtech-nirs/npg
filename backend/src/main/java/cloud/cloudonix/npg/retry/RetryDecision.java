package cloud.cloudonix.npg.retry;

import java.time.LocalDateTime;

public record RetryDecision(RetryDecisionType type, LocalDateTime nextDueAt, String reason) {

    public enum RetryDecisionType {
        RETRY_NOW,
        RETRY_LATER,
        DO_NOT_RETRY_FINAL,
        MANUAL_REVIEW,
        WAIT_FOR_OPERATOR_UP,
        UNKNOWN_DELIVERY_MANUAL_REVIEW
    }
}
