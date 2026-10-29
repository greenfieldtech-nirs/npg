package cloud.cloudonix.npg.config;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;

public class MongoConfig {

    public static Future<MongoClient> configure(Vertx vertx) {
        String uri = getConfig("MONGO_URI", "mongodb://localhost:27017");
        String db = getConfig("MONGO_DB", "npg");
        JsonObject config = new JsonObject()
            .put("connection_string", uri)
            .put("db_name", db);
        return Future.succeededFuture(MongoClient.createShared(vertx, config));
    }

    private static String getConfig(String key, String defaultValue) {
        String value = System.getProperty(key);
        if (value != null && !value.isBlank()) {
            return value;
        }
        value = System.getenv(key);
        return value != null && !value.isBlank() ? value : defaultValue;
    }
}
