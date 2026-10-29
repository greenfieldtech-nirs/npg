package cloud.cloudonix.npg.repository;

import io.vertx.core.Future;
import io.vertx.mysqlclient.MySQLClient;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public class TimerRepository {

    private final SqlClient sqlClient;

    public TimerRepository(SqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    public Future<Long> createTimer(Long workflowId, Long messageId, String timerName,
                                     String timerType, Instant startsAt, Instant dueAt, String actionJson) {
        String timerUuid = UUID.randomUUID().toString();
        return sqlClient.preparedQuery("""
                INSERT INTO np_timers (timer_uuid, workflow_id, message_id, timer_name, timer_type, starts_at, due_at, action_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)
            .execute(Tuple.of(timerUuid, workflowId, messageId, timerName, timerType, startsAt, dueAt, actionJson))
            .map(rs -> rs.property(MySQLClient.LAST_INSERTED_ID));
    }

    public Future<RowSet<Row>> claimDueTimers(String workerId, int batchSize, int lockSeconds) {
        if (!(sqlClient instanceof Pool pool)) {
            return Future.failedFuture("TimerRepository.claimDueTimers requires a Pool SqlClient");
        }
        Instant lockUntil = Instant.now().plus(Duration.ofSeconds(lockSeconds));
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
}
