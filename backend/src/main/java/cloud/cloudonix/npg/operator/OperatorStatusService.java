package cloud.cloudonix.npg.operator;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

/**
 * Peer availability (Down_system / Up_system, or the NOC). While a peer is DOWN the dispatcher
 * holds messages for it instead of burning retries; marking it UP releases them immediately.
 */
public class OperatorStatusService {

    private final Pool pool;

    public OperatorStatusService(Pool pool) {
        this.pool = pool;
    }

    public Future<Void> markDown(Long operatorId, String reason) {
        return pool.withTransaction(tx -> setAvailability(tx, operatorId, "DOWN", reason));
    }

    public Future<Void> markUp(Long operatorId, String reason) {
        return pool.withTransaction(tx -> setAvailability(tx, operatorId, "UP", reason));
    }

    public static Future<Void> setAvailability(SqlClient tx, Long operatorId, String status, String reason) {
        String stamp = "DOWN".equals(status) ? "last_down_at" : "last_up_at";
        Future<Void> updated = tx.preparedQuery("UPDATE np_operators SET availability_status = ?, " + stamp
                + " = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6) WHERE id = ?")
            .execute(Tuple.of(status, operatorId))
            .compose(v -> tx.preparedQuery("INSERT INTO np_operator_status_events (operator_id, event_type, reason) VALUES (?, ?, ?)")
                .execute(Tuple.of(operatorId, status, reason)))
            .mapEmpty();
        if (!"UP".equals(status)) {
            return updated;
        }
        return updated.compose(v -> tx.preparedQuery("""
                UPDATE np_outbox SET due_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6)
                WHERE destination_operator_id = ? AND status = 'PENDING' AND last_error_code = 'OPERATOR_DOWN'
                """).execute(Tuple.of(operatorId)).mapEmpty());
    }
}
