package cloud.cloudonix.npg.config;

import cloud.cloudonix.npg.config.Env;
import cloud.cloudonix.npg.security.PasswordService;
import io.vertx.core.Future;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;

import java.util.List;

/**
 * Idempotent bootstrap of roles and the initial admin user. Safe to run on every startup.
 */
public final class AuthSeeder {

    private static final Logger log = LoggerFactory.getLogger(AuthSeeder.class);
    private static final List<String> ROLES = List.of("ADMIN", "MANAGER", "USER");

    static final int MIN_ADMIN_PASSWORD_LENGTH = 12;
    private static final String DEV_ADMIN_PASSWORD = "admin123";

    private AuthSeeder() {
    }

    /**
     * Password for the very first admin account. Only consulted when that account does not exist
     * yet; in production a missing or short NPG_ADMIN_PASSWORD aborts startup rather than seeding
     * a well-known credential.
     */
    static String initialAdminPassword(String configured) {
        if (configured != null && configured.length() >= MIN_ADMIN_PASSWORD_LENGTH) {
            return configured;
        }
        if (Env.isDevMode()) {
            if (configured == null) {
                log.warn("NPG_ADMIN_PASSWORD not set — seeding admin with the dev default password (NPG_ENV=dev/test only)");
                return DEV_ADMIN_PASSWORD;
            }
            return configured;
        }
        throw new IllegalStateException("NPG_ADMIN_PASSWORD must be set to at least " + MIN_ADMIN_PASSWORD_LENGTH
            + " characters to create the initial admin user (set NPG_ENV=dev to use the dev default)");
    }

    public static Future<Void> ensureSeeded(Pool pool) {
        Future<Void> seeded = Future.succeededFuture();
        for (String role : ROLES) {
            seeded = seeded.compose(v -> pool.preparedQuery(
                    "INSERT INTO np_roles (role_name) VALUES (?) ON DUPLICATE KEY UPDATE role_name = role_name")
                .execute(Tuple.of(role))
                .mapEmpty());
        }

        String username = Env.get("NPG_ADMIN_USERNAME", "admin");
        String configuredPassword = Env.get("NPG_ADMIN_PASSWORD");

        return seeded
            .compose(v -> pool.preparedQuery("SELECT id FROM np_users WHERE username = ?")
                .execute(Tuple.of(username)))
            .compose(rows -> {
                if (rows.iterator().hasNext()) {
                    return Future.succeededFuture();
                }
                String password;
                try {
                    password = initialAdminPassword(configuredPassword);
                } catch (IllegalStateException e) {
                    return Future.failedFuture(e);
                }
                return pool.preparedQuery(
                        "INSERT INTO np_users (username, display_name, password_hash) VALUES (?, ?, ?)")
                    .execute(Tuple.of(username, "Administrator", PasswordService.hash(password)))
                    .mapEmpty();
            })
            .compose(v -> pool.preparedQuery("""
                    INSERT IGNORE INTO np_user_roles (user_id, role_id)
                    SELECT u.id, r.id FROM np_users u
                    JOIN np_roles r ON r.role_name = 'ADMIN'
                    WHERE u.username = ?
                    """)
                .execute(Tuple.of(username)))
            .mapEmpty();
    }
}
