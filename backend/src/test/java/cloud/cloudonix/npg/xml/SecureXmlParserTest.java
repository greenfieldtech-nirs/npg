package cloud.cloudonix.npg.xml;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import static org.junit.jupiter.api.Assertions.*;

class SecureXmlParserTest {

    private final SecureXmlParser parser = new SecureXmlParser();

    @Test
    void validXmlParses() {
        String xml = "\u003cCheck\u003e\u003cPhoneNumber\u003e972501234567\u003c/PhoneNumber\u003e\u003c/Check\u003e";
        XmlParseResult result = parser.parse(xml);
        assertTrue(result.success());
        assertNotNull(result.document());
    }

    @Test
    void malformedXmlFails() {
        String xml = "\u003cCheck\u003e\u003cPhoneNumber\u003e972501234567";
        XmlParseResult result = parser.parse(xml);
        assertFalse(result.success());
        assertNotNull(result.errorMessage());
    }

    @Test
    void xxePayloadIsRejected() {
        String xml = "\u003c?xml version=\"1.0\" encoding=\"UTF-8\"?\u003e\n" +
            "\u003c!DOCTYPE Check [\n" +
            "  \u003c!ENTITY xxe SYSTEM \"file:///etc/passwd\"\u003e\n" +
            "]\u003e\n" +
            "\u003cCheck\u003e\u003cPhoneNumber\u003e\u0026xxe;\u003c/PhoneNumber\u003e\u003c/Check\u003e";
        XmlParseResult result = parser.parse(xml);
        assertFalse(result.success());
    }

    @Test
    void oversizedXmlIsRejected() {
        StringBuilder sb = new StringBuilder();
        sb.append("\u003cCheck\u003e");
        for (int i = 0; i \u003c 200_000; i++) {
            sb.append("\u003cPhoneNumber\u003e972501234567\u003c/PhoneNumber\u003e");
        }
        sb.append("\u003c/Check\u003e");
        SecureXmlParser smallParser = new SecureXmlParser(1024, 1000);
        XmlParseResult result = smallParser.parse(sb.toString());
        assertFalse(result.success());
    }

    @Test
    void xmlBombIsRejected() {
        String xml = "\u003c?xml version=\"1.0\"?\u003e\n" +
            "\u003c!DOCTYPE lolz [\n" +
            "  \u003c!ENTITY lol \"lol\"\u003e\n" +
            "  \u003c!ENTITY lol2 \"\u0026lol;\u0026lol;\u0026lol;\u0026lol;\u0026lol;\u0026lol;\u0026lol;\u0026lol;\u0026lol;\u0026lol;\"\u003e\n" +
            "]\u003e\n" +
            "\u003cCheck\u003e\u003cPhoneNumber\u003e\u0026lol2;\u003c/PhoneNumber\u003e\u003c/Check\u003e";
        XmlParseResult result = parser.parse(xml);
        assertFalse(result.success());
    }
}
