package cloud.cloudonix.npg.api;

import cloud.cloudonix.npg.repository.AuditEventRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mysqlclient.MySQLClient;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;

public class OperatorEndpointApiHandler {

    private final Pool pool;

    public OperatorEndpointApiHandler(Pool pool) {
        this.pool = pool;
    }

    public Future<JsonArray> listEndpoints(String operatorCode) {
        return pool.preparedQuery("""
                SELECT e.* FROM np_operator_endpoints e
                JOIN np_operators o ON e.operator_id = o.id
                WHERE o.operator_code = ?
                ORDER BY e.id
                """)
            .execute(Tuple.of(operatorCode))
            .map(RowJson::rowsToJsonArray);
    }

    public Future<JsonObject> createEndpoint(String operatorCode, JsonObject body, String actorId, String remoteIp) {
        String endpointType = body.getString("endpointType");
        String endpointUrl = body.getString("endpointUrl");
        if (endpointType == null || endpointType.isBlank()) {
            return Future.failedFuture(new ApiError(400, "endpointType is required"));
        }
        if (endpointUrl == null || !(endpointUrl.startsWith("http://") || endpointUrl.startsWith("https://"))) {
            return Future.failedFuture(new ApiError(400, "endpointUrl must start with http:// or https://"));
        }
        return findOperatorId(operatorCode)
            .compose(operatorId -> {
                // ponytail: Tuple.of rejects nulls; tlsSubject/ipAllowlist are nullable
                Tuple params = Tuple.tuple()
                    .addLong(operatorId)
                    .addString(endpointType)
                    .addString(endpointUrl)
                    .addString(body.getString("protocolVersion", "4.0"))
                    .addString(body.getString("authMode", "MTLS"))
                    .addString(body.getString("tlsSubject"))
                    .addString(body.getString("ipAllowlist"))
                    .addBoolean(body.getBoolean("isActive", true));
                return pool.preparedQuery("""
                        INSERT INTO np_operator_endpoints
                          (operator_id, endpoint_type, endpoint_url, protocol_version, auth_mode,
                           tls_subject, ip_allowlist, is_active)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                    .execute(params)
                    .map(rs -> rs.property(MySQLClient.LAST_INSERTED_ID));
            })
            .compose(id -> new AuditEventRepository(pool).insertAuditEvent("USER", actorId, "OPERATOR_ENDPOINT_CREATED",
                    "OPERATOR", operatorCode, new JsonObject().put("endpointId", id).put("endpointType", endpointType)
                        .put("endpointUrl", endpointUrl).put("tlsSubject", body.getString("tlsSubject"))
                        .put("ipAllowlist", body.getString("ipAllowlist")).encode(), remoteIp)
                .map(a -> id))
            .compose(id -> pool.preparedQuery("SELECT * FROM np_operator_endpoints WHERE id = ?")
                .execute(Tuple.of(id))
                .map(rows -> RowJson.rowToJson(rows.iterator().next())));
    }

    public Future<Void> deleteEndpoint(String operatorCode, long endpointId, String actorId, String remoteIp) {
        return findOperatorId(operatorCode)
            .compose(operatorId -> pool.preparedQuery(
                    "DELETE FROM np_operator_endpoints WHERE id = ? AND operator_id = ?")
                .execute(Tuple.of(endpointId, operatorId)))
            .compose(rs -> rs.rowCount() == 0
                ? Future.failedFuture(new ApiError(404, "Endpoint not found: " + endpointId))
                : new AuditEventRepository(pool).insertAuditEvent("USER", actorId, "OPERATOR_ENDPOINT_DELETED", "OPERATOR",
                    operatorCode, new JsonObject().put("endpointId", endpointId).encode(), remoteIp).mapEmpty());
    }

    private Future<Long> findOperatorId(String operatorCode) {
        return pool.preparedQuery("SELECT id FROM np_operators WHERE operator_code = ?")
            .execute(Tuple.of(operatorCode))
            .compose(rows -> rows.iterator().hasNext()
                ? Future.succeededFuture(rows.iterator().next().getLong("id"))
                : Future.failedFuture(new ApiError(404, "Operator not found: " + operatorCode)));
    }
}
