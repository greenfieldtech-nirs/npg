package cloud.cloudonix.npg.ops;

import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.config.Env;
import cloud.cloudonix.npg.config.MongoConfig;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;

/**
 * Health and metrics for every role (HEALTH_PORT, default 9090), including the worker, timer and
 * dispatcher roles that serve no other HTTP. Readiness really checks MariaDB and MongoDB; metrics
 * are Prometheus text, read from the durable state (queue depths, timers, tasks, workflows).
 * Exposed to the internal network only — never route it through the public proxy.
 */
public class HealthVerticle extends AbstractVerticle {

    private static final io.vertx.core.impl.logging.Logger LOG =
        io.vertx.core.impl.logging.LoggerFactory.getLogger(HealthVerticle.class);

    private Pool pool;
    private MongoClient mongo;

    @Override
    public void start(Promise<Void> startPromise) {
        Future<Pool> poolFuture = DatabaseConfig.configure(vertx);
        Future<MongoClient> mongoFuture = MongoConfig.configure(vertx);
        Future.all(poolFuture, mongoFuture)
            .compose(v -> {
                pool = poolFuture.result();
                mongo = mongoFuture.result();
                Router router = Router.router(vertx);
                router.get("/health/live").handler(ctx -> ctx.response().end("OK"));
                router.get("/health/ready").handler(this::ready);
                router.get("/metrics").handler(this::metrics);
                startHeartbeat();
                return vertx.createHttpServer().requestHandler(router).listen(Env.getInt("HEALTH_PORT", 9090));
            })
            .<Void>mapEmpty()
            .onComplete(startPromise);
    }

    /** Reports this process (its role and host) every 15 s for the console's Platform Health view. */
    private void startHeartbeat() {
        String instanceId = java.util.UUID.randomUUID().toString();
        String role = Env.get("APP_ROLE", "all");
        String hostname = Env.get("HOSTNAME", "unknown");
        Runnable beat = () -> Heartbeats.beat(pool, instanceId, role, hostname)
            .onFailure(err -> LOG.warn("Heartbeat failed: " + err.getMessage()));
        beat.run();
        vertx.setPeriodic(Heartbeats.INTERVAL_MS, id -> beat.run());
    }

    private void ready(RoutingContext ctx) {
        respondReadiness(ctx, pool, mongo);
    }

    /** Readiness that really checks the durable stores; shared by every HTTP-serving role. */
    public static void respondReadiness(RoutingContext ctx, Pool pool, MongoClient mongo) {
        Future<String> db = pool.query("SELECT 1").execute().map("UP").otherwise(err -> "DOWN: " + err.getMessage());
        Future<String> audit = mongo.runCommand("ping", new JsonObject().put("ping", 1)).map("UP")
            .otherwise(err -> "DOWN: " + err.getMessage());
        Future.join(db, audit).onComplete(ar -> {
            JsonObject status = new JsonObject().put("mariadb", db.result()).put("mongodb", audit.result());
            boolean up = "UP".equals(db.result()) && "UP".equals(audit.result());
            ctx.response().setStatusCode(up ? 200 : 503).putHeader("Content-Type", "application/json")
                .end(status.put("status", up ? "UP" : "DOWN").encode());
        });
    }

    private void metrics(RoutingContext ctx) {
        pool.query("""
                SELECT 'npg_inbox_items' AS metric, status AS label, COUNT(*) AS value FROM np_inbox GROUP BY status
                UNION ALL SELECT 'npg_outbox_items', status, COUNT(*) FROM np_outbox GROUP BY status
                UNION ALL SELECT 'npg_timers', status, COUNT(*) FROM np_timers GROUP BY status
                UNION ALL SELECT 'npg_timers_overdue', 'PENDING', COUNT(*) FROM np_timers
                          WHERE status = 'PENDING' AND due_at <= UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE
                UNION ALL SELECT 'npg_manual_tasks', status, COUNT(*) FROM np_manual_tasks GROUP BY status
                UNION ALL SELECT 'npg_workflows', CONCAT(workflow_type, '/', current_state), COUNT(*) FROM np_workflows
                          GROUP BY workflow_type, current_state
                UNION ALL SELECT 'npg_operators_down', 'DOWN', COUNT(*) FROM np_operators WHERE availability_status = 'DOWN'
                UNION ALL SELECT 'npg_messages_total', CONCAT(direction, '/', technical_status), COUNT(*) FROM np_messages
                          GROUP BY direction, technical_status
                """).execute()
            .onSuccess(rows -> {
                StringBuilder out = new StringBuilder();
                String current = null;
                for (Row row : rows) {
                    String metric = row.getString("metric");
                    if (!metric.equals(current)) {
                        out.append("# TYPE ").append(metric).append(" gauge\n");
                        current = metric;
                    }
                    out.append(metric).append("{label=\"").append(escape(row.getString("label"))).append("\"} ")
                        .append(row.getLong("value")).append('\n');
                }
                ctx.response().putHeader("Content-Type", "text/plain; version=0.0.4").end(out.toString());
            })
            .onFailure(err -> ctx.response().setStatusCode(503).end("# metrics unavailable: database down\n"));
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
