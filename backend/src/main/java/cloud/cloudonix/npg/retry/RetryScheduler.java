package cloud.cloudonix.npg.retry;

import cloud.cloudonix.npg.repository.OutboxRepository;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;

import java.time.LocalDateTime;

public class RetryScheduler {

    private final OutboxRepository outboxRepository;

    public RetryScheduler(Pool pool) {
        this.outboxRepository = new OutboxRepository(pool);
    }

    public Future<Void> scheduleRetry(Long outboxId, LocalDateTime nextDueAt, String errorCode, String errorMessage) {
        return outboxRepository.markRetryableFailure(outboxId, errorCode, errorMessage, nextDueAt).mapEmpty();
    }

    public Future<Void> escalateToManualReview(Long outboxId, String reason) {
        return outboxRepository.markManualReview(outboxId, reason).mapEmpty();
    }
}
