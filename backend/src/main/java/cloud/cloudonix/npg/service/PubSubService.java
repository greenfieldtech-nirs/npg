package cloud.cloudonix.npg.service;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisOptions;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;

import java.util.List;
import java.util.function.Consumer;

public class PubSubService {

    private final Vertx vertx;
    private final RedisAPI redisApi;

    public PubSubService(Vertx vertx, RedisAPI redisApi) {
        this.vertx = vertx;
        this.redisApi = redisApi;
    }

    public Future<Void> publish(String channel, String message) {
        if (redisApi == null) {
            return Future.succeededFuture();
        }
        return redisApi.publish(channel, message)
            .mapEmpty();
    }

    public Future<Void> subscribe(String channel, Consumer<String> handler) {
        if (redisApi == null) {
            return Future.succeededFuture();
        }
        String uri = System.getProperty("REDIS_URI",
            System.getenv().getOrDefault("REDIS_URI", "redis://localhost:6379"));
        RedisOptions options = new RedisOptions().setConnectionString(uri);
        return Redis.createClient(vertx, options)
            .connect()
            .compose(conn -> {
                conn.handler(response -> {
                    if (response != null && response.size() >= 3) {
                        Response type = response.get(0);
                        Response payload = response.get(2);
                        if ("message".equalsIgnoreCase(type.toString())) {
                            handler.accept(payload.toString());
                        }
                    }
                });
                return conn.send(Request.cmd(Command.SUBSCRIBE).arg(channel))
                    .mapEmpty();
            });
    }

    public Future<Void> broadcastInvalidate(List<String> keys) {
        if (redisApi == null || keys == null || keys.isEmpty()) {
            return Future.succeededFuture();
        }
        JsonObject message = new JsonObject().put("keys", keys);
        return publish("npg:cache:invalidate", message.encode());
    }

    public Future<Void> wakeupTimer() {
        return publish("npg:wakeup:timer", "{\"reason\":\"signal\"}");
    }

    public Future<Void> wakeupDispatcher() {
        return publish("npg:wakeup:dispatcher", "{\"reason\":\"signal\"}");
    }

    public Future<Void> wakeupWorker() {
        return publish("npg:wakeup:worker", "{\"reason\":\"signal\"}");
    }
}
