package cloud.cloudonix.npg.api;

import cloud.cloudonix.npg.security.JwtService;
import cloud.cloudonix.npg.security.PasswordService;
import cloud.cloudonix.npg.security.UserRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;

import java.time.Duration;
import java.util.List;

public class AuthApiHandler {

    private static final Duration TOKEN_TTL = Duration.ofHours(8);

    private final UserRepository userRepository;
    private final JwtService jwtService;

    public AuthApiHandler(Pool pool) {
        this.userRepository = new UserRepository(pool);
        this.jwtService = new JwtService();
    }

    public Future<JsonObject> login(JsonObject body) {
        String username = body != null ? body.getString("username") : null;
        String password = body != null ? body.getString("password") : null;
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            return Future.failedFuture(new ApiError(400, "username and password are required"));
        }
        return userRepository.findByUsername(username)
            .compose(user -> {
                if (user == null || !user.active() || !PasswordService.verify(password, user.passwordHash())) {
                    return Future.failedFuture(new ApiError(401, "Invalid credentials"));
                }
                return userRepository.findRoles(user.id())
                    .map(roles -> new JsonObject()
                        .put("token", jwtService.issue(user.username(), roles, TOKEN_TTL))
                        .put("user", userPayload(user.username(), user.displayName(), roles)));
            });
    }

    public JsonObject me(String username, List<String> roles) {
        return userPayload(username, null, roles);
    }

    private JsonObject userPayload(String username, String displayName, List<String> roles) {
        JsonObject user = new JsonObject()
            .put("username", username)
            .put("roles", new JsonArray(roles));
        if (displayName != null) {
            user.put("displayName", displayName);
        }
        return user;
    }
}
