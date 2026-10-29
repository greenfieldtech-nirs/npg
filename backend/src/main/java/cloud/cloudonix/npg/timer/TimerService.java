package cloud.cloudonix.npg.timer;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.mysqlclient.MySQLClient;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public class TimerService {

    private final Pool pool;

    public TimerService(Pool pool) {
        this.pool = pool;
    }

    public Future<Long> createTimer(Long workflowId, Long messageId, String timerName,
                                     String timerType, LocalDateTime dueAt, JsonObject action) {
        String timerUuid = UUID.randomUUID().toString();
        return pool.preparedQuery("""
                INSERT INTO np_timers (timer_uuid, workflow_id, message_id, timer_name, timer_type, starts_at, due_at, action_json)
                VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6), ?, ?)
                """)
            .execute(Tuple.of(timerUuid, workflowId, messageId, timerName, timerType, dueAt,
                action != null ? action.encode() : null))
            .map(rs -> rs.property(MySQLClient.LAST_INSERTED_ID));
    }

    public Future<RowSet<Row>> claimDueTimers(String workerId, int batchSize, int lockSeconds) {
        LocalDateTime lockUntil = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(lockSeconds);
        return pool.withTransaction(tx -> tx.preparedQuery("""
                UPDATE np_timers
                SET status = 'CLAIMED',
                    locked_by = ?,
                    locked_until = ?,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE status = 'PENDING'
                  AND due_at <= UTC_TIMESTAMP(6)
                  AND (locked_until IS NULL OR locked_until < UTC_TIMESTAMP(6))
                ORDER BY due_at ASC
                LIMIT ?
                """)
            .execute(Tuple.of(workerId, lockUntil, batchSize))
            .compose(u -> tx.preparedQuery("""
                SELECT * FROM np_timers
                WHERE locked_by = ? AND status = 'CLAIMED'
                ORDER BY due_at ASC
                """)
                .execute(Tuple.of(workerId))));
    }

    public Future<RowSet<Row>> markFired(Long timerId) {
        return pool.preparedQuery("""
                UPDATE np_timers SET status = 'SUCCEEDED', fired_at = UTC_TIMESTAMP(6), attempts = attempts + 1,
                                     updated_at = UTC_TIMESTAMP(6) WHERE id = ?
                """)
            .execute(Tuple.of(timerId));
    }

    public Future<RowSet<Row>> markFailedRetryable(Long timerId, LocalDateTime nextDueAt) {
        return pool.preparedQuery("""
                UPDATE np_timers SET status = 'PENDING', due_at = ?, attempts = attempts + 1,
                                     updated_at = UTC_TIMESTAMP(6) WHERE id = ?
                """)
            .execute(Tuple.of(nextDueAt, timerId));
    }

    public Future<RowSet<Row>> cancelTimer(Long timerId) {
        return pool.preparedQuery("""
                UPDATE np_timers SET status = 'CANCELLED', cancelled_at = UTC_TIMESTAMP(6),
                                     updated_at = UTC_TIMESTAMP(6) WHERE id = ?
                """)
            .execute(Tuple.of(timerId));
    }
}
