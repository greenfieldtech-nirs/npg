package cloud.cloudonix.npg.audit;

import cloud.cloudonix.npg.config.MongoConfig;
import cloud.cloudonix.npg.support.TestMongo;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class XmlAuditServiceTest {

    private static MongoClient mongoClient;
    private XmlAuditService service;

    @BeforeAll
    static void setUpClass(Vertx vertx, VertxTestContext ctx) {
        TestMongo.start();
        System.setProperty("MONGO_URI", TestMongo.uri());
        System.setProperty("MONGO_DB", TestMongo.dbName());
        MongoConfig.configure(vertx)
            .onComplete(ctx.succeeding(client -> {
                mongoClient = client;
                ctx.completeNow();
            }));
    }

    @AfterAll
    static void tearDownClass(VertxTestContext ctx) {
        TestMongo.stop();
        ctx.completeNow();
    }

    @BeforeEach
    void setUp(VertxTestContext ctx) {
        service = new XmlAuditService(mongoClient);
        service.ensureIndexes()
            .compose(v -> mongoClient.removeDocuments(XmlAuditService.COLLECTION, new JsonObject()))
            .onComplete(ctx.succeedingThenComplete());
    }

    @Test
    void shouldStoreInboundXmlAndRetrieveByMessageUuid(VertxTestContext ctx) {
        String messageUuid = UUID.randomUUID().toString();
        String rawXml = "<Check><PhoneNumber>972501234567</PhoneNumber></Check>";
        XmlTransportMetadata transport = new XmlTransportMetadata("HTTPS", "1.2.3.4", "CN=op01", new JsonObject());

        service.storeInboundRawXml(messageUuid, null, "Check", "OP01", "OP02",
                "REQ-1", "TX-1", null, "4.0", rawXml, transport)
            .compose(docId -> service.findByMessageUuid(messageUuid))
            .onComplete(ctx.succeeding(doc -> ctx.verify(() -> {
                assertNotNull(doc);
                assertEquals("INBOUND", doc.getString("direction"));
                assertEquals(rawXml, doc.getString("rawXml"));
                assertEquals(service.sha256(rawXml), doc.getString("canonicalSha256"));
                ctx.completeNow();
            })));
    }

    @Test
    void shouldStoreInvalidXmlWithValidationMetadata(VertxTestContext ctx) {
        String messageUuid = UUID.randomUUID().toString();
        String rawXml = "<Invalid>";
        XmlValidationMetadata validation = XmlValidationMetadata.invalid(
            new JsonArray().add(new JsonObject().put("error", "malformed XML")));
        XmlTransportMetadata transport = new XmlTransportMetadata("HTTPS", "1.2.3.4", null, new JsonObject());

        service.storeInvalidRawXml(messageUuid, "INBOUND", "Unknown", "OP01", "OP02",
                "4.0", rawXml, validation, transport)
            .compose(docId -> service.findByMessageUuid(messageUuid))
            .onComplete(ctx.succeeding(doc -> ctx.verify(() -> {
                assertNotNull(doc);
                assertEquals("INVALID", doc.getJsonObject("validation").getString("status"));
                ctx.completeNow();
            })));
    }

    @Test
    void hashVerificationSucceedsForMatchingXml(VertxTestContext ctx) {
        String messageUuid = UUID.randomUUID().toString();
        String rawXml = "<Check/>";
        XmlTransportMetadata transport = new XmlTransportMetadata("HTTPS", "1.2.3.4", null, new JsonObject());

        service.storeOutboundRawXml(messageUuid, null, "Check", "OP02", "OP01",
                "REQ-2", "TX-2", null, "4.0", rawXml, transport)
            .compose(docId -> service.verifyHash(docId, service.sha256(rawXml)))
            .onComplete(ctx.succeeding(valid -> ctx.verify(() -> {
                assertTrue(valid);
                ctx.completeNow();
            })));
    }

    @Test
    void validationMetadataUpdateDoesNotModifyRawXml(VertxTestContext ctx) {
        String messageUuid = UUID.randomUUID().toString();
        String rawXml = "<Check/>";
        XmlTransportMetadata transport = new XmlTransportMetadata("HTTPS", "1.2.3.4", null, new JsonObject());

        service.storeInboundRawXml(messageUuid, null, "Check", "OP01", "OP02",
                "REQ-3", "TX-3", null, "4.0", rawXml, transport)
            .compose(docId -> service.updateValidationMetadata(docId,
                XmlValidationMetadata.invalid(new JsonArray().add(new JsonObject().put("error", "xsd")))))
            .compose(docId -> service.findByMessageUuid(messageUuid))
            .onComplete(ctx.succeeding(doc -> ctx.verify(() -> {
                assertEquals(rawXml, doc.getString("rawXml"));
                assertEquals("INVALID", doc.getJsonObject("validation").getString("status"));
                ctx.completeNow();
            })));
    }
}
