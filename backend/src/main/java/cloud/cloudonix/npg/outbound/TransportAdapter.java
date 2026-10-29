package cloud.cloudonix.npg.outbound;

import io.vertx.core.Future;

public interface TransportAdapter {
    Future<OutboundResult> send(String endpointUrl, String rawXml);
}
