package cloud.cloudonix.npg.config;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisOptions;

public class RedisConfig {

    public static Future<RedisAPI> configure(Vertx vertx) {
        boolean enabled = Boolean.parseBoolean(Env.get("REDIS_ENABLED", "true"));
        if (!enabled) {
            return Future.succeededFuture(null);
        }
        String uri = Env.get("REDIS_URI", "redis://localhost:6379");
        RedisOptions options = new RedisOptions().setConnectionString(uri);
        return Redis.createClient(vertx, options)
            .connect()
            .map(RedisAPI::api);
    }
}
