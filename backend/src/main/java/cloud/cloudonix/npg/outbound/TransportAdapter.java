package cloud.cloudonix.npg.outbound;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

public interface TransportAdapter {

    /**
     * Delivers one message. Never fails the future for delivery problems — they are reported in the
     * {@link OutboundResult} so the dispatcher can decide between retry and escalation.
     *
     * @param headers protocol header fields sent as HTTP headers (X-NP-*)
     */
    Future<OutboundResult> send(String endpointUrl, String rawXml, JsonObject headers);
}
