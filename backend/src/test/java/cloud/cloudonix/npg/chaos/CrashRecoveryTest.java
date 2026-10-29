package cloud.cloudonix.npg.chaos;

import cloud.cloudonix.npg.audit.XmlAuditService;
import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.config.MongoConfig;
import cloud.cloudonix.npg.outbound.OutboundResult;
import cloud.cloudonix.npg.outbound.OutboxDispatcher;
import cloud.cloudonix.npg.outbound.OutboundMessageService;
import cloud.cloudonix.npg.outbound.TransportAdapter;
import cloud.cloudonix.npg.repository.InboxRepository;
import cloud.cloudonix.npg.repository.MessageRepository;
import cloud.cloudonix.npg.repository.OutboxRepository;
import cloud.cloudonix.npg.retry.RetryPolicyService;
import cloud.cloudonix.npg.retry.UnknownDeliveryPolicy;
import cloud.cloudonix.npg.support.TestDatabase;
import cloud.cloudonix.npg.support.TestMongo;
import cloud.cloudonix.npg.timer.TimerProcessorAccess;
import cloud.cloudonix.npg.timer.TimerService;
import cloud.cloudonix.npg.worker.InboxProcessor;
import cloud.cloudonix.npg.worker.PermanentProcessingException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Crash-recovery guarantees of the inbox, outbox and timer queues (docs/04): what happens when a
 * worker dies at each step, when a stale worker wakes up, and when processing keeps failing.
 */
@ExtendWith(VertxExtension.class)
class CrashRecoveryTest {

    private static Pool pool;
    private static XmlAuditService xmlAuditService;
    private static InboxRepository inbox;
    private static OutboxRepository outbox;
    private static TimerService timers;

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
            xmlAuditService = new XmlAuditService(mongoFuture.result());
            inbox = new InboxRepository(pool);
            outbox = new OutboxRepository(pool);
            timers = new TimerService(pool);
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

    @BeforeEach
    void clean(VertxTestContext ctx) {
        pool.query("DELETE FROM np_manual_tasks").execute()
            .compose(v -> pool.query("DELETE FROM np_timers").execute())
            .compose(v -> pool.query("DELETE FROM np_workflow_events").execute())
            .compose(v -> pool.query("DELETE FROM np_audit_events").execute())
            .compose(v -> pool.query("DELETE FROM np_inbox").execute())
            .compose(v -> pool.query("DELETE FROM np_outbox").execute())
            .compose(v -> pool.query("DELETE FROM np_messages").execute())
            .compose(v -> pool.query("DELETE FROM np_workflows").execute())
            .compose(v -> pool.query("DELETE FROM np_operator_endpoints").execute())
            .compose(v -> pool.query("DELETE FROM np_operators").execute())
            .onComplete(ctx.succeedingThenComplete());
    }

    // ---------------------------------------------------------------- inbox

