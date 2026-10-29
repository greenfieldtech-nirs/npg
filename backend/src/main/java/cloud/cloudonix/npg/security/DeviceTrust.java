package cloud.cloudonix.npg.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

/**
 * Signed "this browser has logged in to this account before" token, delivered as an HttpOnly
 * cookie. A login that presents a valid token for the target account is exempt from the
 * per-account limit, so attackers spraying the account cannot lock its owner out. Unlike an IP
 * address, the token is a secret only the owner's browser holds.
 *
 * <p>Format: {@code v1.<accountId>.<expiryEpochSeconds>.<nonce>.<hmac>}. The HMAC also covers a
 * fingerprint of the account's current password hash (never written into the cookie), so changing
 * the password revokes every device token issued before.
 */
public class DeviceTrust {

    public static final String COOKIE_NAME = "npg_device";
    static final String PURPOSE = "device-trust";

    private final JwtService signer;
    private final Duration ttl;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public DeviceTrust(JwtService signer, Duration ttl, Clock clock) {
        this.signer = signer;
        this.ttl = ttl;
        this.clock = clock;
    }

    public Duration ttl() {
        return ttl;
    }

    /** Short digest of the stored password hash; changes whenever the password changes. */
    public static String credentialFingerprint(String passwordHash) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(String.valueOf(passwordHash).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public String issue(long accountId, String passwordHash) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        long expiry = clock.millis() / 1000 + ttl.getSeconds();
        String payload = "v1." + accountId + "." + expiry + "."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        return payload + "." + signer.signDetached(PURPOSE, signedInput(payload, passwordHash));
    }

    public boolean isTrusted(String token, long accountId, String passwordHash) {
        if (token == null) {
            return false;
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 5 || !"v1".equals(parts[0])) {
            return false;
        }
        String payload = String.join(".", parts[0], parts[1], parts[2], parts[3]);
        if (!signer.verifyDetached(PURPOSE, signedInput(payload, passwordHash), parts[4])) {
            return false;
        }
        try {
            return Long.parseLong(parts[1]) == accountId && Long.parseLong(parts[2]) >= clock.millis() / 1000;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String signedInput(String payload, String passwordHash) {
        return payload + "|" + credentialFingerprint(passwordHash);
    }
}
