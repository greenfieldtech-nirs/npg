package cloud.cloudonix.npg.security;

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
 */
public class JwtService {

    public record AuthUser(String username, List<String> roles) {
    }

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] secret;

    public JwtService() {
        // ponytail: dev-default secret; MUST override with NPG_JWT_SECRET in prod
        this(System.getenv().getOrDefault("NPG_JWT_SECRET", "npg-dev-secret-change-me-in-prod"));
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
