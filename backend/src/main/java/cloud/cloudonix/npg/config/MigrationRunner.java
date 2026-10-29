package cloud.cloudonix.npg.config;

import org.flywaydb.core.Flyway;

public class MigrationRunner {

    public static void migrate() {
        String host = getConfig("DB_HOST", "localhost");
        String port = getConfig("DB_PORT", "3306");
        String database = getConfig("DB_NAME", "npg");
        String user = getConfig("DB_USER", "npg");
        String password = getConfig("DB_PASSWORD", "npg");
        String url = "jdbc:mariadb://" + host + ":" + port + "/" + database;
        Flyway flyway = Flyway.configure()
            .dataSource(url, user, password)
            .locations("classpath:db/migration")
            .load();
        flyway.migrate();
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
