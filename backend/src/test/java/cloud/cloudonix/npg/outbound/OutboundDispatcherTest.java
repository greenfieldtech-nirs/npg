package cloud.cloudonix.npg.outbound;

import cloud.cloudonix.npg.audit.XmlAuditService;
import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.config.MongoConfig;
import cloud.cloudonix.npg.support.TestDatabase;
import cloud.cloudonix.npg.support.TestMongo;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class OutboundDispatcherTest {

    private static Pool pool;
    private static SqlClient sqlClient;
    private static MongoClient mongoClient;
    private static XmlAuditService xmlAuditService;
    private static OutboxWriter outboxWriter;

    @BeforeAll
    static void setUpClass(Vertx vertx, VertxTestContext ctx) {
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

        DatabaseConfig.configure(vertx)
            .compose(p -> {
                pool = p;
                sqlClient = p;
                return MongoConfig.configure(vertx);
            })
            .onComplete(ctx.succeeding(client -> {
                mongoClient = client;
                xmlAuditService = new XmlAuditService(mongoClient);
                xmlAuditService.ensureIndexes()
                    .onComplete(ctx.succeeding(v -> {
                        outboxWriter = new OutboxWriter(pool, xmlAuditService);
                        ctx.completeNow();
                    }));
            }));
    }

    @AfterAll
    static void tearDownClass(VertxTestContext ctx) {
        Future<Void> close = (pool != null) ? pool.close().mapEmpty() : Future.succeededFuture();
        close.onComplete(v -> {
            TestDatabase.stop();
            TestMongo.stop();
            ctx.completeNow();
        });
    }

    @BeforeEach
    void cleanTables(VertxTestContext ctx) {
        sqlClient.query("SET FOREIGN_KEY_CHECKS = 0").execute()
            .compose(v -> sqlClient.query("TRUNCATE TABLE np_outbox").execute())
            .compose(v -> sqlClient.query("TRUNCATE TABLE np_messages").execute())
            .compose(v -> sqlClient.query("TRUNCATE TABLE np_operator_endpoints").execute())
            .compose(v -> sqlClient.query("TRUNCATE TABLE np_operators").execute())
            .compose(v -> sqlClient.query("SET FOREIGN_KEY_CHECKS = 1").execute())
            .compose(v -> xmlAuditService.mongoClient().removeDocuments(XmlAuditService.COLLECTION, new JsonObject()))
            .onComplete(ctx.succeedingThenComplete());
    }

    @Test
    void resolveEndpointReturnsNullWhenNoEndpoint(VertxTestContext ctx) {
        sqlClient.preparedQuery("INSERT INTO np_operators (operator_code, operator_name) VALUES ('OPX', 'No Endpoint')")
            .execute()
            .compose(v -> sqlClient.query("SELECT id FROM np_operators WHERE operator_code = 'OPX'").execute())
            .compose(rows -> OutboxDispatcher.resolveEndpoint(pool, rows.iterator().next().getLong("id")))
            .onComplete(ctx.succeeding(url -> ctx.verify(() -> {
                assertNull(url);
                ctx.completeNow();
            })));
    }

    @Test
    void resolveEndpointReturnsActiveInboundUrl(VertxTestContext ctx) {
        sqlClient.preparedQuery("INSERT INTO np_operators (operator_code, operator_name) VALUES ('OPY', 'Has Endpoint')")
            .execute()
            .compose(v -> sqlClient.query("SELECT id FROM np_operators WHERE operator_code = 'OPY'").execute())
            .compose(rows -> {
                Long operatorId = rows.iterator().next().getLong("id");
                return sqlClient.preparedQuery("""
                        INSERT INTO np_operator_endpoints (operator_id, endpoint_type, endpoint_url, is_active)
                        VALUES (?, 'NPG_INBOUND', 'https://opy.example.com/np/inbound', TRUE)
                        """)
                    .execute(io.vertx.sqlclient.Tuple.of(operatorId))
                    // inactive endpoint must not win over the active one
                    .compose(v -> sqlClient.preparedQuery("""
                        INSERT INTO np_operator_endpoints (operator_id, endpoint_type, endpoint_url, is_active)
                        VALUES (?, 'NPG_INBOUND', 'https://opy.example.com/inactive', FALSE)
                        """)
                        .execute(io.vertx.sqlclient.Tuple.of(operatorId)))
                    .compose(v -> OutboxDispatcher.resolveEndpoint(pool, operatorId));
            })
            .onComplete(ctx.succeeding(url -> ctx.verify(() -> {
                assertEquals("https://opy.example.com/np/inbound", url);
                ctx.completeNow();
            })));
    }

    @Test
    void outboxWriterCreatesMessageAndOutboxRows(VertxTestContext ctx) {
        JsonObject body = new JsonObject().put("PhoneNumber", "972501234567");

        outboxWriter.writeOutbound(1L, 2L, "Check", "OP01", "OP02", "REQ-1", "TX-1", null, "4.0", body)
            .onComplete(ctx.succeeding(outboxId -> ctx.verify(() -> {
                assertNotNull(outboxId);

                sqlClient.query("SELECT COUNT(*) AS cnt FROM np_messages").execute()
                    .onComplete(ctx.succeeding(rows -> {
                        Row row = rows.iterator().next();
                        assertEquals(1L, row.getLong("cnt"));
                    }));

                sqlClient.query("SELECT COUNT(*) AS cnt FROM np_outbox").execute()
                    .onComplete(ctx.succeeding(rows -> {
                        Row row = rows.iterator().next();
                        assertEquals(1L, row.getLong("cnt"));
                    }));

                sqlClient.query(
                        "SELECT m.id AS message_id, m.message_uuid, o.message_id AS outbox_message_id " +
                        "FROM np_messages m JOIN np_outbox o ON o.message_id = m.id").execute()
                    .onComplete(ctx.succeeding(rows -> {
                        Row row = rows.iterator().next();
                        assertEquals(row.getLong("message_id"), row.getLong("outbox_message_id"));
                        String messageUuid = row.getString("message_uuid");

                        xmlAuditService.findByMessageUuid(messageUuid)
                            .onComplete(ctx.succeeding(doc -> ctx.verify(() -> {
                                assertNotNull(doc);
                                assertTrue(doc.getString("rawXml").contains("PhoneNumber"));
                                ctx.completeNow();
                            })));
                    }));
            })));
    }
}
