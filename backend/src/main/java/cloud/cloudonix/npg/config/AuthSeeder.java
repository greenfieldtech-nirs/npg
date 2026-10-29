package cloud.cloudonix.npg.config;

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

    private AuthSeeder() {
    }

    public static Future<Void> ensureSeeded(Pool pool) {
        Future<Void> seeded = Future.succeededFuture();
        for (String role : ROLES) {
            seeded = seeded.compose(v -> pool.preparedQuery(
                    "INSERT INTO np_roles (role_name) VALUES (?) ON DUPLICATE KEY UPDATE role_name = role_name")
                .execute(Tuple.of(role))
                .mapEmpty());
        }

        String username = System.getenv().getOrDefault("NPG_ADMIN_USERNAME", "admin");
        String password = System.getenv().getOrDefault("NPG_ADMIN_PASSWORD", "admin123");

        return seeded
            .compose(v -> pool.preparedQuery("SELECT id FROM np_users WHERE username = ?")
                .execute(Tuple.of(username)))
            .compose(rows -> {
                if (rows.iterator().hasNext()) {
                    return Future.succeededFuture();
                }
                if (!System.getenv().containsKey("NPG_ADMIN_PASSWORD")) {
                    log.warn("NPG_ADMIN_PASSWORD not set — seeding admin with the default password. Set it in non-dev environments.");
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
