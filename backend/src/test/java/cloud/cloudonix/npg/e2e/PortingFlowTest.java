package cloud.cloudonix.npg.e2e;

import cloud.cloudonix.npg.acknack.TechnicalAckStatus;
import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.config.MongoConfig;
import cloud.cloudonix.npg.support.TestDatabase;
import cloud.cloudonix.npg.support.TestMongo;
import cloud.cloudonix.npg.workflow.BusinessRuleException;
import cloud.cloudonix.npg.workflow.porting.PortOutPolicy;
import cloud.cloudonix.npg.workflow.porting.PortingService.PortInRequest;
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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end porting from both sides, following the message flow of the live operator trace.
 * LOC is this gateway; DON is the donor of a port-in; P1/P2 are the other operators.
 */
@ExtendWith(VertxExtension.class)
class PortingFlowTest {

    private static final String PHONE = "0812345678";
    private static Pool pool;
    private static MongoClient mongo;
    private static ProtocolHarness auto;

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
            mongo = mongoFuture.result();
            auto = new ProtocolHarness(vertx, pool, mongo, PortOutPolicy.Mode.AUTO_APPROVE);
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
    void reset(VertxTestContext ctx) {
        auto.reset().onComplete(ctx.succeedingThenComplete());
    }

    private Future<String> startPortIn() {
        return auto.porting.startPortIn(new PortInRequest(PHONE, "FIXED", "DON", "123456782", null, null), "noc", "10.9.9.9")
            .map(result -> result.getString("workflowUuid"));
    }

    // ======================================================================== recipient

    @Test
    void portInRunsTheWholeTraceFlowAndPublishesToEveryOtherOperator(VertxTestContext ctx) {
        AtomicReference<String> uuid = new AtomicReference<>();
        startPortIn()
            .compose(id -> {
                uuid.set(id);
                return auto.lastSent("Check", "DON");
            })
            .compose(check -> {
                ctx.verify(() -> {
                    assertTrue(check.xml().contains("<npMessageBody><Check><fixed><networkType>F</networkType>"
                        + "<fixedNumberSingle><numberType>S</numberType><number>" + PHONE + "</number>"), check.xml());
                    assertTrue(check.requestId().matches("NPLOCDON\\d{15}"), check.requestId());
                    assertEquals("Check", check.headers().getString("X-NP-MSG-TYPE"));
                    assertEquals("PORT", check.headers().getString("X-NP-PROCESS-TYPE"));
                });
                return auto.approve("DON", "Check_response", check, null);
            })
            .compose(ack -> auto.workflow(uuid.get()))
            .compose(wf -> {
                ctx.verify(() -> assertEquals("REQUEST_SENT", wf.getString("current_state")));
                return auto.lastSent("Request", "DON");
            })
            .compose(request -> {
                ctx.verify(() -> assertTrue(request.xml().contains("<Request><portingDateTime>"), request.xml()));
                return auto.approve("DON", "Request_response", request, null);
            })
            .compose(ack -> auto.workflow(uuid.get()))
            .compose(wf -> {
                ctx.verify(() -> assertEquals("PORT_SCHEDULED", wf.getString("current_state")));
                return auto.fire("PORT_EXECUTION_DUE");
            })
            .compose(v -> auto.lastSent("Execute", "DON"))
            .compose(execute -> {
                ctx.verify(() -> assertTrue(execute.xml().contains("<Execute/>"), execute.xml()));
                return auto.approve("DON", "Execute_response", execute,
                    "<disconnectDateTime>2026-09-24T16:49:58+03:00</disconnectDateTime>");
            })
            .compose(ack -> auto.workflow(uuid.get()))
            .compose(wf -> {
                ctx.verify(() -> assertEquals("PUBLISH_SENT", wf.getString("current_state")));
                return auto.number(PHONE);
            })
            .compose(number -> {
                ctx.verify(() -> {
                    assertEquals("LOC", number.getString("current_code"), "the number is ours after execution");
                    assertEquals("DON", number.getString("original_code"));
                });
                return auto.count("SELECT COUNT(*) FROM np_messages WHERE direction = 'OUTBOUND' AND message_type = 'Publish'");
            })
            .compose(publishes -> {
                ctx.verify(() -> assertEquals(2L, publishes, "P1 and P2, never the donor"));
                return auto.lastSent("Publish", "P1");
            })
            .compose(publishToP1 -> {
                ctx.verify(() -> {
                    assertTrue(publishToP1.xml().contains("<donor>DON</donor>"), publishToP1.xml());
                    assertTrue(publishToP1.xml().contains("<publishType>Port</publishType>"), publishToP1.xml());
                });
                return auto.approve("P1", "Publish_response", publishToP1, "<routeDateTime>2026-09-24T16:50:08</routeDateTime>");
            })
            .compose(ack -> auto.workflow(uuid.get()))
            .compose(wf -> {
                ctx.verify(() -> assertEquals("PUBLISH_SENT", wf.getString("current_state"), "still waiting for P2"));
                return auto.lastSent("Publish", "P2").compose(p2 -> auto.approve("P2", "Publish_response", p2, null));
            })
            .compose(ack -> auto.workflow(uuid.get()))
            .onComplete(ctx.succeeding(wf -> ctx.verify(() -> {
                assertEquals("COMPLETED", wf.getString("current_state"));
                assertNotNull(wf.getLocalDateTime("completed_at"));
                ctx.completeNow();
            })));
    }

