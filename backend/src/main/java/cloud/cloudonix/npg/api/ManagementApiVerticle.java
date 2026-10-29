package cloud.cloudonix.npg.api;

import cloud.cloudonix.npg.api.security.JwtAuthHandler;
import cloud.cloudonix.npg.security.JwtService;
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
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.redis.client.RedisAPI;
import io.vertx.sqlclient.Pool;

public class ManagementApiVerticle extends AbstractVerticle {

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
                authApiHandler = new AuthApiHandler(pool);
                dashboardApiHandler = new DashboardApiHandler(pool);
                workflowApiHandler = new WorkflowApiHandler(pool);
                messageApiHandler = new MessageApiHandler(pool, xmlAuditService,
                    new cloud.cloudonix.npg.protocol.ProtocolCatalogLoader(vertx,
                        System.getenv().getOrDefault("CATALOG_PATH", "docs/protocol")));
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
        router.route().handler(BodyHandler.create());

        router.get("/health/live").respond(ctx -> Future.succeededFuture("OK"));
        router.get("/health/ready").respond(ctx -> Future.succeededFuture("OK"));

        router.post("/api/auth/login").handler(ctx ->
            handleJson(ctx, authApiHandler.login(ctx.body().asJsonObject())));
        router.get("/api/auth/me")
            .handler(JwtAuthHandler.authenticated())
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
            .handler(JwtAuthHandler.authenticated())
            .handler(ctx ->
            handleJson(ctx, dashboardApiHandler.getDashboard()));

        router.get("/api/workflows")
            .handler(JwtAuthHandler.authenticated())
            .handler(ctx ->
            handleJson(ctx, workflowApiHandler.searchWorkflows(
                ctx.request().getParam("phoneNumber"),
                ctx.request().getParam("state"),
                Integer.parseInt(ctx.request().getParam("page", "0")),
                Integer.parseInt(ctx.request().getParam("size", "20")))));
        router.get("/api/workflows/:workflowUuid")
            .handler(JwtAuthHandler.authenticated())
            .handler(ctx ->
            handleJson(ctx, workflowApiHandler.getWorkflow(ctx.pathParam("workflowUuid"))));
        router.get("/api/workflows/:workflowUuid/events")
            .handler(JwtAuthHandler.authenticated())
            .handler(ctx ->
            handleJson(ctx, workflowApiHandler.getWorkflowEvents(ctx.pathParam("workflowUuid"))));
        router.post("/api/workflows/:workflowUuid/manual-transition")
            .handler(JwtAuthHandler.requireAny("ADMIN", "MANAGER"))
            .handler(ctx -> {
                JsonObject body = ctx.body().asJsonObject();
                handleEmpty(ctx, workflowApiHandler.manualTransition(
                    ctx.pathParam("workflowUuid"),
                    body.getString("toState"),
                    body.getString("reason"),
                    ctx.get("actorId")));
            });

        router.get("/api/messages")
            .handler(JwtAuthHandler.authenticated())
            .handler(ctx ->
            handleJson(ctx, messageApiHandler.searchMessages(
                ctx.request().getParam("requestId"),
                ctx.request().getParam("messageType"),
                ctx.request().getParam("direction"),
                ctx.request().getParam("technicalStatus"),
                ctx.request().getParam("dateFrom"),
                ctx.request().getParam("dateTo"),
                Integer.parseInt(ctx.request().getParam("page", "0")),
                Integer.parseInt(ctx.request().getParam("size", "20")))));
        router.get("/api/messages/:messageUuid/pair")
            .handler(JwtAuthHandler.authenticated())
            .handler(ctx ->
            handleJson(ctx, messageApiHandler.getMessagePair(ctx.pathParam("messageUuid"))));
        router.get("/api/messages/:messageUuid")
            .handler(JwtAuthHandler.authenticated())
            .handler(ctx ->
            handleJson(ctx, messageApiHandler.getMessage(ctx.pathParam("messageUuid"))));
        router.get("/api/messages/:messageUuid/xml")
            .handler(JwtAuthHandler.authenticated())
            .handler(ctx -> {
            String remoteIp = ctx.request().remoteAddress() != null ? ctx.request().remoteAddress().host() : null;
            messageApiHandler.getMessageXml(ctx.pathParam("messageUuid"), ctx.get("actorId"), remoteIp)
                .onSuccess(xml -> ctx.response()
                    .putHeader("Content-Type", "application/xml")
                    .end(xml))
                .onFailure(err -> error(ctx, err));
        });

