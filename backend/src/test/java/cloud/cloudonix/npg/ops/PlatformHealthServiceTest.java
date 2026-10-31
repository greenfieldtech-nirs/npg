package cloud.cloudonix.npg.ops;

import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.support.TestDatabase;
import cloud.cloudonix.npg.support.TestMongo;
import cloud.cloudonix.npg.support.TestRedis;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

/** Platform health from real stores and heartbeats. */
@ExtendWith(VertxExtension.class)
class PlatformHealthServiceTest {

    private static Pool pool;
    private static MongoClient mongo;
    private static RedisAPI redis;

    @BeforeAll
    static void setUpClass(Vertx vertx, VertxTestContext ctx) {
        TestDatabase.start();
        TestMongo.start();
        TestRedis.start();
        System.setProperty("DB_HOST", TestDatabase.host());
        System.setProperty("DB_PORT", String.valueOf(TestDatabase.port()));
        System.setProperty("DB_NAME", TestDatabase.database());
        System.setProperty("DB_USER", TestDatabase.user());
        System.setProperty("DB_PASSWORD", TestDatabase.password());
        TestDatabase.migrate();
        mongo = MongoClient.createShared(vertx, new JsonObject().put("connection_string", TestMongo.uri()).put("db_name", TestMongo.dbName()));
        redis = RedisAPI.api(Redis.createClient(vertx, TestRedis.uri()));
        DatabaseConfig.configure(vertx).onComplete(ctx.succeeding(p -> {
            pool = p;
            ctx.completeNow();
        }));
    }

    @AfterAll
    static void tearDownClass(VertxTestContext ctx) {
        Future<Void> close = pool != null ? pool.close() : Future.succeededFuture();
        close.onComplete(v -> {
            TestDatabase.stop();
            ctx.completeNow();
        });
    }

    @BeforeEach
    void clean(VertxTestContext ctx) {
        TestDatabase.clean(pool).onComplete(ctx.succeedingThenComplete());
    }

    private static JsonObject role(JsonObject health, String name) {
        JsonArray roles = health.getJsonArray("roles");
        return roles.stream().map(JsonObject.class::cast).filter(r -> name.equals(r.getString("role"))).findFirst().orElseThrow();
    }

    private static JsonObject store(JsonObject health, String name) {
        return health.getJsonArray("stores").stream().map(JsonObject.class::cast)
            .filter(s -> name.equals(s.getString("name"))).findFirst().orElseThrow();
    }

    @Test
    void anAllInOneProcessServesEveryRole(VertxTestContext ctx) {
        Heartbeats.beat(pool, "dev-1", "all", "dev-host")
            .compose(v -> new PlatformHealthService(pool, mongo, redis).check())
            .onComplete(ctx.succeeding(health -> ctx.verify(() -> {
                for (String r : PlatformHealthService.ROLES) {
                    assertEquals("UP", role(health, r).getString("status"), r);
                }
                assertEquals("UP", store(health, "mariadb").getString("status"));
                assertEquals("UP", store(health, "mongodb").getString("status"));
                assertEquals("UP", store(health, "redis").getString("status"));
                assertEquals("UP", health.getString("status"), health.encodePrettily());
                ctx.completeNow();
            })));
    }

    @Test
    void aSilentOrMissingRoleTakesThePlatformDown(VertxTestContext ctx) {
        Heartbeats.beat(pool, "w-1", "worker", "worker-host")
            .compose(v -> Heartbeats.beat(pool, "t-1", "timer", "timer-host"))
            // the timer process went quiet five minutes ago
            .compose(v -> pool.query("UPDATE np_instance_heartbeats SET last_seen_at = UTC_TIMESTAMP(6) - INTERVAL 5 MINUTE "
                + "WHERE instance_id = 't-1'").execute())
            .compose(v -> new PlatformHealthService(pool, mongo, redis).check())
            .onComplete(ctx.succeeding(health -> ctx.verify(() -> {
                assertEquals("UP", role(health, "worker").getString("status"));
                assertEquals("STALE", role(health, "timer").getString("status"));
                assertEquals("MISSING", role(health, "dispatcher").getString("status"));
                assertEquals("DOWN", health.getString("status"));
                ctx.completeNow();
            })));
    }

    @Test
    void aDisabledOrUnreachableCacheNeverTakesThePlatformDown(Vertx vertx, VertxTestContext ctx) {
        RedisAPI unreachable = RedisAPI.api(Redis.createClient(vertx, "redis://127.0.0.1:1"));
        Heartbeats.beat(pool, "dev-1", "all", "dev-host")
            .compose(v -> new PlatformHealthService(pool, mongo, unreachable).check())
            .compose(health -> {
                ctx.verify(() -> {
                    assertEquals("DOWN", store(health, "redis").getString("status"));
                    assertEquals("DEGRADED", health.getString("status"), "redis is not critical");
                });
                return new PlatformHealthService(pool, mongo, null).check();
            })
            .onComplete(ctx.succeeding(health -> ctx.verify(() -> {
                assertEquals("DISABLED", store(health, "redis").getString("status"));
                assertEquals("UP", health.getString("status"));
                ctx.completeNow();
            })));
    }

    @Test
    void workPilingUpDegradesThePlatform() {
        JsonArray stores = new JsonArray().add(new JsonObject().put("name", "mariadb").put("critical", true).put("status", "UP"));
        JsonArray roles = new JsonArray().add(new JsonObject().put("role", "worker").put("status", "UP"));
        JsonObject calm = new JsonObject().put("timersOverdue", 0L)
            .put("inbox", new JsonObject().put("oldestDueSeconds", 10L)).put("outbox", new JsonObject().put("oldestDueSeconds", 0L));
        assertEquals("UP", PlatformHealthService.overall(stores, roles, calm));
        assertEquals("DEGRADED", PlatformHealthService.overall(stores, roles, calm.copy().put("timersOverdue", 3L)));
        assertEquals("DEGRADED", PlatformHealthService.overall(stores, roles,
            calm.copy().put("inbox", new JsonObject().put("oldestDueSeconds", PlatformHealthService.LAG_DEGRADED_SECONDS + 1L))));
    }
}
