package cloud.cloudonix.npg.simulation;

import cloud.cloudonix.npg.acknack.TechnicalAck;
import cloud.cloudonix.npg.acknack.TechnicalAckStatus;
import cloud.cloudonix.npg.audit.XmlAuditService;
import cloud.cloudonix.npg.audit.XmlTransportMetadata;
import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.config.MongoConfig;
import cloud.cloudonix.npg.inbound.InboundGatewayService;
import cloud.cloudonix.npg.number.NumberOwnershipService;
import cloud.cloudonix.npg.protocol.ProtocolBody;
import cloud.cloudonix.npg.protocol.ProtocolCatalogLoader;
import cloud.cloudonix.npg.protocol.ProtocolHeader;
import cloud.cloudonix.npg.support.TestDatabase;
import cloud.cloudonix.npg.support.TestMongo;
import cloud.cloudonix.npg.workflow.WorkflowEngine;
import cloud.cloudonix.npg.workflow.handler.WorkflowHandlerRegistry;
import cloud.cloudonix.npg.xml.SecureXmlParser;
import cloud.cloudonix.npg.xml.XmlParseResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class TraceSimulationTest {

    private static final String REQUEST_ID = "NPHLBZ260924417320001";
    private static final XmlTransportMetadata TRANSPORT =
        new XmlTransportMetadata("HTTPS", "10.0.0.1", null, new JsonObject());

    // Real trace: HL (donor side initiates) <-> BZ, publish to HI.
    private record Fixture(String file, String from, String to, String msgType, String trxNo) {
        Map<String, String> headers() {
            return Map.of(
                "x-np-from", from,
                "x-np-to", to,
                "x-np-msg-type", msgType,
                "x-np-request-id", REQUEST_ID,
                "x-np-trx-no", trxNo,
                "x-np-version-no", "1"
            );
        }

        String xml() {
            try {
                return Files.readString(Path.of("src/test/resources/simulation", file));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static final List<Fixture> TRACE = List.of(
        new Fixture("01-check.xml", "HL", "BZ", "Check", "HL000163454039"),
        new Fixture("02-check-response.xml", "BZ", "HL", "Check_response", "BZ000959453468"),
        new Fixture("03-request.xml", "HL", "BZ", "Request", "HL000163454222"),
        new Fixture("04-request-response.xml", "BZ", "HL", "Request_response", "BZ000959453698"),
        new Fixture("05-execute.xml", "HL", "BZ", "Execute", "HL000163454488"),
        new Fixture("06-execute-response.xml", "BZ", "HL", "Execute_response", "BZ000959460823"),
        new Fixture("07-publish.xml", "HL", "HI", "Publish", "HL000163468556"),
        new Fixture("08-publish-response.xml", "HI", "HL", "Publish_response", "HI000027281731")
    );

    private static Vertx vertx;
    private static Pool pool;
    private static XmlAuditService xmlAuditService;
    private InboundGatewayService service;
    private ProtocolCatalogLoader catalogLoader;

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
            .compose(v -> pool.query("TRUNCATE TABLE np_workflows").execute())
            .compose(v -> pool.query("TRUNCATE TABLE np_workflow_events").execute())
            .compose(v -> pool.query("TRUNCATE TABLE np_numbers").execute())
            .compose(v -> pool.query("TRUNCATE TABLE np_number_ownership_history").execute())
            .compose(v -> pool.query("TRUNCATE TABLE np_operators").execute())
            .compose(v -> pool.query("SET FOREIGN_KEY_CHECKS = 1").execute())
            .compose(v -> xmlAuditService.mongoClient().removeDocuments(XmlAuditService.COLLECTION, new JsonObject()))
            .onComplete(ctx.succeedingThenComplete());
    }

    @BeforeEach
    void initService() {
        catalogLoader = new ProtocolCatalogLoader(vertx, "../docs/protocol");
        service = new InboundGatewayService(pool, xmlAuditService, catalogLoader);
    }

    private Future<Void> replayTrace() {
        Future<Void> chain = Future.succeededFuture();
        for (Fixture f : TRACE) {
            chain = chain.compose(v -> service.receive(f.xml(), TRANSPORT, f.headers())
                .map(ack -> {
                    assertEquals(TechnicalAckStatus.ACK, ack.status(),
                        "Expected ACK for " + f.file() + " but got " + ack.status());
                    return null;
                }));
        }
        return chain;
    }

    @Test
    void traceMessagesAreAckedAndPersisted(VertxTestContext ctx) {
        replayTrace()
            .compose(v -> pool.query(
                "SELECT message_uuid, message_type, direction, request_id, transaction_id, xml_sha256 "
                    + "FROM np_messages ORDER BY id").execute())
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertEquals(8, rows.size());
                int i = 0;
                for (io.vertx.sqlclient.Row row : rows) {
                    Fixture f = TRACE.get(i++);
                    assertEquals(f.msgType(), row.getString("message_type"));
                    assertEquals("INBOUND", row.getString("direction"));
                    assertEquals(REQUEST_ID, row.getString("request_id"));
                    assertEquals(f.trxNo(), row.getString("transaction_id"));
                    assertEquals(XmlAuditService.sha256(f.xml()), row.getString("xml_sha256"));
                }
                ctx.completeNow();
            })));
    }

    @Test
    void traceCreatesInboxItemsAndMongoAuditDocs(VertxTestContext ctx) {
        replayTrace()
            .compose(v -> pool.query("SELECT COUNT(*) AS c FROM np_inbox").execute())
            .compose(rows -> {
                assertEquals(8L, rows.iterator().next().getLong("c"));
                return pool.query("SELECT message_uuid FROM np_messages ORDER BY id").execute();
            })
            .compose(rows -> {
                Future<Void> chain = Future.succeededFuture();
                int i = 0;
                for (io.vertx.sqlclient.Row row : rows) {
                    Fixture f = TRACE.get(i++);
                    chain = chain.compose(v -> xmlAuditService.findByMessageUuid(row.getString("message_uuid")))
                        .map(doc -> {
                            assertNotNull(doc, "Mongo audit doc missing for " + f.file());
                            assertEquals(XmlAuditService.sha256(f.xml()), doc.getString("canonicalSha256"));
                            assertEquals(f.msgType(), doc.getString("messageType"));
                            return null;
                        });
                }
                return chain;
            })
            .onComplete(ctx.succeedingThenComplete());
    }

    @Test
    void checkFixtureExtractsPhoneNumber() {
        XmlParseResult parsed = new SecureXmlParser().parse(TRACE.get(0).xml());
        assertTrue(parsed.success());
        ProtocolHeader header = catalogLoader.extractHeader(parsed.document(), TRACE.get(0).headers());
        assertEquals("Check", header.messageType());
        assertEquals("HL", header.senderOperator());
        assertEquals("BZ", header.receiverOperator());
        assertEquals("HL000163454039", header.transactionId());
        ProtocolBody body = catalogLoader.extractBody(parsed.document(), header.messageType(), TRACE.get(0).headers());
        assertEquals("08XXXXXXX", body.phoneNumber());
    }

    @Test
    void responsesCorrelateViaRequestTrxNo(VertxTestContext ctx) {
        replayTrace()
            .compose(v -> pool.query(
                "SELECT message_type, correlation_id FROM np_messages ORDER BY id").execute())
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                Map<String, String> expected = Map.of(
                    "Check_response", "HL000163454039",
                    "Request_response", "HL000163454222",
                    "Execute_response", "HL000163464488",
                    "Publish_response", "HL000163468556"
                );
                for (io.vertx.sqlclient.Row row : rows) {
                    String expectedCorrelation = expected.get(row.getString("message_type"));
                    if (expectedCorrelation != null) {
                        assertEquals(expectedCorrelation, row.getString("correlation_id"));
                    }
                }
                ctx.completeNow();
            })));
    }

    @Test
    void duplicateReplayIsNacked(VertxTestContext ctx) {
        Fixture f = TRACE.get(0);
        service.receive(f.xml(), TRANSPORT, f.headers())
            .compose(first -> {
                assertEquals(TechnicalAckStatus.ACK, first.status());
                return service.receive(f.xml(), TRANSPORT, f.headers());
            })
            .onComplete(ctx.succeeding(second -> ctx.verify(() -> {
                assertEquals(TechnicalAckStatus.NACK, second.status());
                assertEquals("NACK_DUPLICATE_MESSAGE", second.nackReason().code());
                ctx.completeNow();
            })));
    }

    @Test
    void checkMessageRegistersNumberAndWorkflow(VertxTestContext ctx) {
        Fixture f = TRACE.get(0);
        Map<String, String> headers = new java.util.HashMap<>(f.headers());
        headers.put("x-np-request-id", "REQ-REG-" + java.util.UUID.randomUUID());
        headers.put("x-np-trx-no", "HL000999000111");

        WorkflowHandlerRegistry registry = new WorkflowHandlerRegistry(pool, xmlAuditService,
            catalogLoader, new WorkflowEngine(pool), new NumberOwnershipService(pool));

        service.receive(f.xml(), TRANSPORT, headers)
            .compose(ack -> {
                assertEquals(TechnicalAckStatus.ACK, ack.status());
                return pool.query("SELECT * FROM np_inbox ORDER BY id DESC LIMIT 1").execute();
            })
            .compose(rows -> {
                Row inboxRow = rows.iterator().next();
                return pool.withTransaction(tx -> registry.handle(tx, inboxRow));
            })
            .compose(v -> pool.preparedQuery(
                "SELECT phone_number, number_type FROM np_numbers WHERE phone_number = ?")
                .execute(io.vertx.sqlclient.Tuple.of("08XXXXXXX")))
            .compose(rows -> {
                assertTrue(rows.iterator().hasNext(), "np_numbers must contain 08XXXXXXX");
                assertEquals("FIXED", rows.iterator().next().getString("number_type"),
                    "08XXXXXXX is a fixed/landline number (networkType=F in the trace)");
                return pool.preparedQuery(
                    "SELECT current_state FROM np_workflows WHERE request_id = ?")
                    .execute(io.vertx.sqlclient.Tuple.of(headers.get("x-np-request-id")));
            })
            .compose(rows -> {
                assertTrue(rows.iterator().hasNext(), "np_workflows must contain the workflow");
                assertEquals("CHECK_SENT", rows.iterator().next().getString("current_state"));
                return pool.preparedQuery(
                    "SELECT change_reason FROM np_number_ownership_history WHERE phone_number = ?")
                    .execute(io.vertx.sqlclient.Tuple.of("08XXXXXXX"));
            })
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertTrue(rows.iterator().hasNext(), "ownership history must record the registration");
                ctx.completeNow();
            })));
    }

    @Test
    void unknownOperatorsAreAutoDiscovered(VertxTestContext ctx) {
        Fixture f = TRACE.get(0);
        Map<String, String> headers = new java.util.HashMap<>(f.headers());
        headers.put("x-np-request-id", "REQ-AUTO-" + java.util.UUID.randomUUID());
        headers.put("x-np-trx-no", "HL000AUTO00001");

        service.receive(f.xml(), TRANSPORT, headers)
            .compose(ack -> {
                assertEquals(TechnicalAckStatus.ACK, ack.status());
                return pool.query(
                    "SELECT operator_code, requires_configuration FROM np_operators ORDER BY operator_code")
                    .execute();
            })
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                Map<String, Boolean> found = new java.util.HashMap<>();
                for (Row row : rows) {
                    found.put(row.getString("operator_code"), row.getBoolean("requires_configuration"));
                }
                assertEquals(Boolean.TRUE, found.get("HL"), "sender operator HL must be auto-discovered");
                assertEquals(Boolean.TRUE, found.get("BZ"), "receiver operator BZ must be auto-discovered");
                ctx.completeNow();
            })));
    }

    @Test
    void legacyFlatFormatStillWorks(VertxTestContext ctx) {
        String xml = "<Check>" +
            "<senderOperator>OP01</senderOperator>" +
            "<receiverOperator>OP02</receiverOperator>" +
            "<messageType>Check</messageType>" +
            "<requestId>REQ-LEGACY-1</requestId>" +
            "<transactionId>TX-LEGACY-1</transactionId>" +
            "<protocolVersion>4.0</protocolVersion>" +
            "<PhoneNumber>972501234567</PhoneNumber>" +
            "</Check>";

        service.receive(xml, TRANSPORT, Map.of())
            .onComplete(ctx.succeeding(ack -> ctx.verify(() -> {
                assertEquals(TechnicalAckStatus.ACK, ack.status());
                ctx.completeNow();
            })));
    }
}
