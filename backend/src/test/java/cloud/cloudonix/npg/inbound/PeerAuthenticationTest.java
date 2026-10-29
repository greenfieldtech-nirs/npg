package cloud.cloudonix.npg.inbound;

import cloud.cloudonix.npg.acknack.TechnicalAck;
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
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** STRICT inbound authentication: only configured peers presenting their credential get an ACK. */
@ExtendWith(VertxExtension.class)
class PeerAuthenticationTest {

    private static Pool pool;
    private static InboundGatewayService strict;

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
        Future<Pool> poolFuture = DatabaseConfig.configure(vertx);
        Future<MongoClient> mongoFuture = MongoConfig.configure(vertx);
        Future.all(poolFuture, mongoFuture).onComplete(ctx.succeeding(v -> {
            pool = poolFuture.result();
            strict = new InboundGatewayService(pool, new XmlAuditService(mongoFuture.result()),
                new ProtocolCatalogLoader(vertx, "../docs/protocol"),
                new PeerAuthenticator(PeerAuthenticator.Mode.STRICT));
            ctx.completeNow();
        }));
    }

    @AfterAll
    static void tearDownClass(VertxTestContext ctx) {
        Future<Void> close = pool != null ? pool.close().mapEmpty() : Future.succeededFuture();
        close.onComplete(v -> {
            TestMongo.stop();
            TestDatabase.stop();
            ctx.completeNow();
        });
    }

    /** LOC is this gateway; PEER has an endpoint whose credential each test sets. */
    @BeforeEach
    void operators(VertxTestContext ctx) {
        pool.query("DELETE FROM np_inbox").execute()
            .compose(v -> pool.query("DELETE FROM np_messages").execute())
            .compose(v -> pool.query("DELETE FROM np_operator_endpoints").execute())
            .compose(v -> pool.query("DELETE FROM np_operators WHERE operator_code IN ('LOC', 'PEER', 'STRANGER', 'OTHER')").execute())
            .compose(v -> pool.query("INSERT INTO np_operators (operator_code, operator_name, is_local) VALUES ('LOC', 'Us', TRUE)").execute())
            .compose(v -> pool.query("INSERT INTO np_operators (operator_code, operator_name) VALUES ('PEER', 'Peer'), ('OTHER', 'Other')").execute())
            .onComplete(ctx.succeedingThenComplete());
    }

    @Test
    void allowlistedSourceIsAccepted(VertxTestContext ctx) {
        credential(null, "10.20.0.0/16, 192.0.2.7")
            .compose(v -> receive("PEER", "LOC", "10.20.3.4", null))
            .onComplete(ctx.succeeding(ack -> ctx.verify(() -> {
                assertEquals(TechnicalAckStatus.ACK, ack.status());
                ctx.completeNow();
            })));
    }

    @Test
    void sourceOutsideTheAllowlistIsRefused(VertxTestContext ctx) {
        credential(null, "10.20.0.0/16")
            .compose(v -> receive("PEER", "LOC", "203.0.113.9", null))
            .onComplete(ctx.succeeding(ack -> ctx.verify(() -> {
                assertNack(ack, "NACK_UNAUTHORIZED_SENDER");
                ctx.completeNow();
            })));
    }

    @Test
    void verifiedCertificateSubjectMustMatch(VertxTestContext ctx) {
        credential("CN=peer-npg,O=Peer", null)
            .compose(v -> receive("PEER", "LOC", "198.51.100.1", "CN=peer-npg,O=Peer"))
            .compose(ack -> {
                ctx.verify(() -> assertEquals(TechnicalAckStatus.ACK, ack.status()));
                return receive("PEER", "LOC", "198.51.100.1", "CN=impostor,O=Peer");
            })
            .compose(ack -> {
                ctx.verify(() -> assertNack(ack, "NACK_UNAUTHORIZED_SENDER"));
                return receive("PEER", "LOC", "198.51.100.1", null);
            })
            .onComplete(ctx.succeeding(ack -> ctx.verify(() -> {
                assertNack(ack, "NACK_UNAUTHORIZED_SENDER");
                ctx.completeNow();
            })));
    }

    @Test
    void anotherPeersCredentialDoesNotWork(VertxTestContext ctx) {
        // OTHER claims to be PEER from an address only OTHER is allowed to use
        credential(null, "10.20.0.0/16")
            .compose(v -> pool.query("""
                    INSERT INTO np_operator_endpoints (operator_id, endpoint_type, endpoint_url, ip_allowlist)
                    SELECT id, 'NPG_INBOUND', 'https://other', '172.16.5.5' FROM np_operators WHERE operator_code = 'OTHER'
                    """).execute())
            .compose(v -> receive("PEER", "LOC", "172.16.5.5", null))
            .onComplete(ctx.succeeding(ack -> ctx.verify(() -> {
                assertNack(ack, "NACK_UNAUTHORIZED_SENDER");
                ctx.completeNow();
            })));
    }

    @Test
    void peerWithoutAnyCredentialCannotAuthenticate(VertxTestContext ctx) {
        credential(null, null)
            .compose(v -> receive("PEER", "LOC", "10.20.3.4", null))
            .onComplete(ctx.succeeding(ack -> ctx.verify(() -> {
                assertNack(ack, "NACK_UNAUTHORIZED_SENDER");
                ctx.completeNow();
            })));
    }

    @Test
    void unknownSenderIsRefusedButRegisteredForConfiguration(VertxTestContext ctx) {
        receive("STRANGER", "LOC", "10.20.3.4", null)
            .compose(ack -> {
                ctx.verify(() -> assertNack(ack, "NACK_UNAUTHORIZED_SENDER"));
                return pool.query("SELECT requires_configuration FROM np_operators WHERE operator_code = 'STRANGER'").execute();
            })
            .compose(rows -> {
                ctx.verify(() -> assertTrue(rows.iterator().next().getBoolean("requires_configuration")));
                return pool.query("SELECT COUNT(*) AS c FROM np_inbox").execute();
            })
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertEquals(0L, rows.iterator().next().getLong("c"));
                ctx.completeNow();
            })));
    }

    @Test
    void messagesForAnotherReceiverAreRefused(VertxTestContext ctx) {
        credential(null, "10.20.0.0/16")
            .compose(v -> receive("PEER", "OTHER", "10.20.3.4", null))
            .onComplete(ctx.succeeding(ack -> ctx.verify(() -> {
                assertNack(ack, "NACK_RECEIVER_NOT_FOUND");
                ctx.completeNow();
            })));
    }

    @Test
    void inactiveOrUnconfiguredPeersAreRefused(VertxTestContext ctx) {
        credential(null, "10.20.0.0/16")
            .compose(v -> pool.query("UPDATE np_operators SET is_active = FALSE WHERE operator_code = 'PEER'").execute())
            .compose(v -> receive("PEER", "LOC", "10.20.3.4", null))
            .onComplete(ctx.succeeding(ack -> ctx.verify(() -> {
                assertNack(ack, "NACK_UNAUTHORIZED_SENDER");
                ctx.completeNow();
            })));
    }

    private static void assertNack(TechnicalAck ack, String code) {
        assertEquals(TechnicalAckStatus.NACK, ack.status());
        assertEquals(code, ack.nackReason().code());
    }

    private Future<Void> credential(String tlsSubject, String ipAllowlist) {
        return pool.preparedQuery("""
                INSERT INTO np_operator_endpoints (operator_id, endpoint_type, endpoint_url, tls_subject, ip_allowlist)
                SELECT id, 'NPG_INBOUND', 'https://peer.example/np/inbound', ?, ? FROM np_operators WHERE operator_code = 'PEER'
                """).execute(Tuple.of(tlsSubject, ipAllowlist)).mapEmpty();
    }

    private Future<TechnicalAck> receive(String from, String to, String clientIp, String verifiedSubject) {
        String xml = "<npMessageBody><Check><fixed><fixedNumberSingle><numberType>S</numberType>"
            + "<number>0812345678</number></fixedNumberSingle></fixed></Check></npMessageBody>";
        Map<String, String> headers = Map.of("x-np-from", from, "x-np-to", to,
            "x-np-request-id", "REQ-" + UUID.randomUUID(), "x-np-trx-no", from + UUID.randomUUID());
        return strict.receive(xml, new XmlTransportMetadata("HTTPS", clientIp, verifiedSubject, new JsonObject()), headers);
    }
}
