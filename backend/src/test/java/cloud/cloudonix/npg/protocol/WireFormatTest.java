package cloud.cloudonix.npg.protocol;

import cloud.cloudonix.npg.outbound.OutboundMessageBuilder;
import cloud.cloudonix.npg.outbound.OutboundResult;
import cloud.cloudonix.npg.outbound.PeerAckParser;
import cloud.cloudonix.npg.xml.SecureXmlParser;
import cloud.cloudonix.npg.xml.XmlParseResult;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Outbound serialisation and peer-reply interpretation against the observed wire format. */
class WireFormatTest {

    private static Vertx vertx;
    private static ProtocolCatalogLoader catalog;
    private final OutboundMessageBuilder builder = new OutboundMessageBuilder();
    private final PeerAckParser ackParser = new PeerAckParser();

    @BeforeAll
    static void load() {
        vertx = Vertx.vertx();
        catalog = new ProtocolCatalogLoader(vertx, "../docs/protocol");
    }

    @AfterAll
    static void close() {
        vertx.close();
    }

    @Test
    void checkBodyRoundTripsThroughTheInboundExtractor() {
        JsonObject body = new JsonObject().put("fixed", new JsonObject()
            .put("networkType", "F")
            .put("fixedNumberSingle", new JsonObject().put("numberType", "S").put("number", "081234567"))
            .put("identificationValue", "123456782"));
        String xml = builder.build("Check", body);
        assertTrue(xml.contains("<npMessageBody><Check><fixed><networkType>F</networkType>"), xml);

        XmlParseResult parsed = new SecureXmlParser().parse(xml);
        assertTrue(parsed.success(), parsed.errorMessage());
        Map<String, String> transport = Map.of("x-np-from", "HL", "x-np-to", "BZ",
            "x-np-request-id", "NPHLBZ1", "x-np-trx-no", "HL000000000001", "x-np-version-no", "1");
        ProtocolHeader header = catalog.extractHeader(parsed.document(), transport);
        assertEquals("Check", header.messageType());
        assertEquals("HL", header.senderOperator());
        JsonObject fields = catalog.extractBody(parsed.document(), "Check", transport).toJson();
        assertEquals("081234567", fields.getString("phoneNumber"));
        assertEquals("F", fields.getString("networkType"));
    }

    @Test
    void emptyBodyIsSelfClosingAndArraysRepeat() {
        assertTrue(builder.build("Execute", null).contains("<Execute/>"));
        String xml = builder.build("Publish", new JsonObject()
            .put("number", new JsonArray().add("1").add("2")).putNull("skipped"));
        assertTrue(xml.contains("<number>1</number><number>2</number>"), xml);
        assertFalse(xml.contains("skipped"), xml);
    }

    @Test
    void textIsEscapedAndKeysCannotInjectMarkup() {
        String xml = builder.build("Cancel", new JsonObject().put("reason", "a</reason><x>&"));
        assertTrue(xml.contains("<reason>a&lt;/reason&gt;&lt;x&gt;&amp;</reason>"), xml);
        assertThrows(IllegalArgumentException.class,
            () -> builder.build("Cancel", new JsonObject().put("bad><x", "v")));
    }

    @Test
    void responseWithoutRequestTrxNoIsNotCorrelatable() {
        XmlParseResult parsed = new SecureXmlParser().parse(
            "<npMessageBody><Check_response><positiveApproval><approvalInd>Y</approvalInd></positiveApproval>"
                + "</Check_response></npMessageBody>");
        assertNull(catalog.extractHeader(parsed.document(), Map.of()).correlationId());
        assertTrue(catalog.isResponse("Check_response"));
        assertTrue(catalog.isResponse("Publish_response"));
        assertFalse(catalog.isResponse("Execute"), "requests never need requestTrxNo");
        assertFalse(catalog.isResponse("Down_system"));
    }

    @Test
    void nackCodesMapToCatalogHttpStatuses() {
        assertEquals(409, catalog.httpStatusForNack("NACK_DUPLICATE_MESSAGE"));
        assertEquals(403, catalog.httpStatusForNack("NACK_UNAUTHORIZED_SENDER"));
        assertEquals(400, catalog.httpStatusForNack("SOMETHING_ELSE"));
    }

    @Test
    void executeDirectionFollowsTheTrace() {
        JsonObject execute = catalog.findMessage("Execute").orElseThrow();
        assertEquals("RECIPIENT_OPERATOR", execute.getString("sender_role"));
        assertEquals("DONOR_OPERATOR", execute.getString("receiver_role"));
    }

    // ---- peer replies

    @Test
    void plain2xxIsAck() {
        assertEquals("ACK", ackParser.interpret(200, "").ackStatus());
        assertEquals("ACK", ackParser.interpret(200,
            "<AckNack><Status>ACK</Status><AckId>1</AckId></AckNack>").ackStatus());
    }

    @Test
    void explicitNackCarriesItsCode() {
        OutboundResult r = ackParser.interpret(400,
            "<AckNack><Status>NACK</Status><NackReason><Code>NACK_MISSING_HEADER</Code>"
                + "<Message>no TRX</Message></NackReason></AckNack>");
        assertEquals("NACK", r.ackStatus());
        assertEquals("NACK_MISSING_HEADER", r.nackCode());
    }

    @Test
    void duplicateMeansAlreadyDelivered() {
        assertEquals("ACK", ackParser.interpret(400,
            "<AckNack><Status>NACK</Status><NackReason><Code>NACK_DUPLICATE_MESSAGE</Code></NackReason></AckNack>")
            .ackStatus());
        assertEquals("ACK", ackParser.interpret(409, null).ackStatus());
    }

    @Test
    void traceStyleAckCodes() {
        assertEquals("ACK", ackParser.interpret(200, "<NP_ACK><ACK_CODE>Ack00</ACK_CODE></NP_ACK>").ackStatus());
        OutboundResult r = ackParser.interpret(200, "<NP_ACK><ACK_CODE>Ack07</ACK_CODE></NP_ACK>");
        assertEquals("NACK", r.ackStatus());
        assertEquals("Ack07", r.nackCode());
    }

    @Test
    void serverErrorsAndThrottlingAreTransient() {
        assertTrue(ackParser.interpret(503, "busy").transportError());
        assertTrue(ackParser.interpret(429, null).transportError());
        assertTrue(ackParser.interpret(500, "<AckNack><Status>NACK</Status></AckNack>").transportError());
    }

    @Test
    void unexplained4xxIsARejection() {
        OutboundResult r = ackParser.interpret(404, "not here");
        assertEquals("NACK", r.ackStatus());
        assertEquals("HTTP_404", r.nackCode());
    }
}
