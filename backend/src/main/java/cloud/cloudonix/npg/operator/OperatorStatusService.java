package cloud.cloudonix.npg.operator;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;

public class OperatorStatusService {

    private final Pool pool;

    public OperatorStatusService(Pool pool) {
        this.pool = pool;
    }

    public Future<Void> markDown(Long operatorId, String reason) {
        return pool.withTransaction(tx -> tx.preparedQuery("""
                UPDATE np_operators SET availability_status = 'DOWN', last_down_at = UTC_TIMESTAMP(6),
                                        updated_at = UTC_TIMESTAMP(6) WHERE id = ?
                """)
            .execute(Tuple.of(operatorId))
            .compose(v -> tx.preparedQuery("""
                    INSERT INTO np_operator_status_events (operator_id, event_type, reason)
                    VALUES (?, 'DOWN', ?)
                    """)
                .execute(Tuple.of(operatorId, reason))))
            .mapEmpty();
    }

    public Future<Void> markUp(Long operatorId, String reason) {
        return pool.withTransaction(tx -> tx.preparedQuery("""
                UPDATE np_operators SET availability_status = 'UP', last_up_at = UTC_TIMESTAMP(6),
                                        updated_at = UTC_TIMESTAMP(6) WHERE id = ?
                """)
            .execute(Tuple.of(operatorId))
            .compose(v -> tx.preparedQuery("""
                    INSERT INTO np_operator_status_events (operator_id, event_type, reason)
                    VALUES (?, 'UP', ?)
                    """)
                .execute(Tuple.of(operatorId, reason))))
            .mapEmpty();
    }
}
