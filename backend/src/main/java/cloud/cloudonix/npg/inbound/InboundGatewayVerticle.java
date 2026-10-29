package cloud.cloudonix.npg.inbound;

import cloud.cloudonix.npg.audit.XmlTransportMetadata;
import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.config.MongoConfig;
import cloud.cloudonix.npg.protocol.ProtocolCatalogLoader;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.sqlclient.Pool;

public class InboundGatewayVerticle extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(InboundGatewayVerticle.class);

    private HttpServer server;

    @Override
    public void start(Promise<Void> startPromise) {
        String catalogPath = config().getString("catalogPath", "docs/protocol");

        Future<Pool> poolFuture = DatabaseConfig.configure(vertx);
        Future<MongoClient> mongoFuture = MongoConfig.configure(vertx);

        Future.all(poolFuture, mongoFuture)
            .compose(result -> {
                Pool pool = poolFuture.result();
                MongoClient mongoClient = mongoFuture.result();
                cloud.cloudonix.npg.audit.XmlAuditService xmlAuditService =
                    new cloud.cloudonix.npg.audit.XmlAuditService(mongoClient);
                ProtocolCatalogLoader catalogLoader = new ProtocolCatalogLoader(vertx, catalogPath);
                InboundGatewayService service = new InboundGatewayService(pool, xmlAuditService, catalogLoader);

                Router router = Router.router(vertx);
                router.route().handler(BodyHandler.create().setBodyLimit(1024 * 1024));
                router.post("/np/inbound").handler(ctx -> handleInbound(ctx, service));
                router.get("/health/live").handler(ctx -> ctx.response().setStatusCode(200).end("OK"));
                router.get("/health/ready").handler(ctx -> ctx.response().setStatusCode(200).end("OK"));
                router.get("/metrics").handler(ctx ->
                    ctx.response().setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonObject().put("inbound_messages_total", 0).encode()));

                return vertx.createHttpServer()
                    .requestHandler(router)
                    .listen(config().getInteger("port", 8080));
            })
            .onSuccess(httpServer -> {
                this.server = httpServer;
                log.info("Inbound gateway listening on port " + httpServer.actualPort());
                startPromise.complete();
            })
            .onFailure(startPromise::fail);
    }

    private void handleInbound(RoutingContext ctx, InboundGatewayService service) {
        String rawXml = ctx.body().asString();
        String remoteIp = ctx.request().remoteAddress() != null ? ctx.request().remoteAddress().host() : null;
        XmlTransportMetadata transport = new XmlTransportMetadata("HTTPS", remoteIp, null, new JsonObject());

        // Real trace delivers NP header fields out-of-band as X-NP-* HTTP headers.
        java.util.Map<String, String> transportHeaders = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, String> h : ctx.request().headers()) {
            if (h.getKey().toLowerCase(java.util.Locale.ROOT).startsWith("x-np-")) {
                transportHeaders.put(h.getKey().toLowerCase(java.util.Locale.ROOT), h.getValue());
            }
        }

        service.receive(rawXml, transport, transportHeaders)
            .onComplete(result -> {
                if (result.succeeded()) {
                    ctx.response()
                        .setStatusCode(result.result().status().name().equals("ACK") ? 200 : 400)
                        .putHeader("Content-Type", "application/xml")
                        .end(result.result().toXml());
                } else {
                    log.error("Inbound handling failed", result.cause());
                    ctx.response()
                        .setStatusCode(500)
                        .end("<AckNack><Status>NACK</Status><Code>NACK_INTERNAL_ERROR</Code></AckNack>");
                }
            });
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        if (server != null) {
            server.close(stopPromise);
        } else {
            stopPromise.complete();
        }
    }
}
