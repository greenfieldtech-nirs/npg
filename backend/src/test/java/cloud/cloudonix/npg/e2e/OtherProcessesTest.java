package cloud.cloudonix.npg.e2e;

import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.config.MongoConfig;
import cloud.cloudonix.npg.support.TestDatabase;
import cloud.cloudonix.npg.support.TestMongo;
import cloud.cloudonix.npg.workflow.PublicationTracker;
import cloud.cloudonix.npg.workflow.WorkflowSupport;
import cloud.cloudonix.npg.workflow.handler.InquiryWorkflowHandler;
import cloud.cloudonix.npg.workflow.handler.MaintenanceWorkflowHandler;
import cloud.cloudonix.npg.workflow.handler.NumberReturnWorkflowHandler;
import cloud.cloudonix.npg.workflow.handler.SynchronizationWorkflowHandler;
import cloud.cloudonix.npg.workflow.porting.PortOutPolicy;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
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

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Number return, inquiry, maintenance, synchronization, and publication ownership safeguards. */
@ExtendWith(VertxExtension.class)
class OtherProcessesTest {

    private static final String PHONE = "0812345678";
    private static Pool pool;
    private static ProtocolHarness h;
    private static NumberReturnWorkflowHandler returns;
    private static InquiryWorkflowHandler inquiries;
    private static MaintenanceWorkflowHandler maintenance;
    private static SynchronizationWorkflowHandler sync;

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
            h = new ProtocolHarness(vertx, pool, mongoFuture.result(), PortOutPolicy.Mode.AUTO_APPROVE);
            returns = new NumberReturnWorkflowHandler(h.services, new PublicationTracker(new WorkflowSupport(h.services)));
            inquiries = new InquiryWorkflowHandler(h.services);
            maintenance = new MaintenanceWorkflowHandler(h.services);
            sync = new SynchronizationWorkflowHandler(h.services);
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
        h.reset().onComplete(ctx.succeedingThenComplete());
    }

    /** Records PHONE as originally DON's and currently served by {@code current}. */
    private Future<Void> numberOwned(String current, String original) {
        return Future.all(h.operatorId(current), h.operatorId(original))
            .compose(ids -> pool.preparedQuery("INSERT INTO np_numbers (phone_number, number_type, current_operator_id, "
                    + "original_operator_id, status) VALUES (?, 'FIXED', ?, ?, 'PORTED')")
                .execute(Tuple.of(PHONE, ids.resultAt(0), ids.resultAt(1))))
            .mapEmpty();
    }

    // ===================================================================== number return

    @Test
    void returningAPortedInNumberAfterTheCoolingPeriod(VertxTestContext ctx) {
        AtomicReference<String> uuid = new AtomicReference<>();
        numberOwned("LOC", "DON")
            .compose(v -> returns.startReturn(PHONE, "noc", null))
            .compose(started -> {
                uuid.set(started.getString("workflowUuid"));
                return h.workflow(uuid.get());
            })
            .compose(wf -> {
                ctx.verify(() -> assertEquals("RETURN_COOLING_PERIOD", wf.getString("current_state")));
                return h.fire("RETURN_COOLING_PERIOD");
            })
            .compose(v -> h.lastSent("Return", "DON"))
            .compose(ret -> h.approve("DON", "Return_response", ret, null))
            .compose(ack -> h.number(PHONE))
            .compose(number -> {
                ctx.verify(() -> assertEquals("DON", number.getString("current_code"), "back with its original operator"));
                return h.lastSent("Publish", "P1");
            })
            .compose(publish -> {
                ctx.verify(() -> assertTrue(publish.xml().contains("<publishType>Return</publishType>"), publish.xml()));
                return h.approve("P1", "Publish_response", publish, null)
                    .compose(a -> h.lastSent("Publish", "P2"))
                    .compose(p2 -> h.approve("P2", "Publish_response", p2, null));
            })
            .compose(ack -> h.workflow(uuid.get()))
            .onComplete(ctx.succeeding(wf -> ctx.verify(() -> {
                assertEquals("RETURNED", wf.getString("current_state"));
                ctx.completeNow();
            })));
    }

    @Test
    void onlyNumbersWeServeAfterAPortInCanBeReturned(VertxTestContext ctx) {
        numberOwned("P1", "DON")
            .compose(v -> returns.startReturn(PHONE, "noc", null))
            .onComplete(ctx.failing(err -> ctx.verify(() -> {
                assertTrue(err.getMessage().contains("not a number we currently serve"), err.getMessage());
                ctx.completeNow();
            })));
    }

    @Test
    void acceptingAReturnOfOurOriginalNumber(VertxTestContext ctx) {
        numberOwned("P1", "LOC")
            .compose(v -> h.deliver("P1", "Return", "NPP1LOC261030000000101", ProtocolHarness.checkBody(PHONE)))
            .compose(ack -> h.lastSent("Return_response", "P1"))
            .compose(response -> {
                ctx.verify(() -> assertTrue(response.xml().contains("<approvalInd>Y</approvalInd>"), response.xml()));
                return h.number(PHONE);
            })
            .onComplete(ctx.succeeding(number -> ctx.verify(() -> {
                assertEquals("LOC", number.getString("current_code"));
                ctx.completeNow();
            })));
    }

    @Test
    void aReturnFromAnOperatorThatDoesNotServeTheNumberIsRejected(VertxTestContext ctx) {
        numberOwned("P1", "LOC")
            .compose(v -> h.deliver("P2", "Return", "NPP2LOC261030000000102", ProtocolHarness.checkBody(PHONE)))
            .compose(ack -> h.lastSent("Return_response", "P2"))
            .compose(response -> {
                ctx.verify(() -> assertTrue(response.xml().contains("<negativeApproval>"), response.xml()));
                return h.number(PHONE);
            })
            .onComplete(ctx.succeeding(number -> ctx.verify(() -> {
                assertEquals("P1", number.getString("current_code"));
                ctx.completeNow();
            })));
    }

    // ===================================================================== inquiry

    @Test
    void answeringAnInquiryFromOurRoutingView(VertxTestContext ctx) {
        numberOwned("P2", "DON")
            .compose(v -> h.deliver("P1", "Inquire_number", "NPP1LOC261030000000201", ProtocolHarness.checkBody(PHONE)))
            .compose(ack -> h.lastSent("Inquire_number_response", "P1"))
            .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
                assertTrue(response.xml().contains("<currentOperator>P2</currentOperator>"), response.xml());
                ctx.completeNow();
            })));
    }

    @Test
    void askingAPeerWhoServesANumber(VertxTestContext ctx) {
        AtomicReference<String> uuid = new AtomicReference<>();
        inquiries.inquire(PHONE, "P1", "noc", null)
            .compose(started -> {
                uuid.set(started.getString("workflowUuid"));
                return h.lastSent("Inquire_number", "P1");
            })
            .compose(inquiry -> h.approve("P1", "Inquire_number_response", inquiry, "<currentOperator>P2</currentOperator>"))
            .compose(ack -> h.workflow(uuid.get()))
            .onComplete(ctx.succeeding(wf -> ctx.verify(() -> {
                assertEquals("INQUIRY_COMPLETED", wf.getString("current_state"));
                assertTrue(wf.getValue("context_json").toString().contains("\"currentOperator\":\"P2\""));
                ctx.completeNow();
            })));
    }

    // ===================================================================== maintenance

    @Test
    void peerMaintenanceHoldsItsTrafficUntilItIsBackUp(VertxTestContext ctx) {
        h.deliver("P1", "Down_system", "NPP1P1261030000000301", "<reason>upgrade</reason>")
            .compose(ack -> h.count("SELECT COUNT(*) FROM np_operators WHERE operator_code = 'P1' AND availability_status = 'DOWN'"))
            .compose(down -> {
                ctx.verify(() -> assertEquals(1L, down));
                return inquiries.inquire(PHONE, "P1", "noc", null);
            })
            .compose(started -> new cloud.cloudonix.npg.repository.OutboxRepository(pool).claimBatch("d", 10, 60))
            .compose(rows -> new cloud.cloudonix.npg.outbound.OutboxDispatcher(pool, h.audit(),
                (url, xml, headers) -> Future.failedFuture("must not send to a peer that is down"),
                new cloud.cloudonix.npg.retry.RetryPolicyService(),
                new cloud.cloudonix.npg.retry.UnknownDeliveryPolicy(java.util.Set.of()), 60).dispatchAll(rows))
            .compose(v -> h.count("SELECT COUNT(*) FROM np_outbox WHERE status = 'PENDING' AND last_error_code = 'OPERATOR_DOWN' "
                + "AND attempts = 0 AND due_at > UTC_TIMESTAMP(6)"))
            .compose(held -> {
                ctx.verify(() -> assertEquals(1L, held, "held without spending an attempt"));
                return h.deliver("P1", "Up_system", "NPP1P1261030000000302", "<reason>done</reason>");
            })
            .compose(ack -> h.count("SELECT COUNT(*) FROM np_outbox WHERE status = 'PENDING' AND due_at <= UTC_TIMESTAMP(6)"))
            .compose(released -> {
                ctx.verify(() -> assertEquals(1L, released, "Up_system releases held traffic immediately"));
                return h.count("SELECT COUNT(*) FROM np_workflows WHERE workflow_type = 'MAINTENANCE' AND current_state = 'OPERATOR_MARKED_UP'");
            })
            .onComplete(ctx.succeeding(closed -> ctx.verify(() -> {
                assertEquals(1L, closed);
                ctx.completeNow();
            })));
    }

    @Test
    void announcingOurOwnMaintenanceToEveryPeer(VertxTestContext ctx) {
        maintenance.announce(true, "planned upgrade", "PT2H", "admin", null)
            .compose(v -> h.count("SELECT COUNT(*) FROM np_messages WHERE direction = 'OUTBOUND' AND message_type = 'Down_system'"))
            .compose(downs -> {
                ctx.verify(() -> assertEquals(3L, downs, "DON, P1 and P2"));
                return maintenance.announce(false, "upgrade done", null, "admin", null);
            })
            .compose(v -> h.count("SELECT COUNT(*) FROM np_workflows WHERE workflow_type = 'MAINTENANCE' AND current_state = 'UP_ANNOUNCED'"))
            .onComplete(ctx.succeeding(done -> ctx.verify(() -> {
                assertEquals(1L, done);
                ctx.completeNow();
            })));
    }

    // ===================================================================== synchronization

    @Test
    void answeringASyncRequestWithASnapshot(VertxTestContext ctx) {
        numberOwned("P2", "DON")
            .compose(v -> h.deliver("P1", "DB_synch_request", "NPP1LOC261030000000401", "<syncType>FULL</syncType>"))
            .compose(ack -> h.lastSent("DB_synch_response", "P1"))
            .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
                assertTrue(response.xml().contains("<fileReference>npg-sync:"), response.xml());
                assertTrue(response.xml().contains("<recordCount>1</recordCount>"), response.xml());
                ctx.completeNow();
            })));
    }

    @Test
    void requestingASyncFromAPeer(VertxTestContext ctx) {
        AtomicReference<String> uuid = new AtomicReference<>();
        sync.requestSync("P1", "FULL", "noc", null)
            .compose(started -> {
                uuid.set(started.getString("workflowUuid"));
                return h.lastSent("DB_synch_request", "P1");
            })
            .compose(request -> h.approve("P1", "DB_synch_response", request, "<fileReference>sftp://p1/sync.xml</fileReference>"))
            .compose(ack -> h.workflow(uuid.get()))
            .onComplete(ctx.succeeding(wf -> ctx.verify(() -> {
                assertEquals("SYNC_COMPLETED", wf.getString("current_state"));
                ctx.completeNow();
            })));
    }

    // ===================================================================== ownership safeguards

    @Test
    void aPublishCannotHijackANumberWeStillServe(VertxTestContext ctx) {
        numberOwned("LOC", "LOC")
            .compose(v -> h.deliver("P1", "Publish", "NPP1DON261030000000501", "<donor>LOC</donor><publishType>Port</publishType>"
                + "<fixed><fixedNumberSingle><numberType>S</numberType><number>" + PHONE + "</number></fixedNumberSingle></fixed>"))
            .compose(ack -> h.lastSent("Publish_response", "P1"))
            .compose(response -> {
                ctx.verify(() -> assertTrue(response.xml().contains("<negativeApproval>"), response.xml()));
                return h.number(PHONE);
            })
            .compose(number -> {
                ctx.verify(() -> assertEquals("LOC", number.getString("current_code"), "routing unchanged"));
                return h.count("SELECT COUNT(*) FROM np_manual_tasks WHERE task_type = 'PUBLICATION_CONFLICT'");
            })
            .onComplete(ctx.succeeding(tasks -> ctx.verify(() -> {
                assertEquals(1L, tasks);
                ctx.completeNow();
            })));
    }

    @Test
    void aReturnPublicationFromAnOperatorThatDoesNotServeTheNumberIsRefused(VertxTestContext ctx) {
        numberOwned("P1", "DON")
            .compose(v -> h.deliver("P2", "Publish", "NPP2DON261030000000502", "<donor>P2</donor><originalOperator>DON</originalOperator>"
                + "<publishType>Return</publishType><fixed><fixedNumberSingle><numberType>S</numberType><number>" + PHONE
                + "</number></fixedNumberSingle></fixed>"))
            .compose(ack -> h.number(PHONE))
            .onComplete(ctx.succeeding(number -> ctx.verify(() -> {
                assertEquals("P1", number.getString("current_code"));
                ctx.completeNow();
            })));
    }

    @Test
    void aPublishMustNameTheRecordedOwnerAsDonor(VertxTestContext ctx) {
        numberOwned("P2", "DON")
            .compose(v -> h.deliver("P1", "Publish", "NPP1XX261030000000510", "<publishType>Port</publishType>"
                + "<fixed><fixedNumberSingle><numberType>S</numberType><number>" + PHONE + "</number></fixedNumberSingle></fixed>"))
            .compose(ack -> h.number(PHONE))
            .onComplete(ctx.succeeding(number -> ctx.verify(() -> {
                assertEquals("P2", number.getString("current_code"), "no donor named: refused");
                ctx.completeNow();
            })));
    }

    @Test
    void aPublishClaimingOurNativeNumberNeedsACompletedPortOut(VertxTestContext ctx) {
        h.deliver("P1", "Publish", "NPP1LOC261030000000511", "<donor>LOC</donor><publishType>Port</publishType>"
                + "<fixed><fixedNumberSingle><numberType>S</numberType><number>" + PHONE + "</number></fixedNumberSingle></fixed>")
            .compose(ack -> h.number(PHONE))
            .onComplete(ctx.succeeding(number -> ctx.verify(() -> {
                assertNull(number, "unrecorded native number stays ours");
                ctx.completeNow();
            })));
    }

    @Test
    void returnPublicationsForUnrecordedNumbersAreRefused(VertxTestContext ctx) {
        h.deliver("P1", "Publish", "NPP1DON261030000000512", "<donor>P1</donor><originalOperator>DON</originalOperator>"
                + "<publishType>Return</publishType><fixed><fixedNumberSingle><numberType>S</numberType><number>" + PHONE
                + "</number></fixedNumberSingle></fixed>")
            .compose(ack -> h.number(PHONE))
            .onComplete(ctx.succeeding(number -> ctx.verify(() -> {
                assertNull(number);
                ctx.completeNow();
            })));
    }

    /** A plan range (as imported from data.gov.il) and, optionally, the operator mapped to its holder. */
    private Future<Void> planRange(String nsnPrefix, String allocatedTo, String mappedOperator) {
        Future<Void> insert = pool.preparedQuery("""
                INSERT INTO np_numbering_plan (ndc, e164_pattern, national_pattern, nsn_prefix, nsn_length, range_size,
                                               allocated_to, number_type, libphonenumber_valid)
                VALUES ('8', '+972 8 X', '08-X', ?, ?, 1000, ?, 'FIXED_LINE', TRUE)
                """).execute(Tuple.of(nsnPrefix, PHONE.length() - 1, allocatedTo)).mapEmpty();
        return mappedOperator == null ? insert : insert.compose(v -> pool.preparedQuery(
            "UPDATE np_operators SET numbering_plan_name = ? WHERE operator_code = ?").execute(Tuple.of(allocatedTo, mappedOperator)).mapEmpty());
    }

    @Test
    void aPublicationIsVerifiedAgainstTheNumberingPlan(VertxTestContext ctx) {
        // no configured range: the imported plan says DON's company holds the number
        planRange("8123", "DONOR COMPANY LTD", "DON")
            .compose(v -> h.deliver("P1", "Publish", "NPP1DON261030000000530", publishFor("DON")))
            .compose(ack -> h.number(PHONE))
            .compose(number -> {
                ctx.verify(() -> assertNotNull(number, "applied: the donor is the plan's holder"));
                return h.lastSent("Publish_response", "P1");
            })
            .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
                assertTrue(response.xml().contains("<approvalInd>Y</approvalInd>"), response.xml());
                ctx.completeNow();
            })));
    }

    @Test
    void aPublicationNamingSomeoneOtherThanThePlanHolderIsRefused(VertxTestContext ctx) {
        planRange("8123", "DONOR COMPANY LTD", "DON")
            .compose(v -> h.deliver("P1", "Publish", "NPP1P2261030000000531", publishFor("P2")))
            .compose(ack -> h.number(PHONE))
            .onComplete(ctx.succeeding(number -> ctx.verify(() -> {
                assertNull(number);
                ctx.completeNow();
            })));
    }

    @Test
    void anUnmappedPlanHolderNeverFallsBackToABroaderRange(VertxTestContext ctx) {
        // 81 belongs to DON, but the more specific 8123 to a company no operator is mapped to: unknown
        planRange("81", "DONOR COMPANY LTD", "DON")
            .compose(v -> planRange("8123", "SMALL CARRIER LTD", null))
            .compose(v -> h.deliver("P1", "Publish", "NPP1DON261030000000532", publishFor("DON")))
            .compose(ack -> h.number(PHONE))
            .onComplete(ctx.succeeding(number -> ctx.verify(() -> {
                assertNull(number);
                ctx.completeNow();
            })));
    }

    @Test
    void anUnverifiablePublicationIsRefusedForTheNoc(VertxTestContext ctx) {
        // no record, no range, no plan: naming a donor is not enough
        h.deliver("P1", "Publish", "NPP1DON261030000000533", publishFor("DON"))
            .compose(ack -> h.number(PHONE))
            .compose(number -> {
                ctx.verify(() -> assertNull(number));
                return h.lastSent("Publish_response", "P1");
            })
            .compose(response -> {
                ctx.verify(() -> assertTrue(response.xml().contains("<negativeApproval>"), response.xml()));
                return h.count("SELECT COUNT(*) FROM np_manual_tasks WHERE task_type = 'PUBLICATION_CONFLICT' AND status = 'OPEN'");
            })
            .onComplete(ctx.succeeding(tasks -> ctx.verify(() -> {
                assertEquals(1L, tasks);
                ctx.completeNow();
            })));
    }

    private Future<Void> range(String prefix, String operator) {
        return h.operatorId(operator).compose(id -> pool.preparedQuery(
            "INSERT INTO np_number_ranges (operator_id, prefix) VALUES (?, ?)").execute(Tuple.of(id, prefix))).mapEmpty();
    }

    private static String publishFor(String donor) {
        return (donor != null ? "<donor>" + donor + "</donor>" : "") + "<publishType>Port</publishType>"
            + "<fixed><fixedNumberSingle><numberType>S</numberType><number>" + PHONE + "</number></fixedNumberSingle></fixed>";
    }

    @Test
    void ourNeverPortedNumberCannotBePublishedAwayWhateverDonorIsNamed(VertxTestContext ctx) {
        range("081", "LOC")
            .compose(v -> h.deliver("P1", "Publish", "NPP1DON261030000000520", publishFor("DON")))
            .compose(ack -> h.number(PHONE))
            .onComplete(ctx.succeeding(number -> ctx.verify(() -> {
                assertNull(number, "still natively ours");
                ctx.completeNow();
            })));
    }

    @Test
    void aForeignNumberMustNameItsRangeHolderAsDonor(VertxTestContext ctx) {
        range("081", "DON")
            .compose(v -> h.deliver("P1", "Publish", "NPP1P2261030000000521", publishFor("P2")))
            .compose(ack -> h.number(PHONE))
            .compose(number -> {
                ctx.verify(() -> assertNull(number, "P2 is not the range holder"));
                return h.deliver("P1", "Publish", "NPP1DON261030000000522", publishFor("DON"));
            })
            .compose(ack -> h.number(PHONE))
            .onComplete(ctx.succeeding(number -> ctx.verify(() -> {
                assertEquals("P1", number.getString("current_code"));
                assertEquals("DON", number.getString("original_code"));
                ctx.completeNow();
            })));
    }

    @Test
    void anUnknownNumberWithoutADonorIsRefused(VertxTestContext ctx) {
        h.deliver("P1", "Publish", "NPP1XX261030000000523", publishFor(null))
            .compose(ack -> h.number(PHONE))
            .onComplete(ctx.succeeding(number -> ctx.verify(() -> {
                assertNull(number);
                ctx.completeNow();
            })));
    }

    @Test
    void asDonorWeRejectAPortOutOfANumberFromSomeoneElsesRange(VertxTestContext ctx) {
        range("081", "P2")
            .compose(v -> h.deliver("P1", "Check", "NPP1LOC261030000000524", ProtocolHarness.checkBody(PHONE)))
            .compose(ack -> h.lastSent("Check_response", "P1"))
            .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
                assertTrue(response.xml().contains("<rejectReasonCode>REJECTED_NUMBER_NOT_FOUND</rejectReasonCode>"), response.xml());
                ctx.completeNow();
            })));
    }

    @Test
    void aStaleCancelPublishCannotRollBackNewerRouting(VertxTestContext ctx) {
        String publish = "<donor>DON</donor><publishType>Port</publishType><fixed><fixedNumberSingle><numberType>S</numberType>"
            + "<number>" + PHONE + "</number></fixedNumberSingle></fixed>";
        range("081", "DON")
            .compose(v -> h.deliver("P1", "Publish", "NPP1DON261030000000503", publish))
            // the number then moved on to P2 (a later port we applied)
            .compose(ack -> h.operatorId("P2"))
            .compose(p2 -> pool.preparedQuery("UPDATE np_numbers SET current_operator_id = ? WHERE phone_number = ?")
                .execute(Tuple.of(p2, PHONE)))
            .compose(v -> h.deliver("P1", "Cancel_Publish", "NPP1DON261030000000503",
                "<fixed><fixedNumberSingle><numberType>S</numberType><number>" + PHONE + "</number></fixedNumberSingle></fixed>"))
            .compose(ack -> h.number(PHONE))
            .onComplete(ctx.succeeding(number -> ctx.verify(() -> {
                assertEquals("P2", number.getString("current_code"));
                ctx.completeNow();
            })));
    }

    @Test
    void portOutExecuteIsRefusedOnceTheNumberMovedElsewhere(VertxTestContext ctx) {
        String requestId = "NPP1LOC261030000000504";
        h.deliver("P1", "Check", requestId, ProtocolHarness.checkBody(PHONE))
            .compose(ack -> h.deliver("P1", "Request", requestId, "<portingDateTime>"
                + java.time.OffsetDateTime.now().plusHours(1).withNano(0) + "</portingDateTime>"))
            .compose(ack -> numberOwned("P2", "LOC"))
            .compose(v -> h.deliver("P1", "Execute", requestId, ""))
            .compose(ack -> h.lastSent("Execute_response", "P1"))
            .compose(response -> {
                ctx.verify(() -> assertTrue(response.xml().contains("<negativeApproval>"), response.xml()));
                return h.number(PHONE);
            })
            .onComplete(ctx.succeeding(number -> ctx.verify(() -> {
                assertEquals("P2", number.getString("current_code"));
                ctx.completeNow();
            })));
    }
}
