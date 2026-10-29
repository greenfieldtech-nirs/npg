package cloud.cloudonix.npg.service;

import cloud.cloudonix.npg.config.RedisConfig;
import cloud.cloudonix.npg.support.TestRedis;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.redis.client.RedisAPI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
class CacheServiceTest {

    private static RedisAPI redisApi;
    private static CacheService cacheService;

    @BeforeAll
    static void setUpClass(Vertx vertx, VertxTestContext ctx) {
        TestRedis.start();
        System.setProperty("REDIS_URI", TestRedis.uri());
        RedisConfig.configure(vertx)
            .onComplete(ctx.succeeding(api -> {
                redisApi = api;
                cacheService = new CacheService(api);
                ctx.completeNow();
            }));
    }

    @AfterAll
    static void tearDownClass(VertxTestContext ctx) {
        TestRedis.stop();
        ctx.completeNow();
    }

    @Test
    void shouldStoreAndRetrieveValue(VertxTestContext ctx) {
        cacheService.set("test:key", "{\"name\":\"Alice\"}", 60)
            .compose(v -> cacheService.get("test:key"))
            .onComplete(ctx.succeeding(value -> ctx.verify(() -> {
                assertTrue(value.isPresent());
                assertEquals("{\"name\":\"Alice\"}", value.get());
                ctx.completeNow();
            })));
    }

    @Test
    void shouldReturnEmptyForMissingKey(VertxTestContext ctx) {
        cacheService.get("test:missing")
            .onComplete(ctx.succeeding(value -> ctx.verify(() -> {
                assertTrue(value.isEmpty());
                ctx.completeNow();
            })));
    }
}
