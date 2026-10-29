package cloud.cloudonix.npg.repository;

import io.vertx.core.Future;
import io.vertx.mysqlclient.MySQLClient;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public class InboxRepository {

    private final SqlClient sqlClient;

    public InboxRepository(SqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    public Future<Long> createInboxItem(Long messageId, Long workflowId, String itemType,
                                        LocalDateTime dueAt, int priority, int maxAttempts) {
        String inboxUuid = UUID.randomUUID().toString();
        return sqlClient.preparedQuery("""
                INSERT INTO np_inbox (inbox_uuid, message_id, workflow_id, item_type, due_at, priority, max_attempts)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)
            .execute(Tuple.of(inboxUuid, messageId, workflowId, itemType, dueAt, priority, maxAttempts))
            .map(rs -> rs.property(MySQLClient.LAST_INSERTED_ID));
    }

    public Future<RowSet<Row>> claimBatch(String workerId, int batchSize, int lockSeconds) {
        if (!(sqlClient instanceof Pool pool)) {
            return Future.failedFuture("InboxRepository.claimBatch requires a Pool SqlClient");
        }
        LocalDateTime lockUntil = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(lockSeconds);
        return pool.withTransaction(tx -> tx.preparedQuery("""
                UPDATE np_inbox
                SET status = 'CLAIMED',
                    locked_by = ?,
                    locked_until = ?,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE status IN ('PENDING', 'FAILED_RETRYABLE', 'CLAIMED')
                  AND due_at <= UTC_TIMESTAMP(6)
                  AND (locked_until IS NULL OR locked_until < UTC_TIMESTAMP(6))
                ORDER BY priority ASC, due_at ASC
                LIMIT ?
                """)
            .execute(Tuple.of(workerId, lockUntil, batchSize))
            .compose(u -> tx.preparedQuery("""
                SELECT * FROM np_inbox
                WHERE locked_by = ? AND status = 'CLAIMED'
                ORDER BY priority ASC, due_at ASC
                """)
                .execute(Tuple.of(workerId))));
    }

    public Future<RowSet<Row>> markProcessing(Long inboxId) {
        return sqlClient.preparedQuery("""
                UPDATE np_inbox SET status = 'PROCESSING', attempts = attempts + 1, updated_at = UTC_TIMESTAMP(6)
                WHERE id = ?
                """)
            .execute(Tuple.of(inboxId));
    }

    public Future<RowSet<Row>> markSucceeded(Long inboxId) {
        return sqlClient.preparedQuery("UPDATE np_inbox SET status = 'SUCCEEDED', updated_at = UTC_TIMESTAMP(6) WHERE id = ?")
            .execute(Tuple.of(inboxId));
    }

    public Future<RowSet<Row>> markRetryableFailure(Long inboxId, String errorCode, String errorMessage, LocalDateTime nextDueAt) {
        return sqlClient.preparedQuery("""
                UPDATE np_inbox
                SET status = 'FAILED_RETRYABLE',
                    due_at = ?,
                    last_error_code = ?,
                    last_error_message = ?,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE id = ?
                """)
            .execute(Tuple.of(nextDueAt, errorCode, errorMessage, inboxId));
    }

    public Future<RowSet<Row>> markFinalFailure(Long inboxId, String errorCode, String errorMessage) {
        return sqlClient.preparedQuery("""
                UPDATE np_inbox
                SET status = 'FAILED_FINAL',
                    last_error_code = ?,
                    last_error_message = ?,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE id = ?
                """)
            .execute(Tuple.of(errorCode, errorMessage, inboxId));
    }
}
