package cloud.cloudonix.npg.api;

import cloud.cloudonix.npg.repository.AuditEventRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mysqlclient.MySQLClient;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;

import java.util.regex.Pattern;

/** Allocated number blocks per operator (ADMIN); they decide who natively serves a never-ported number. */
public class NumberRangeApiHandler {

    private static final Pattern PREFIX = Pattern.compile("0\\d{1,9}");

    private final Pool pool;

    public NumberRangeApiHandler(Pool pool) {
        this.pool = pool;
    }

    public Future<JsonArray> list() {
        return pool.query("""
                SELECT r.id, r.prefix, r.description, r.created_at, o.operator_code FROM np_number_ranges r
                JOIN np_operators o ON o.id = r.operator_id ORDER BY r.prefix
                """).execute().map(RowJson::rowsToJsonArray);
    }

    public Future<JsonObject> create(JsonObject body, String actor, String remoteIp) {
        String prefix = body.getString("prefix");
        if (prefix == null || !PREFIX.matcher(prefix.trim()).matches()) {
            return Future.failedFuture(new ApiError(400, "prefix must be a national-format digit prefix, e.g. 0812"));
        }
        String operatorCode = body.getString("operatorCode");
        return pool.withTransaction(tx -> tx.preparedQuery("SELECT id FROM np_operators WHERE operator_code = ?")
            .execute(Tuple.of(operatorCode))
            .compose(rows -> {
                if (!rows.iterator().hasNext()) {
                    return Future.failedFuture(new ApiError(400, "Unknown operator: " + operatorCode));
                }
                return tx.preparedQuery("INSERT INTO np_number_ranges (operator_id, prefix, description) VALUES (?, ?, ?)")
                    .execute(Tuple.of(rows.iterator().next().getLong("id"), prefix.trim(), body.getString("description")))
                    .recover(err -> Future.failedFuture(err.getMessage() != null && err.getMessage().contains("Duplicate")
                        ? new ApiError(409, "Prefix " + prefix + " is already allocated") : err));
            })
            .compose(rs -> {
                long id = rs.property(MySQLClient.LAST_INSERTED_ID);
                return new AuditEventRepository(tx).insertAuditEvent("USER", actor, "NUMBER_RANGE_CREATED", "NUMBER_RANGE",
                        String.valueOf(id), new JsonObject().put("prefix", prefix).put("operator", operatorCode).encode(), remoteIp)
                    .map(a -> new JsonObject().put("id", id).put("prefix", prefix.trim()).put("operatorCode", operatorCode));
            }));
    }

    public Future<Void> delete(long id, String actor, String remoteIp) {
        return pool.withTransaction(tx -> tx.preparedQuery("DELETE FROM np_number_ranges WHERE id = ?").execute(Tuple.of(id))
            .compose(rs -> rs.rowCount() == 0 ? Future.failedFuture(new ApiError(404, "Range not found"))
                : new AuditEventRepository(tx).insertAuditEvent("USER", actor, "NUMBER_RANGE_DELETED", "NUMBER_RANGE",
                    String.valueOf(id), "{}", remoteIp).mapEmpty()));
    }
}
