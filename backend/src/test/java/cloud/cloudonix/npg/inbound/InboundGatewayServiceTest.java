package cloud.cloudonix.npg.inbound;

import cloud.cloudonix.npg.acknack.TechnicalAckStatus;
import cloud.cloudonix.npg.audit.XmlAuditService;
import cloud.cloudonix.npg.audit.XmlTransportMetadata;
import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.config.MongoConfig;
import cloud.cloudonix.npg.protocol.ProtocolCatalogLoader;
import cloud.cloudonix.npg.support.TestDatabase;
import cloud.cloudonix.npg.support.TestMongo;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class InboundGatewayServiceTest {

    private static Vertx vertx;
    private static Pool pool;
    private static XmlAuditService xmlAuditService;
    private InboundGatewayService service;

    @BeforeAll
    static void setUpClass(Vertx v, VertxTestContext ctx) {
        vertx = v;
        TestDatabase.start();
        TestMongo.start();
        System.setProperty("DB_HOST", TestDatabase.host());
        System.setProperty("DB_PORT", String.valueOf(TestDatabase.port()));
        System.setProperty("DB_NAME", TestDatabase.database());
        System.setProperty("DB_USER", TestDatabase.user());
        System.setProperty("DB_PASSWORD", TestDatabase.password());
        System.setProperty("MONGO_URI", TestMongo.uri());
        System.setProperty("MONGO_DB", TestMongo.dbName());
        TestDatabase.migrate();

        Future<Pool> poolFuture = DatabaseConfig.configure(vertx);
        Future<MongoClient> mongoFuture = MongoConfig.configure(vertx);

        Future.all(poolFuture, mongoFuture)
            .onComplete(ctx.succeeding(result -> {
                pool = poolFuture.result();
                xmlAuditService = new XmlAuditService(mongoFuture.result());
                ctx.completeNow();
            }));
    }

    @AfterAll
    static void tearDownClass(VertxTestContext ctx) {
        Future<Void> closePool = (pool != null) ? pool.close().mapEmpty() : Future.succeededFuture();
        closePool.onComplete(v -> {
            TestMongo.stop();
            TestDatabase.stop();
            ctx.completeNow();
        });
    }

    @BeforeEach
    void clean(VertxTestContext ctx) {
        pool.query("SET FOREIGN_KEY_CHECKS = 0").execute()
            .compose(v -> pool.query("TRUNCATE TABLE np_messages").execute())
            .compose(v -> pool.query("TRUNCATE TABLE np_inbox").execute())
            .compose(v -> pool.query("SET FOREIGN_KEY_CHECKS = 1").execute())
            .compose(v -> xmlAuditService.mongoClient().removeDocuments(XmlAuditService.COLLECTION, new JsonObject()))
            .onComplete(ctx.succeedingThenComplete());
    }

    private InboundGatewayService newService() {
        // ponytail: tests run with user.dir=backend/, so ../docs/protocol resolves to the repo docs;
        // in the dev container docs are mounted at /docs, which is the same path from /workspace.
        ProtocolCatalogLoader catalogLoader = new ProtocolCatalogLoader(vertx, "../docs/protocol");
        return new InboundGatewayService(pool, xmlAuditService, catalogLoader);
    }

    @Test
    void validInboundMessageReturnsAck(VertxTestContext ctx) {
        service = newService();

        String xml = "<Check>" +
            "<senderOperator>OP01</senderOperator>" +
            "<receiverOperator>OP02</receiverOperator>" +
            "<messageType>Check</messageType>" +
            "<requestId>REQ-1</requestId>" +
            "<transactionId>TX-1</transactionId>" +
            "<protocolVersion>4.0</protocolVersion>" +
            "<PhoneNumber>972501234567</PhoneNumber>" +
            "</Check>";

        service.receive(xml, new XmlTransportMetadata("HTTPS", "1.2.3.4", null, new JsonObject()))
            .onComplete(ctx.succeeding(ack -> ctx.verify(() -> {
                assertEquals(TechnicalAckStatus.ACK, ack.status());
                ctx.completeNow();
            })));
    }

    @Test
    void invalidXmlReturnsNack(VertxTestContext ctx) {
        service = newService();

        service.receive("<Check>", new XmlTransportMetadata("HTTPS", "1.2.3.4", null, new JsonObject()))
            .onComplete(ctx.succeeding(ack -> ctx.verify(() -> {
                assertEquals(TechnicalAckStatus.NACK, ack.status());
                ctx.completeNow();
            })));
    }

    @Test
    void duplicateMessageReturnsNack(VertxTestContext ctx) {
        service = newService();

        String xml = "<Check>" +
            "<senderOperator>OP01</senderOperator>" +
            "<receiverOperator>OP02</receiverOperator>" +
            "<messageType>Check</messageType>" +
            "<requestId>REQ-DUP</requestId>" +
            "<transactionId>TX-DUP-1</transactionId>" +
            "<protocolVersion>4.0</protocolVersion>" +
            "<PhoneNumber>972501234567</PhoneNumber>" +
            "</Check>";

        XmlTransportMetadata transport = new XmlTransportMetadata("HTTPS", "1.2.3.4", null, new JsonObject());
        service.receive(xml, transport)
            .compose(ack -> service.receive(xml, transport))
            .onComplete(ctx.succeeding(ack -> ctx.verify(() -> {
                assertEquals(TechnicalAckStatus.NACK, ack.status());
                assertEquals("NACK_DUPLICATE_MESSAGE", ack.nackReason().code());
                ctx.completeNow();
            })));
    }
}
