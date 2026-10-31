package cloud.cloudonix.npg.ops;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.redis.client.RedisAPI;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Health of every platform component for the console: the stores, each runtime role (from the
 * heartbeats), the processing backlog and peer availability. Each check is bounded in time, so a
 * hung store reports DOWN instead of hanging the page.
 */
public class PlatformHealthService {

    static final List<String> ROLES = List.of("api", "inbound", "worker", "timer", "dispatcher");
    /** Work due for longer than this means a role is not keeping up. */
    static final int LAG_DEGRADED_SECONDS = 300;
    private static final long CHECK_TIMEOUT_MS = 2_000;

    private final Pool pool;
    private final MongoClient mongo;
    private final RedisAPI redis;

    public PlatformHealthService(Pool pool, MongoClient mongo, RedisAPI redis) {
        this.pool = pool;
        this.mongo = mongo;
        this.redis = redis;
    }

    public Future<JsonObject> check() {
        Future<JsonObject> mariadb = timed("mariadb", true, () -> pool.query("SELECT 1").execute().mapEmpty());
        Future<JsonObject> mongodb = timed("mongodb", true, () -> mongo.runCommand("ping", new JsonObject().put("ping", 1)).mapEmpty());
        // Redis is optional (REDIS_ENABLED=false): a disabled cache is reported, never counted against health
        Future<JsonObject> cache = redis == null
            ? Future.succeededFuture(new JsonObject().put("name", "redis").put("critical", false).put("status", "DISABLED"))
            : timed("redis", false, () -> redis.ping(List.of()).mapEmpty());
        Future<JsonArray> roles = roles();
        Future<JsonObject> queues = queues();
        Future<JsonObject> peers = peers();
        return Future.join(mariadb, mongodb, cache, roles, queues, peers).transform(ignored -> {
            JsonArray stores = new JsonArray().add(mariadb.result()).add(mongodb.result()).add(cache.result());
            JsonArray roleList = roles.succeeded() ? roles.result() : new JsonArray();
            JsonObject queueStats = queues.succeeded() ? queues.result() : null;
            JsonObject peerStats = peers.succeeded() ? peers.result() : null;
            return Future.succeededFuture(new JsonObject()
                .put("status", overall(stores, roleList, queueStats))
                .put("stores", stores)
                .put("roles", roleList)
                .put("queues", queueStats)
                .put("peers", peerStats)
                .put("checkedAt", java.time.Instant.now().toString()));
        });
    }

    /** DOWN: a critical store or a role is not up. DEGRADED: the cache is down or work is piling up. */
    static String overall(JsonArray stores, JsonArray roles, JsonObject queues) {
        boolean down = stores.stream().map(JsonObject.class::cast)
                .anyMatch(s -> s.getBoolean("critical") && !"UP".equals(s.getString("status")))
            || roles.isEmpty()
            || roles.stream().map(JsonObject.class::cast).anyMatch(r -> !"UP".equals(r.getString("status")));
        if (down) {
            return "DOWN";
        }
        boolean degraded = stores.stream().map(JsonObject.class::cast).anyMatch(s -> "DOWN".equals(s.getString("status")))
            || queues == null
            || queues.getLong("timersOverdue", 0L) > 0
            || queues.getJsonObject("inbox").getLong("oldestDueSeconds", 0L) > LAG_DEGRADED_SECONDS
            || queues.getJsonObject("outbox").getLong("oldestDueSeconds", 0L) > LAG_DEGRADED_SECONDS;
        return degraded ? "DEGRADED" : "UP";
    }

    private Future<JsonObject> timed(String name, boolean critical, java.util.function.Supplier<Future<Void>> probe) {
        long start = System.nanoTime();
        JsonObject result = new JsonObject().put("name", name).put("critical", critical);
        return probe.get().timeout(CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .map(v -> result.put("status", "UP").put("latencyMs", (System.nanoTime() - start) / 1_000_000))
            .otherwise(err -> result.put("status", "DOWN").put("error", String.valueOf(err.getMessage())));
    }

    /** Per role: UP when a process serving it reported recently, STALE when all went quiet, MISSING when none ever did. */
    private Future<JsonArray> roles() {
        return pool.query("""
                SELECT role, hostname, started_at, last_seen_at,
                       TIMESTAMPDIFF(SECOND, last_seen_at, UTC_TIMESTAMP(6)) AS age_seconds
                FROM np_instance_heartbeats ORDER BY last_seen_at DESC
                """).execute()
            .map(rows -> {
                JsonArray out = new JsonArray();
                for (String role : ROLES) {
                    JsonArray instances = new JsonArray();
                    boolean fresh = false;
                    for (Row row : rows) {
                        String served = row.getString("role");
                        if (!served.equals(role) && !served.equals("all")) {
                            continue;
                        }
                        long age = row.getLong("age_seconds");
                        fresh |= age <= Heartbeats.STALE_AFTER_SECONDS;
                        instances.add(new JsonObject().put("hostname", row.getString("hostname")).put("role", served)
                            .put("lastSeenSecondsAgo", age).put("startedAt", String.valueOf(row.getLocalDateTime("started_at"))));
                    }
                    String status = instances.isEmpty() ? "MISSING" : fresh ? "UP" : "STALE";
                    out.add(new JsonObject().put("role", role).put("status", status).put("instances", instances));
                }
                return out;
            });
    }

    private Future<JsonObject> queues() {
        return pool.query("""
                SELECT 'inbox' AS queue, COUNT(*) AS due,
                       COALESCE(TIMESTAMPDIFF(SECOND, MIN(due_at), UTC_TIMESTAMP(6)), 0) AS oldest_due_seconds
                FROM np_inbox WHERE status IN ('PENDING', 'FAILED_RETRYABLE') AND due_at <= UTC_TIMESTAMP(6)
                UNION ALL
                SELECT 'outbox', COUNT(*), COALESCE(TIMESTAMPDIFF(SECOND, MIN(due_at), UTC_TIMESTAMP(6)), 0)
                FROM np_outbox WHERE status IN ('PENDING', 'FAILED_RETRYABLE') AND due_at <= UTC_TIMESTAMP(6)
                UNION ALL
                SELECT 'timers', COUNT(*), COALESCE(TIMESTAMPDIFF(SECOND, MIN(due_at), UTC_TIMESTAMP(6)), 0)
                FROM np_timers WHERE status = 'PENDING' AND due_at <= UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE
                UNION ALL
                SELECT 'outboxReview', COUNT(*), 0 FROM np_outbox WHERE status IN ('MANUAL_REVIEW', 'UNKNOWN_DELIVERY')
                """).execute()
            .map(rows -> {
                JsonObject out = new JsonObject();
                for (Row row : rows) {
                    String queue = row.getString("queue");
                    long due = row.getLong("due");
                    switch (queue) {
                        case "timers" -> out.put("timersOverdue", due).put("oldestOverdueSeconds", row.getLong("oldest_due_seconds"));
                        case "outboxReview" -> out.put("outboxNeedingReview", due);
                        default -> out.put(queue, new JsonObject().put("due", due).put("oldestDueSeconds", row.getLong("oldest_due_seconds")));
                    }
                }
                return out;
            });
    }

    private Future<JsonObject> peers() {
        return pool.query("""
                SELECT COUNT(*) AS active, COALESCE(SUM(availability_status = 'DOWN'), 0) AS down
                FROM np_operators WHERE is_local = FALSE AND is_active = TRUE AND requires_configuration = FALSE
                """).execute()
            .map(rows -> {
                Row row = rows.iterator().next();
                return new JsonObject().put("active", row.getLong("active")).put("down", row.getLong("down"));
            });
    }
}
