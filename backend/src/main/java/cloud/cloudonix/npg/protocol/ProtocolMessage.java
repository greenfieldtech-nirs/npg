package cloud.cloudonix.npg.protocol;

import io.vertx.core.json.JsonObject;

public record ProtocolMessage(ProtocolHeader header, ProtocolBody body, String rawXml) {

    public JsonObject toJson() {
        return new JsonObject()
            .put("header", header.toJson())
            .put("body", body.toJson());
    }
}
