package cloud.cloudonix.npg.api;

import cloud.cloudonix.npg.repository.AuditEventRepository;
import cloud.cloudonix.npg.security.PasswordService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mysqlclient.MySQLClient;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class UserManagementApiHandler {

    private static final Pattern USERNAME = Pattern.compile("[a-z0-9._-]{3,64}");
    private static final Set<String> ALLOWED_ROLES = Set.of("ADMIN", "MANAGER", "USER");

    private final Pool pool;
    private final AuditEventRepository auditEventRepository;

    public UserManagementApiHandler(Pool pool) {
        this.pool = pool;
        this.auditEventRepository = new AuditEventRepository(pool);
    }

    public Future<JsonArray> listUsers() {
        return pool.query(USER_SELECT + " ORDER BY u.username")
            .execute()
            .map(rows -> {
                JsonArray users = new JsonArray();
                rows.forEach(row -> users.add(userPayload(row)));
                return users;
            });
    }

    public Future<JsonObject> createUser(JsonObject body, String actorId, String remoteIp) {
        if (body == null) {
            return Future.failedFuture(new ApiError(400, "Request body is required"));
        }
        String username = body.getString("username");
        String password = body.getString("password");
        if (username == null || !USERNAME.matcher(username).matches()) {
            return Future.failedFuture(new ApiError(400,
                "username is required (3-64 chars: lowercase letters, digits, . _ -)"));
        }
        String passwordError = validatePassword(password);
        if (passwordError != null) {
            return Future.failedFuture(new ApiError(400, passwordError));
        }
        List<String> roles = parseRoles(body.getValue("roles"));
        if (roles == null) {
            return Future.failedFuture(new ApiError(400,
                "roles must be a non-empty subset of ADMIN/MANAGER/USER"));
        }
        String hash = PasswordService.hash(password);
        return pool.preparedQuery("""
                INSERT INTO np_users (username, display_name, email, password_hash) VALUES (?, ?, ?, ?)
                """)
            .execute(Tuple.of(username, body.getString("displayName"), body.getString("email"), hash))
            .recover(err -> err.getMessage() != null && err.getMessage().toLowerCase().contains("duplicate")
                ? Future.failedFuture(new ApiError(409, "User already exists: " + username))
                : Future.failedFuture(err))
            .compose(rs -> replaceRoles(rs.property(MySQLClient.LAST_INSERTED_ID), roles))
            .compose(v -> auditEventRepository.insertAuditEvent("USER", actorId, "USER_CREATED",
                "USER", username, new JsonObject().put("roles", roles).encode(), remoteIp))
            .compose(v -> getUser(username));
    }

    public Future<JsonObject> updateUser(String username, JsonObject body, String actorId, String remoteIp) {
        if (body == null) {
            return Future.failedFuture(new ApiError(400, "Request body is required"));
        }
        return findUserId(username)
            .compose(userId -> {
                if (userId == null) {
                    return Future.failedFuture(new ApiError(404, "User not found: " + username));
                }
                List<String> changed = new ArrayList<>();
                List<Object> params = new ArrayList<>();
                if (body.containsKey("displayName")) {
                    params.add(body.getString("displayName"));
                    changed.add("displayName");
                }
                if (body.containsKey("email")) {
                    params.add(body.getString("email"));
                    changed.add("email");
                }
                if (body.containsKey("isActive")) {
                    params.add(Boolean.TRUE.equals(body.getBoolean("isActive")));
                    changed.add("isActive");
                }
                if (body.containsKey("password")) {
                    String passwordError = validatePassword(body.getString("password"));
                    if (passwordError != null) {
                        return Future.failedFuture(new ApiError(400, passwordError));
                    }
                    params.add(PasswordService.hash(body.getString("password")));
                    changed.add("password");
                }
                List<String> roles = body.containsKey("roles") ? parseRoles(body.getValue("roles")) : null;
                if (body.containsKey("roles") && roles == null) {
                    return Future.failedFuture(new ApiError(400,
                        "roles must be a non-empty subset of ADMIN/MANAGER/USER"));
                }
                Future<Void> update = Future.succeededFuture();
                if (!params.isEmpty()) {
                    StringBuilder set = new StringBuilder();
                    for (String field : changed) {
                        String column = switch (field) {
                            case "displayName" -> "display_name";
                            case "isActive" -> "is_active";
                            case "password" -> "password_hash";
                            default -> field;
                        };
                        set.append(set.isEmpty() ? "" : ", ").append(column).append(" = ?");
                    }
                    params.add(userId);
                    update = pool.preparedQuery("UPDATE np_users SET " + set + " WHERE id = ?")
                        .execute(Tuple.from(params))
                        .mapEmpty();
                }
                if (roles != null) {
                    changed.add("roles");
                    update = update
                        .compose(v -> pool.preparedQuery("DELETE FROM np_user_roles WHERE user_id = ?")
                            .execute(Tuple.of(userId)))
                        .compose(v -> replaceRoles(userId, roles));
                }
                JsonObject details = new JsonObject().put("changedFields", changed);
                if (roles != null) {
                    details.put("roles", roles);
                }
                return update
                    .compose(v -> auditEventRepository.insertAuditEvent("USER", actorId, "USER_UPDATED",
                        "USER", username, details.encode(), remoteIp))
                    .compose(v -> getUser(username));
            });
    }

    private Future<JsonObject> getUser(String username) {
        return pool.preparedQuery(USER_SELECT + " WHERE u.username = ?")
            .execute(Tuple.of(username))
            .map(rows -> rows.iterator().hasNext() ? userPayload(rows.iterator().next()) : null);
    }

    private Future<Long> findUserId(String username) {
        return pool.preparedQuery("SELECT id FROM np_users WHERE username = ?")
            .execute(Tuple.of(username))
            .map(rows -> rows.iterator().hasNext() ? rows.iterator().next().getLong("id") : null);
    }

    // ponytail: links inserted one statement per role; role sets are tiny (<=3)
    private Future<Void> replaceRoles(long userId, List<String> roles) {
        Future<Void> linked = Future.succeededFuture();
        for (String role : roles) {
            linked = linked.compose(v -> pool.preparedQuery("""
                    INSERT INTO np_user_roles (user_id, role_id)
                    SELECT ?, r.id FROM np_roles r WHERE r.role_name = ?
                    """)
                .execute(Tuple.of(userId, role))
                .mapEmpty());
        }
        return linked;
    }

    private static String validatePassword(String password) {
        if (password == null || password.length() < 8) {
            return "password must be at least 8 characters";
        }
        return null;
    }

    private static List<String> parseRoles(Object raw) {
        if (!(raw instanceof JsonArray array) || array.isEmpty()) {
            return null;
        }
        Set<String> roles = new LinkedHashSet<>();
        for (int i = 0; i < array.size(); i++) {
            if (!(array.getValue(i) instanceof String role) || !ALLOWED_ROLES.contains(role)) {
                return null;
            }
            roles.add(role);
        }
        return List.copyOf(roles);
    }

    private static final String USER_SELECT = """
        SELECT u.id, u.username, u.display_name, u.email, u.is_active, u.created_at, u.updated_at,
               (SELECT GROUP_CONCAT(r.role_name) FROM np_user_roles ur
                 JOIN np_roles r ON r.id = ur.role_id WHERE ur.user_id = u.id) AS role_names
        FROM np_users u
        """;

    private static JsonObject userPayload(Row row) {
        JsonObject json = RowJson.rowToJson(row);
        String roleNames = json.getString("roleNames");
        json.remove("roleNames");
        json.put("roles", roleNames == null ? new JsonArray() : new JsonArray(List.of(roleNames.split(","))));
        return json;
    }
}
