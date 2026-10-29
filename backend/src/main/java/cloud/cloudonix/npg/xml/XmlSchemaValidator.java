package cloud.cloudonix.npg.xml;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.w3c.dom.Document;

public class XmlSchemaValidator {

    public JsonObject validate(Document document, String schemaPath) {
        // Placeholder until official XSD is available.
        // Return VALID if document is non-null, otherwise INVALID.
        if (document == null) {
            return new JsonObject()
                .put("valid", false)
                .put("errors", new JsonArray().add("Document is null"));
        }
        return new JsonObject()
            .put("valid", true)
            .put("errors", new JsonArray());
    }
}
