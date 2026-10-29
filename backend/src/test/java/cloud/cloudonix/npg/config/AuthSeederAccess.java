package cloud.cloudonix.npg.config;

/** Test bridge to package-private {@link AuthSeeder} policy. */
public final class AuthSeederAccess {

    private AuthSeederAccess() {
    }

    public static String initialAdminPassword(String configured) {
        return AuthSeeder.initialAdminPassword(configured);
    }
}
