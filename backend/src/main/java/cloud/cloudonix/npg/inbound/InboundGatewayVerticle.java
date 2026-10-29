package cloud.cloudonix.npg.inbound;

import cloud.cloudonix.npg.acknack.TechnicalAck;
import cloud.cloudonix.npg.acknack.TechnicalAckStatus;
import cloud.cloudonix.npg.audit.XmlTransportMetadata;
import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.config.Env;
import cloud.cloudonix.npg.config.MongoConfig;
import cloud.cloudonix.npg.protocol.ProtocolCatalogLoader;
import cloud.cloudonix.npg.security.ClientAddress;
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
    private final ClientAddress clientAddress = ClientAddress.fromEnvironment();
    private final boolean trustProxyClientCert = Env.getBoolean("INBOUND_TRUST_PROXY_CLIENT_CERT", false);

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
                router.post("/np/inbound").handler(ctx -> handleInbound(ctx, service, catalogLoader));
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

    private void handleInbound(RoutingContext ctx, InboundGatewayService service, ProtocolCatalogLoader catalog) {
        String rawXml = ctx.body().asString();

        // the observed wire format carries the NP header fields out-of-band as X-NP-* HTTP headers;
        // they are part of the message and are stored with the raw XML for audit
        java.util.Map<String, String> transportHeaders = new java.util.HashMap<>();
        JsonObject storedHeaders = new JsonObject();
        for (java.util.Map.Entry<String, String> h : ctx.request().headers()) {
            String name = h.getKey().toLowerCase(java.util.Locale.ROOT);
            if (name.startsWith("x-np-")) {
                transportHeaders.put(name, h.getValue());
                storedHeaders.put(name, h.getValue());
            }
        }
        String scheme = ctx.request().isSSL() ? "HTTPS" : "HTTP";
        XmlTransportMetadata transport = new XmlTransportMetadata(scheme, clientAddress.resolve(ctx.request()),
            verifiedClientSubject(ctx), storedHeaders);

        service.receive(rawXml, transport, transportHeaders)
            .onComplete(result -> {
                if (result.succeeded()) {
                    TechnicalAck ack = result.result();
                    int status = ack.status() == TechnicalAckStatus.ACK ? 200
                        : catalog.httpStatusForNack(ack.nackReason() != null ? ack.nackReason().code() : null);
                    ctx.response()
                        .setStatusCode(status)
                        .putHeader("Content-Type", "application/xml")
                        .end(ack.toXml());
                } else {
                    // not ACKed: nothing was durably accepted, the peer must retry (docs/04)
                    log.error("Inbound handling failed", result.cause());
                    ctx.response()
                        .setStatusCode(500)
                        .putHeader("Content-Type", "application/xml")
                        .end("<AckNack><Status>NACK</Status><NackReason><Code>NACK_INTERNAL_ERROR</Code>"
                            + "<Message>Temporary failure, retry later</Message></NackReason></AckNack>");
                }
            });
    }

    /**
     * Subject DN of the peer's client certificate, as verified by our TLS-terminating NGINX
     * ({@code ssl_verify_client}). Only read when INBOUND_TRUST_PROXY_CLIENT_CERT=true — set it once
     * the proxy verifies client certificates and overwrites these headers on every request (see
     * nginx/*.conf) — and only from a trusted proxy. Otherwise the headers are client-forgeable and
     * certificate-subject credentials can never be satisfied.
     */
    private String verifiedClientSubject(RoutingContext ctx) {
        if (!trustProxyClientCert || !clientAddress.fromTrustedProxy(ctx.request())) {
            return null;
        }
        if (!"SUCCESS".equals(ctx.request().getHeader("X-SSL-Client-Verify"))) {
            return null;
        }
        String subject = ctx.request().getHeader("X-SSL-Client-S-DN");
        return subject == null || subject.isBlank() ? null : subject.trim();
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
