package cloud.cloudonix.npg.security;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.List;

public class UserRepository {

    public record UserRecord(long id, String username, String displayName, String passwordHash, boolean active) {
    }

    private final SqlClient sqlClient;

    public UserRepository(SqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    public Future<UserRecord> findByUsername(String username) {
        return sqlClient.preparedQuery("""
                SELECT id, username, display_name, password_hash, is_active
                FROM np_users WHERE username = ?
                """)
            .execute(Tuple.of(username))
            .map(rows -> rows.iterator().hasNext() ? map(rows.iterator().next()) : null);
    }

    public Future<List<String>> findRoles(long userId) {
        return sqlClient.preparedQuery("""
                SELECT r.role_name FROM np_roles r
                JOIN np_user_roles ur ON ur.role_id = r.id
                WHERE ur.user_id = ?
                """)
            .execute(Tuple.of(userId))
            .map(rows -> {
                List<String> roles = new ArrayList<>();
                rows.forEach(row -> roles.add(row.getString("role_name")));
                return roles;
            });
    }

    private UserRecord map(Row row) {
        return new UserRecord(
            row.getLong("id"),
            row.getString("username"),
            row.getString("display_name"),
            row.getString("password_hash"),
            Boolean.TRUE.equals(row.getBoolean("is_active")));
    }
}
