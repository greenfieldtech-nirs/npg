package cloud.cloudonix.npg.repository;

import cloud.cloudonix.npg.domain.Operator;
import cloud.cloudonix.npg.service.CacheService;
import cloud.cloudonix.npg.service.PubSubService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;

import java.util.List;
import java.util.Optional;

public class CachedOperatorRepository {

    private static final String KEY_PREFIX = "npg:operator:code:";
    private static final long TTL_SECONDS = 300;

    private final OperatorRepository delegate;
    private final CacheService cacheService;
    private final PubSubService pubSubService;

    public CachedOperatorRepository(OperatorRepository delegate, CacheService cacheService, PubSubService pubSubService) {
        this.delegate = delegate;
        this.cacheService = cacheService;
        this.pubSubService = pubSubService;
    }

    public Future<Long> insertOperator(String operatorCode, String operatorName, String licenseType) {
        return insertOperator(operatorCode, operatorName, licenseType, false);
    }

    public Future<Long> insertOperator(String operatorCode, String operatorName, String licenseType, boolean isLocal) {
        return delegate.insertOperator(operatorCode, operatorName, licenseType, isLocal)
            .compose(id -> cacheService.delete(cacheKey(operatorCode))
                .compose(v -> pubSubService.broadcastInvalidate(List.of(cacheKey(operatorCode))))
                .map(id));
    }

    public Future<Long> insertAutoDiscovered(String operatorCode) {
        return delegate.insertAutoDiscovered(operatorCode)
            .compose(id -> invalidate(operatorCode).map(id));
    }

    public Future<Void> updateOperator(String operatorCode, String operatorName, String licenseType,
                                       String contactName, String contactPhone, String contactEmail,
                                       Boolean isActive) {
        return delegate.updateOperator(operatorCode, operatorName, licenseType,
                contactName, contactPhone, contactEmail, isActive)
            .compose(v -> invalidate(operatorCode));
    }

    public Future<Void> invalidate(String operatorCode) {
        return cacheService.delete(cacheKey(operatorCode))
            .compose(v -> pubSubService.broadcastInvalidate(List.of(cacheKey(operatorCode))));
    }

    public Future<Optional<Operator>> findByCode(String operatorCode) {
        String key = cacheKey(operatorCode);
        return cacheService.get(key)
            .compose(cached -> {
                if (cached.isPresent()) {
                    return Future.succeededFuture(Optional.of(operatorFromJson(new JsonObject(cached.get()))));
                }
                return delegate.findByCode(operatorCode)
                    .compose(rows -> {
                        Optional<Operator> operator = firstOperator(rows);
                        if (operator.isPresent()) {
                            String json = operatorToJson(operator.get()).encode();
                            return cacheService.set(key, json, TTL_SECONDS)
                                .map(operator);
                        }
                        return Future.succeededFuture(operator);
                    });
            });
    }

    private String cacheKey(String operatorCode) {
        return KEY_PREFIX + operatorCode;
    }

    private Optional<Operator> firstOperator(RowSet<Row> rows) {
        if (rows == null || !rows.iterator().hasNext()) {
            return Optional.empty();
        }
        return Optional.of(operatorFromRow(rows.iterator().next()));
    }

    private Operator operatorFromRow(Row row) {
        return new Operator(
            row.getLong("id"),
            row.getString("operator_code"),
            row.getString("operator_name"),
            row.getString("license_type"),
            row.getBoolean("is_active"),
            row.getString("availability_status")
        );
    }

    private Operator operatorFromJson(JsonObject json) {
        return new Operator(
            json.getLong("id"),
            json.getString("operatorCode"),
            json.getString("operatorName"),
            json.getString("licenseType"),
            json.getBoolean("active"),
            json.getString("availabilityStatus")
        );
    }

    private JsonObject operatorToJson(Operator operator) {
        return new JsonObject()
            .put("id", operator.id())
            .put("operatorCode", operator.operatorCode())
            .put("operatorName", operator.operatorName())
            .put("licenseType", operator.licenseType())
            .put("active", operator.active())
            .put("availabilityStatus", operator.availabilityStatus());
    }
}