    @Test
    void rejectedCheckEndsThePortIn(VertxTestContext ctx) {
        AtomicReference<String> uuid = new AtomicReference<>();
        startPortIn()
            .compose(id -> {
                uuid.set(id);
                return auto.lastSent("Check", "DON");
            })
            .compose(check -> auto.rejectWith("DON", "Check_response", check, "REJECTED_INELIGIBLE"))
            .compose(ack -> auto.workflow(uuid.get()))
            .compose(wf -> {
                ctx.verify(() -> {
                    assertEquals("CHECK_REJECTED", wf.getString("current_state"));
                    assertEquals("REJECTED_INELIGIBLE", wf.getString("failure_code"));
                });
                return auto.count("SELECT COUNT(*) FROM np_timers WHERE status = 'PENDING'");
            })
            .onComplete(ctx.succeeding(pending -> ctx.verify(() -> {
                assertEquals(0L, pending, "the response timer was stopped");
                ctx.completeNow();
            })));
    }

    @Test
    void unansweredCheckEscalatesToTheNoc(VertxTestContext ctx) {
        AtomicReference<String> uuid = new AtomicReference<>();
        startPortIn()
            .compose(id -> {
                uuid.set(id);
                return auto.fire("CHECK_RESPONSE_TIMEOUT");
            })
            .compose(v -> auto.workflow(uuid.get()))
            .compose(wf -> {
                ctx.verify(() -> assertEquals("MANUAL_REVIEW", wf.getString("current_state")));
                return auto.count("SELECT COUNT(*) FROM np_manual_tasks WHERE task_type = 'RESPONSE_TIMEOUT'");
            })
            .onComplete(ctx.succeeding(tasks -> ctx.verify(() -> {
                assertEquals(1L, tasks);
                ctx.completeNow();
            })));
    }

    @Test
    void anotherOperatorCannotAnswerOurCheck(VertxTestContext ctx) {
        AtomicReference<String> uuid = new AtomicReference<>();
        startPortIn()
            .compose(id -> {
                uuid.set(id);
                return auto.lastSent("Check", "DON");
            })
            .compose(check -> auto.approve("P1", "Check_response", check, null))
            .compose(ack -> auto.workflow(uuid.get()))
            .compose(wf -> {
                ctx.verify(() -> assertEquals("CHECK_SENT", wf.getString("current_state")));
                return auto.count("SELECT COUNT(*) FROM np_inbox WHERE status = 'FAILED_FINAL'");
            })
            .onComplete(ctx.succeeding(failed -> ctx.verify(() -> {
                assertEquals(1L, failed, "the forged answer is refused and surfaced, not applied");
                ctx.completeNow();
            })));
    }

