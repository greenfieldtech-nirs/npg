package cloud.cloudonix.npg.config;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;

public class MongoConfig {

    public static Future<MongoClient> configure(Vertx vertx) {
        String uri = Env.get("MONGO_URI", "mongodb://localhost:27017");
        String db = Env.get("MONGO_DB", "npg");
        JsonObject config = new JsonObject()
            .put("connection_string", uri)
            .put("db_name", db);
        return Future.succeededFuture(MongoClient.createShared(vertx, config));
    }
}
