package cloud.cloudonix.npg.support;

import org.flywaydb.core.Flyway;
import org.testcontainers.containers.MariaDBContainer;

/**
 * Shared test database lifecycle helper.
 *
 * When the environment provides DB_HOST (e.g. from the Docker Compose dev stack),
 * tests connect to that database directly. Otherwise a Testcontainers MariaDB
 * container is started for isolated local test runs.
 */
public class TestDatabase {

    private static final boolean USE_ENV;
    private static MariaDBContainer<?> container;

    static {
        String host = System.getenv("DB_HOST");
        USE_ENV = host != null && !host.isBlank();
    }

    public static void start() {
        if (!USE_ENV) {
            container = new MariaDBContainer<>("mariadb:10.11")
                .withCommand("mysqld", "--default-time-zone=+00:00")
                .withDatabaseName(database())
                .withUsername(user())
                .withPassword(password());
            container.start();
        }
    }

    public static void stop() {
        if (container != null) {
            container.stop();
        }
    }

    public static void migrate() {
        Flyway flyway = Flyway.configure()
            .dataSource(jdbcUrl(), user(), password())
            .locations("filesystem:src/main/resources/db/migration")
            .load();
        flyway.migrate();
    }

    public static String host() {
        return USE_ENV ? System.getenv("DB_HOST") : container.getHost();
    }

    public static int port() {
        return USE_ENV ? Integer.parseInt(env("DB_PORT", "3306")) : container.getMappedPort(3306);
    }

    public static String database() {
        return env("DB_NAME", "npg");
    }

    public static String user() {
        return env("DB_USER", "npg");
    }

    public static String password() {
        return env("DB_PASSWORD", "npg");
    }

    public static String jdbcUrl() {
        return "jdbc:mariadb://" + host() + ":" + port() + "/" + database();
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value != null && !value.isBlank() ? value : defaultValue;
    }

    /** Child-before-parent order for {@link #clean}. Users/roles and operators are kept. */
    private static final String[] BUSINESS_TABLES = {
        "np_manual_task_notes", "np_manual_tasks", "np_timers", "np_publication_recipients", "np_workflow_events",
        "np_inbox", "np_outbox", "np_messages", "np_number_ownership_history", "np_numbers", "np_workflows",
        "np_audit_events"
    };

    /** Deletes all workflow, queue, message and number data (DELETE, so FK checks stay on). */
    public static io.vertx.core.Future<Void> clean(io.vertx.sqlclient.Pool pool) {
        io.vertx.core.Future<Void> chain = io.vertx.core.Future.succeededFuture();
        for (String table : BUSINESS_TABLES) {
            chain = chain.compose(v -> pool.query("DELETE FROM " + table).execute().mapEmpty());
        }
        return chain;
    }
}
