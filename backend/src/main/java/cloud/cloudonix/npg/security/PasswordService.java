package cloud.cloudonix.npg.security;

import org.mindrot.jbcrypt.BCrypt;

public final class PasswordService {

    private PasswordService() {
    }

    public static String hash(String raw) {
        return BCrypt.hashpw(raw, BCrypt.gensalt());
    }

    public static boolean verify(String raw, String hashed) {
        if (raw == null || hashed == null || hashed.isBlank()) {
            return false;
        }
        try {
            return BCrypt.checkpw(raw, hashed);
        } catch (IllegalArgumentException e) {
            // malformed stored hash (e.g. the '' default) — treat as mismatch, never as an error
            return false;
        }
    }
}