    @Test
    void cancellingAScheduledPort(VertxTestContext ctx) {
        AtomicReference<String> uuid = new AtomicReference<>();
        scheduledPortIn(uuid)
            .compose(v -> auto.porting.cancelPort(uuid.get(), "customer changed their mind", "noc", null))
            .compose(v -> auto.count("SELECT COUNT(*) FROM np_timers WHERE timer_name = 'PORT_EXECUTION_DUE' AND status = 'PENDING'"))
            .compose(execTimers -> {
                ctx.verify(() -> assertEquals(0L, execTimers, "a cancelled port must not execute"));
                return auto.lastSent("Cancel", "DON");
            })
            .compose(cancel -> auto.approve("DON", "Cancel_response", cancel, null))
            .compose(ack -> auto.workflow(uuid.get()))
            .compose(wf -> {
                ctx.verify(() -> assertEquals("CANCELLED", wf.getString("current_state")));
                return auto.count("SELECT COUNT(*) FROM np_audit_events WHERE action = 'PORT_CANCEL_REQUESTED' AND actor_id = 'noc'");
            })
            .onComplete(ctx.succeeding(audits -> ctx.verify(() -> {
                assertEquals(1L, audits);
                ctx.completeNow();
            })));
    }

    @Test
    void movingAScheduledPort(VertxTestContext ctx) {
        AtomicReference<String> uuid = new AtomicReference<>();
        LocalDateTime later = LocalDateTime.now(ZoneOffset.UTC).plusDays(2).withNano(0);
        scheduledPortIn(uuid)
            .compose(v -> auto.porting.updatePortTime(uuid.get(), later, "noc", null))
            .compose(v -> auto.lastSent("Update", "DON"))
            .compose(update -> auto.approve("DON", "Update_response", update, null))
            .compose(ack -> auto.workflow(uuid.get()))
            .compose(wf -> {
                ctx.verify(() -> {
                    assertEquals("PORT_SCHEDULED", wf.getString("current_state"));
                    assertEquals(later, wf.getLocalDateTime("approved_port_time"));
                });
                return auto.count("SELECT COUNT(*) FROM np_timers WHERE timer_name = 'PORT_EXECUTION_DUE' "
                    + "AND status = 'PENDING' AND due_at = ?", later);
            })
            .onComplete(ctx.succeeding(rescheduled -> ctx.verify(() -> {
                assertEquals(1L, rescheduled);
                ctx.completeNow();
            })));
    }

    @Test
    void secondPortInOfTheSameNumberIsRefused(VertxTestContext ctx) {
        startPortIn()
            .compose(v -> startPortIn())
            .onComplete(ctx.failing(err -> ctx.verify(() -> {
                assertInstanceOf(BusinessRuleException.class, err);
                assertEquals(409, ((BusinessRuleException) err).status());
                ctx.completeNow();
            })));
    }

    @Test
    void unansweredPublicationIsPartial(VertxTestContext ctx) {
        AtomicReference<String> uuid = new AtomicReference<>();
        scheduledPortIn(uuid)
            .compose(v -> auto.fire("PORT_EXECUTION_DUE"))
            .compose(v -> auto.lastSent("Execute", "DON"))
            .compose(execute -> auto.approve("DON", "Execute_response", execute, null))
            .compose(ack -> auto.lastSent("Publish", "P1"))
            .compose(p1 -> auto.approve("P1", "Publish_response", p1, null))
            .compose(ack -> auto.fire("PUBLICATION_RESPONSE_TIMEOUT"))
            .compose(v -> auto.workflow(uuid.get()))
            .compose(wf -> {
                ctx.verify(() -> assertEquals("PARTIALLY_PUBLISHED", wf.getString("current_state")));
                return auto.count("SELECT COUNT(*) FROM np_publication_recipients WHERE status = 'TIMEOUT'");
            })
            .onComplete(ctx.succeeding(timedOut -> ctx.verify(() -> {
                assertEquals(1L, timedOut, "P2 never answered");
                ctx.completeNow();
            })));
    }

