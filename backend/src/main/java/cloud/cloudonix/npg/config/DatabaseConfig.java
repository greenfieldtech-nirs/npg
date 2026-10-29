package cloud.cloudonix.npg.config;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.mysqlclient.MySQLConnectOptions;
import io.vertx.mysqlclient.MySQLPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;

public class DatabaseConfig {

    public static Future<Pool> configure(Vertx vertx) {
        String host = getConfig("DB_HOST", "localhost");
        int port;
        try {
            port = Integer.parseInt(getConfig("DB_PORT", "3306"));
        } catch (NumberFormatException e) {
            return Future.failedFuture("DB_PORT must be a valid integer");
        }
        String database = getConfig("DB_NAME", "npg");
        String user = getConfig("DB_USER", "npg");
        String password = getConfig("DB_PASSWORD", "npg");

        int maxPoolSize;
        try {
            maxPoolSize = Integer.parseInt(getConfig("DB_POOL_MAX_SIZE", "10"));
        } catch (NumberFormatException e) {
            return Future.failedFuture("DB_POOL_MAX_SIZE must be a valid integer");
        }
        if (maxPoolSize <= 0) {
            return Future.failedFuture("DB_POOL_MAX_SIZE must be a positive integer");
        }

        MySQLConnectOptions connectOptions = new MySQLConnectOptions()
            .setHost(host)
            .setPort(port)
            .setDatabase(database)
            .setUser(user)
            .setPassword(password);

        PoolOptions poolOptions = new PoolOptions().setMaxSize(maxPoolSize);
        Pool pool = MySQLPool.pool(vertx, connectOptions, poolOptions);

        // Validate that the database server is configured to use UTC. This is required
        // because CURRENT_TIMESTAMP(6) defaults and ON UPDATE clauses depend on the
        // session/server timezone. Applications and Docker Compose must run MariaDB/MySQL
        // with --default-time-zone=+00:00 (or equivalent).
        return pool.query("SELECT @@global.time_zone AS tz").execute()
            .compose(rows -> {
                String tz = rows.iterator().next().getString("tz");
                if (!"+00:00".equals(tz)) {
                    Future<Pool> failure = Future.failedFuture("Database global timezone is " + tz + ", expected +00:00. " +
                        "Configure the database server with --default-time-zone=+00:00");
                    return pool.close()
                        .compose(v -> failure)
                        .recover(err -> failure);
                }
                return Future.succeededFuture(pool);
            });
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
