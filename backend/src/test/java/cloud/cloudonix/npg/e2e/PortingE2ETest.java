package cloud.cloudonix.npg.e2e;

import cloud.cloudonix.npg.acknack.TechnicalAckStatus;
import cloud.cloudonix.npg.audit.XmlAuditService;
import cloud.cloudonix.npg.audit.XmlTransportMetadata;
import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.config.MongoConfig;
import cloud.cloudonix.npg.inbound.InboundGatewayService;
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
class PortingE2ETest {

    private static Vertx vertx;
    private static Pool pool;
    private static XmlAuditService xmlAuditService;
    private InboundGatewayService inboundService;

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
            .compose(result -> {
                pool = poolFuture.result();
                xmlAuditService = new XmlAuditService(mongoFuture.result());
                return xmlAuditService.ensureIndexes();
            })
            .onComplete(ctx.succeedingThenComplete());
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

    @Test
    void checkMessageCreatesWorkflowAndInboxItem(VertxTestContext ctx) {
        // ponytail: tests run with user.dir=backend/, so ../docs/protocol resolves to the repo docs;
        // in the dev container docs are mounted at /docs, same relative path from /workspace.
        ProtocolCatalogLoader catalogLoader = new ProtocolCatalogLoader(vertx, "../docs/protocol");
        inboundService = new InboundGatewayService(pool, xmlAuditService, catalogLoader);

        String xml = "<Check>" +
            "<senderOperator>OP01</senderOperator>" +
            "<receiverOperator>OP02</receiverOperator>" +
            "<messageType>Check</messageType>" +
            "<requestId>REQ-E2E-1</requestId>" +
            "<transactionId>TX-E2E-1</transactionId>" +
            "<protocolVersion>4.0</protocolVersion>" +
            "<PhoneNumber>972501234567</PhoneNumber>" +
            "</Check>";

        inboundService.receive(xml, new XmlTransportMetadata("HTTPS", "1.2.3.4", null, new JsonObject()))
            .compose(ack -> {
                assertEquals(TechnicalAckStatus.ACK, ack.status());
                return pool.query("SELECT COUNT(*) AS c FROM np_inbox").execute();
            })
            .compose(rows -> {
                assertEquals(1L, rows.iterator().next().getLong("c"));
                return pool.query("SELECT message_uuid, direction, idempotency_key FROM np_messages").execute();
            })
            .compose(rows -> {
                assertEquals(1, rows.size());
                var row = rows.iterator().next();
                assertEquals("INBOUND", row.getString("direction"));
                assertEquals("INBOUND|OP01|OP02|Check|REQ-E2E-1|TX-E2E-1", row.getString("idempotency_key"));
                return xmlAuditService.findByMessageUuid(row.getString("message_uuid"));
            })
            .onComplete(ctx.succeeding(doc -> ctx.verify(() -> {
                assertNotNull(doc, "raw XML audit document must exist in MongoDB");
                assertEquals("Check", doc.getString("messageType"));
                ctx.completeNow();
            })));
    }
}
