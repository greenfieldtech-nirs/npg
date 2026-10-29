package cloud.cloudonix.npg.timer;

import cloud.cloudonix.npg.repository.QueueClaims;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.mysqlclient.MySQLClient;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public class TimerService {

    private final SqlClient pool;

    public TimerService(SqlClient pool) {
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

    // a CLAIMED timer whose lock expired belongs to a crashed timer worker and may be reclaimed
    private static final String CLAIMABLE = """
        due_at <= UTC_TIMESTAMP(6)
          AND (status = 'PENDING' OR (status = 'CLAIMED' AND locked_until < UTC_TIMESTAMP(6)))""";

    /** Claims due timers; each returned row's {@code locked_by} is its claim token. */
    public Future<RowSet<Row>> claimDueTimers(String workerId, int batchSize, int lockSeconds) {
        return QueueClaims.claim(pool, "np_timers", CLAIMABLE, "due_at ASC, id ASC", workerId, batchSize, lockSeconds);
    }

    public Future<Boolean> markFired(Long timerId, String claimToken) {
        return pool.preparedQuery("""
                UPDATE np_timers SET status = 'SUCCEEDED', fired_at = UTC_TIMESTAMP(6), attempts = attempts + 1,
                                     locked_by = NULL, locked_until = NULL, updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND locked_by = ?
                """)
            .execute(Tuple.of(timerId, claimToken))
            .map(rs -> rs.rowCount() == 1);
    }

    public Future<Boolean> markFailedRetryable(Long timerId, String claimToken, LocalDateTime nextDueAt) {
        return pool.preparedQuery("""
                UPDATE np_timers SET status = 'PENDING', due_at = ?, attempts = attempts + 1,
                                     locked_by = NULL, locked_until = NULL, updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND locked_by = ?
                """)
            .execute(Tuple.of(nextDueAt, timerId, claimToken))
            .map(rs -> rs.rowCount() == 1);
    }

    public Future<Boolean> markFailedFinal(Long timerId, String claimToken) {
        return pool.preparedQuery("""
                UPDATE np_timers SET status = 'FAILED', attempts = attempts + 1,
                                     locked_by = NULL, locked_until = NULL, updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND locked_by = ?
                """)
            .execute(Tuple.of(timerId, claimToken))
            .map(rs -> rs.rowCount() == 1);
    }

    public Future<RowSet<Row>> cancelTimer(Long timerId) {
        return pool.preparedQuery("""
                UPDATE np_timers SET status = 'CANCELLED', cancelled_at = UTC_TIMESTAMP(6),
                                     updated_at = UTC_TIMESTAMP(6) WHERE id = ?
                """)
            .execute(Tuple.of(timerId));
    }
}
