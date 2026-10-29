package cloud.cloudonix.npg.config;

import org.flywaydb.core.Flyway;

public class MigrationRunner {

    public static void migrate() {
        String host = Env.get("DB_HOST", "localhost");
        String port = Env.get("DB_PORT", "3306");
        String database = Env.get("DB_NAME", "npg");
        String user = Env.get("DB_USER", "npg");
        String password = Env.get("DB_PASSWORD", "npg");
        String url = "jdbc:mariadb://" + host + ":" + port + "/" + database;
        Flyway flyway = Flyway.configure()
            .dataSource(url, user, password)
            .locations("classpath:db/migration")
            .load();
        flyway.migrate();
    }
}
