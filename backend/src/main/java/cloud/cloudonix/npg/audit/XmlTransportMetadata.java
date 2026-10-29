package cloud.cloudonix.npg.audit;

import io.vertx.core.json.JsonObject;

public record XmlTransportMetadata(String protocol, String remoteIp, String tlsSubject, JsonObject headers) {

    public JsonObject toJson() {
        return new JsonObject()
            .put("protocol", protocol)
            .put("remoteIp", remoteIp)
            .put("tlsSubject", tlsSubject)
            .put("headers", headers != null ? headers : new JsonObject());
    }
}
