package cloud.cloudonix.npg.api;

import cloud.cloudonix.npg.security.JwtService;
import cloud.cloudonix.npg.security.LoginThrottle;
import cloud.cloudonix.npg.security.PasswordService;
import cloud.cloudonix.npg.security.UserRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

public class AuthApiHandler {

    private static final Duration TOKEN_TTL = Duration.ofHours(8);
    static final int MAX_USERNAME_LENGTH = 128;

    private final UserRepository userRepository;
    private final JwtService jwtService;
    // per username+source: stops a single client guessing; per account: stops rotating sources
    private final LoginThrottle perSource;
    private final LoginThrottle perAccount;

    public AuthApiHandler(Pool pool, JwtService jwtService) {
        this(pool, jwtService,
            new LoginThrottle(5, Duration.ofMinutes(5), Clock.systemUTC()),
            new LoginThrottle(20, Duration.ofMinutes(15), Clock.systemUTC()));
    }

    AuthApiHandler(Pool pool, JwtService jwtService, LoginThrottle perSource, LoginThrottle perAccount) {
        this.userRepository = new UserRepository(pool);
        this.jwtService = jwtService;
        this.perSource = perSource;
        this.perAccount = perAccount;
    }

    /**
     * Throttle keys must match how the database matches usernames: np_users uses a
     * case-insensitive, trailing-space-insensitive collation, so "Admin " is the same account.
     */
    static String canonicalUsername(String username) {
        return username.strip().toLowerCase(Locale.ROOT);
    }

    public Future<JsonObject> login(JsonObject body, String remoteIp) {
        String username = body != null ? body.getString("username") : null;
        String password = body != null ? body.getString("password") : null;
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            return Future.failedFuture(new ApiError(400, "username and password are required"));
        }
        if (username.length() > MAX_USERNAME_LENGTH) {
            return Future.failedFuture(new ApiError(400, "username is too long"));
        }
        String account = canonicalUsername(username);
        String sourceKey = account + "|" + remoteIp;
        // reserve the attempt up front so concurrent guesses are counted while in flight
        if (!perSource.tryAcquire(sourceKey) || !perAccount.tryAcquire(account)) {
            return Future.failedFuture(new ApiError(429, "Too many failed login attempts, try again later"));
        }
        return userRepository.findByUsername(username)
            .compose(user -> {
                if (user == null || !user.active() || !PasswordService.verify(password, user.passwordHash())) {
                    return Future.failedFuture(new ApiError(401, "Invalid credentials"));
                }
                perSource.reset(sourceKey);
                perAccount.reset(account);
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