    private Future<Void> scheduledPortIn(AtomicReference<String> uuid) {
        return startPortIn()
            .compose(id -> {
                uuid.set(id);
                return auto.lastSent("Check", "DON");
            })
            .compose(check -> auto.approve("DON", "Check_response", check, null))
            .compose(ack -> auto.lastSent("Request", "DON"))
            .compose(request -> auto.approve("DON", "Request_response", request, null))
            .mapEmpty();
    }

    // ======================================================================== donor

    @Test
    void portOutFollowsTheRecipientThroughToDisconnection(VertxTestContext ctx) {
        String requestId = "NPP1LOC261030000000001";
        auto.deliver("P1", "Check", requestId, ProtocolHarness.checkBody(PHONE))
            .compose(ack -> {
                ctx.verify(() -> assertEquals(TechnicalAckStatus.ACK, ack.status()));
                return auto.lastSent("Check_response", "P1");
            })
            .compose(response -> {
                ctx.verify(() -> {
                    assertTrue(response.xml().contains("<positiveApproval><approvalInd>Y</approvalInd>"), response.xml());
                    assertNotNull(response.correlationTrxNo(), "answers carry requestTrxNo");
                    assertTrue(response.xml().contains("<requestTrxNo>" + response.correlationTrxNo() + "</requestTrxNo>"));
                    assertEquals(requestId, response.requestId());
                });
                return auto.deliver("P1", "Request", requestId, "<portingDateTime>"
                    + java.time.OffsetDateTime.now().plusHours(1).withNano(0) + "</portingDateTime>");
            })
            .compose(ack -> auto.workflow(requestId))
            .compose(wf -> {
                ctx.verify(() -> {
                    assertEquals("DONOR", wf.getString("local_role"));
                    assertEquals("PORT_SCHEDULED", wf.getString("current_state"));
                    assertNotNull(wf.getLocalDateTime("approved_port_time"));
                });
                return auto.deliver("P1", "Execute", requestId, "");
            })
            .compose(ack -> auto.lastSent("Execute_response", "P1"))
            .compose(response -> {
                ctx.verify(() -> assertTrue(response.xml().contains("<disconnectDateTime>"), response.xml()));
                return auto.workflow(requestId);
            })
            .compose(wf -> {
                ctx.verify(() -> assertEquals("COMPLETED", wf.getString("current_state")));
                return auto.number(PHONE);
            })
            .onComplete(ctx.succeeding(number -> ctx.verify(() -> {
                assertEquals("P1", number.getString("current_code"), "released to the recipient");
                assertEquals("LOC", number.getString("original_code"));
                ctx.completeNow();
            })));
    }

