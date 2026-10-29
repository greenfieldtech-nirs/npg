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

    // stale CLAIMED and PROCESSING rows (worker crashed mid-item) are reclaimable once the lock expires;
    // within one ordering key only the oldest unfinished row is claimable, so a process's messages
    // are handled in arrival order (the derived table lets MariaDB/MySQL read the updated table)
    private static final String CLAIMABLE = """
        due_at <= UTC_TIMESTAMP(6)
          AND (status IN ('PENDING', 'FAILED_RETRYABLE')
               OR (status IN ('CLAIMED', 'PROCESSING') AND locked_until < UTC_TIMESTAMP(6)))
          AND (ordering_key IS NULL OR id IN (
                SELECT head FROM (
                  SELECT MIN(id) AS head FROM np_inbox
                  WHERE ordering_key IS NOT NULL AND status IN ('PENDING', 'FAILED_RETRYABLE', 'CLAIMED', 'PROCESSING')
                  GROUP BY ordering_key) AS heads))""";

    private final SqlClient sqlClient;

    public InboxRepository(SqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    /** {@code dueAt} null means due now by the database clock (claims compare against that clock). */
    public Future<Long> createInboxItem(Long messageId, Long workflowId, String itemType,
                                        LocalDateTime dueAt, int priority, int maxAttempts) {
        return createInboxItem(messageId, workflowId, itemType, null, dueAt, priority, maxAttempts);
    }

    /** @param orderingKey items with the same key are processed one at a time, in insertion order */
    public Future<Long> createInboxItem(Long messageId, Long workflowId, String itemType, String orderingKey,
                                        LocalDateTime dueAt, int priority, int maxAttempts) {
        String inboxUuid = UUID.randomUUID().toString();
        return sqlClient.preparedQuery("""
                INSERT INTO np_inbox (inbox_uuid, message_id, workflow_id, item_type, ordering_key, due_at, priority, max_attempts)
                VALUES (?, ?, ?, ?, ?, COALESCE(?, UTC_TIMESTAMP(6)), ?, ?)
                """)
            .execute(Tuple.from(new Object[]{inboxUuid, messageId, workflowId, itemType, orderingKey, dueAt, priority, maxAttempts}))
            .map(rs -> rs.property(MySQLClient.LAST_INSERTED_ID));
    }

    /** Ordering key of a protocol process: one sender's messages for one REQUEST_ID. */
    public static String orderingKey(Long senderOperatorId, String requestId) {
        return (senderOperatorId != null ? senderOperatorId : "") + "|" + (requestId != null ? requestId : "");
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
