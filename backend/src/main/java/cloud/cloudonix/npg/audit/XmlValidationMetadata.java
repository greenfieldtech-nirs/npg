package cloud.cloudonix.npg.audit;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public record XmlValidationMetadata(String status, JsonArray errors) {

    public JsonObject toJson() {
        return new JsonObject()
            .put("status", status)
            .put("errors", errors != null ? errors : new JsonArray());
    }

    public static XmlValidationMetadata valid() {
        return new XmlValidationMetadata("VALID", new JsonArray());
    }

    public static XmlValidationMetadata invalid(JsonArray errors) {
        return new XmlValidationMetadata("INVALID", errors);
    }
}
