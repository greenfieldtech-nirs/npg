package cloud.cloudonix.npg.numberingplan;

import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.support.TestDatabase;
import cloud.cloudonix.npg.workflow.BusinessRuleException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Import (official source and upload), queries and number lookup against the real database. */
@ExtendWith(VertxExtension.class)
class NumberingPlanServiceTest {

    private static Pool pool;
    private static NumberingPlanService upload;
    private static HttpServer source;
    private static final AtomicReference<String> served = new AtomicReference<>();
    private static final AtomicReference<Integer> servedStatus = new AtomicReference<>(200);
    private static NumberingPlanService official;

    @BeforeAll
    static void setUpClass(Vertx vertx, VertxTestContext ctx) {
        TestDatabase.start();
        System.setProperty("DB_HOST", TestDatabase.host());
        System.setProperty("DB_PORT", String.valueOf(TestDatabase.port()));
        System.setProperty("DB_NAME", TestDatabase.database());
        System.setProperty("DB_USER", TestDatabase.user());
        System.setProperty("DB_PASSWORD", TestDatabase.password());
        TestDatabase.migrate();
        DatabaseConfig.configure(vertx)
            .compose(p -> {
                pool = p;
                upload = new NumberingPlanService(vertx, p);
                // stands in for data.gov.il
                return vertx.createHttpServer().requestHandler(req -> req.response().setStatusCode(servedStatus.get())
                    .putHeader("Content-Type", "text/csv").end(served.get())).listen(0);
            })
            .onComplete(ctx.succeeding(server -> {
                source = server;
                official = new NumberingPlanService(vertx, pool, "http://localhost:" + server.actualPort() + "/plan.csv");
                ctx.completeNow();
            }));
    }

    @AfterAll
    static void tearDownClass(VertxTestContext ctx) {
        Future<Void> close = pool != null ? pool.close().mapEmpty() : Future.succeededFuture();
        close.compose(v -> source != null ? source.close() : Future.succeededFuture()).onComplete(v -> {
            TestDatabase.stop();
            ctx.completeNow();
        });
    }

    @BeforeEach
    void clean(VertxTestContext ctx) throws Exception {
        served.set(NumberingPlanParserTest.sample());
        servedStatus.set(200);
        TestDatabase.clean(pool).onComplete(ctx.succeedingThenComplete());
    }

