package cloud.cloudonix.npg;

import cloud.cloudonix.npg.api.ManagementApiVerticle;
import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.config.MigrationRunner;
import cloud.cloudonix.npg.config.MongoConfig;
import cloud.cloudonix.npg.inbound.InboundGatewayVerticle;
import cloud.cloudonix.npg.outbound.OutboundDispatcherVerticle;
import cloud.cloudonix.npg.timer.TimerWorkerVerticle;
import cloud.cloudonix.npg.worker.InboxWorkerVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.sqlclient.Pool;

public class Application {

    private static final Logger log = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        try {
            MigrationRunner.migrate();
        } catch (Exception e) {
            log.error("Database migration failed, aborting startup", e);
            System.exit(1);
        }
        Vertx vertx = Vertx.vertx();
        String role = System.getenv().getOrDefault("APP_ROLE", "all");
        log.info("Starting NPG backend with role: " + role);

        Future<Pool> poolFuture = DatabaseConfig.configure(vertx);
        Future<MongoClient> mongoFuture = MongoConfig.configure(vertx);

        Future.all(poolFuture, mongoFuture)
            .compose(result -> cloud.cloudonix.npg.config.AuthSeeder.ensureSeeded(poolFuture.result()).map(result))
            .compose(result -> {
                Pool pool = poolFuture.result();
                MongoClient mongoClient = mongoFuture.result();
                cloud.cloudonix.npg.audit.XmlAuditService xmlAuditService =
                    new cloud.cloudonix.npg.audit.XmlAuditService(mongoClient);
                return xmlAuditService.ensureIndexes().map(v -> mongoClient);
            })
            .compose(client -> {
                if ("api".equals(role) || "all".equals(role)) {
                    DeploymentOptions options = new DeploymentOptions()
                        .setConfig(new JsonObject().put("port", Integer.parseInt(System.getenv().getOrDefault("API_PORT", "8080"))));
                    return vertx.deployVerticle(ManagementApiVerticle.class, options).map(client);
                }
                return Future.succeededFuture(client);
            })
            .compose(client -> {
                if ("inbound".equals(role) || "all".equals(role)) {
                    DeploymentOptions options = new DeploymentOptions()
                        .setConfig(new JsonObject()
                            .put("port", Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")))
                            .put("catalogPath", System.getenv().getOrDefault("CATALOG_PATH", "docs/protocol")));
                    return vertx.deployVerticle(InboundGatewayVerticle.class, options).map(client);
                }
                return Future.succeededFuture(client);
            })
            .compose(client -> {
                if ("worker".equals(role) || "all".equals(role)) {
                    return vertx.deployVerticle(new InboxWorkerVerticle()).map(client);
                }
                return Future.succeededFuture(client);
            })
            .compose(client -> {
                if ("timer".equals(role) || "all".equals(role)) {
                    return vertx.deployVerticle(new TimerWorkerVerticle()).map(client);
                }
                return Future.succeededFuture(client);
            })
            .compose(client -> {
                if ("dispatcher".equals(role) || "all".equals(role)) {
                    return vertx.deployVerticle(new OutboundDispatcherVerticle()).map(client);
                }
                return Future.succeededFuture(client);
            })
            .onSuccess(v -> log.info("Application started in role: " + role))
            .onFailure(err -> {
                log.error("Failed to start application", err);
                System.exit(1);
            });
    }
}