    @Test
    void aBurstOfOneProcessIsHandledInArrivalOrder(VertxTestContext ctx) {
        // as in the live trace: Check, Request and Execute arrive before any of them is processed
        String requestId = "NPP1LOC261030000000099";
        auto.accept("P1", "Check", requestId, ProtocolHarness.checkBody(PHONE))
            .compose(a -> auto.accept("P1", "Request", requestId, "<portingDateTime>"
                + java.time.OffsetDateTime.now().plusMinutes(5).withNano(0) + "</portingDateTime>"))
            .compose(a -> auto.accept("P1", "Execute", requestId, ""))
            .compose(a -> auto.processInbox())
            .compose(v -> auto.workflow(requestId))
            .compose(wf -> {
                ctx.verify(() -> assertEquals("COMPLETED", wf.getString("current_state")));
                return auto.count("SELECT COUNT(*) FROM np_messages WHERE direction = 'OUTBOUND' AND message_type IN "
                    + "('Check_response', 'Request_response', 'Execute_response') AND xml_sha256 IS NOT NULL");
            })
            .compose(responses -> {
                ctx.verify(() -> assertEquals(3L, responses, "every message answered, none against a stale state"));
                // the publisher then announces the port to us as well
                return auto.accept("P1", "Publish", requestId, "<donor>LOC</donor><publishType>Port</publishType>"
                        + "<fixed><fixedNumberSingle><numberType>S</numberType><number>" + PHONE + "</number></fixedNumberSingle></fixed>")
                    .compose(a -> auto.processInbox());
            })
            .compose(v -> auto.number(PHONE))
            .onComplete(ctx.succeeding(number -> ctx.verify(() -> {
                assertEquals("P1", number.getString("current_code"));
                assertEquals("LOC", number.getString("original_code"));
                assertNotEquals(number.getLong("current_operator_id"), number.getLong("previous_operator_id"),
                    "re-announcing the current owner keeps the real previous owner");
                ctx.completeNow();
            })));
    }

    @Test
    void portOutOfANumberServedElsewhereIsRejected(VertxTestContext ctx) {
        String requestId = "NPP1LOC261030000000002";
        auto.operatorId("P2")
            .compose(p2 -> pool.preparedQuery("INSERT INTO np_numbers (phone_number, number_type, current_operator_id, status) "
                + "VALUES (?, 'FIXED', ?, 'PORTED')").execute(io.vertx.sqlclient.Tuple.of(PHONE, p2)))
            .compose(v -> auto.deliver("P1", "Check", requestId, ProtocolHarness.checkBody(PHONE)))
            .compose(ack -> auto.lastSent("Check_response", "P1"))
            .compose(response -> {
                ctx.verify(() -> assertTrue(response.xml().contains("<rejectReasonCode>REJECTED_NUMBER_NOT_FOUND</rejectReasonCode>"),
                    response.xml()));
                return auto.workflow(requestId);
            })
            .onComplete(ctx.succeeding(wf -> ctx.verify(() -> {
                assertEquals("CHECK_REJECTED", wf.getString("current_state"));
                ctx.completeNow();
            })));
    }

    @Test
    void anotherOperatorCannotDriveSomeoneElsesPortOut(VertxTestContext ctx) {
        String requestId = "NPP1LOC261030000000003";
        auto.deliver("P1", "Check", requestId, ProtocolHarness.checkBody(PHONE))
            // P2 reuses P1's REQUEST_ID to try to execute the port to itself
            .compose(ack -> auto.deliver("P2", "Execute", requestId, ""))
            .compose(ack -> auto.lastSent("Execute_response", "P2"))
            .compose(response -> {
                ctx.verify(() -> assertTrue(response.xml().contains("<negativeApproval>"), response.xml()));
                return auto.workflow(requestId);
            })
            .compose(wf -> {
                ctx.verify(() -> assertEquals("CHECK_APPROVED", wf.getString("current_state"), "P1's process is untouched"));
                return auto.number(PHONE);
            })
            .onComplete(ctx.succeeding(number -> ctx.verify(() -> {
                assertNull(number, "ownership never moved");
                ctx.completeNow();
            })));
    }

    @Test
    void recipientCanCancelAnApprovedPortOut(VertxTestContext ctx) {
        String requestId = "NPP1LOC261030000000004";
        auto.deliver("P1", "Check", requestId, ProtocolHarness.checkBody(PHONE))
            .compose(ack -> auto.deliver("P1", "Cancel", requestId, "<reason>customer withdrew</reason>"))
            .compose(ack -> auto.lastSent("Cancel_response", "P1"))
            .compose(response -> {
                ctx.verify(() -> assertTrue(response.xml().contains("<approvalInd>Y</approvalInd>"), response.xml()));
                return auto.workflow(requestId);
            })
            .onComplete(ctx.succeeding(wf -> ctx.verify(() -> {
                assertEquals("CANCELLED", wf.getString("current_state"));
                ctx.completeNow();
            })));
    }

