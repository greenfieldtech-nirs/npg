package cloud.cloudonix.npg.repository;

import io.vertx.core.Future;
import io.vertx.mysqlclient.MySQLClient;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Durable inbox. Status changes after a claim are fenced by the claim token stored in
 * {@code locked_by}; each returns whether this caller still owned the row.
 */
public class InboxRepository {

    // stale CLAIMED and PROCESSING rows (worker crashed mid-item) are reclaimable once the lock expires
    private static final String CLAIMABLE = """
        due_at <= UTC_TIMESTAMP(6)
          AND (status IN ('PENDING', 'FAILED_RETRYABLE')
               OR (status IN ('CLAIMED', 'PROCESSING') AND locked_until < UTC_TIMESTAMP(6)))""";

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

    /** Claims up to {@code batchSize} rows; each returned row's {@code locked_by} is its claim token. */
    public Future<RowSet<Row>> claimBatch(String workerId, int batchSize, int lockSeconds) {
        return QueueClaims.claim(sqlClient, "np_inbox", CLAIMABLE, "priority ASC, due_at ASC, id ASC",
            workerId, batchSize, lockSeconds);
    }

    /** CLAIMED → PROCESSING, counting the attempt and refreshing the lock for the processing window. */
    public Future<Boolean> markProcessing(Long inboxId, String claimToken, int lockSeconds) {
        return sqlClient.preparedQuery("""
                UPDATE np_inbox SET status = 'PROCESSING', attempts = attempts + 1, locked_until = ?,
                                    updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND locked_by = ? AND status = 'CLAIMED'
                """)
            .execute(Tuple.of(QueueClaims.lockUntil(lockSeconds), inboxId, claimToken))
            .map(rs -> rs.rowCount() == 1);
    }

    public Future<Boolean> markSucceeded(Long inboxId, String claimToken) {
        return sqlClient.preparedQuery("""
                UPDATE np_inbox SET status = 'SUCCEEDED', locked_by = NULL, locked_until = NULL,
                                    last_error_code = NULL, last_error_message = NULL,
                                    updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND locked_by = ?
                """)
            .execute(Tuple.of(inboxId, claimToken))
            .map(rs -> rs.rowCount() == 1);
    }

    public Future<Boolean> markRetryableFailure(Long inboxId, String claimToken, String errorCode,
                                                String errorMessage, LocalDateTime nextDueAt) {
        return sqlClient.preparedQuery("""
                UPDATE np_inbox
                SET status = 'FAILED_RETRYABLE', due_at = ?, locked_by = NULL, locked_until = NULL,
                    last_error_code = ?, last_error_message = ?, updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND locked_by = ?
                """)
            .execute(Tuple.of(nextDueAt, errorCode, QueueClaims.clip(errorMessage), inboxId, claimToken))
            .map(rs -> rs.rowCount() == 1);
    }

    public Future<Boolean> markFinalFailure(Long inboxId, String claimToken, String errorCode, String errorMessage) {
        return sqlClient.preparedQuery("""
                UPDATE np_inbox
                SET status = 'FAILED_FINAL', locked_by = NULL, locked_until = NULL,
                    last_error_code = ?, last_error_message = ?, updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND locked_by = ?
                """)
            .execute(Tuple.of(errorCode, QueueClaims.clip(errorMessage), inboxId, claimToken))
            .map(rs -> rs.rowCount() == 1);
    }
}