        router.get("/api/operators")
            .handler(JwtAuthHandler.authenticated())
            .handler(ctx ->
            handleJson(ctx, operatorApiHandler.listOperators()));
        router.post("/api/operators")
            .handler(JwtAuthHandler.requireAny("ADMIN", "MANAGER"))
            .handler(ctx -> {
                String remoteIp = ctx.request().remoteAddress() != null ? ctx.request().remoteAddress().host() : null;
                handleJson(ctx, operatorApiHandler.createOperator(ctx.body().asJsonObject(), ctx.get("actorId"), remoteIp));
            });
        router.put("/api/operators/:operatorCode")
            .handler(JwtAuthHandler.authenticated())
            .handler(ctx -> updateOperatorWithLocalConfigGate(ctx));
        router.get("/api/operators/:operatorCode")
            .handler(JwtAuthHandler.authenticated())
            .handler(ctx ->
            handleJson(ctx, operatorApiHandler.getOperator(ctx.pathParam("operatorCode"))));
        router.post("/api/operators/:operatorCode/mark-down")
            .handler(JwtAuthHandler.requireAny("ADMIN", "MANAGER"))
            .handler(ctx -> {
                JsonObject body = ctx.body().asJsonObject();
                String reason = body != null ? body.getString("reason", "manual") : "manual";
                handleEmpty(ctx, operatorApiHandler.markDown(ctx.pathParam("operatorCode"), reason));
            });

        router.get("/api/operators/:operatorCode/endpoints")
            .handler(JwtAuthHandler.authenticated())
            .handler(ctx ->
            handleJson(ctx, operatorEndpointApiHandler.listEndpoints(ctx.pathParam("operatorCode"))));
        router.post("/api/operators/:operatorCode/endpoints")
            .handler(JwtAuthHandler.requireAny("ADMIN"))
            .handler(ctx -> handleJson(ctx, operatorEndpointApiHandler.createEndpoint(
                ctx.pathParam("operatorCode"), ctx.body().asJsonObject())));
        router.delete("/api/operators/:operatorCode/endpoints/:endpointId")
            .handler(JwtAuthHandler.requireAny("ADMIN"))
            .handler(ctx -> handleEmpty(ctx, operatorEndpointApiHandler.deleteEndpoint(
                ctx.pathParam("operatorCode"), Long.parseLong(ctx.pathParam("endpointId")))));

        router.get("/api/manual-tasks")
            .handler(JwtAuthHandler.authenticated())
            .handler(ctx ->
            handleJson(ctx, manualTaskApiHandler.listTasks(ctx.request().getParam("status"))));
        router.post("/api/manual-tasks/:taskUuid/resolve")
            .handler(JwtAuthHandler.requireAny("ADMIN", "MANAGER"))
            .handler(ctx -> {
                JsonObject body = ctx.body().asJsonObject();
                handleEmpty(ctx, manualTaskApiHandler.resolveTask(
                    ctx.pathParam("taskUuid"),
                    body != null ? body.getString("resolution") : null));
            });

        router.get("/api/users")
            .handler(JwtAuthHandler.requireAny("ADMIN"))
            .handler(ctx -> handleJson(ctx, userManagementApiHandler.listUsers()));
        router.post("/api/users")
            .handler(JwtAuthHandler.requireAny("ADMIN"))
            .handler(ctx -> handleJson(ctx, userManagementApiHandler.createUser(
                ctx.body().asJsonObject(), ctx.get("actorId"), remoteIp(ctx))));
        router.put("/api/users/:username")
            .handler(JwtAuthHandler.requireAny("ADMIN"))
            .handler(ctx -> handleJson(ctx, userManagementApiHandler.updateUser(
                ctx.pathParam("username"), ctx.body().asJsonObject(), ctx.get("actorId"), remoteIp(ctx))));

        router.get("/api/settings/local")
            .handler(JwtAuthHandler.requireAny("ADMIN"))
            .handler(ctx -> ctx.response()
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("publicBaseUrl",
                    System.getenv().getOrDefault("APP_PUBLIC_BASE_URL", "http://localhost:8080")).encode()));

        router.get("/api/numbers")
            .handler(JwtAuthHandler.authenticated())
            .handler(ctx ->
            handleJson(ctx, numberApiHandler.searchByPrefix(
                ctx.request().getParam("prefix"),
                Integer.parseInt(ctx.request().getParam("size", "50")))));
        router.get("/api/numbers/:phoneNumber")
            .handler(JwtAuthHandler.authenticated())
            .handler(ctx ->
            handleJson(ctx, numberApiHandler.getNumber(ctx.pathParam("phoneNumber"))));
        router.get("/api/numbers/:phoneNumber/history")
            .handler(JwtAuthHandler.authenticated())
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
        JsonObject body = ctx.body().asJsonObject();
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
                String remoteIp = ctx.request().remoteAddress() != null ? ctx.request().remoteAddress().host() : null;
                return operatorApiHandler.updateOperator(operatorCode, body, user.username(), remoteIp);
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

    private void error(RoutingContext ctx, Throwable err) {
        int status = err instanceof ApiError apiError ? apiError.status : 500;
        ctx.response().setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(new JsonObject().put("error", err.getMessage()).encode());
    }
}