    @Test
    void manualModeWaitsForTheNocDecision(Vertx vertx, VertxTestContext ctx) {
        ProtocolHarness manual = new ProtocolHarness(vertx, pool, mongo, PortOutPolicy.Mode.MANUAL);
        String requestId = "NPP1LOC261030000000005";
        manual.deliver("P1", "Check", requestId, ProtocolHarness.checkBody(PHONE))
            .compose(ack -> manual.count("SELECT COUNT(*) FROM np_messages WHERE message_type = 'Check_response'"))
            .compose(responses -> {
                ctx.verify(() -> assertEquals(0L, responses, "nothing is answered before the NOC decides"));
                return manual.workflow(requestId);
            })
            .compose(wf -> {
                ctx.verify(() -> assertEquals("CHECK_RECEIVED", wf.getString("current_state")));
                return manual.porting.decidePortOut(wf.getString("workflow_uuid"), true, null, null, "supervisor", null)
                    .map(wf.getString("workflow_uuid"));
            })
            .compose(uuid -> manual.lastSent("Check_response", "P1"))
            .compose(response -> {
                ctx.verify(() -> assertTrue(response.xml().contains("<approvalInd>Y</approvalInd>")));
                return manual.count("SELECT COUNT(*) FROM np_manual_tasks WHERE task_type = 'PORT_OUT_DECISION' AND status = 'OPEN'");
            })
            .compose(openTasks -> {
                ctx.verify(() -> assertEquals(0L, openTasks, "deciding closes the task"));
                return manual.count("SELECT COUNT(*) FROM np_audit_events WHERE action = 'PORT_OUT_APPROVED' AND actor_id = 'supervisor'");
            })
            .onComplete(ctx.succeeding(audits -> ctx.verify(() -> {
                assertEquals(1L, audits);
                ctx.completeNow();
            })));
    }

    // ======================================================================== third party

    @Test
    void publicationFromAnotherOperatorUpdatesOurRoutingAndCanBeWithdrawn(VertxTestContext ctx) {
        String requestId = "NPP1DON261030000000006";
        String publish = "<donor>DON</donor><connectDateTime>2026-09-24T16:50:08+03:00</connectDateTime>"
            + "<publishType>Port</publishType><disconnectDateTime>2026-09-24T16:50:08+03:00</disconnectDateTime>"
            + "<fixed><fixedNumberSingle><numberType>S</numberType><number>" + PHONE + "</number></fixedNumberSingle></fixed>";
        auto.deliver("P1", "Publish", requestId, publish)
            .compose(ack -> auto.number(PHONE))
            .compose(number -> {
                ctx.verify(() -> {
                    assertEquals("P1", number.getString("current_code"));
                    assertEquals("DON", number.getString("original_code"));
                });
                return auto.lastSent("Publish_response", "P1");
            })
            .compose(response -> {
                ctx.verify(() -> assertTrue(response.xml().contains("<routeDateTime>"), response.xml()));
                return auto.deliver("P1", "Cancel_Publish", requestId,
                    "<fixed><fixedNumberSingle><numberType>S</numberType><number>" + PHONE + "</number></fixedNumberSingle></fixed>");
            })
            .compose(ack -> auto.number(PHONE))
            .onComplete(ctx.succeeding(number -> ctx.verify(() -> {
                assertEquals("DON", number.getString("current_code"), "routing restored");
                ctx.completeNow();
            })));
    }

    @SuppressWarnings("unused")
    private static JsonObject row(Row r) {
        return r.toJson();
    }
}
