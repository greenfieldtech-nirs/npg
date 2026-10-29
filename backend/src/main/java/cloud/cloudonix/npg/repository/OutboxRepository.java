package cloud.cloudonix.npg.repository;

import io.vertx.core.Future;
import io.vertx.mysqlclient.MySQLClient;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Durable outbox. Status changes after a claim are fenced by the claim token in {@code locked_by}.
 *
 * <p>A row in SENDING is never reclaimed for sending: if its lock expires the dispatcher died
 * between the network call and recording the result, so the peer may or may not have the message.
 * {@link #sweepStaleSending} moves such rows to UNKNOWN_DELIVERY for protocol-specific handling.
 */
public class OutboxRepository {

    private static final String CLAIMABLE = """
        due_at <= UTC_TIMESTAMP(6)
          AND (status IN ('PENDING', 'FAILED_RETRYABLE')
               OR (status = 'CLAIMED' AND locked_until < UTC_TIMESTAMP(6)))""";

    private final SqlClient sqlClient;

    public OutboxRepository(SqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    public Future<Long> createOutboxItem(Long messageId, Long workflowId, Long destinationOperatorId,
                                          String itemType, LocalDateTime dueAt, int priority, int maxAttempts) {
        String outboxUuid = UUID.randomUUID().toString();
        return sqlClient.preparedQuery("""
                INSERT INTO np_outbox (outbox_uuid, message_id, workflow_id, destination_operator_id, item_type, due_at, priority, max_attempts)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)
            .execute(Tuple.of(outboxUuid, messageId, workflowId, destinationOperatorId, itemType, dueAt, priority, maxAttempts))
            .map(rs -> rs.property(MySQLClient.LAST_INSERTED_ID));
    }

    /** Claims up to {@code batchSize} rows; each returned row's {@code locked_by} is its claim token. */
    public Future<RowSet<Row>> claimBatch(String workerId, int batchSize, int lockSeconds) {
        return QueueClaims.claim(sqlClient, "np_outbox", CLAIMABLE, "priority ASC, due_at ASC, id ASC",
            workerId, batchSize, lockSeconds);
    }

    /** CLAIMED → SENDING immediately before the network call; the lock bounds the send window. */
    public Future<Boolean> markSending(Long outboxId, String claimToken, int lockSeconds) {
        return sqlClient.preparedQuery("""
                UPDATE np_outbox SET status = 'SENDING', attempts = attempts + 1, locked_until = ?,
                                     updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND locked_by = ? AND status = 'CLAIMED'
                """)
            .execute(Tuple.of(QueueClaims.lockUntil(lockSeconds), outboxId, claimToken))
            .map(rs -> rs.rowCount() == 1);
    }

    public Future<Boolean> markSent(Long outboxId, String claimToken) {
        return sqlClient.preparedQuery("""
                UPDATE np_outbox SET status = 'SUCCEEDED', locked_by = NULL, locked_until = NULL,
                                     last_error_code = NULL, last_error_message = NULL,
                                     updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND locked_by = ?
                """)
            .execute(Tuple.of(outboxId, claimToken))
            .map(rs -> rs.rowCount() == 1);
    }

    public Future<Boolean> markRetryableFailure(Long outboxId, String claimToken, String errorCode,
                                                String errorMessage, LocalDateTime nextDueAt) {
        return sqlClient.preparedQuery("""
                UPDATE np_outbox
                SET status = 'FAILED_RETRYABLE', due_at = ?, locked_by = NULL, locked_until = NULL,
                    last_error_code = ?, last_error_message = ?, updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND locked_by = ?
                """)
            .execute(Tuple.of(nextDueAt, errorCode, QueueClaims.clip(errorMessage), outboxId, claimToken))
            .map(rs -> rs.rowCount() == 1);
    }

    public Future<Boolean> markFinalFailure(Long outboxId, String claimToken, String errorCode, String errorMessage) {
        return sqlClient.preparedQuery("""
                UPDATE np_outbox
                SET status = 'FAILED_FINAL', locked_by = NULL, locked_until = NULL,
                    last_error_code = ?, last_error_message = ?, updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND locked_by = ?
                """)
            .execute(Tuple.of(errorCode, QueueClaims.clip(errorMessage), outboxId, claimToken))
            .map(rs -> rs.rowCount() == 1);
    }

    public Future<Boolean> markManualReview(Long outboxId, String claimToken, String errorCode, String reason) {
        return sqlClient.preparedQuery("""
                UPDATE np_outbox
                SET status = 'MANUAL_REVIEW', locked_by = NULL, locked_until = NULL,
                    last_error_code = ?, last_error_message = ?, updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND locked_by = ?
                """)
            .execute(Tuple.of(errorCode, QueueClaims.clip(reason), outboxId, claimToken))
            .map(rs -> rs.rowCount() == 1);
    }

    /**
     * Moves SENDING rows whose lock expired to UNKNOWN_DELIVERY and returns them (claimed with a
     * fresh token so a concurrent sweeper cannot pick up the same rows).
     */
    public Future<RowSet<Row>> sweepStaleSending(String workerId, int batchSize) {
        if (!(sqlClient instanceof Pool pool)) {
            return Future.failedFuture("OutboxRepository.sweepStaleSending requires a Pool SqlClient");
        }
        String token = QueueClaims.newClaimToken(workerId);
        return pool.withTransaction(tx -> tx.preparedQuery("""
                UPDATE np_outbox
                SET status = 'UNKNOWN_DELIVERY', locked_by = ?, locked_until = NULL,
                    last_error_code = 'UNKNOWN_DELIVERY',
                    last_error_message = 'Dispatcher stopped after sending began; delivery outcome unknown',
                    updated_at = UTC_TIMESTAMP(6)
                WHERE status = 'SENDING' AND locked_until < UTC_TIMESTAMP(6)
                ORDER BY id ASC
                LIMIT ?
                """)
            .execute(Tuple.of(token, batchSize))
            .compose(u -> tx.preparedQuery(
                    "SELECT * FROM np_outbox WHERE locked_by = ? AND status = 'UNKNOWN_DELIVERY' ORDER BY id")
                .execute(Tuple.of(token))));
    }

    /** UNKNOWN_DELIVERY → re-send, for message types that are safe to deliver twice. */
    public Future<Boolean> requeueUnknownDelivery(Long outboxId, String sweepToken) {
        return sqlClient.preparedQuery("""
                UPDATE np_outbox
                SET status = 'FAILED_RETRYABLE', due_at = UTC_TIMESTAMP(6), locked_by = NULL,
                    last_error_code = 'UNKNOWN_DELIVERY_RESENT', updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND locked_by = ? AND status = 'UNKNOWN_DELIVERY'
                """)
            .execute(Tuple.of(outboxId, sweepToken))
            .map(rs -> rs.rowCount() == 1);
    }

    /** UNKNOWN_DELIVERY → MANUAL_REVIEW, for message types that must not be sent twice blindly. */
    public Future<Boolean> holdUnknownDeliveryForReview(Long outboxId, String sweepToken) {
        return sqlClient.preparedQuery("""
                UPDATE np_outbox
                SET status = 'MANUAL_REVIEW', locked_by = NULL, updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND locked_by = ? AND status = 'UNKNOWN_DELIVERY'
                """)
            .execute(Tuple.of(outboxId, sweepToken))
            .map(rs -> rs.rowCount() == 1);
    }
}
