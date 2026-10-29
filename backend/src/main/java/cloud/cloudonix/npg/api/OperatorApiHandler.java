package cloud.cloudonix.npg.api;

import cloud.cloudonix.npg.operator.OperatorStatusService;
import cloud.cloudonix.npg.repository.AuditEventRepository;
import cloud.cloudonix.npg.repository.CachedOperatorRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;

public class OperatorApiHandler {

    private final Pool pool;
    private final CachedOperatorRepository cachedOperators;
    private final AuditEventRepository auditEventRepository;
    private final OperatorStatusService operatorStatusService;

    public OperatorApiHandler(Pool pool, CachedOperatorRepository cachedOperators,
                              AuditEventRepository auditEventRepository) {
        this.pool = pool;
        this.cachedOperators = cachedOperators;
        this.auditEventRepository = auditEventRepository;
        this.operatorStatusService = new OperatorStatusService(pool);
    }

    public Future<JsonArray> listOperators() {
        return pool.query("SELECT * FROM np_operators ORDER BY operator_code")
            .execute()
            .map(RowJson::rowsToJsonArray);
    }

    public Future<JsonObject> getOperator(String operatorCode) {
        return pool.preparedQuery("SELECT * FROM np_operators WHERE operator_code = ?")
            .execute(Tuple.of(operatorCode))
            .map(rows -> rows.iterator().hasNext() ? RowJson.rowToJson(rows.iterator().next()) : null);
    }

    public Future<JsonObject> createOperator(JsonObject body, String actorId, String remoteIp) {
        String code = body.getString("operatorCode");
        String name = body.getString("operatorName");
        String licenseType = body.getString("licenseType");
        String reason = body.getString("reason");
        if (code == null || code.isBlank() || name == null || name.isBlank()) {
            return Future.failedFuture(new ApiError(400, "operatorCode and operatorName are required"));
        }
        String trimmedCode = code.trim();
        boolean isLocal = Boolean.TRUE.equals(body.getBoolean("isLocal"));
        return cachedOperators.insertOperator(trimmedCode, name.trim(), licenseType, isLocal)
            // ponytail: insert and exclusivity clear aren't in one tx; each is a single atomic
            // statement and this path is admin-only. Upgrade to withTransaction if that matters.
            .compose(id -> isLocal ? clearOtherLocals(trimmedCode) : Future.succeededFuture())
            .recover(err -> {
                if (err.getMessage() != null && err.getMessage().toLowerCase().contains("duplicate")) {
                    return Future.failedFuture(new ApiError(409, "Operator already exists: " + trimmedCode));
                }
                return Future.failedFuture(err);
            })
            .compose(id -> auditEventRepository.insertAuditEvent("USER", actorId, "OPERATOR_CREATED",
                    "OPERATOR", trimmedCode, new JsonObject().put("reason", reason).encode(), remoteIp))
            .compose(v -> getOperator(trimmedCode));
    }

    public Future<JsonObject> updateOperator(String operatorCode, JsonObject body, String actorId, String remoteIp) {
        String reason = body.getString("reason");
        return getOperator(operatorCode)
            .compose(existing -> {
                if (existing == null) {
                    return Future.failedFuture(new ApiError(404, "Operator not found: " + operatorCode));
                }
                String name = body.getString("operatorName", existing.getString("operatorName"));
                String licenseType = body.getString("licenseType", existing.getString("licenseType"));
                String contactName = body.getString("contactName", existing.getString("contactName"));
                String contactPhone = body.getString("contactPhone", existing.getString("contactPhone"));
                String contactEmail = body.getString("contactEmail", existing.getString("contactEmail"));
                Boolean isActive = body.getBoolean("isActive", existing.getBoolean("isActive"));
                // cachedOperators.updateOperator already deletes the cache key and broadcasts invalidation
                return cachedOperators.updateOperator(operatorCode, name, licenseType,
                        contactName, contactPhone, contactEmail, isActive)
                    .compose(v -> body.containsKey("isLocal")
                        ? setLocalExclusive(operatorCode, Boolean.TRUE.equals(body.getBoolean("isLocal")))
                        : Future.succeededFuture())
                    .compose(v -> auditEventRepository.insertAuditEvent("USER", actorId, "OPERATOR_UPDATED",
                            "OPERATOR", operatorCode, new JsonObject().put("reason", reason).encode(), remoteIp))
                    .compose(v -> getOperator(operatorCode));
            });
    }

    /** NOC marks a peer down/up (e.g. it announced maintenance by phone); audited. */
    public Future<Void> setAvailability(String operatorCode, String status, String reason, String actorId, String remoteIp) {
        if (reason == null || reason.isBlank()) {
            return Future.failedFuture(new ApiError(400, "A reason is required"));
        }
        return pool.withTransaction(tx -> tx.preparedQuery("SELECT id FROM np_operators WHERE operator_code = ?")
            .execute(Tuple.of(operatorCode))
            .compose(rows -> {
                if (!rows.iterator().hasNext()) {
                    return Future.failedFuture(new ApiError(404, "Operator not found: " + operatorCode));
                }
                return OperatorStatusService.setAvailability(tx, rows.iterator().next().getLong("id"), status, reason)
                    .compose(v -> new AuditEventRepository(tx).insertAuditEvent("USER", actorId,
                        "DOWN".equals(status) ? "OPERATOR_MARKED_DOWN" : "OPERATOR_MARKED_UP", "OPERATOR", operatorCode,
                        new JsonObject().put("reason", reason).encode(), remoteIp))
                    .mapEmpty();
            }));
    }

    private Future<Void> clearOtherLocals(String operatorCode) {
        return pool.preparedQuery("UPDATE np_operators SET is_local = FALSE WHERE operator_code != ?")
            .execute(Tuple.of(operatorCode))
            .mapEmpty();
    }

    private Future<Void> setLocalExclusive(String operatorCode, boolean isLocal) {
        if (!isLocal) {
            return pool.preparedQuery("UPDATE np_operators SET is_local = FALSE WHERE operator_code = ?")
                .execute(Tuple.of(operatorCode))
                .mapEmpty();
        }
        // exclusivity: clear all others and set this one in a single transaction
        return pool.withTransaction(tx -> tx
                .preparedQuery("UPDATE np_operators SET is_local = FALSE WHERE operator_code != ?")
                .execute(Tuple.of(operatorCode))
                .compose(v -> tx.preparedQuery("UPDATE np_operators SET is_local = TRUE WHERE operator_code = ?")
                    .execute(Tuple.of(operatorCode))))
            .mapEmpty();
    }

}
