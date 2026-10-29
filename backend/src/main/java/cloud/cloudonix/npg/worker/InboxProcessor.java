package cloud.cloudonix.npg.worker;

import cloud.cloudonix.npg.repository.InboxRepository;
import cloud.cloudonix.npg.repository.ManualTaskRepository;
import io.vertx.core.Future;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Processes claimed inbox rows: CLAIMED → PROCESSING → SUCCEEDED, or FAILED_RETRYABLE with
 * exponential backoff, or FAILED_FINAL plus a manual task once retries are exhausted or the
 * failure is permanent. Every status change is fenced by the row's claim token.
 *
 * <p>The handler's database effects and the fenced SUCCEEDED update commit in one transaction. If
 * the claim was lost meanwhile (the handler outlived its lock and another worker reclaimed the
 * row), the fenced update matches nothing and the whole transaction rolls back — so business
 * effects are applied exactly once even when two workers process the same item.
 *
 * <p>A row reclaimed after a worker crash arrives with its attempts already counted, so a message
 * that crashes the worker every time ends in FAILED_FINAL instead of looping forever.
 */
public class InboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(InboxProcessor.class);
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(10);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(15);

    private final Pool pool;
    private final InboxRepository inbox;
    private final BiFunction<SqlConnection, Row, Future<Void>> handler;
    private final int lockSeconds;

    public InboxProcessor(Pool pool, BiFunction<SqlConnection, Row, Future<Void>> handler, int lockSeconds) {
        this.pool = pool;
        this.inbox = new InboxRepository(pool);
        this.handler = handler;
        this.lockSeconds = lockSeconds;
    }

    /** Processes every row; completes when all are settled (individual failures are recorded, not propagated). */
    public Future<Void> processAll(Iterable<Row> rows) {
        List<Future<Void>> all = new ArrayList<>();
        for (Row row : rows) {
            all.add(processOne(row).recover(err -> {
                log.error("Inbox item " + row.getLong("id") + " could not be settled", err);
                return Future.succeededFuture();
            }));
        }
        return Future.join(all).mapEmpty();
    }

    Future<Void> processOne(Row row) {
        Long id = row.getLong("id");
        String token = row.getString("locked_by");
        int attempts = row.getInteger("attempts");
        int maxAttempts = row.getInteger("max_attempts");
        if (attempts >= maxAttempts) {
            return failFinal(row, token, "RETRIES_EXHAUSTED",
                "Gave up after " + attempts + " attempts (worker stopped mid-processing or kept failing)");
        }
        return inbox.markProcessing(id, token, lockSeconds).compose(owned -> {
            if (!owned) {
                return Future.succeededFuture(); // reclaimed by another worker meanwhile
            }
            return pool.withTransaction(tx -> handler.apply(tx, row)
                    .compose(v -> new InboxRepository(tx).markSucceeded(id, token))
                    .compose(stillOwned -> stillOwned
                        ? Future.<Void>succeededFuture()
                        : Future.failedFuture(new LostClaimException(id))))
                .transform(ar -> {
                if (ar.succeeded()) {
                    return Future.<Void>succeededFuture();
                }
                Throwable err = ar.cause();
                if (err instanceof LostClaimException) {
                    log.warn("Inbox item " + id + " was reclaimed by another worker; rolled back this attempt");
                    return Future.<Void>succeededFuture();
                }
                int attemptNumber = attempts + 1;
                if (err instanceof PermanentProcessingException || attemptNumber >= maxAttempts) {
                    String code = err instanceof PermanentProcessingException ? "PERMANENT_FAILURE" : "RETRIES_EXHAUSTED";
                    return failFinal(row, token, code, String.valueOf(err.getMessage()));
                }
                log.warn("Inbox item " + id + " failed (attempt " + attemptNumber + "/" + maxAttempts + "), will retry: "
                    + err.getMessage());
                return inbox.markRetryableFailure(id, token, "HANDLER_ERROR", String.valueOf(err.getMessage()),
                    LocalDateTime.now(ZoneOffset.UTC).plus(backoff(attemptNumber))).mapEmpty();
            });
        });
    }

    /** FAILED_FINAL and the manual task commit together, so a failed item is never left without a task. */
    private Future<Void> failFinal(Row row, String token, String code, String message) {
        Long id = row.getLong("id");
        log.error("Inbox item " + id + " failed permanently (" + code + "): " + message);
        return pool.withTransaction(tx -> new InboxRepository(tx).markFinalFailure(id, token, code, message)
            .compose(owned -> owned
                ? new ManualTaskRepository(tx).open(row.getLong("workflow_id"), row.getLong("message_id"),
                    "INBOX_PROCESSING_FAILED", ManualTaskRepository.SEVERITY_HIGH,
                    "Inbound " + row.getString("item_type") + " could not be processed (" + code + "): " + message)
                    .mapEmpty()
                : Future.<Void>succeededFuture()));
    }

    static Duration backoff(int attemptNumber) {
        long seconds = BASE_BACKOFF.getSeconds() << Math.min(attemptNumber - 1, 20);
        return seconds >= MAX_BACKOFF.getSeconds() ? MAX_BACKOFF : Duration.ofSeconds(seconds);
    }

    /** The fenced commit found the row owned by someone else: this attempt must not take effect. */
    private static final class LostClaimException extends RuntimeException {
        LostClaimException(Long inboxId) {
            super("Lost claim on inbox item " + inboxId, null, false, false);
        }
    }
}
