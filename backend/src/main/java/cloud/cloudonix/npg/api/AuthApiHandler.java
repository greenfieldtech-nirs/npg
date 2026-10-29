package cloud.cloudonix.npg.api;

import cloud.cloudonix.npg.security.JwtService;
import cloud.cloudonix.npg.security.KnownLoginSources;
import cloud.cloudonix.npg.security.LoginThrottle;
import cloud.cloudonix.npg.security.PasswordService;
import cloud.cloudonix.npg.security.UserRepository;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
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
    // compared against when the username is unknown, so response time does not reveal which exist
    private static final String DUMMY_HASH = PasswordService.hash("npg-timing-equaliser");

    private final Vertx vertx;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    // per account+source: stops one client guessing; per account: stops rotating sources
    private final LoginThrottle perSource;
    private final LoginThrottle perAccount;
    private final KnownLoginSources knownSources;

    public AuthApiHandler(Vertx vertx, Pool pool, JwtService jwtService) {
        this(vertx, pool, jwtService,
            new LoginThrottle(5, Duration.ofMinutes(5), Clock.systemUTC()),
            new LoginThrottle(20, Duration.ofMinutes(15), Clock.systemUTC()),
            new KnownLoginSources(Duration.ofHours(24), Clock.systemUTC(), 50_000));
    }

    AuthApiHandler(Vertx vertx, Pool pool, JwtService jwtService, LoginThrottle perSource,
                   LoginThrottle perAccount, KnownLoginSources knownSources) {
        this.vertx = vertx;
        this.userRepository = new UserRepository(pool);
        this.jwtService = jwtService;
        this.perSource = perSource;
        this.perAccount = perAccount;
        this.knownSources = knownSources;
    }

    /**
     * Throttle key for a login attempt. When the database matched an account, the key is that
     * account's id — the collation decides which strings name the same user ("Admin ", "ädmin"...),
     * so only the database identity is a safe key. Unknown names have no account to protect.
     */
    static String accountKey(UserRepository.UserRecord user, String username) {
        return user != null ? "id:" + user.id() : "unknown:" + username.strip().toLowerCase(Locale.ROOT);
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
        return userRepository.findByUsername(username).compose(user -> {
            String account = accountKey(user, username);
            String sourceKey = account + "|" + remoteIp;
            // reserve the attempt before verifying so concurrent guesses count while in flight;
            // a source that recently logged in to this account is exempt from the account-wide
            // limit, so spraying from elsewhere cannot lock the owner out
            boolean allowed = perSource.tryAcquire(sourceKey)
                && (knownSources.isKnown(account, remoteIp) || perAccount.tryAcquire(account));
            if (!allowed) {
                return Future.failedFuture(new ApiError(429, "Too many failed login attempts, try again later"));
            }
            String hash = user != null ? user.passwordHash() : DUMMY_HASH;
            // bcrypt is deliberately slow: keep it off the event loop
            return vertx.executeBlocking(() -> PasswordService.verify(password, hash), false)
                .compose(matches -> {
                    if (user == null || !user.active() || !matches) {
                        return Future.failedFuture(new ApiError(401, "Invalid credentials"));
                    }
                    perSource.reset(sourceKey);
                    perAccount.reset(account);
                    knownSources.remember(account, remoteIp);
                    return userRepository.findRoles(user.id())
                        .map(roles -> new JsonObject()
                            .put("token", jwtService.issue(user.username(), roles, TOKEN_TTL))
                            .put("user", userPayload(user.username(), user.displayName(), roles)));
                });
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
