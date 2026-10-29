package cloud.cloudonix.npg.security;

import cloud.cloudonix.npg.xml.SecureXmlParser;
import cloud.cloudonix.npg.xml.XmlParseResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class XmlSecurityTest {

    private final SecureXmlParser parser = new SecureXmlParser();

    @Test
    void xxeBlocked() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<!DOCTYPE Check [\n" +
            "  <!ENTITY xxe SYSTEM \"file:///etc/passwd\">\n" +
            "]>\n" +
            "<Check><PhoneNumber>&xxe;</PhoneNumber></Check>";
        XmlParseResult result = parser.parse(xml);
        assertFalse(result.success(), "XXE payload must be rejected");
    }

    @Test
    void billionLaughsBlocked() {
        String xml = "<?xml version=\"1.0\"?>\n" +
            "<!DOCTYPE lolz [\n" +
            "  <!ENTITY lol \"lol\">\n" +
            "  <!ENTITY lol2 \"&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;\">\n" +
            "  <!ENTITY lol3 \"&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;\">\n" +
            "]>\n" +
            "<Check><PhoneNumber>&lol3;</PhoneNumber></Check>";
        XmlParseResult result = parser.parse(xml);
        assertFalse(result.success(), "XML bomb must be rejected");
    }

    @Test
    void oversizedXmlBlocked() {
        StringBuilder sb = new StringBuilder();
        sb.append("<Check>");
        for (int i = 0; i < 200_000; i++) {
            sb.append("<PhoneNumber>972501234567</PhoneNumber>");
        }
        sb.append("</Check>");
        XmlParseResult result = parser.parse(sb.toString());
        assertFalse(result.success(), "XML over 1 MB default limit must be rejected");
    }
}
