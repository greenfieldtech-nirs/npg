package cloud.cloudonix.npg.api;

import cloud.cloudonix.npg.workflow.ProtocolBodies;
import cloud.cloudonix.npg.workflow.porting.PortingService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** Porting actions of the management console; each is audited by {@link PortingService}. */
public class PortingApiHandler {

    static final int MAX_BATCH = 500;

    private final PortingService porting;

    public PortingApiHandler(PortingService porting) {
        this.porting = porting;
    }

    /**
     * Starts port-ins. A customer-level batch ({@code phoneNumbers}) is split into one workflow per
     * number (docs/01 rule 2); each number succeeds or fails on its own.
     */
    public Future<JsonObject> start(JsonObject body, String actor, String remoteIp) {
        List<String> numbers = new ArrayList<>();
        if (body.getString("phoneNumber") != null) {
            numbers.add(body.getString("phoneNumber"));
        }
        JsonArray batch = body.getJsonArray("phoneNumbers");
        if (batch != null) {
            batch.forEach(n -> numbers.add(String.valueOf(n)));
        }
        if (numbers.isEmpty()) {
            return Future.failedFuture(new ApiError(400, "phoneNumber or phoneNumbers is required"));
        }
        if (numbers.size() > MAX_BATCH) {
            return Future.failedFuture(new ApiError(400, "At most " + MAX_BATCH + " numbers per batch"));
        }
        LocalDateTime portTime = time(body, "requestedPortTime");
        String batchId = numbers.size() > 1 ? body.getString("customerBatchId", java.util.UUID.randomUUID().toString()) : null;
        JsonArray results = new JsonArray();
        Future<Void> chain = Future.succeededFuture();
        for (String number : numbers) {
            chain = chain.compose(v -> porting.startPortIn(new PortingService.PortInRequest(number, body.getString("numberType"),
                        body.getString("donorOperatorCode"), body.getString("identificationValue"), portTime, batchId), actor, remoteIp)
                .map(started -> started.put("phoneNumber", number))
                .recover(err -> {
                    if (numbers.size() == 1) {
                        return Future.failedFuture(err);
                    }
                    return Future.succeededFuture(new JsonObject().put("phoneNumber", number).put("error", err.getMessage()));
                })
                .map(results::add)
                .mapEmpty());
        }
        return chain.map(v -> numbers.size() == 1 ? results.getJsonObject(0)
            : new JsonObject().put("customerBatchId", batchId).put("results", results));
    }

    public Future<Void> updatePortTime(String workflowUuid, JsonObject body, String actor, String remoteIp) {
        LocalDateTime portTime = time(body, "portTime");
        if (portTime == null) {
            return Future.failedFuture(new ApiError(400, "portTime is required (ISO-8601 with offset)"));
        }
        return porting.updatePortTime(workflowUuid, portTime, actor, remoteIp);
    }

    public Future<Void> cancel(String workflowUuid, JsonObject body, String actor, String remoteIp) {
        return porting.cancelPort(workflowUuid, body.getString("reason"), actor, remoteIp);
    }

    public Future<Void> cancelPublication(String workflowUuid, JsonObject body, String actor, String remoteIp) {
        return porting.cancelPublication(workflowUuid, body.getString("reason"), actor, remoteIp);
    }

    public Future<Void> decide(String workflowUuid, JsonObject body, String actor, String remoteIp) {
        Boolean approve = body.getBoolean("approve");
        if (approve == null) {
            return Future.failedFuture(new ApiError(400, "approve (true/false) is required"));
        }
        return porting.decidePortOut(workflowUuid, approve, body.getString("reasonCode"), body.getString("reasonText"),
            actor, remoteIp);
    }

    private static LocalDateTime time(JsonObject body, String field) {
        String value = body.getString(field);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ProtocolBodies.parseUtc(value);
        } catch (DateTimeParseException e) {
            throw new ApiError(400, field + " must be an ISO-8601 date-time");
        }
    }
}
