package cloud.cloudonix.npg.outbound;

import cloud.cloudonix.npg.xml.SecureXmlParser;
import cloud.cloudonix.npg.xml.XmlParseResult;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/**
 * Interprets a peer's synchronous reply to an outbound message as a technical ACK/NACK.
 *
 * <p>The exact ACK serialisation is unconfirmed (docs/protocol/open-questions.md), so both shapes
 * seen so far are understood: our own {@code <AckNack><Status>..</Status>} and the trace's
 * {@code ACK_CODE} ({@code Ack00} = accepted). Then:
 * <ul>
 *   <li>NACK_DUPLICATE_MESSAGE (or HTTP 409) means the peer already holds this exact message — it
 *       was delivered, so it counts as an ACK. This is what makes re-sends after an unknown
 *       delivery safe.</li>
 *   <li>Other NACKs and remaining 4xx are technical rejections (not retried).</li>
 *   <li>5xx, 408 and 429 are transient: retry.</li>
 * </ul>
 */
public final class PeerAckParser {

    static final String DUPLICATE = "NACK_DUPLICATE_MESSAGE";

    private final SecureXmlParser xmlParser = new SecureXmlParser(64 * 1024, 50);

    public OutboundResult interpret(int httpStatus, String body) {
        if (httpStatus >= 500 || httpStatus == 408 || httpStatus == 429) {
            return OutboundResult.transportError("HTTP_" + httpStatus, "Peer returned HTTP " + httpStatus);
        }
        Document doc = parse(body);
        String status = text(doc, "Status");
        String nackCode = firstNonBlank(text(doc, "Code"), text(doc, "NACK_CODE"), text(doc, "nackCode"));
        String ackCode = firstNonBlank(text(doc, "ACK_CODE"), text(doc, "ackCode"));

        if (DUPLICATE.equals(nackCode) || httpStatus == 409) {
            return OutboundResult.ack();
        }
        if ("NACK".equalsIgnoreCase(status)) {
            return OutboundResult.nack(nackCode != null ? nackCode : "NACK", text(doc, "Message"));
        }
        if (ackCode != null && !ackCode.startsWith("Ack00")) {
            return OutboundResult.nack(ackCode, "Peer ACK_CODE " + ackCode);
        }
        if (httpStatus >= 200 && httpStatus < 300) {
            return OutboundResult.ack();
        }
        return OutboundResult.nack(nackCode != null ? nackCode : "HTTP_" + httpStatus,
            "Peer rejected the message with HTTP " + httpStatus);
    }

    private Document parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        XmlParseResult result = xmlParser.parse(body);
        return result.success() ? result.document() : null;
    }

    private static String text(Document doc, String element) {
        if (doc == null) {
            return null;
        }
        NodeList nodes = doc.getElementsByTagName(element);
        if (nodes.getLength() == 0) {
            return null;
        }
        String value = nodes.item(0).getTextContent();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
