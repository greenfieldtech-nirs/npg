package cloud.cloudonix.npg.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * The one claim algorithm shared by the inbox, outbox and timer tables (docs/04): a single atomic
 * {@code UPDATE ... WHERE <claimable> ORDER BY ... LIMIT n} stamps a fresh claim token into
 * {@code locked_by}, then the claimed rows are selected by that token. No SELECT-then-UPDATE and
 * no reliance on SKIP LOCKED.
 *
 * <p>The token is unique per claim call, not per worker. Callers pass it back on every later
 * status change ({@code WHERE locked_by = token}) so a worker whose lock expired and was
 * reclaimed by someone else cannot overwrite the new owner's progress (fencing).
 */
public final class QueueClaims {

    private QueueClaims() {
    }

    public static String newClaimToken(String workerId) {
        return workerId + ":" + UUID.randomUUID();
    }

    /**
     * @param table          trusted table name (constant, never user input)
     * @param claimableWhere trusted SQL predicate selecting rows that may be claimed now
     * @param orderBy        trusted ORDER BY expression
     */
    public static Future<RowSet<Row>> claim(SqlClient client, String table, String claimableWhere, String orderBy,
                                     String workerId, int batchSize, int lockSeconds) {
        if (!(client instanceof Pool pool)) {
            return Future.failedFuture(table + " claims require a Pool SqlClient");
        }
        String token = newClaimToken(workerId);
        LocalDateTime lockUntil = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(lockSeconds);
        return pool.withTransaction(tx -> tx.preparedQuery(
                "UPDATE " + table + " SET status = 'CLAIMED', locked_by = ?, locked_until = ?,"
                    + " updated_at = UTC_TIMESTAMP(6) WHERE " + claimableWhere
                    + " ORDER BY " + orderBy + " LIMIT ?")
            .execute(Tuple.of(token, lockUntil, batchSize))
            .compose(u -> tx.preparedQuery(
                    "SELECT * FROM " + table + " WHERE locked_by = ? AND status = 'CLAIMED' ORDER BY " + orderBy)
                .execute(Tuple.of(token))));
    }

    static LocalDateTime lockUntil(int lockSeconds) {
        return LocalDateTime.now(ZoneOffset.UTC).plusSeconds(lockSeconds);
    }

    /** Truncates error text for the last_error_message columns. */
    static String clip(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }
}
