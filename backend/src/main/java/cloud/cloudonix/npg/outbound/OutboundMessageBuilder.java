package cloud.cloudonix.npg.outbound;

import io.vertx.core.json.JsonObject;

public class OutboundMessageBuilder {

    public String build(String messageType, JsonObject header, JsonObject body) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<").append(messageType).append(">\n");
        sb.append("  <Header>\n");
        if (header != null) {
            header.forEach(entry -> sb.append("    <")
                .append(entry.getKey())
                .append(">")
                .append(escapeXml(String.valueOf(entry.getValue())))
                .append("</")
                .append(entry.getKey())
                .append(">\n"));
        }
        sb.append("  </Header>\n");
        sb.append("  <Body>\n");
        if (body != null) {
            body.forEach(entry -> sb.append("    <")
                .append(entry.getKey())
                .append(">")
                .append(escapeXml(String.valueOf(entry.getValue())))
                .append("</")
                .append(entry.getKey())
                .append(">\n"));
        }
        sb.append("  </Body>\n");
        sb.append("</").append(messageType).append(">");
        return sb.toString();
    }

    private String escapeXml(String value) {
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }
}
