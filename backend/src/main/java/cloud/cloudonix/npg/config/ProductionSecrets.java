package cloud.cloudonix.npg.config;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Refuses to run production with the example placeholders of docker/.env.production.example: they
 * pass the length checks, but are published in the repository.
 */
public final class ProductionSecrets {

    static final String PLACEHOLDER = "REQUIRED-change-me";
    /** MONGO_URI carries MONGO_PASSWORD in the production stack. */
    static final List<String> SECRETS = List.of("NPG_ADMIN_PASSWORD", "NPG_JWT_SECRET", "DB_PASSWORD", "MONGO_URI");

    private ProductionSecrets() {
    }

    /** The settings that still hold the example placeholder. */
    static List<String> placeholders(UnaryOperator<String> lookup) {
        return SECRETS.stream().filter(name -> {
            String value = lookup.apply(name);
            return value != null && value.contains(PLACEHOLDER);
        }).toList();
    }

    public static void check() {
        if (Env.isDevMode()) {
            return;
        }
        List<String> unset = placeholders(Env::get);
        if (!unset.isEmpty()) {
            throw new IllegalStateException("replace the example placeholder (" + PLACEHOLDER + ") in docker/.env.production for: "
                + String.join(", ", unset));
        }
    }
}
