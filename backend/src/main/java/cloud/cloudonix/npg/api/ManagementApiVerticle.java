package cloud.cloudonix.npg.api;

import cloud.cloudonix.npg.config.Env;
import cloud.cloudonix.npg.api.security.JwtAuthHandler;
import cloud.cloudonix.npg.security.JwtService;
import cloud.cloudonix.npg.security.LoginThrottle;
import cloud.cloudonix.npg.security.UserRepository;
import cloud.cloudonix.npg.audit.XmlAuditService;
import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.config.MongoConfig;
import cloud.cloudonix.npg.config.RedisConfig;
import cloud.cloudonix.npg.repository.AuditEventRepository;
import cloud.cloudonix.npg.repository.CachedOperatorRepository;
import cloud.cloudonix.npg.repository.OperatorRepository;
import cloud.cloudonix.npg.service.CacheService;
import cloud.cloudonix.npg.service.PubSubService;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.redis.client.RedisAPI;
import io.vertx.sqlclient.Pool;

public class ManagementApiVerticle extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(ManagementApiVerticle.class);
    static final long MAX_BODY_BYTES = 1024 * 1024;

    private JwtAuthHandler.Guard guard;
    private AuthApiHandler authApiHandler;
    private DashboardApiHandler dashboardApiHandler;
    private WorkflowApiHandler workflowApiHandler;
    private MessageApiHandler messageApiHandler;
    private OperatorApiHandler operatorApiHandler;
    private OperatorEndpointApiHandler operatorEndpointApiHandler;
    private ManualTaskApiHandler manualTaskApiHandler;
    private NumberApiHandler numberApiHandler;
    private UserManagementApiHandler userManagementApiHandler;

    @Override
    public void start(Promise<Void> startPromise) {
        Future<Pool> poolFuture = DatabaseConfig.configure(vertx);
        Future<MongoClient> mongoFuture = MongoConfig.configure(vertx);
        Future<RedisAPI> redisFuture = RedisConfig.configure(vertx);

        Future.all(poolFuture, mongoFuture, redisFuture)
            .compose(v -> {
                Pool pool = poolFuture.result();
                XmlAuditService xmlAuditService = new XmlAuditService(mongoFuture.result());
                CacheService cacheService = new CacheService(redisFuture.result());
                PubSubService pubSubService = new PubSubService(vertx, redisFuture.result());
                JwtService jwt = JwtService.fromEnvironment();
                guard = new JwtAuthHandler.Guard(jwt, new UserRepository(pool));
                authApiHandler = new AuthApiHandler(pool, jwt, new LoginThrottle());
                dashboardApiHandler = new DashboardApiHandler(pool);
                workflowApiHandler = new WorkflowApiHandler(pool);
                messageApiHandler = new MessageApiHandler(pool, xmlAuditService,
                    new cloud.cloudonix.npg.protocol.ProtocolCatalogLoader(vertx,
                        Env.get("CATALOG_PATH", "docs/protocol")));
                CachedOperatorRepository cachedOperators = new CachedOperatorRepository(
                    new OperatorRepository(pool), cacheService, pubSubService);
                operatorApiHandler = new OperatorApiHandler(pool, cachedOperators, new AuditEventRepository(pool));
                operatorEndpointApiHandler = new OperatorEndpointApiHandler(pool);
                manualTaskApiHandler = new ManualTaskApiHandler(pool);
                numberApiHandler = new NumberApiHandler(pool);
                userManagementApiHandler = new UserManagementApiHandler(pool);
                return xmlAuditService.ensureIndexes();
            })
            .compose(v -> {
                Router router = buildRouter();
                int port = config().getInteger("port", 8080);
                return vertx.createHttpServer()
                    .requestHandler(router)
                    .listen(port);
            })
            .<Void>mapEmpty()
            .onComplete(startPromise);
    }

    private Router buildRouter() {
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create().setBodyLimit(MAX_BODY_BYTES));
        router.route().failureHandler(this::failure);

        router.get("/health/live").respond(ctx -> Future.succeededFuture("OK"));
        router.get("/health/ready").respond(ctx -> Future.succeededFuture("OK"));

        router.post("/api/auth/login").handler(ctx ->
            handleJson(ctx, authApiHandler.login(jsonBody(ctx), remoteIp(ctx))));
        router.get("/api/auth/me")
            .handler(guard.authenticated())
            .handler(ctx -> {
                JwtService.AuthUser user = ctx.get("authUser");
                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end(authApiHandler.me(user.username(), user.roles()).encode());
            });

        router.get("/metrics").handler(ctx -> {
            JsonObject metrics = new JsonObject()
                .put("inbound_messages_total", 0)
                .put("outbound_messages_total", 0)
                .put("inbox_pending_count", 0)
                .put("outbox_pending_count", 0)
                .put("timers_due_count", 0)
                .put("manual_tasks_open_count", 0);
            ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(metrics.encode());
        });

        router.get("/api/dashboard")
            .handler(guard.authenticated())
            .handler(ctx ->
            handleJson(ctx, dashboardApiHandler.getDashboard()));

        router.get("/api/workflows")
            .handler(guard.authenticated())
            .handler(ctx ->
            handleJson(ctx, workflowApiHandler.searchWorkflows(
                ctx.request().getParam("phoneNumber"),
                ctx.request().getParam("state"),
                intParam(ctx, "page", 0),
                intParam(ctx, "size", 20))));
        router.get("/api/workflows/:workflowUuid")
            .handler(guard.authenticated())
            .handler(ctx ->
            handleJson(ctx, workflowApiHandler.getWorkflow(ctx.pathParam("workflowUuid"))));
        router.get("/api/workflows/:workflowUuid/events")
            .handler(guard.authenticated())
            .handler(ctx ->
            handleJson(ctx, workflowApiHandler.getWorkflowEvents(ctx.pathParam("workflowUuid"))));
        router.post("/api/workflows/:workflowUuid/manual-transition")
            .handler(guard.requireAny("ADMIN", "MANAGER"))
            .handler(ctx -> {
                JsonObject body = jsonBody(ctx);
                handleEmpty(ctx, workflowApiHandler.manualTransition(
                    ctx.pathParam("workflowUuid"),
                    body.getString("toState"),
                    body.getString("reason"),
                    ctx.get("actorId")));
            });

        router.get("/api/messages")
            .handler(guard.authenticated())
            .handler(ctx ->
            handleJson(ctx, messageApiHandler.searchMessages(
                ctx.request().getParam("requestId"),
                ctx.request().getParam("messageType"),
                ctx.request().getParam("direction"),
                ctx.request().getParam("technicalStatus"),
                ctx.request().getParam("dateFrom"),
                ctx.request().getParam("dateTo"),
                intParam(ctx, "page", 0),
                intParam(ctx, "size", 20))));
        router.get("/api/messages/:messageUuid/pair")
            .handler(guard.authenticated())
            .handler(ctx ->
            handleJson(ctx, messageApiHandler.getMessagePair(ctx.pathParam("messageUuid"))));
        router.get("/api/messages/:messageUuid")
            .handler(guard.authenticated())
            .handler(ctx ->
            handleJson(ctx, messageApiHandler.getMessage(ctx.pathParam("messageUuid"))));
        router.get("/api/messages/:messageUuid/xml")
            .handler(guard.authenticated())
            .handler(ctx -> {
            String remoteIp = ctx.request().remoteAddress() != null ? ctx.request().remoteAddress().host() : null;
            messageApiHandler.getMessageXml(ctx.pathParam("messageUuid"), ctx.get("actorId"), remoteIp)
                .onSuccess(xml -> ctx.response()
                    .putHeader("Content-Type", "application/xml")
                    .end(xml))
                .onFailure(err -> error(ctx, err));
        });

        router.get("/api/operators")
            .handler(guard.authenticated())
            .handler(ctx ->
            handleJson(ctx, operatorApiHandler.listOperators()));
        router.post("/api/operators")
            .handler(guard.requireAny("ADMIN", "MANAGER"))
            .handler(ctx -> {
                JsonObject body = jsonBody(ctx);
                // the local (own-gateway) identity is ADMIN-only, same rule as PUT
                JwtService.AuthUser user = ctx.get("authUser");
                if (Boolean.TRUE.equals(body.getBoolean("isLocal")) && !user.roles().contains("ADMIN")) {
                    error(ctx, new ApiError(403, "Forbidden"));
                    return;
                }
                handleJson(ctx, operatorApiHandler.createOperator(body, ctx.get("actorId"), remoteIp(ctx)));
            });
        router.put("/api/operators/:operatorCode")
            .handler(guard.authenticated())
            .handler(ctx -> updateOperatorWithLocalConfigGate(ctx));
        router.get("/api/operators/:operatorCode")
            .handler(guard.authenticated())
            .handler(ctx ->
            handleJson(ctx, operatorApiHandler.getOperator(ctx.pathParam("operatorCode"))));
        router.post("/api/operators/:operatorCode/mark-down")
            .handler(guard.requireAny("ADMIN", "MANAGER"))
            .handler(ctx -> {
                JsonObject body = jsonBody(ctx);
                String reason = body != null ? body.getString("reason", "manual") : "manual";
                handleEmpty(ctx, operatorApiHandler.markDown(ctx.pathParam("operatorCode"), reason));
            });

        router.get("/api/operators/:operatorCode/endpoints")
            .handler(guard.authenticated())
            .handler(ctx ->
            handleJson(ctx, operatorEndpointApiHandler.listEndpoints(ctx.pathParam("operatorCode"))));
        router.post("/api/operators/:operatorCode/endpoints")
            .handler(guard.requireAny("ADMIN"))
            .handler(ctx -> handleJson(ctx, operatorEndpointApiHandler.createEndpoint(
                ctx.pathParam("operatorCode"), jsonBody(ctx))));
        router.delete("/api/operators/:operatorCode/endpoints/:endpointId")
            .handler(guard.requireAny("ADMIN"))
            .handler(ctx -> handleEmpty(ctx, operatorEndpointApiHandler.deleteEndpoint(
                ctx.pathParam("operatorCode"), longPathParam(ctx, "endpointId"))));

        router.get("/api/manual-tasks")
            .handler(guard.authenticated())
            .handler(ctx ->
            handleJson(ctx, manualTaskApiHandler.listTasks(ctx.request().getParam("status"))));
        router.post("/api/manual-tasks/:taskUuid/resolve")
            .handler(guard.requireAny("ADMIN", "MANAGER"))
            .handler(ctx -> {
                JsonObject body = jsonBody(ctx);
                handleEmpty(ctx, manualTaskApiHandler.resolveTask(
                    ctx.pathParam("taskUuid"),
                    body != null ? body.getString("resolution") : null));
            });

        router.get("/api/users")
            .handler(guard.requireAny("ADMIN"))
            .handler(ctx -> handleJson(ctx, userManagementApiHandler.listUsers()));
        router.post("/api/users")
            .handler(guard.requireAny("ADMIN"))
            .handler(ctx -> handleJson(ctx, userManagementApiHandler.createUser(
                jsonBody(ctx), ctx.get("actorId"), remoteIp(ctx))));
        router.put("/api/users/:username")
            .handler(guard.requireAny("ADMIN"))
            .handler(ctx -> handleJson(ctx, userManagementApiHandler.updateUser(
                ctx.pathParam("username"), jsonBody(ctx), ctx.get("actorId"), remoteIp(ctx))));

        router.get("/api/settings/local")
            .handler(guard.requireAny("ADMIN"))
            .handler(ctx -> ctx.response()
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("publicBaseUrl",
                    Env.get("APP_PUBLIC_BASE_URL", "http://localhost:8080")).encode()));

        router.get("/api/numbers")
            .handler(guard.authenticated())
            .handler(ctx ->
            handleJson(ctx, numberApiHandler.searchByPrefix(
                ctx.request().getParam("prefix"),
                intParam(ctx, "size", 50))));
        router.get("/api/numbers/:phoneNumber")
            .handler(guard.authenticated())
            .handler(ctx ->
            handleJson(ctx, numberApiHandler.getNumber(ctx.pathParam("phoneNumber"))));
        router.get("/api/numbers/:phoneNumber/history")
            .handler(guard.authenticated())
            .handler(ctx ->
            handleJson(ctx, numberApiHandler.getNumberHistory(ctx.pathParam("phoneNumber"))));

        return router;
    }

    /**
     * PUT /api/operators/:code gate: any change touching local configuration
     * (body carries isLocal OR the target operator is currently local) requires ADMIN;
     * anything else requires MANAGER or ADMIN.
     */
    private void updateOperatorWithLocalConfigGate(RoutingContext ctx) {
        String operatorCode = ctx.pathParam("operatorCode");
        JsonObject body = jsonBody(ctx);
        JwtService.AuthUser user = ctx.get("authUser");
        handleJson(ctx, operatorApiHandler.getOperator(operatorCode)
            .compose(existing -> {
                boolean localConfigChange = (body != null && body.containsKey("isLocal"))
                    || (existing != null && Boolean.TRUE.equals(existing.getBoolean("isLocal")));
                boolean isAdmin = user.roles().contains("ADMIN");
                boolean isManager = user.roles().contains("MANAGER");
                if (localConfigChange ? !isAdmin : !(isAdmin || isManager)) {
                    return Future.failedFuture(new ApiError(403, "Forbidden"));
                }
                return operatorApiHandler.updateOperator(operatorCode, body, user.username(), remoteIp(ctx));
            }));
    }

    private static String remoteIp(RoutingContext ctx) {
        return ctx.request().remoteAddress() != null ? ctx.request().remoteAddress().host() : null;
    }

    private void handleJson(RoutingContext ctx, Future<?> future) {
        future
            .onSuccess(result -> {
                if (result == null) {
                    ctx.response().setStatusCode(404)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("error", "Not found").encode());
                    return;
                }
                String body = result instanceof JsonObject jo ? jo.encode()
                    : result instanceof JsonArray ja ? ja.encode()
                    : JsonObject.mapFrom(result).encode();
                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end(body);
            })
            .onFailure(err -> error(ctx, err));
    }

    private void handleEmpty(RoutingContext ctx, Future<Void> future) {
        future
            .onSuccess(v -> ctx.response().setStatusCode(200).end())
            .onFailure(err -> error(ctx, err));
    }

    /**
     * Parsed JSON request body; an absent body is an empty object, a malformed one is a 400.
     */
    static JsonObject jsonBody(RoutingContext ctx) {
        try {
            JsonObject body = ctx.body().asJsonObject();
            return body != null ? body : new JsonObject();
        } catch (DecodeException | ClassCastException e) {
            throw new ApiError(400, "Request body must be a JSON object");
        }
    }

    static int intParam(RoutingContext ctx, String name, int defaultValue) {
        String value = ctx.request().getParam(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new ApiError(400, "Query parameter '" + name + "' must be an integer");
        }
    }

    static long longPathParam(RoutingContext ctx, String name) {
        try {
            return Long.parseLong(ctx.pathParam(name));
        } catch (NumberFormatException e) {
            throw new ApiError(400, "Path parameter '" + name + "' must be numeric");
        }
    }

    /** Router-level failure handler: exceptions thrown synchronously by handlers land here. */
    private void failure(RoutingContext ctx) {
        Throwable err = ctx.failure();
        if (err != null) {
            error(ctx, err);
            return;
        }
        int status = ctx.statusCode() > 0 ? ctx.statusCode() : 500;
        String message = status == 413 ? "Request body too large" : status >= 500 ? "Internal server error" : "Bad request";
        ctx.response().setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(new JsonObject().put("error", message).encode());
    }

    /**
     * ApiError messages are written for clients; anything else is internal and is only logged —
     * the client gets a generic message plus an id to correlate with the log line.
     */
    private void error(RoutingContext ctx, Throwable err) {
        if (ctx.response().ended()) {
            return;
        }
        JsonObject body;
        int status;
        if (err instanceof ApiError apiError) {
            status = apiError.status;
            body = new JsonObject().put("error", apiError.getMessage());
        } else {
            status = 500;
            String errorId = java.util.UUID.randomUUID().toString();
            log.error("Unhandled API error " + errorId + " on " + ctx.request().method() + " "
                + ctx.request().path(), err);
            body = new JsonObject().put("error", "Internal server error").put("errorId", errorId);
        }
        ctx.response().setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(body.encode());
    }
}
