package cloud.cloudonix.npg.xml;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;

public class SecureXmlParser {

    public static final long DEFAULT_MAX_SIZE_BYTES = 1 * 1024 * 1024; // 1 MB
    public static final int DEFAULT_MAX_DEPTH = 1000;

    private final long maxSizeBytes;
    private final int maxDepth;

    public SecureXmlParser() {
        this(DEFAULT_MAX_SIZE_BYTES, DEFAULT_MAX_DEPTH);
    }

    public SecureXmlParser(long maxSizeBytes, int maxDepth) {
        this.maxSizeBytes = maxSizeBytes;
        this.maxDepth = maxDepth;
    }

    public XmlParseResult parse(String rawXml) {
        if (rawXml == null) {
            return XmlParseResult.fail(null, "XML body is null");
        }
        if (rawXml.getBytes().length > maxSizeBytes) {
            return XmlParseResult.fail(rawXml, "XML exceeds maximum size of " + maxSizeBytes + " bytes");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            InputSource source = new InputSource(new StringReader(rawXml));
            Document document = builder.parse(source);
            int depth = computeDepth(document.getDocumentElement(), 0);
            if (depth > maxDepth) {
                return XmlParseResult.fail(rawXml, "XML exceeds maximum depth of " + maxDepth);
            }
            return XmlParseResult.ok(document, rawXml);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            return XmlParseResult.fail(rawXml, "XML parse error: " + e.getMessage());
        }
    }

    private int computeDepth(org.w3c.dom.Node node, int currentDepth) {
        if (node == null) return currentDepth;
        int maxChildDepth = currentDepth;
        org.w3c.dom.Node child = node.getFirstChild();
        while (child != null) {
            if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                int childDepth = computeDepth(child, currentDepth + 1);
                if (childDepth > maxChildDepth) {
                    maxChildDepth = childDepth;
                }
            }
            child = child.getNextSibling();
        }
        return maxChildDepth;
    }
}
