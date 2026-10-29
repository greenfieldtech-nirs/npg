package cloud.cloudonix.npg.number;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
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
        AtomicReference<Long> previousOperatorId = new AtomicReference<>();
        return pool.withTransaction(tx -> tx.preparedQuery(
                    "SELECT id, current_operator_id FROM np_numbers WHERE phone_number = ?")
            .execute(Tuple.of(phoneNumber))
            .compose(rows -> {
                if (rows.iterator().hasNext()) {
                    Row row = rows.iterator().next();
                    Long numberId = row.getLong("id");
                    previousOperatorId.set(row.getLong("current_operator_id"));
                    return tx.preparedQuery("""
                            UPDATE np_numbers SET previous_operator_id = current_operator_id,
                                                  current_operator_id = ?, status = 'PORTED',
                                                  last_ported_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6)
                            WHERE id = ?
                            """)
                        .execute(Tuple.of(newOperatorId, numberId))
                        .mapEmpty();
                }
                previousOperatorId.set(null);
                return tx.preparedQuery("""
                        INSERT INTO np_numbers (phone_number, number_type, current_operator_id, status)
                        VALUES (?, ?, ?, 'PORTED')
                        """)
                    .execute(Tuple.of(phoneNumber, numberType, newOperatorId))
                    .mapEmpty();
            })
            .compose(v -> tx.preparedQuery("""
                    INSERT INTO np_number_ownership_history (phone_number, previous_operator_id, new_operator_id,
                                                             change_reason, workflow_id, message_id, effective_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)
                .execute(Tuple.of(phoneNumber, previousOperatorId.get(), newOperatorId, changeReason,
                    workflowId, messageId, LocalDateTime.now(ZoneOffset.UTC)))
                .mapEmpty()));
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
