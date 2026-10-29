package cloud.cloudonix.npg.protocol;

import io.vertx.core.json.JsonObject;

public record ProtocolBody(JsonObject fields) {

    public ProtocolBody {
        fields = fields != null ? fields : new JsonObject();
    }

    public String phoneNumber() {
        return fields.getString("phoneNumber");
    }

    public String responseCode() {
        return fields.getString("responseCode");
    }

    public JsonObject toJson() {
        return fields.copy();
    }
}
