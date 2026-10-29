package cloud.cloudonix.npg.number;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

public class NumberOwnershipService {

    private final Pool pool;

    public NumberOwnershipService(Pool pool) {
        this.pool = pool;
    }

    public Future<Void> updateCurrentOperator(String phoneNumber, Long newOperatorId,
                                               String changeReason, Long workflowId, Long messageId) {
        return updateCurrentOperator(phoneNumber, newOperatorId, changeReason, workflowId, messageId, "MOBILE");
    }

    public Future<Void> updateCurrentOperator(String phoneNumber, Long newOperatorId,
                                               String changeReason, Long workflowId, Long messageId,
                                               String numberType) {
        return pool.withTransaction(tx -> transfer(tx, phoneNumber, numberType, newOperatorId, "PORTED",
            changeReason, workflowId, messageId));
    }

    /**
     * Records that {@code phoneNumber} is now served by {@code newOperatorId}, inside the caller's
     * transaction, with an ownership-history row. The first operator ever recorded for a number
     * that was unknown becomes its original operator only if the caller says so via
     * {@code originalIfUnknown}; otherwise the previous owner is kept as previous_operator_id.
     */
    public static Future<Void> transfer(SqlClient tx, String phoneNumber, String numberType, Long newOperatorId,
                                        String status, String changeReason, Long workflowId, Long messageId) {
        return transfer(tx, phoneNumber, numberType, newOperatorId, null, status, changeReason, workflowId, messageId);
    }

    public static Future<Void> transfer(SqlClient tx, String phoneNumber, String numberType, Long newOperatorId,
                                        Long originalIfUnknown, String status, String changeReason,
                                        Long workflowId, Long messageId) {
        AtomicReference<Long> previousOperatorId = new AtomicReference<>();
        String timestampColumn = "RETURNED".equals(status) ? "last_returned_at" : "last_ported_at";
        return tx.preparedQuery("SELECT id, current_operator_id FROM np_numbers WHERE phone_number = ? FOR UPDATE")
            .execute(Tuple.of(phoneNumber))
            .compose(rows -> {
                if (rows.iterator().hasNext()) {
                    Row row = rows.iterator().next();
                    previousOperatorId.set(row.getLong("current_operator_id"));
                    return tx.preparedQuery("UPDATE np_numbers SET previous_operator_id = current_operator_id, "
                            + "current_operator_id = ?, status = ?, " + timestampColumn + " = UTC_TIMESTAMP(6), "
                            + "source_message_id = ?, updated_at = UTC_TIMESTAMP(6) WHERE id = ?")
                        .execute(Tuple.of(newOperatorId, status, messageId, row.getLong("id")))
                        .mapEmpty();
                }
                previousOperatorId.set(originalIfUnknown);
                return tx.preparedQuery("INSERT INTO np_numbers (phone_number, number_type, original_operator_id, "
                        + "previous_operator_id, current_operator_id, status, " + timestampColumn + ", source_message_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), ?)")
                    .execute(Tuple.from(new Object[]{phoneNumber, numberType != null ? numberType : "UNKNOWN",
                        originalIfUnknown, originalIfUnknown, newOperatorId, status, messageId}))
                    .mapEmpty();
            })
            .compose(v -> tx.preparedQuery("""
                    INSERT INTO np_number_ownership_history (phone_number, previous_operator_id, new_operator_id,
                                                             change_reason, workflow_id, message_id, effective_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)
                .execute(Tuple.of(phoneNumber, previousOperatorId.get(), newOperatorId, changeReason,
                    workflowId, messageId, LocalDateTime.now(ZoneOffset.UTC)))
                .mapEmpty());
    }

    /** Current owner of a number, or null when this gateway has never recorded it. */
    public static Future<Long> currentOperatorId(SqlClient tx, String phoneNumber) {
        return tx.preparedQuery("SELECT current_operator_id FROM np_numbers WHERE phone_number = ?")
            .execute(Tuple.of(phoneNumber))
            .map(rows -> rows.iterator().hasNext() ? rows.iterator().next().getLong("current_operator_id") : null);
    }

    public Future<RowSet<Row>> findCurrentOperator(String phoneNumber) {
        return pool.preparedQuery("""
                SELECT n.*, o.operator_code FROM np_numbers n
                LEFT JOIN np_operators o ON n.current_operator_id = o.id
                WHERE n.phone_number = ?
                """)
            .execute(Tuple.of(phoneNumber));
    }
}
