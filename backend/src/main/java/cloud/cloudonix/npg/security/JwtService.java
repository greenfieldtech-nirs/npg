package cloud.cloudonix.npg.security;

import cloud.cloudonix.npg.config.Env;
import cloud.cloudonix.npg.config.Env;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Minimal HS256 JWT issuer/verifier — hand-rolled, no JWT library.
 * Only HS256 tokens signed with our secret are accepted; the alg header is pinned.
 */
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    static final int MIN_SECRET_LENGTH = 32;
    private static final String DEV_SECRET = "npg-dev-secret-not-for-production-use";

    public record AuthUser(String username, List<String> roles) {
    }

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] secret;

    /**
     * Builds the service from NPG_JWT_SECRET. Outside dev mode a missing or short secret is a
     * startup failure: a guessable signing key lets anyone mint ADMIN tokens.
     */
    public static JwtService fromEnvironment() {
        String secret = Env.get("NPG_JWT_SECRET");
        if (Env.isDevMode()) {
            if (secret == null) {
                log.warn("NPG_JWT_SECRET not set — using the built-in dev secret (NPG_ENV=dev/test only)");
                secret = DEV_SECRET;
            }
            return new JwtService(secret);
        }
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("NPG_JWT_SECRET must be set to at least " + MIN_SECRET_LENGTH
                + " characters (set NPG_ENV=dev to use the built-in development secret)");
        }
        return new JwtService(secret);
    }

    public JwtService(String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String issue(String username, List<String> roles, Duration ttl) {
        long now = System.currentTimeMillis() / 1000;
        String header = B64.encodeToString(new JsonObject()
            .put("alg", "HS256").put("typ", "JWT").encode().getBytes(StandardCharsets.UTF_8));
        String payload = B64.encodeToString(new JsonObject()
            .put("sub", username)
            .put("roles", new JsonArray(roles))
            .put("iat", now)
            .put("exp", now + ttl.getSeconds())
            .encode().getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + payload;
        return signingInput + "." + sign(signingInput);
    }

    public Optional<AuthUser> verify(String token) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) {
                return Optional.empty();
            }
            JsonObject header = new JsonObject(new String(B64D.decode(parts[0]), StandardCharsets.UTF_8));
            if (!"HS256".equals(header.getString("alg"))) {
                return Optional.empty();
            }
            String expected = sign(parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    parts[2].getBytes(StandardCharsets.UTF_8))) {
                return Optional.empty();
            }
            JsonObject payload = new JsonObject(new String(B64D.decode(parts[1]), StandardCharsets.UTF_8));
            Long exp = payload.getLong("exp");
            String sub = payload.getString("sub");
            if (exp == null || sub == null || exp < System.currentTimeMillis() / 1000) {
                return Optional.empty();
            }
            List<String> roles = payload.getJsonArray("roles", new JsonArray()).stream()
                .map(Object::toString).toList();
            return Optional.of(new AuthUser(sub, roles));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * HMAC signature for non-JWT values (e.g. device-trust cookies). The purpose is mixed into the
     * signed input so a signature made for one purpose can never be replayed as another.
     */
    public String signDetached(String purpose, String payload) {
        return sign("detached:" + purpose + "\n" + payload);
    }

    public boolean verifyDetached(String purpose, String payload, String signature) {
        if (signature == null) {
            return false;
        }
        return MessageDigest.isEqual(signDetached(purpose, payload).getBytes(StandardCharsets.UTF_8),
            signature.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return B64.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
