package cloud.cloudonix.npg.xml;

import org.w3c.dom.Document;

public record XmlParseResult(Document document, String rawXml, boolean success, String errorMessage) {

    public static XmlParseResult ok(Document document, String rawXml) {
        return new XmlParseResult(document, rawXml, true, null);
    }

    public static XmlParseResult fail(String rawXml, String errorMessage) {
        return new XmlParseResult(null, rawXml, false, errorMessage);
    }
}
