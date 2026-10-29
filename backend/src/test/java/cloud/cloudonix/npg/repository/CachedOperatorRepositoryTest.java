package cloud.cloudonix.npg.repository;

import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.config.RedisConfig;
import cloud.cloudonix.npg.domain.Operator;
import cloud.cloudonix.npg.service.CacheService;
import cloud.cloudonix.npg.service.PubSubService;
import cloud.cloudonix.npg.support.TestDatabase;
import cloud.cloudonix.npg.support.TestRedis;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.redis.client.RedisAPI;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
class CachedOperatorRepositoryTest {

    private static Pool pool;
    private static OperatorRepository operatorRepository;
    private static CachedOperatorRepository cachedRepository;
    private static RedisAPI redisApi;

    @BeforeAll
    static void setUpClass(Vertx vertx, VertxTestContext ctx) {
        TestDatabase.start();
        TestRedis.start();

        System.setProperty("DB_HOST", TestDatabase.host());
        System.setProperty("DB_PORT", String.valueOf(TestDatabase.port()));
        System.setProperty("DB_NAME", TestDatabase.database());
        System.setProperty("DB_USER", TestDatabase.user());
        System.setProperty("DB_PASSWORD", TestDatabase.password());
        System.setProperty("REDIS_URI", TestRedis.uri());
        TestDatabase.migrate();

        DatabaseConfig.configure(vertx)
            .compose(p -> {
                pool = p;
                operatorRepository = new OperatorRepository(p);
                return RedisConfig.configure(vertx);
            })
            .compose(api -> {
                redisApi = api;
                CacheService cacheService = new CacheService(api);
                PubSubService pubSubService = new PubSubService(vertx, api);
                cachedRepository = new CachedOperatorRepository(operatorRepository, cacheService, pubSubService);
                return Future.succeededFuture();
            })
            .onComplete(ctx.succeedingThenComplete());
    }

    @AfterAll
    static void tearDownClass(VertxTestContext ctx) {
        Future<Void> closePool = (pool != null) ? pool.close().mapEmpty() : Future.succeededFuture();
        closePool.onComplete(v -> {
            TestRedis.stop();
            TestDatabase.stop();
            ctx.completeNow();
        });
    }

    @BeforeEach
    void clean(VertxTestContext ctx) {
        pool.query("SET FOREIGN_KEY_CHECKS = 0").execute()
            .compose(v -> pool.query("TRUNCATE TABLE np_operators").execute())
            .compose(v -> pool.query("SET FOREIGN_KEY_CHECKS = 1").execute())
            .compose(v -> redisApi.flushall(List.of()))
            .onComplete(ctx.succeedingThenComplete());
    }

    @Test
    void shouldCacheOperatorByCode(VertxTestContext ctx) {
        operatorRepository.insertOperator("OP99", "Cached Operator", "MOBILE")
            .compose(id -> cachedRepository.findByCode("OP99"))
            .onComplete(ctx.succeeding(operator -> ctx.verify(() -> {
                assertTrue(operator.isPresent());
                assertEquals("OP99", operator.get().operatorCode());
                assertEquals("Cached Operator", operator.get().operatorName());
                ctx.completeNow();
            })));
    }
}
