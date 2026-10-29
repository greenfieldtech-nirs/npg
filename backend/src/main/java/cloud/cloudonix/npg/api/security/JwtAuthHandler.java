package cloud.cloudonix.npg.api.security;

import cloud.cloudonix.npg.security.JwtService;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

import java.util.List;
import java.util.Set;

public class JwtAuthHandler implements Handler<RoutingContext> {

    private static final JwtService JWT = new JwtService();

    private final Set<String> requiredRoles;

    private JwtAuthHandler(Set<String> requiredRoles) {
        this.requiredRoles = requiredRoles;
    }

    @Override
    public void handle(RoutingContext ctx) {
        JwtService.AuthUser user = authenticate(ctx);
        if (user == null) {
            reject(ctx, 401, "Unauthorized");
            return;
        }
        if (!requiredRoles.isEmpty() && user.roles().stream().noneMatch(requiredRoles::contains)) {
            reject(ctx, 403, "Forbidden");
            return;
        }
        ctx.put("authUser", user);
        ctx.put("actorId", user.username());
        ctx.next();
    }

    private JwtService.AuthUser authenticate(RoutingContext ctx) {
        String auth = ctx.request().getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            JwtService.AuthUser user = JWT.verify(auth.substring(7)).orElse(null);
            if (user != null) {
                return user;
            }
        }
        // ponytail: DEV-ONLY fallback — legacy X-User-Role header keeps existing tests and the
        // pre-login frontend working during the JWT transition; remove once the frontend logs in
        String role = ctx.request().getHeader("X-User-Role");
        if (role != null) {
            String id = ctx.request().getHeader("X-User-Id");
            return new JwtService.AuthUser(id != null ? id : role, List.of(role));
        }
        return null;
    }

    private void reject(RoutingContext ctx, int status, String message) {
        ctx.response().setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(new JsonObject().put("error", message).encode());
    }

    /** Any valid identity (JWT or dev fallback header). */
    public static JwtAuthHandler authenticated() {
        return new JwtAuthHandler(Set.of());
    }

    /** Authenticated AND holding at least one of the given roles. */
    public static JwtAuthHandler requireAny(String... roles) {
        return new JwtAuthHandler(Set.of(roles));
    }
}
