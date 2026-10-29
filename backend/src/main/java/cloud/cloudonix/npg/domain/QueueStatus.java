package cloud.cloudonix.npg.domain;

public enum QueueStatus {
    PENDING,
    CLAIMED,
    PROCESSING,
    SENDING,
    WAITING,
    SUCCEEDED,
    FAILED_RETRYABLE,
    FAILED_FINAL,
    UNKNOWN_DELIVERY,
    CANCELLED,
    MANUAL_REVIEW
}