    @Test
    void importsTheOfficialFileAndSummarisesIt(VertxTestContext ctx) {
        official.importOfficial("admin", "10.0.0.1")
            .compose(result -> {
                ctx.verify(() -> {
                    assertEquals(10, result.getInteger("ranges"));
                    assertEquals(7, result.getInteger("operators"));
                    assertEquals(2L, result.getLong("rangesNotValidInLibphonenumber"), "050-1 M2M and 13-02 IoT are outside Google's metadata");
                });
                return official.summary();
            })
            .compose(summary -> {
                ctx.verify(() -> {
                    JsonObject totals = summary.getJsonObject("totals");
                    assertEquals(10L, totals.getLong("ranges"));
                    assertEquals(7L, totals.getLong("operators"));
                    assertEquals(2L, totals.getLong("notValidInLibphonenumber"));
                    // five blocks of 1M, four of 100k and CLOUDONIX's 1k
                    assertEquals(5_401_000L, totals.getLong("numbers"));
                    JsonObject top = summary.getJsonArray("byOperator").getJsonObject(0);
                    assertNotNull(top.getString("operator"));
                    assertNotNull(summary.getJsonObject("lastImport"));
                    assertEquals("admin", summary.getJsonObject("lastImport").getString("importedBy"));
                });
                return pool.query("SELECT COUNT(*) AS c FROM np_audit_events WHERE action = 'NUMBERING_PLAN_IMPORTED'").execute();
            })
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertEquals(1L, rows.iterator().next().getLong("c"));
                ctx.completeNow();
            })));
    }

    @Test
    void aBlockedOrBrokenDownloadNeverReplacesTheCurrentPlan(VertxTestContext ctx) {
        upload.importCsv(served.get(), "upload", "admin", null)
            .compose(v -> {
                // what data.gov.il's CDN returns to clients it blocks
                servedStatus.set(200);
                served.set("<HTML><BODY><H1>403 ERROR</H1>Request blocked.</BODY></HTML>");
                return official.importOfficial("admin", null).transform(ar -> Future.succeededFuture(ar));
            })
            .compose(attempt -> {
                ctx.verify(() -> {
                    assertTrue(attempt.failed());
                    assertInstanceOf(BusinessRuleException.class, attempt.cause());
                });
                servedStatus.set(403);
                return official.importOfficial("admin", null).transform(ar -> Future.succeededFuture(ar));
            })
            .compose(attempt -> {
                ctx.verify(() -> assertEquals(502, ((BusinessRuleException) attempt.cause()).status()));
                return pool.query("SELECT COUNT(*) AS c FROM np_numbering_plan").execute();
            })
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertEquals(10L, rows.iterator().next().getLong("c"), "the previous plan is intact");
                ctx.completeNow();
            })));
    }

    @Test
    void filtersRangesByOperatorTypeAndDigits(VertxTestContext ctx) {
        upload.importCsv(served.get(), "upload", "admin", null)
            .compose(v -> upload.ranges("HOT TELECOM LP", null, null, null))
            .compose(hot -> {
                ctx.verify(() -> assertEquals(2, hot.size()));
                return upload.ranges(null, null, "MOBILE", null);
            })
            .compose(mobile -> {
                ctx.verify(() -> {
                    assertEquals(1, mobile.size());
                    assertEquals("052-2XXXXXX", mobile.getJsonObject(0).getString("nationalPattern"));
                });
                return upload.ranges(null, null, null, "02-321");
            })
            .compose(found -> {
                ctx.verify(() -> {
                    assertEquals(1, found.size());
                    assertEquals("CLOUDONIX LTD", found.getJsonObject(0).getString("allocatedTo"));
                });
                // a whole number, international form, finds the range that holds it
                return upload.ranges(null, null, null, "+972 3 300 1234");
            })
            .compose(holding -> {
                ctx.verify(() -> assertEquals("03-30XXXXX", holding.getJsonObject(0).getString("nationalPattern")));
                return upload.ranges(null, null, null, "bezeq");
            })
            .onComplete(ctx.succeeding(byName -> ctx.verify(() -> {
                assertEquals(2, byName.size());
                ctx.completeNow();
            })));
    }

    @Test
    void lookupFindsTheMostSpecificRangeAndThePortingStatus(VertxTestContext ctx) {
        upload.importCsv(served.get(), "upload", "admin", null)
            .compose(v -> upload.lookup("+972 2-321-3456"))
            .compose(found -> {
                ctx.verify(() -> {
                    // 02-3213XXX (CLOUDONIX) is inside nothing else here, and wins over shorter prefixes
                    assertEquals("CLOUDONIX LTD", found.getJsonObject("range").getString("allocatedTo"));
                    assertEquals("FIXED_LINE", found.getString("numberType"));
                    assertEquals("02-321-3456", found.getString("national"));
                    assertNull(found.getValue("porting"), "never ported");
                });
                return pool.query("INSERT INTO np_operators (operator_code, operator_name) VALUES ('NPLX', 'Lookup Peer') "
                        + "ON DUPLICATE KEY UPDATE operator_name = operator_name").execute()
                    .compose(r -> pool.query("INSERT INTO np_numbers (phone_number, number_type, current_operator_id, status) "
                        + "SELECT '0522345678', 'MOBILE', id, 'PORTED' FROM np_operators WHERE operator_code = 'NPLX'").execute())
                    .compose(r -> upload.lookup("052-2345678"));
            })
            .compose(ported -> {
                ctx.verify(() -> {
                    assertEquals("CELLCOM ISRAEL LTD", ported.getJsonObject("range").getString("allocatedTo"), "original allocation");
                    assertEquals("NPLX", ported.getJsonObject("porting").getString("currentOperator"), "where it lives now");
                });
                return upload.lookup("hello").transform(ar -> Future.succeededFuture(ar));
            })
            .onComplete(ctx.succeeding(bad -> ctx.verify(() -> {
                assertTrue(bad.failed());
                assertEquals(400, ((BusinessRuleException) bad.cause()).status());
                ctx.completeNow();
            })));
    }
}