    @Test
    void workerCrashMidProcessingIsReclaimedAfterLockExpiry(VertxTestContext ctx) {
        insertInboxItem(3)
            .compose(id -> inbox.claimBatch("crashed", 10, 60))
            .compose(rows -> {
                Row row = rows.iterator().next();
                // crashed worker: reached PROCESSING with a lock that has already expired
                return inbox.markProcessing(row.getLong("id"), row.getString("locked_by"), -1);
            })
            .compose(v -> inbox.claimBatch("recovery", 10, 60))
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertEquals(1, rows.size());
                assertEquals(1, rows.iterator().next().getInteger("attempts"), "the crashed attempt is counted");
                ctx.completeNow();
            })));
    }

    @Test
    void processingRowWithLiveLockIsNotReclaimed(VertxTestContext ctx) {
        insertInboxItem(3)
            .compose(id -> inbox.claimBatch("busy", 10, 60))
            .compose(rows -> {
                Row row = rows.iterator().next();
                return inbox.markProcessing(row.getLong("id"), row.getString("locked_by"), 60);
            })
            .compose(v -> inbox.claimBatch("other", 10, 60))
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertEquals(0, rows.size());
                ctx.completeNow();
            })));
    }

    @Test
    void staleWorkerCannotOverwriteTheNewOwner(VertxTestContext ctx) {
        insertInboxItem(3)
            .compose(id -> inbox.claimBatch("stale", 10, -1))
            .compose(staleRows -> inbox.claimBatch("fresh", 10, 60)
                .compose(freshRows -> inbox.markSucceeded(staleRows.iterator().next().getLong("id"),
                    staleRows.iterator().next().getString("locked_by"))))
            .compose(staleWon -> {
                ctx.verify(() -> assertFalse(staleWon, "fenced: the stale claim token no longer owns the row"));
                return pool.query("SELECT status FROM np_inbox").execute();
            })
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertEquals("CLAIMED", rows.iterator().next().getString("status"));
                ctx.completeNow();
            })));
    }

    @Test
    void successfulProcessingMarksSucceeded(VertxTestContext ctx) {
        InboxProcessor processor = new InboxProcessor(pool, (tx, row) -> Future.succeededFuture(), 60);
        insertInboxItem(3)
            .compose(id -> inbox.claimBatch("w", 10, 60))
            .compose(processor::processAll)
            .compose(v -> pool.query("SELECT status, locked_by, attempts FROM np_inbox").execute())
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                Row row = rows.iterator().next();
                assertEquals("SUCCEEDED", row.getString("status"));
                assertNull(row.getString("locked_by"));
                assertEquals(1, row.getInteger("attempts"));
                ctx.completeNow();
            })));
    }

    @Test
    void transientHandlerFailureIsRetriedLater(VertxTestContext ctx) {
        InboxProcessor processor = new InboxProcessor(pool, (tx, row) -> Future.failedFuture("db hiccup"), 60);
        insertInboxItem(3)
            .compose(id -> inbox.claimBatch("w", 10, 60))
            .compose(processor::processAll)
            .compose(v -> pool.query("""
                    SELECT status, attempts, locked_by, last_error_message, due_at > UTC_TIMESTAMP(6) AS later
                    FROM np_inbox""").execute())
            .compose(rows -> {
                Row row = rows.iterator().next();
                ctx.verify(() -> {
                    assertEquals("FAILED_RETRYABLE", row.getString("status"));
                    assertEquals(1, row.getInteger("attempts"));
                    assertNull(row.getString("locked_by"));
                    assertEquals("db hiccup", row.getString("last_error_message"));
                    assertEquals(1, row.getInteger("later"), "backoff pushes due_at into the future");
                });
                return countTasks("INBOX_PROCESSING_FAILED");
            })
            .onComplete(ctx.succeeding(tasks -> ctx.verify(() -> {
                assertEquals(0L, tasks, "no manual task while retries remain");
                ctx.completeNow();
            })));
    }

    @Test
    void exhaustedRetriesFailFinalWithManualTask(VertxTestContext ctx) {
        InboxProcessor processor = new InboxProcessor(pool, (tx, row) -> Future.failedFuture("still broken"), 60);
        insertInboxItem(2)
            .compose(id -> pool.preparedQuery("UPDATE np_inbox SET attempts = 1 WHERE id = ?").execute(Tuple.of(id)))
            .compose(v -> inbox.claimBatch("w", 10, 60))
            .compose(processor::processAll)
            .compose(v -> pool.query("SELECT status, last_error_code FROM np_inbox").execute())
            .compose(rows -> {
                ctx.verify(() -> {
                    assertEquals("FAILED_FINAL", rows.iterator().next().getString("status"));
                    assertEquals("RETRIES_EXHAUSTED", rows.iterator().next().getString("last_error_code"));
                });
                return countTasks("INBOX_PROCESSING_FAILED");
            })
            .onComplete(ctx.succeeding(tasks -> ctx.verify(() -> {
                assertEquals(1L, tasks);
                ctx.completeNow();
            })));
    }

    @Test
    void messageThatKeepsCrashingTheWorkerEndsFinalWithoutRunningAgain(VertxTestContext ctx) {
        AtomicInteger handlerCalls = new AtomicInteger();
        InboxProcessor processor = new InboxProcessor(pool, (tx, row) -> {
            handlerCalls.incrementAndGet();
            return Future.succeededFuture();
        }, 60);
        // three crashed attempts already counted, max is 3
        insertInboxItem(3)
            .compose(id -> pool.preparedQuery("UPDATE np_inbox SET attempts = 3 WHERE id = ?").execute(Tuple.of(id)))
            .compose(v -> inbox.claimBatch("w", 10, 60))
            .compose(processor::processAll)
            .compose(v -> pool.query("SELECT status FROM np_inbox").execute())
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertEquals("FAILED_FINAL", rows.iterator().next().getString("status"));
                assertEquals(0, handlerCalls.get());
                ctx.completeNow();
            })));
    }

    @Test
    void handlerEffectsRollBackWhenTheClaimWasLostMidProcessing(VertxTestContext ctx) {
        // the handler outlives its lock: another worker takes the row over while it is still running
        InboxProcessor slow = new InboxProcessor(pool, (tx, row) ->
            tx.query("INSERT INTO np_audit_events (event_uuid, actor_type, action, entity_type, entity_id) "
                    + "VALUES (UUID(), 'SYSTEM', 'SIDE_EFFECT', 'TEST', 'x')").execute()
                .compose(v -> pool.preparedQuery("UPDATE np_inbox SET locked_by = 'other-worker' WHERE id = ?")
                    .execute(Tuple.of(row.getLong("id"))))
                .mapEmpty(), 60);
        insertInboxItem(3)
            .compose(id -> inbox.claimBatch("slow", 10, 60))
            .compose(slow::processAll)
            .compose(v -> pool.query("SELECT COUNT(*) AS c FROM np_audit_events WHERE action = 'SIDE_EFFECT'").execute())
            .compose(rows -> {
                ctx.verify(() -> assertEquals(0L, rows.iterator().next().getLong("c"), "side effect must roll back"));
                return pool.query("SELECT status, locked_by FROM np_inbox").execute();
            })
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertEquals("other-worker", rows.iterator().next().getString("locked_by"));
                assertNotEquals("SUCCEEDED", rows.iterator().next().getString("status"));
                ctx.completeNow();
            })));
    }

    @Test
    void permanentFailureSkipsRetries(VertxTestContext ctx) {
        InboxProcessor processor = new InboxProcessor(pool,
            (tx, row) -> Future.failedFuture(new PermanentProcessingException("no handler")), 60);
        insertInboxItem(5)
            .compose(id -> inbox.claimBatch("w", 10, 60))
            .compose(processor::processAll)
            .compose(v -> pool.query("SELECT status, last_error_code FROM np_inbox").execute())
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertEquals("FAILED_FINAL", rows.iterator().next().getString("status"));
                assertEquals("PERMANENT_FAILURE", rows.iterator().next().getString("last_error_code"));
                ctx.completeNow();
            })));
    }

    // ---------------------------------------------------------------- outbox

    @Test
    void sendingRowIsNeverReclaimedForSending(VertxTestContext ctx) {
        insertOutboxRow("Request", "SENDING", -60)
            .compose(id -> outbox.claimBatch("w", 10, 60))
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertEquals(0, rows.size());
                ctx.completeNow();
            })));
    }

    @Test
    void unknownDeliveryOfSafeTypeIsResent(VertxTestContext ctx) {
        insertOutboxRow("Inquire_number", "SENDING", -60)
            .compose(id -> dispatcher(okTransport()).recoverUnknownDeliveries("sweeper", 10))
            .compose(n -> pool.query("SELECT status, last_error_code, locked_by FROM np_outbox").execute())
            .compose(rows -> {
                ctx.verify(() -> {
                    Row row = rows.iterator().next();
                    assertEquals("FAILED_RETRYABLE", row.getString("status"));
                    assertEquals("UNKNOWN_DELIVERY_RESENT", row.getString("last_error_code"));
                    assertNull(row.getString("locked_by"));
                });
                return countTasks("UNKNOWN_DELIVERY");
            })
            .onComplete(ctx.succeeding(tasks -> ctx.verify(() -> {
                assertEquals(0L, tasks);
                ctx.completeNow();
            })));
    }

    @Test
    void unknownDeliveryOfUnsafeTypeIsHeldForReview(VertxTestContext ctx) {
        insertOutboxRow("Request", "SENDING", -60)
            .compose(id -> dispatcher(okTransport()).recoverUnknownDeliveries("sweeper", 10))
            .compose(n -> pool.query("SELECT status FROM np_outbox").execute())
            .compose(rows -> {
                ctx.verify(() -> assertEquals("MANUAL_REVIEW", rows.iterator().next().getString("status")));
                return countTasks("UNKNOWN_DELIVERY");
            })
            .onComplete(ctx.succeeding(tasks -> ctx.verify(() -> {
                assertEquals(1L, tasks);
                ctx.completeNow();
            })));
    }

    @Test
    void sendingRowWithLiveLockIsLeftAlone(VertxTestContext ctx) {
        insertOutboxRow("Request", "SENDING", 60)
            .compose(id -> dispatcher(okTransport()).recoverUnknownDeliveries("sweeper", 10))
            .onComplete(ctx.succeeding(n -> ctx.verify(() -> {
                assertEquals(0, n);
                ctx.completeNow();
            })));
    }

    @Test
    void ackMarksOutboxSucceededAndSendsTheStoredProtocolHeaders(VertxTestContext ctx) {
        java.util.concurrent.atomic.AtomicReference<JsonObject> sentHeaders = new java.util.concurrent.atomic.AtomicReference<>();
        TransportAdapter capturing = (url, xml, headers) -> {
            sentHeaders.set(headers);
            return Future.succeededFuture(OutboundResult.ack());
        };
        writeOutbound("Check")
            .compose(v -> outbox.claimBatch("w", 10, 60))
            .compose(rows -> dispatcher(capturing).dispatchAll(rows))
            .compose(v -> pool.query("""
                    SELECT o.status, o.attempts, m.technical_status, m.ack_status, m.transaction_id, m.request_id
                    FROM np_outbox o JOIN np_messages m ON m.id = o.message_id""").execute())
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                Row row = rows.iterator().next();
                assertEquals("SUCCEEDED", row.getString("status"));
                assertEquals(1, row.getInteger("attempts"));
                assertEquals("DELIVERED", row.getString("technical_status"));
                assertEquals("ACK", row.getString("ack_status"));
                JsonObject h = sentHeaders.get();
                assertEquals("Check", h.getString("X-NP-MSG-TYPE"));
                assertEquals("LOCAL", h.getString("X-NP-FROM"));
                assertEquals("PEER", h.getString("X-NP-TO"));
                assertEquals(row.getString("transaction_id"), h.getString("X-NP-TRX-NO"));
                assertEquals(row.getString("request_id"), h.getString("X-NP-REQUEST-ID"));
                assertTrue(h.getString("X-NP-TRX-NO").matches("LOCAL\\d{12}"), h.getString("X-NP-TRX-NO"));
                assertFalse(h.containsKey("X-NP-RETRY-NO"), "first attempt is not a retry");
                ctx.completeNow();
            })));
    }

    @Test
    void retriesAreMarkedWithRetryNumber(VertxTestContext ctx) {
        java.util.concurrent.atomic.AtomicReference<JsonObject> sentHeaders = new java.util.concurrent.atomic.AtomicReference<>();
        TransportAdapter capturing = (url, xml, headers) -> {
            sentHeaders.set(headers);
            return Future.succeededFuture(OutboundResult.ack());
        };
        writeOutbound("Check")
            .compose(v -> pool.query("UPDATE np_outbox SET attempts = 2").execute())
            .compose(v -> outbox.claimBatch("w", 10, 60))
            .compose(rows -> dispatcher(capturing).dispatchAll(rows))
            .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
                assertEquals("2", sentHeaders.get().getString("X-NP-RETRY-NO"));
                assertNotNull(sentHeaders.get().getString("X-NP-RETRY-DATE"));
                ctx.completeNow();
            })));
    }

    @Test
    void protocolIdsFollowTheTraceShapeAndNeverRepeat(VertxTestContext ctx) {
        cloud.cloudonix.npg.protocol.ProtocolIds.nextTrxNo(pool, "HL")
            .compose(a -> cloud.cloudonix.npg.protocol.ProtocolIds.nextTrxNo(pool, "HL").map(b -> new String[]{a, b}))
            .compose(trx -> cloud.cloudonix.npg.protocol.ProtocolIds.nextRequestId(pool, "HL", "BZ")
                .map(req -> new String[]{trx[0], trx[1], req}))
            .onComplete(ctx.succeeding(ids -> ctx.verify(() -> {
                assertTrue(ids[0].matches("HL\\d{12}"), ids[0]);
                assertNotEquals(ids[0], ids[1]);
                assertTrue(ids[2].matches("NPHLBZ\\d{6}\\d{9}"), ids[2]);
                ctx.completeNow();
            })));
    }

    @Test
    void transportErrorIsRetried(VertxTestContext ctx) {
        TransportAdapter down = (url, xml, headers) -> Future.succeededFuture(OutboundResult.transportError("TRANSPORT_ERROR", "refused"));
        writeOutbound("Check")
            .compose(v -> outbox.claimBatch("w", 10, 60))
            .compose(rows -> dispatcher(down).dispatchAll(rows))
            .compose(v -> pool.query("SELECT status, last_error_code FROM np_outbox").execute())
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertEquals("FAILED_RETRYABLE", rows.iterator().next().getString("status"));
                assertEquals("TRANSPORT_ERROR", rows.iterator().next().getString("last_error_code"));
                ctx.completeNow();
            })));
    }

    @Test
    void nackStopsAndOpensManualTask(VertxTestContext ctx) {
        TransportAdapter rejecting = (url, xml, headers) -> Future.succeededFuture(OutboundResult.nack("NACK_X", "bad header"));
        writeOutbound("Check")
            .compose(v -> outbox.claimBatch("w", 10, 60))
            .compose(rows -> dispatcher(rejecting).dispatchAll(rows))
            .compose(v -> pool.query("SELECT status FROM np_outbox").execute())
            .compose(rows -> {
                ctx.verify(() -> assertEquals("MANUAL_REVIEW", rows.iterator().next().getString("status")));
                return countTasks("OUTBOUND_DELIVERY_FAILED");
            })
            .onComplete(ctx.succeeding(tasks -> ctx.verify(() -> {
                assertEquals(1L, tasks);
                ctx.completeNow();
            })));
    }

    @Test
    void failureBeforeSendIsAnOrdinaryRetryNotUnknownDelivery(VertxTestContext ctx) {
        AtomicInteger sends = new AtomicInteger();
        TransportAdapter counting = (url, xml, headers) -> {
            sends.incrementAndGet();
            return Future.succeededFuture(OutboundResult.ack());
        };
        // outbox row whose raw XML was never stored: preparation fails before any network call
        insertOutboxRow("Check", "PENDING", 0)
            .compose(id -> outbox.claimBatch("w", 10, 60))
            .compose(rows -> dispatcher(counting).dispatchAll(rows))
            .compose(v -> pool.query("SELECT status, last_error_code FROM np_outbox").execute())
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertEquals("FAILED_RETRYABLE", rows.iterator().next().getString("status"));
                assertEquals("PREPARE_FAILED", rows.iterator().next().getString("last_error_code"));
                assertEquals(0, sends.get());
                ctx.completeNow();
            })));
    }

    // ---------------------------------------------------------------- timers

    @Test
    void timerClaimedByCrashedWorkerIsReclaimed(VertxTestContext ctx) {
        timers.createTimer(null, null, "T", "TEST", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5), new JsonObject())
            .compose(id -> timers.claimDueTimers("crashed", 10, -1))
            .compose(rows -> timers.claimDueTimers("recovery", 10, 60))
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertEquals(1, rows.size());
                ctx.completeNow();
            })));
    }

    @Test
    void failingTimerActionIsRetriedThenEscalated(VertxTestContext ctx) {
        TimerProcessorAccess failing = new TimerProcessorAccess(pool, row -> Future.failedFuture("boom"));
        timers.createTimer(null, null, "T", "TEST", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5), new JsonObject())
            .compose(id -> timers.claimDueTimers("w", 10, 60))
            .compose(failing::fireAll)
            .compose(v -> pool.query("SELECT status, attempts, due_at > UTC_TIMESTAMP(6) AS later FROM np_timers").execute())
            .compose(rows -> {
                ctx.verify(() -> {
                    Row row = rows.iterator().next();
                    assertEquals("PENDING", row.getString("status"));
                    assertEquals(1, row.getInteger("attempts"));
                    assertEquals(1, row.getInteger("later"));
                });
                // jump to the last allowed attempt
                return pool.query("UPDATE np_timers SET attempts = " + (TimerProcessorAccess.MAX_ATTEMPTS - 1)
                    + ", due_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND").execute();
            })
            .compose(v -> timers.claimDueTimers("w", 10, 60))
            .compose(failing::fireAll)
            .compose(v -> pool.query("SELECT status FROM np_timers").execute())
            .compose(rows -> {
                ctx.verify(() -> assertEquals("FAILED", rows.iterator().next().getString("status")));
                return countTasks("TIMER_ACTION_FAILED");
            })
            .onComplete(ctx.succeeding(tasks -> ctx.verify(() -> {
                assertEquals(1L, tasks);
                ctx.completeNow();
            })));
    }

    // ---------------------------------------------------------------- helpers

    private OutboxDispatcher dispatcher(TransportAdapter transport) {
        return new OutboxDispatcher(pool, xmlAuditService, transport, new RetryPolicyService(),
            new UnknownDeliveryPolicy(Set.of("Check", "Inquire_number")), 60);
    }

    private static TransportAdapter okTransport() {
        return (url, xml, headers) -> Future.succeededFuture(OutboundResult.ack());
    }

    private Future<Long> insertInboxItem(int maxAttempts) {
        return new MessageRepository(pool).insertMessage(UUID.randomUUID().toString(), "Check", "INBOUND", null, null,
                "REQ", "TX-" + UUID.randomUUID(), null, UUID.randomUUID().toString(), null, null, "4.0")
            .compose(msgId -> inbox.createInboxItem(msgId, null, "Check",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), 100, maxAttempts));
    }

    /** Outbox row in a given status; lockOffsetSeconds sets locked_until relative to now. */
    private Future<Long> insertOutboxRow(String type, String status, int lockOffsetSeconds) {
        return new MessageRepository(pool).insertMessage(UUID.randomUUID().toString(), type, "OUTBOUND", null, null,
                "REQ", "TX-" + UUID.randomUUID(), null, UUID.randomUUID().toString(), null, null, "4.0")
            .compose(msgId -> outbox.createOutboxItem(msgId, null, 1L, type,
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), 100, 3))
            .compose(id -> pool.preparedQuery("""
                    UPDATE np_outbox SET status = ?, locked_by = 'someone',
                           locked_until = UTC_TIMESTAMP(6) + INTERVAL ? SECOND WHERE id = ?
                    """).execute(Tuple.of(status, lockOffsetSeconds, id))
                .map(id));
    }

    /** Real outbound message (Mongo + message + outbox) to an operator with a configured endpoint. */
    private Future<Void> writeOutbound(String type) {
        return pool.query("INSERT INTO np_operators (operator_code, operator_name) VALUES ('PEER', 'Peer')").execute()
            .compose(rs -> pool.query("SELECT id FROM np_operators WHERE operator_code = 'PEER'").execute())
            .compose(rows -> {
                Long operatorId = rows.iterator().next().getLong("id");
                return pool.preparedQuery("""
                        INSERT INTO np_operator_endpoints (operator_id, endpoint_type, endpoint_url)
                        VALUES (?, 'NPG_INBOUND', 'https://peer.example/np/inbound')
                        """).execute(Tuple.of(operatorId))
                    .compose(v -> new OutboundMessageService(xmlAuditService).send(pool, null, type, "PORT",
                        new cloud.cloudonix.npg.domain.Operator(null, "LOCAL", "Local", null, true, "UP"),
                        new cloud.cloudonix.npg.domain.Operator(operatorId, "PEER", "Peer", null, true, "UP"),
                        "REQ-" + UUID.randomUUID(), null, new JsonObject().put("number", "0501234567")));
            })
            .mapEmpty();
    }

    private Future<Long> countTasks(String taskType) {
        return pool.preparedQuery("SELECT COUNT(*) AS c FROM np_manual_tasks WHERE task_type = ?")
            .execute(Tuple.of(taskType))
            .map(rows -> rows.iterator().next().getLong("c"));
    }
}
