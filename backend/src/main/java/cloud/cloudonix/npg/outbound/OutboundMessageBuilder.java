package cloud.cloudonix.npg.outbound;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Serialises an outbound protocol body in the observed wire format:
 * {@code <npMessageBody><MessageType>...</MessageType></npMessageBody>}. Header fields travel
 * separately as X-NP-* HTTP headers (see {@link cloud.cloudonix.npg.protocol.ProtocolHeader}).
 *
 * <p>The body is a JSON tree: objects become nested elements (insertion order is element order),
 * arrays repeat their element, nulls are omitted, everything else is escaped text. Element names
 * must be plain XML names so a JSON key can never inject markup.
 */
public class OutboundMessageBuilder {

    private static final Pattern XML_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

    public String build(String messageType, JsonObject body) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<npMessageBody>");
        element(sb, messageType, body != null ? body : new JsonObject());
        return sb.append("</npMessageBody>").toString();
    }

    private void element(StringBuilder sb, String name, Object value) {
        if (value == null) {
            return;
        }
        if (!XML_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a valid XML element name: " + name);
        }
        if (value instanceof JsonArray array) {
            for (Object item : array) {
                element(sb, name, item);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            value = new JsonObject(castMap(map));
        }
        if (value instanceof JsonObject object) {
            if (object.isEmpty()) {
                sb.append('<').append(name).append("/>");
                return;
            }
            sb.append('<').append(name).append('>');
            object.forEach(entry -> element(sb, entry.getKey(), entry.getValue()));
            sb.append("</").append(name).append('>');
            return;
        }
        sb.append('<').append(name).append('>').append(escape(String.valueOf(value))).append("</").append(name).append('>');
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    static String escape(String value) {
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }
}
