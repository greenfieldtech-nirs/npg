package cloud.cloudonix.npg.outbound;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

public class HttpXmlTransportAdapter implements TransportAdapter {

    private final WebClient webClient;

    public HttpXmlTransportAdapter(Vertx vertx) {
        WebClientOptions options = new WebClientOptions()
            .setConnectTimeout(5000)
            .setIdleTimeout(10);
        this.webClient = WebClient.create(vertx, options);
    }

    @Override
    public Future<OutboundResult> send(String endpointUrl, String rawXml) {
        return webClient.postAbs(endpointUrl)
            .putHeader("Content-Type", "application/xml")
            .sendBuffer(Buffer.buffer(rawXml))
            .map(response -> {
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    String body = response.bodyAsString();
                    if (body != null && body.contains("<Status>NACK</Status>")) {
                        return OutboundResult.nack("NACK_RECEIVED", "Remote operator returned NACK");
                    }
                    return OutboundResult.ack();
                }
                return OutboundResult.transportError("HTTP_" + response.statusCode(), "HTTP error " + response.statusCode());
            })
            .recover(err -> Future.succeededFuture(
                OutboundResult.transportError("TRANSPORT_ERROR", String.valueOf(err.getMessage()))));
    }
}
