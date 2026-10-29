package cloud.cloudonix.npg.config;

import java.util.Set;

/**
 * Single source of runtime configuration. A JVM system property wins over the environment
 * variable of the same name (tests set system properties); blank values count as unset.
 */
public final class Env {

    private static final Set<String> DEV_ENVIRONMENTS = Set.of("dev", "development", "test");

    private Env() {
    }

    public static String get(String key, String defaultValue) {
        String value = System.getProperty(key);
        if (value != null && !value.isBlank()) {
            return value;
        }
        value = System.getenv(key);
        return value != null && !value.isBlank() ? value : defaultValue;
    }

    public static String get(String key) {
        return get(key, null);
    }

    public static int getInt(String key, int defaultValue) {
        String value = get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Configuration " + key + " must be an integer, got: " + value);
        }
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key);
        return value == null ? defaultValue : Boolean.parseBoolean(value.trim());
    }

    /**
     * NPG_ENV selects insecure conveniences (default secrets, default admin password).
     * Anything other than dev/development/test is treated as production — secure by default.
     */
    public static boolean isDevMode() {
        return DEV_ENVIRONMENTS.contains(get("NPG_ENV", "production").trim().toLowerCase());
    }
}
