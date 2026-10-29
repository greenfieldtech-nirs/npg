package cloud.cloudonix.npg.outbound;

import cloud.cloudonix.npg.config.Env;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.PfxOptions;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

/**
 * HTTPS raw-XML transport (the default adapter; SOAP would be another implementation).
 *
 * <p>Outbound mTLS: set OUTBOUND_TLS_KEYSTORE (PKCS#12 client certificate) and
 * OUTBOUND_TLS_KEYSTORE_PASSWORD; OUTBOUND_TLS_TRUSTSTORE / _PASSWORD replace the JVM trust store
 * when peers use a private CA.
 */
public class HttpXmlTransportAdapter implements TransportAdapter {

    private final WebClient webClient;
    private final PeerAckParser ackParser = new PeerAckParser();

    public HttpXmlTransportAdapter(Vertx vertx) {
        WebClientOptions options = new WebClientOptions()
            .setConnectTimeout(Env.getInt("OUTBOUND_CONNECT_TIMEOUT_MS", 5000))
            .setIdleTimeout(Env.getInt("OUTBOUND_IDLE_TIMEOUT_SECONDS", 30))
            .setMaxPoolSize(Env.getInt("OUTBOUND_MAX_CONNECTIONS", 20));
        String keystore = Env.get("OUTBOUND_TLS_KEYSTORE");
        if (keystore != null) {
            options.setKeyCertOptions(new PfxOptions().setPath(keystore)
                .setPassword(Env.get("OUTBOUND_TLS_KEYSTORE_PASSWORD", "")));
        }
        String truststore = Env.get("OUTBOUND_TLS_TRUSTSTORE");
        if (truststore != null) {
            options.setTrustOptions(new PfxOptions().setPath(truststore)
                .setPassword(Env.get("OUTBOUND_TLS_TRUSTSTORE_PASSWORD", "")));
        }
        this.webClient = WebClient.create(vertx, options);
    }

    @Override
    public Future<OutboundResult> send(String endpointUrl, String rawXml, JsonObject headers) {
        HttpRequest<Buffer> request = webClient.postAbs(endpointUrl)
            .timeout(Env.getInt("OUTBOUND_REQUEST_TIMEOUT_MS", 15000))
            .putHeader("Content-Type", "application/xml");
        if (headers != null) {
            headers.forEach(h -> {
                if (h.getValue() != null) {
                    request.putHeader(h.getKey(), String.valueOf(h.getValue()));
                }
            });
        }
        return request.sendBuffer(Buffer.buffer(rawXml))
            .map(response -> ackParser.interpret(response.statusCode(), response.bodyAsString()))
            .recover(err -> Future.succeededFuture(
                OutboundResult.transportError("TRANSPORT_ERROR", String.valueOf(err.getMessage()))));
    }
}
