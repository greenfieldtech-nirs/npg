package cloud.cloudonix.npg.timer;

import cloud.cloudonix.npg.repository.ManualTaskRepository;
import io.vertx.core.Future;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Fires claimed timers. A failed action is retried with backoff; after {@link #MAX_ATTEMPTS} the
 * timer is marked FAILED and a manual task is opened, so a deadline is never silently lost.
 * Actions themselves must be idempotent: a timer reclaimed after a crash may fire twice.
 */
public class TimerProcessor {

    private static final Logger log = LoggerFactory.getLogger(TimerProcessor.class);
    static final int MAX_ATTEMPTS = 5;

    private final Pool pool;
    private final TimerService timers;
    private final Function<Row, Future<Void>> action;

    public TimerProcessor(Pool pool, Function<Row, Future<Void>> action) {
        this.pool = pool;
        this.timers = new TimerService(pool);
        this.action = action;
    }

    public Future<Void> fireAll(Iterable<Row> rows) {
        List<Future<Void>> all = new ArrayList<>();
        for (Row row : rows) {
            all.add(fireOne(row).recover(err -> {
                log.error("Timer " + row.getLong("id") + " could not be settled", err);
                return Future.succeededFuture();
            }));
        }
        return Future.join(all).mapEmpty();
    }

    Future<Void> fireOne(Row row) {
        Long id = row.getLong("id");
        String token = row.getString("locked_by");
        return action.apply(row).transform(ar -> {
            if (ar.succeeded()) {
                return timers.markFired(id, token).mapEmpty();
            }
            int attemptNumber = row.getInteger("attempts") + 1;
            if (attemptNumber >= MAX_ATTEMPTS) {
                log.error("Timer " + id + " (" + row.getString("timer_name") + ") failed permanently: " + ar.cause().getMessage());
                return pool.withTransaction(tx -> new TimerService(tx).markFailedFinal(id, token)
                    .compose(owned -> owned
                        ? new ManualTaskRepository(tx).open(row.getLong("workflow_id"), row.getLong("message_id"),
                            "TIMER_ACTION_FAILED", ManualTaskRepository.SEVERITY_HIGH,
                            "Timer " + row.getString("timer_name") + " could not fire after " + attemptNumber
                                + " attempts: " + ar.cause().getMessage()).mapEmpty()
                        : Future.<Void>succeededFuture()));
            }
            log.warn("Timer " + id + " action failed (attempt " + attemptNumber + "), will retry: " + ar.cause().getMessage());
            return timers.markFailedRetryable(id, token,
                LocalDateTime.now(ZoneOffset.UTC).plus(Duration.ofSeconds(30L << (attemptNumber - 1)))).mapEmpty();
        });
    }
}
