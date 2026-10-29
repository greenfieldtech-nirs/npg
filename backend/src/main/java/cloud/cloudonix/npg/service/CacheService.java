package cloud.cloudonix.npg.service;

import io.vertx.core.Future;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.Response;

import java.util.List;
import java.util.Optional;

public class CacheService {

    private final RedisAPI redisApi;

    public CacheService(RedisAPI redisApi) {
        this.redisApi = redisApi;
    }

    public Future<Void> set(String key, String value, long ttlSeconds) {
        if (redisApi == null) {
            return Future.succeededFuture();
        }
        return redisApi.setex(key, String.valueOf(ttlSeconds), value)
            .mapEmpty();
    }

    public Future<Optional<String>> get(String key) {
        if (redisApi == null) {
            return Future.succeededFuture(Optional.empty());
        }
        return redisApi.get(key)
            .map(response -> {
                if (response == null) {
                    return Optional.<String>empty();
                }
                return Optional.of(response.toString());
            });
    }

    public Future<Void> delete(String key) {
        if (redisApi == null) {
            return Future.succeededFuture();
        }
        return redisApi.del(List.of(key))
            .mapEmpty();
    }
}
