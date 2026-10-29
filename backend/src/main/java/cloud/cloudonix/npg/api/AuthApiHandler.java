package cloud.cloudonix.npg.api;

import cloud.cloudonix.npg.config.Env;
import cloud.cloudonix.npg.security.DeviceTrust;
import cloud.cloudonix.npg.security.JwtService;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Login and identity endpoints.
 *
 * <p>Brute-force defence, in order:
 * <ol>
 *   <li>a per-client-IP limit, which also covers unknown usernames;</li>
 *   <li>a per-account limit keyed on the database id — the collation decides which strings name
 *       the same account, so only the database's own match is a safe key, and the table can never
 *       hold more entries than there are accounts. A valid device-trust cookie for the account
 *       skips this limit, so spraying an account cannot lock its owner out;</li>
 *   <li>a cap on concurrent password verifications (bcrypt is deliberately expensive).</li>
 * </ol>
 */
public class AuthApiHandler {

    private static final Duration TOKEN_TTL = Duration.ofHours(8);
    static final int MAX_USERNAME_LENGTH = 128;
    // compared against when the username is unknown, so response time does not reveal which exist
    private static final String DUMMY_HASH = PasswordService.hash("npg-timing-equaliser");

    /** Successful login: response body plus a fresh device-trust token for the cookie. */
    public record LoginResult(JsonObject body, String deviceToken) {
    }

    private final Vertx vertx;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final DeviceTrust deviceTrust;
    private final LoginThrottle perIp;
    private final LoginThrottle perAccount;
    private final int maxConcurrentVerifications;
    private final AtomicInteger verificationsInFlight = new AtomicInteger();

    public AuthApiHandler(Vertx vertx, Pool pool, JwtService jwtService) {
        this(vertx, pool, jwtService,
            new DeviceTrust(jwtService, Duration.ofDays(30), Clock.systemUTC()),
            new LoginThrottle(Env.getInt("LOGIN_MAX_ATTEMPTS_PER_IP", 20), Duration.ofMinutes(5), Clock.systemUTC()),
            new LoginThrottle(Env.getInt("LOGIN_MAX_ATTEMPTS_PER_ACCOUNT", 10), Duration.ofMinutes(15), Clock.systemUTC()),
            Env.getInt("LOGIN_MAX_CONCURRENT_VERIFICATIONS", 16));
    }

    AuthApiHandler(Vertx vertx, Pool pool, JwtService jwtService, DeviceTrust deviceTrust,
                   LoginThrottle perIp, LoginThrottle perAccount, int maxConcurrentVerifications) {
        this.vertx = vertx;
        this.userRepository = new UserRepository(pool);
        this.jwtService = jwtService;
        this.deviceTrust = deviceTrust;
        this.perIp = perIp;
        this.perAccount = perAccount;
        this.maxConcurrentVerifications = maxConcurrentVerifications;
    }

    public DeviceTrust deviceTrust() {
        return deviceTrust;
    }

    public Future<LoginResult> login(JsonObject body, String clientIp, String deviceToken) {
        String username = body != null ? body.getString("username") : null;
        String password = body != null ? body.getString("password") : null;
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            return Future.failedFuture(new ApiError(400, "username and password are required"));
        }
        if (username.length() > MAX_USERNAME_LENGTH) {
            return Future.failedFuture(new ApiError(400, "username is too long"));
        }
        String ipKey = String.valueOf(clientIp);
        if (!perIp.tryAcquire(ipKey)) {
            return Future.failedFuture(tooManyAttempts());
        }
        return userRepository.findByUsername(username).compose(user -> {
            String accountKey = user != null ? "id:" + user.id() : null;
            boolean countsAgainstAccount = user != null && !deviceTrust.isTrusted(deviceToken, user.id(), user.passwordHash());
            if (countsAgainstAccount && !perAccount.tryAcquire(accountKey)) {
                return Future.failedFuture(tooManyAttempts());
            }
            if (verificationsInFlight.incrementAndGet() > maxConcurrentVerifications) {
                verificationsInFlight.decrementAndGet();
                // overload is not a credential failure: give the reservations back
                perIp.release(ipKey);
                if (countsAgainstAccount) {
                    perAccount.release(accountKey);
                }
                return Future.failedFuture(new ApiError(503, "Login is busy, retry shortly"));
            }
            String hash = user != null ? user.passwordHash() : DUMMY_HASH;
            // bcrypt is deliberately slow: keep it off the event loop
            return vertx.executeBlocking(() -> PasswordService.verify(password, hash), false)
                .onComplete(ar -> verificationsInFlight.decrementAndGet())
                .compose(matches -> {
                    if (user == null || !user.active() || !matches) {
                        return Future.failedFuture(new ApiError(401, "Invalid credentials"));
                    }
                    // a success returns only its own reservation; earlier failures keep counting
                    perIp.release(ipKey);
                    perAccount.reset(accountKey);
                    return userRepository.findRoles(user.id())
                        .map(roles -> new LoginResult(
                            new JsonObject()
                                .put("token", jwtService.issue(user.username(), roles, TOKEN_TTL))
                                .put("user", userPayload(user.username(), user.displayName(), roles)),
                            deviceTrust.issue(user.id(), user.passwordHash())));
                });
        });
    }

    private static ApiError tooManyAttempts() {
        return new ApiError(429, "Too many failed login attempts, try again later");
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
