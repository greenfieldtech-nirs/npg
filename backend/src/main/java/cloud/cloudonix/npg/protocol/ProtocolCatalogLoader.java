package cloud.cloudonix.npg.protocol;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ProtocolCatalogLoader {

    private final Vertx vertx;
    private final String basePath;

    public ProtocolCatalogLoader(Vertx vertx, String basePath) {
        this.vertx = vertx;
        this.basePath = basePath;
    }

    public JsonObject loadMessageCatalog() {
        return loadYaml("message-catalog.yml");
    }

    public JsonObject loadTimerMatrix() {
        return loadYaml("timer-matrix.yml");
    }

    public JsonObject loadRejectionCodes() {
        return loadYaml("rejection-codes.yml");
    }

    public JsonObject loadFieldDictionary() {
        return loadYaml("field-dictionary.yml");
    }

    @SuppressWarnings("unchecked")
    private JsonObject loadYaml(String fileName) {
        try {
            Buffer buffer = vertx.fileSystem().readFileBlocking(basePath + "/" + fileName);
            Yaml yaml = new Yaml();
            Map<String, Object> loaded = yaml.load(buffer.toString());
            return loaded == null ? new JsonObject() : new JsonObject(loaded);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load " + fileName, e);
        }
    }

    public Optional<JsonObject> findMessage(String messageType) {
        JsonArray messages = loadMessageCatalog().getJsonArray("messages", new JsonArray());
        for (Object o : messages) {
            JsonObject msg = (JsonObject) o;
            if (messageType.equals(msg.getString("name"))) {
                return Optional.of(msg);
            }
        }
        return Optional.empty();
    }

    public List<String> knownMessageTypes() {
        JsonArray messages = loadMessageCatalog().getJsonArray("messages", new JsonArray());
        return messages.stream()
            .map(o -> ((JsonObject) o).getString("name"))
            .toList();
    }

    public ProtocolHeader extractHeader(org.w3c.dom.Document document) {
        return extractHeader(document, Map.of());
    }

    public ProtocolHeader extractHeader(org.w3c.dom.Document document, Map<String, String> transportHeaders) {
        org.w3c.dom.Element root = document.getDocumentElement();
        String rootName = localName(root);
        if ("npMessageBody".equalsIgnoreCase(rootName)) {
            // Real trace format: header fields arrive out-of-band (X-NP-* transport headers).
            org.w3c.dom.Element msgElement = firstElementChild(root);
            return new ProtocolHeader(
                transportHeader(transportHeaders, "x-np-from"),
                transportHeader(transportHeaders, "x-np-to"),
                msgElement != null ? localName(msgElement) : null,
                transportHeader(transportHeaders, "x-np-request-id"),
                transportHeader(transportHeaders, "x-np-trx-no"),
                correlationId(document, transportHeaders),
                null,
                transportHeader(transportHeaders, "x-np-version-no")
            );
        }
        if ("npMessage".equalsIgnoreCase(rootName)) {
            // Envelope format: header child element plus npMessageBody.
            org.w3c.dom.Element headerEl = findChildIgnoreCase(root, "npMessageHeader", "header");
            org.w3c.dom.Element bodyEl = findChildIgnoreCase(root, "npMessageBody");
            String messageType = headerEl != null ? childTextAny(headerEl,
                "MSG_TYPE", "msgType", "messageType") : null;
            if (messageType == null && bodyEl != null) {
                org.w3c.dom.Element msgElement = firstElementChild(bodyEl);
                messageType = msgElement != null ? localName(msgElement) : null;
            }
            return new ProtocolHeader(
                headerEl != null ? childTextAny(headerEl, "FROM", "from", "senderOperator") : null,
                headerEl != null ? childTextAny(headerEl, "TO", "to", "receiverOperator") : null,
                messageType,
                headerEl != null ? childTextAny(headerEl, "REQUEST_ID", "requestId") : null,
                headerEl != null ? childTextAny(headerEl, "TRX_NO", "trxNo", "transactionId") : null,
                correlationId(document, transportHeaders),
                null,
                headerEl != null ? childTextAny(headerEl, "VERSION_NO", "versionNo", "protocolVersion") : null
            );
        }
        // Legacy flat format.
        return new ProtocolHeader(
            textContent(document, "senderOperator"),
            textContent(document, "receiverOperator"),
            textContent(document, "messageType"),
            textContent(document, "requestId"),
            textContent(document, "transactionId"),
            textContent(document, "correlationId"),
            textContent(document, "timestamp"),
            textContent(document, "protocolVersion")
        );
    }

    public ProtocolBody extractBody(org.w3c.dom.Document document, String messageType) {
        return extractBody(document, messageType, Map.of());
    }

    public ProtocolBody extractBody(org.w3c.dom.Document document, String messageType,
                                    Map<String, String> transportHeaders) {
        org.w3c.dom.Element root = document.getDocumentElement();
        String rootName = localName(root);
        if ("npMessageBody".equalsIgnoreCase(rootName) || "npMessage".equalsIgnoreCase(rootName)) {
            // Real trace / envelope format: flatten all leaf elements under the message-type element.
            org.w3c.dom.Element bodyRoot = "npMessage".equalsIgnoreCase(rootName)
                ? findChildIgnoreCase(root, "npMessageBody") : root;
            JsonObject fields = new JsonObject();
            if (bodyRoot != null) {
                org.w3c.dom.Element msgElement = firstElementChild(bodyRoot);
                if (msgElement != null) {
                    flattenLeaves(msgElement, fields);
                }
            }
            // Aliases: <number> is the ported phone number leaf in fixed/mobile structures.
            if (fields.getString("phoneNumber") == null && fields.getString("number") != null) {
                fields.put("phoneNumber", fields.getString("number"));
            }
            return new ProtocolBody(fields);
        }
        // Legacy flat format.
        JsonObject fields = new JsonObject();
        org.w3c.dom.Node child = root.getFirstChild();
        while (child != null) {
            if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE
                && !isHeaderElement(child.getNodeName())) {
                fields.put(child.getNodeName(), child.getTextContent());
            }
            child = child.getNextSibling();
        }
        return new ProtocolBody(fields);
    }

    private void flattenLeaves(org.w3c.dom.Element element, JsonObject fields) {
        org.w3c.dom.Element childElement = firstElementChild(element);
        if (childElement == null) {
            fields.put(localName(element), element.getTextContent());
            return;
        }
        org.w3c.dom.Node child = element.getFirstChild();
        while (child != null) {
            if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                flattenLeaves((org.w3c.dom.Element) child, fields);
            }
            child = child.getNextSibling();
        }
    }

    private String correlationId(org.w3c.dom.Document document, Map<String, String> transportHeaders) {
        // Responses correlate to the original request via <requestTrxNo> in the body.
        org.w3c.dom.NodeList list = document.getElementsByTagName("requestTrxNo");
        if (list.getLength() > 0 && !list.item(0).getTextContent().isBlank()) {
            return list.item(0).getTextContent();
        }
        return transportHeader(transportHeaders, "x-np-request-trx-no");
    }

    private String transportHeader(Map<String, String> transportHeaders, String name) {
        if (transportHeaders == null) return null;
        for (Map.Entry<String, String> e : transportHeaders.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private org.w3c.dom.Element firstElementChild(org.w3c.dom.Element element) {
        org.w3c.dom.Node child = element.getFirstChild();
        while (child != null) {
            if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                return (org.w3c.dom.Element) child;
            }
            child = child.getNextSibling();
        }
        return null;
    }

    private org.w3c.dom.Element findChildIgnoreCase(org.w3c.dom.Element parent, String... names) {
        org.w3c.dom.Node child = parent.getFirstChild();
        while (child != null) {
            if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                for (String name : names) {
                    if (localName(child).equalsIgnoreCase(name)) {
                        return (org.w3c.dom.Element) child;
                    }
                }
            }
            child = child.getNextSibling();
        }
        return null;
    }

    private String childTextAny(org.w3c.dom.Element parent, String... names) {
        org.w3c.dom.Element child = findChildIgnoreCase(parent, names);
        return child != null ? child.getTextContent() : null;
    }

    private String localName(org.w3c.dom.Node node) {
        String local = node.getLocalName();
        return local != null ? local : node.getNodeName();
    }

    private boolean isHeaderElement(String name) {
        return List.of("senderOperator", "receiverOperator", "messageType", "requestId",
            "transactionId", "correlationId", "timestamp", "protocolVersion").contains(name);
    }

    private String textContent(org.w3c.dom.Document document, String elementName) {
        org.w3c.dom.NodeList list = document.getElementsByTagName(elementName);
        if (list.getLength() == 0) return null;
        return list.item(0).getTextContent();
    }
}
