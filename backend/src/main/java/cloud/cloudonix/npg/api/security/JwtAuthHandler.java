package cloud.cloudonix.npg.api.security;

import cloud.cloudonix.npg.security.JwtService;
import cloud.cloudonix.npg.security.UserRepository;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

import java.util.Set;

/**
 * Bearer-token gate for the management API. The token proves identity only: on every request
 * the user is re-read from the database, so deactivation or a role change takes effect
 * immediately instead of when the (8h) token expires. Roles always come from the database.
 */
public final class JwtAuthHandler implements Handler<RoutingContext> {

    private final JwtService jwt;
    private final UserRepository users;
    private final Set<String> requiredRoles;

    private JwtAuthHandler(JwtService jwt, UserRepository users, Set<String> requiredRoles) {
        this.jwt = jwt;
        this.users = users;
        this.requiredRoles = requiredRoles;
    }

    @Override
    public void handle(RoutingContext ctx) {
        String auth = ctx.request().getHeader("Authorization");
        JwtService.AuthUser claimed = auth != null && auth.startsWith("Bearer ")
            ? jwt.verify(auth.substring(7)).orElse(null)
            : null;
        if (claimed == null) {
            reject(ctx, 401, "Unauthorized");
            return;
        }
        currentUser(claimed.username()).onComplete(ar -> {
            JwtService.AuthUser user = ar.succeeded() ? ar.result() : null;
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
        });
    }

    private Future<JwtService.AuthUser> currentUser(String username) {
        return users.findByUsername(username).compose(record -> {
            if (record == null || !record.active()) {
                return Future.succeededFuture(null);
            }
            return users.findRoles(record.id()).map(roles -> new JwtService.AuthUser(record.username(), roles));
        });
    }

    private static void reject(RoutingContext ctx, int status, String message) {
        ctx.response().setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(new JsonObject().put("error", message).encode());
    }

    /** Builds route guards sharing one verifier and user lookup. */
    public static final class Guard {

        private final JwtService jwt;
        private final UserRepository users;

        public Guard(JwtService jwt, UserRepository users) {
            this.jwt = jwt;
            this.users = users;
        }

        /** Any active, authenticated user. */
        public JwtAuthHandler authenticated() {
            return new JwtAuthHandler(jwt, users, Set.of());
        }

        /** Authenticated AND currently holding at least one of the given roles. */
        public JwtAuthHandler requireAny(String... roles) {
            return new JwtAuthHandler(jwt, users, Set.of(roles));
        }
    }
}
