package cloud.cloudonix.npg.protocol;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The protocol model from docs/protocol (message catalog, timer matrix, rejection codes, field
 * dictionary). Files are read once, at construction, so request handling never touches the disk.
 */
public class ProtocolCatalogLoader {

    private final JsonObject messageCatalog;
    private final JsonObject timerMatrix;
    private final JsonObject rejectionCodes;
    private final JsonObject fieldDictionary;
    private final Map<String, JsonObject> messagesByName;
    private final Map<String, Integer> nackHttpStatus;
    private final java.util.Set<String> responseTypes;

    public ProtocolCatalogLoader(Vertx vertx, String basePath) {
        this.messageCatalog = loadYaml(vertx, basePath, "message-catalog.yml");
        this.timerMatrix = loadYaml(vertx, basePath, "timer-matrix.yml");
        this.rejectionCodes = loadYaml(vertx, basePath, "rejection-codes.yml");
        this.fieldDictionary = loadYaml(vertx, basePath, "field-dictionary.yml");
        Map<String, JsonObject> byName = new java.util.LinkedHashMap<>();
        for (Object o : messageCatalog.getJsonArray("messages", new JsonArray())) {
            JsonObject msg = (JsonObject) o;
            byName.put(msg.getString("name"), msg);
        }
        this.messagesByName = java.util.Collections.unmodifiableMap(byName);
        // a response is whatever another message names as its business_response
        this.responseTypes = byName.values().stream()
            .map(m -> m.getString("business_response"))
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, Integer> statuses = new java.util.HashMap<>();
        for (Object o : rejectionCodes.getJsonArray("technical_rejection_codes", new JsonArray())) {
            JsonObject code = (JsonObject) o;
            if (code.getInteger("http_status") != null) {
                statuses.put(code.getString("code"), code.getInteger("http_status"));
            }
        }
        this.nackHttpStatus = java.util.Collections.unmodifiableMap(statuses);
    }

    public JsonObject loadMessageCatalog() {
        return messageCatalog;
    }

    public JsonObject loadTimerMatrix() {
        return timerMatrix;
    }

    public JsonObject loadRejectionCodes() {
        return rejectionCodes;
    }

    public JsonObject loadFieldDictionary() {
        return fieldDictionary;
    }

    @SuppressWarnings("unchecked")
    private static JsonObject loadYaml(Vertx vertx, String basePath, String fileName) {
        try {
            Buffer buffer = vertx.fileSystem().readFileBlocking(basePath + "/" + fileName);
            Yaml yaml = new Yaml();
            Map<String, Object> loaded = yaml.load(buffer.toString());
            return loaded == null ? new JsonObject() : new JsonObject(loaded);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load " + basePath + "/" + fileName, e);
        }
    }

    public Optional<JsonObject> findMessage(String messageType) {
        return messageType == null ? Optional.empty() : Optional.ofNullable(messagesByName.get(messageType));
    }

    public List<String> knownMessageTypes() {
        return List.copyOf(messagesByName.keySet());
    }

    /** True for messages that answer another message (and must therefore carry requestTrxNo). */
    public boolean isResponse(String messageType) {
        return responseTypes.contains(messageType);
    }

    /** HTTP status for a technical NACK code (rejection-codes.yml), 400 when unspecified. */
    public int httpStatusForNack(String nackCode) {
        return nackHttpStatus.getOrDefault(nackCode, 400);
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
