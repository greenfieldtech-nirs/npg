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

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(VertxExtension.class)
class PubSubServiceTest {

    private static RedisAPI redisApi;
    private static PubSubService pubSubService;

    @BeforeAll
    static void setUpClass(Vertx vertx, VertxTestContext ctx) {
        TestRedis.start();
        System.setProperty("REDIS_URI", TestRedis.uri());
        RedisConfig.configure(vertx)
            .onComplete(ctx.succeeding(api -> {
                redisApi = api;
                pubSubService = new PubSubService(vertx, api);
                ctx.completeNow();
            }));
    }

    @AfterAll
    static void tearDownClass(VertxTestContext ctx) {
        TestRedis.stop();
        ctx.completeNow();
    }

    @Test
    void shouldReceivePublishedMessage(Vertx vertx, VertxTestContext ctx) {
        AtomicReference<String> received = new AtomicReference<>();
        String channel = "npg:test:channel";

        pubSubService.subscribe(channel, received::set)
            .compose(v -> pubSubService.publish(channel, "hello"))
            .compose(v -> awaitMessage(vertx, received))
            .onComplete(ctx.succeeding(value -> ctx.verify(() -> {
                assertEquals("hello", value);
                ctx.completeNow();
            })));
    }

    // ponytail: poll for the pub/sub delivery instead of racing the publish future
    private io.vertx.core.Future<String> awaitMessage(Vertx vertx, AtomicReference<String> received) {
        return io.vertx.core.Future.future(promise -> {
            long[] remaining = {100};
            vertx.setPeriodic(10, id -> {
                if (received.get() != null) {
                    vertx.cancelTimer(id);
                    promise.complete(received.get());
                } else if (--remaining[0] <= 0) {
                    vertx.cancelTimer(id);
                    promise.fail("Timed out waiting for pub/sub message");
                }
            });
        });
    }
}
