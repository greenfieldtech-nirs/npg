package cloud.cloudonix.npg.api;

import cloud.cloudonix.npg.config.AuthSeeder;
import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.config.MongoConfig;
import cloud.cloudonix.npg.security.JwtService;
import cloud.cloudonix.npg.support.TestDatabase;
import cloud.cloudonix.npg.support.TestMongo;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.mindrot.jbcrypt.BCrypt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class ManagementApiTest {

    private static Vertx vertx;
    private static Pool pool;
    private static MongoClient mongoClient;
    private static WebClient webClient;
    private static final String TEST_ADMIN = "tester";

    @BeforeAll
    static void setUpClass(Vertx v, VertxTestContext ctx) {
        vertx = v;
        System.setProperty("NPG_ENV", "test");
        // every request here comes from 127.0.0.1; per-IP limiting is covered by dedicated tests
        System.setProperty("LOGIN_MAX_ATTEMPTS_PER_IP", "100000");
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
                mongoClient = mongoFuture.result();
                // ponytail: ephemeral port, not fixed 8080 — local hosts may already hold 8080
                // (e.g. Podman gvproxy); inside the dev container any port is free
                int port = freePort();
                webClient = WebClient.create(vertx,
                    new WebClientOptions().setDefaultHost("localhost").setDefaultPort(port));
                // deployed once per class: a second listen on the same port would fail
                vertx.deployVerticle(ManagementApiVerticle.class,
                        new DeploymentOptions().setConfig(new JsonObject().put("port", port)))
                    .onComplete(ctx.succeedingThenComplete());
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
    void cleanTables(VertxTestContext ctx) {
        // ponytail: DELETE not TRUNCATE — pool connections vary, per-session FK_CHECKS toggles are unreliable
        TestDatabase.clean(pool)
            .compose(v -> pool.query("DELETE FROM np_operator_endpoints").execute())
            .compose(v -> pool.query("DELETE FROM np_operator_status_events").execute())
            .compose(v -> pool.query("DELETE FROM np_operators").execute())
            .compose(v -> pool.query("DELETE FROM np_user_roles").execute())
            .compose(v -> pool.query("DELETE FROM np_users").execute())
            .compose(v -> pool.query("DELETE FROM np_roles").execute())
            .compose(v -> mongoClient.removeDocuments("xml_messages", new JsonObject()))
            .compose(v -> pool.preparedQuery(
                "INSERT INTO np_operators (operator_code, operator_name) VALUES (?, ?)")
                .execute(Tuple.of("OP01", "Operator One")))
            .compose(v -> AuthSeeder.ensureSeeded(pool))
            .compose(v -> seedUser(TEST_ADMIN, "tester-password", "ADMIN"))
            .onComplete(ctx.succeedingThenComplete());
    }

    @Test
    void healthEndpointReturnsOk(VertxTestContext ctx) {
        webClient.get("/health/live").send()
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(200, resp.statusCode());
                ctx.completeNow();
            })));
    }

    @Test
    void listOperatorsReturnsData(VertxTestContext ctx) {
        webClient.get("/api/operators")
            .putHeader("Authorization", adminAuth())
            .send()
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(200, resp.statusCode());
                JsonArray body = resp.bodyAsJsonArray();
                assertEquals(1, body.size());
                assertEquals("OP01", body.getJsonObject(0).getString("operatorCode"));
                ctx.completeNow();
            })));
    }

    @Test
    void dashboardReturnsCounts(VertxTestContext ctx) {
        webClient.get("/api/dashboard")
            .putHeader("Authorization", adminAuth())
            .send()
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(200, resp.statusCode());
                JsonObject body = resp.bodyAsJsonObject();
                for (String key : new String[]{"activeWorkflows", "inboxPending", "outboxPending",
                        "openManualTasks", "failedWorkflows", "operatorsDown", "timersDue"}) {
                    assertTrue(body.containsKey(key), "missing key: " + key);
                }
                ctx.completeNow();
            })));
    }

    @Test
    void manualTransitionRequiresRole(VertxTestContext ctx) {
        seedWorkflow("REQ-MT-NOROLE")
            .compose(uuid -> webClient.post("/api/workflows/" + uuid + "/manual-transition")
                .sendJsonObject(new JsonObject().put("toState", "CHECK_SENT").put("reason", "test")))
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                // no credentials at all → 401 (was 403 under the header-only placeholder)
                assertEquals(401, resp.statusCode());
                ctx.completeNow();
            })));
    }

    @Test
    void manualTransitionSucceedsWithRole(VertxTestContext ctx) {
        seedWorkflow("REQ-MT-ADMIN")
            .compose(uuid -> webClient.post("/api/workflows/" + uuid + "/manual-transition")
                .putHeader("Authorization", adminAuth())
                .sendJsonObject(new JsonObject().put("toState", "CHECK_SENT").put("reason", "test"))
                .compose(resp -> {
                    ctx.verify(() -> assertEquals(200, resp.statusCode()));
                    return pool.preparedQuery("""
                            SELECT w.current_state,
                                   (SELECT COUNT(*) FROM np_workflow_events e
                                     WHERE e.workflow_id = w.id AND e.event_type = 'MANUAL_TRANSITION') AS manual_events
                            FROM np_workflows w WHERE w.workflow_uuid = ?
                            """).execute(Tuple.of(uuid));
                }))
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                var row = rows.iterator().next();
                assertEquals("CHECK_SENT", row.getString("current_state"));
                assertEquals(1L, row.getLong("manual_events"));
                ctx.completeNow();
            })));
    }

    @Test
    void createOperatorRequiresRole(VertxTestContext ctx) {
        webClient.post("/api/operators")
            .sendJsonObject(new JsonObject()
                .put("operatorCode", "OP77").put("operatorName", "Seventy Seven")
                .put("licenseType", "MOBILE").put("reason", "seed"))
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                // no credentials at all → 401 (was 403 under the header-only placeholder)
                assertEquals(401, resp.statusCode());
                ctx.completeNow();
            })));
    }

    @Test
    void createOperatorSucceeds(VertxTestContext ctx) {
        webClient.post("/api/operators")
            .putHeader("Authorization", adminAuth())
            .sendJsonObject(new JsonObject()
                .put("operatorCode", "OP77").put("operatorName", "Seventy Seven")
                .put("licenseType", "MOBILE").put("reason", "seed"))
            .compose(resp -> {
                ctx.verify(() -> {
                    assertEquals(200, resp.statusCode());
                    assertEquals("OP77", resp.bodyAsJsonObject().getString("operatorCode"));
                });
                return webClient.get("/api/operators/OP77")
                    .putHeader("Authorization", adminAuth())
                    .send();
            })
            .compose(resp -> {
                ctx.verify(() -> assertEquals("Seventy Seven", resp.bodyAsJsonObject().getString("operatorName")));
                return pool.preparedQuery(
                    "SELECT COUNT(*) AS c FROM np_audit_events WHERE action = 'OPERATOR_CREATED' AND entity_id = 'OP77'")
                    .execute();
            })
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertEquals(1L, rows.iterator().next().getLong("c"));
                ctx.completeNow();
            })));
    }

    @Test
    void createDuplicateReturnsConflict(VertxTestContext ctx) {
        JsonObject body = new JsonObject()
            .put("operatorCode", "OP77").put("operatorName", "Seventy Seven")
            .put("licenseType", "MOBILE").put("reason", "seed");
        webClient.post("/api/operators")
            .putHeader("Authorization", adminAuth())
            .sendJsonObject(body)
            .compose(resp -> {
                ctx.verify(() -> assertEquals(200, resp.statusCode()));
                return webClient.post("/api/operators")
                    .putHeader("Authorization", adminAuth())
                    .sendJsonObject(body);
            })
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(409, resp.statusCode());
                ctx.completeNow();
            })));
    }

    @Test
    void updateOperatorChangesFields(VertxTestContext ctx) {
        webClient.post("/api/operators")
            .putHeader("Authorization", adminAuth())
            .sendJsonObject(new JsonObject()
                .put("operatorCode", "OP77").put("operatorName", "Seventy Seven")
                .put("licenseType", "MOBILE").put("reason", "seed"))
            .compose(resp -> {
                ctx.verify(() -> assertEquals(200, resp.statusCode(), resp.bodyAsString()));
                return webClient.put("/api/operators/OP77")
                    .putHeader("Authorization", adminAuth())
                    .sendJsonObject(new JsonObject()
                        .put("operatorName", "Renamed").put("isActive", false).put("reason", "rename"));
            })
            .compose(resp -> {
                ctx.verify(() -> assertEquals(200, resp.statusCode(), resp.bodyAsString()));
                return webClient.get("/api/operators/OP77")
                    .putHeader("Authorization", adminAuth())
                    .send();
            })
            .compose(resp -> {
                ctx.verify(() -> {
                    JsonObject body = resp.bodyAsJsonObject();
                    assertEquals("Renamed", body.getString("operatorName"));
                    assertEquals(false, body.getBoolean("isActive"));
                });
                return pool.preparedQuery(
                    "SELECT COUNT(*) AS c FROM np_audit_events WHERE action = 'OPERATOR_UPDATED' AND entity_id = 'OP77'")
                    .execute();
            })
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertEquals(1L, rows.iterator().next().getLong("c"));
                ctx.completeNow();
            })));
    }

    @Test
    void updateMissingReturns404(VertxTestContext ctx) {
        webClient.put("/api/operators/NOPE")
            .putHeader("Authorization", adminAuth())
            .sendJsonObject(new JsonObject().put("operatorName", "X").put("reason", "rename"))
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(404, resp.statusCode());
                ctx.completeNow();
            })));
    }

    @Test
    void getMessagePairLinksResponseToRequest(VertxTestContext ctx) {
        seedMessage("Check", "INBOUND", "REQ-PAIR", "TX-PAIR-1", null)
            .compose(v -> seedMessage("Check_response", "INBOUND", "REQ-PAIR", "TX-PAIR-2", "TX-PAIR-1"))
            .compose(responseUuid -> webClient.get("/api/messages/" + responseUuid + "/pair")
                .putHeader("Authorization", adminAuth())
                .send())
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(200, resp.statusCode());
                JsonObject body = resp.bodyAsJsonObject();
                assertEquals("Check", body.getJsonObject("request").getString("messageType"));
                assertEquals("TX-PAIR-1", body.getJsonObject("request").getString("transactionId"));
                assertEquals("Check_response", body.getJsonObject("response").getString("messageType"));
                ctx.completeNow();
            })));
    }

    @Test
    void searchMessagesFiltersByDirectionAndType(VertxTestContext ctx) {
        seedMessage("Check", "INBOUND", "REQ-F1", "TX-F1", null)
            .compose(v -> seedMessage("Publish", "OUTBOUND", "REQ-F2", "TX-F2", null))
            .compose(v -> webClient.get("/api/messages?direction=INBOUND")
                .putHeader("Authorization", adminAuth())
                .send())
            .compose(resp -> {
                ctx.verify(() -> {
                    JsonArray body = resp.bodyAsJsonArray();
                    assertEquals(1, body.size());
                    assertEquals("Check", body.getJsonObject(0).getString("messageType"));
                });
                return webClient.get("/api/messages?messageType=Publish")
                    .putHeader("Authorization", adminAuth())
                    .send();
            })
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                JsonArray body = resp.bodyAsJsonArray();
                assertEquals(1, body.size());
                assertEquals("OUTBOUND", body.getJsonObject(0).getString("direction"));
                ctx.completeNow();
            })));
    }

    @Test
    void prefixSearchFindsNumbers(VertxTestContext ctx) {
        Future<Void> seed = pool.preparedQuery("""
                INSERT INTO np_numbers (phone_number, number_type, status) VALUES (?, 'FIXED', 'PORTED')
                """)
            .execute(Tuple.of("08XXXXXXX"))
            .compose(v -> pool.preparedQuery("""
                INSERT INTO np_numbers (phone_number, number_type, status) VALUES (?, 'FIXED', 'PORTED')
                """)
            .execute(Tuple.of("086123456")))
            .mapEmpty();

        seed
            .compose(v -> webClient.get("/api/numbers?prefix=08")
                .putHeader("Authorization", adminAuth())
                .send())
            .compose(resp -> {
                ctx.verify(() -> {
                    JsonArray body = resp.bodyAsJsonArray();
                    assertEquals(2, body.size());
                });
                return webClient.get("/api/numbers?prefix=0861")
                    .putHeader("Authorization", adminAuth())
                    .send();
            })
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                JsonArray body = resp.bodyAsJsonArray();
                assertEquals(1, body.size());
                assertEquals("086123456", body.getJsonObject(0).getString("phoneNumber"));
                ctx.completeNow();
            })));
    }

    @Test
    void endpointsCrud(VertxTestContext ctx) {
        JsonObject endpoint = new JsonObject()
            .put("endpointType", "NPG_INBOUND")
            .put("endpointUrl", "https://op01.example.com/np/inbound");
        webClient.post("/api/operators/OP01/endpoints")
            .putHeader("Authorization", adminAuth())
            .sendJsonObject(endpoint)
            .compose(resp -> {
                ctx.verify(() -> {
                    assertEquals(200, resp.statusCode(), resp.bodyAsString());
                    JsonObject body = resp.bodyAsJsonObject();
                    assertEquals("NPG_INBOUND", body.getString("endpointType"));
                    assertEquals("https://op01.example.com/np/inbound", body.getString("endpointUrl"));
                    assertEquals("4.0", body.getString("protocolVersion"));
                    assertEquals("MTLS", body.getString("authMode"));
                });
                return webClient.get("/api/operators/OP01/endpoints")
                    .putHeader("Authorization", adminAuth())
                    .send();
            })
            .compose(resp -> {
                ctx.verify(() -> {
                    JsonArray body = resp.bodyAsJsonArray();
                    assertEquals(1, body.size());
                    assertEquals("https://op01.example.com/np/inbound",
                        body.getJsonObject(0).getString("endpointUrl"));
                });
                return webClient.get("/api/operators/OP01/endpoints")
                    .putHeader("Authorization", adminAuth())
                    .send()
                    .compose(r -> webClient.delete("/api/operators/OP01/endpoints/"
                            + r.bodyAsJsonArray().getJsonObject(0).getLong("id"))
                        .putHeader("Authorization", adminAuth())
                        .send());
            })
            .compose(resp -> {
                ctx.verify(() -> assertEquals(200, resp.statusCode(), resp.bodyAsString()));
                return webClient.get("/api/operators/OP01/endpoints")
                    .putHeader("Authorization", adminAuth())
                    .send();
            })
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(0, resp.bodyAsJsonArray().size());
                ctx.completeNow();
            })));
    }

    @Test
    void createEndpointRequiresOperator(VertxTestContext ctx) {
        webClient.post("/api/operators/NOPE/endpoints")
            .putHeader("Authorization", adminAuth())
            .sendJsonObject(new JsonObject()
                .put("endpointType", "NPG_INBOUND")
                .put("endpointUrl", "https://x.example.com/np/inbound"))
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(404, resp.statusCode());
                ctx.completeNow();
            })));
    }

    @Test
    void createEndpointValidatesUrl(VertxTestContext ctx) {
        webClient.post("/api/operators/OP01/endpoints")
            .putHeader("Authorization", adminAuth())
            .sendJsonObject(new JsonObject()
                .put("endpointType", "NPG_INBOUND")
                .put("endpointUrl", "ftp://x.example.com"))
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(400, resp.statusCode());
                ctx.completeNow();
            })));
    }

    @Test
    void localFlagIsExclusive(VertxTestContext ctx) {
        webClient.post("/api/operators")
            .putHeader("Authorization", adminAuth())
            .sendJsonObject(new JsonObject()
                .put("operatorCode", "LOCAL-A").put("operatorName", "Local A").put("isLocal", true))
            .compose(resp -> {
                ctx.verify(() -> assertEquals(200, resp.statusCode(), resp.bodyAsString()));
                return webClient.post("/api/operators")
                    .putHeader("Authorization", adminAuth())
                    .sendJsonObject(new JsonObject()
                        .put("operatorCode", "LOCAL-B").put("operatorName", "Local B").put("isLocal", true));
            })
            .compose(resp -> {
                ctx.verify(() -> assertEquals(200, resp.statusCode(), resp.bodyAsString()));
                return webClient.get("/api/operators/LOCAL-A")
                    .putHeader("Authorization", adminAuth())
                    .send();
            })
            .compose(resp -> {
                ctx.verify(() -> assertEquals(false, resp.bodyAsJsonObject().getBoolean("isLocal")));
                return webClient.get("/api/operators/LOCAL-B")
                    .putHeader("Authorization", adminAuth())
                    .send();
            })
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(true, resp.bodyAsJsonObject().getBoolean("isLocal"));
                ctx.completeNow();
            })));
    }

    @Test
    void loginIssuesToken(VertxTestContext ctx) {
        webClient.post("/api/auth/login")
            .sendJsonObject(new JsonObject().put("username", "admin").put("password", "admin123"))
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(200, resp.statusCode(), resp.bodyAsString());
                JsonObject body = resp.bodyAsJsonObject();
                assertNotNull(body.getString("token"));
                JsonObject user = body.getJsonObject("user");
                assertEquals("admin", user.getString("username"));
                assertTrue(user.getJsonArray("roles").contains("ADMIN"));
                ctx.completeNow();
            })));
    }

    @Test
    void loginRejectsBadPassword(VertxTestContext ctx) {
        webClient.post("/api/auth/login")
            .sendJsonObject(new JsonObject().put("username", "admin").put("password", "wrong"))
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(401, resp.statusCode());
                ctx.completeNow();
            })));
    }

    @Test
    void meReturnsUser(VertxTestContext ctx) {
        login("admin", "admin123")
            .compose(token -> webClient.get("/api/auth/me")
                .putHeader("Authorization", "Bearer " + token)
                .send())
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(200, resp.statusCode(), resp.bodyAsString());
                assertEquals("admin", resp.bodyAsJsonObject().getString("username"));
                assertTrue(resp.bodyAsJsonObject().getJsonArray("roles").contains("ADMIN"));
                ctx.completeNow();
            })));
    }

    @Test
    void unauthenticatedRequestRejected(VertxTestContext ctx) {
        webClient.get("/api/operators").send()
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(401, resp.statusCode());
                ctx.completeNow();
            })));
    }

    @Test
    void userRoleCanReadButNotWrite(VertxTestContext ctx) {
        seedUser("reader", "user123", "USER")
            .compose(v -> login("reader", "user123"))
            .compose(token -> webClient.get("/api/operators")
                .putHeader("Authorization", "Bearer " + token)
                .send()
                .compose(resp -> {
                    ctx.verify(() -> assertEquals(200, resp.statusCode(), resp.bodyAsString()));
                    return webClient.post("/api/operators")
                        .putHeader("Authorization", "Bearer " + token)
                        .sendJsonObject(new JsonObject()
                            .put("operatorCode", "OP88").put("operatorName", "Eighty Eight"));
                }))
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(403, resp.statusCode());
                ctx.completeNow();
            })));
    }

    @Test
    void managerCannotModifyLocalConfig(VertxTestContext ctx) {
        pool.query("UPDATE np_operators SET is_local = TRUE WHERE operator_code = 'OP01'").execute()
            .compose(v -> pool.query(
                "INSERT INTO np_operators (operator_code, operator_name) VALUES ('OP77', 'Peer')").execute())
            .compose(v -> seedUser("mgr", "manager123", "MANAGER"))
            .compose(v -> login("mgr", "manager123"))
            .compose(token -> webClient.put("/api/operators/OP01")
                .putHeader("Authorization", "Bearer " + token)
                .sendJsonObject(new JsonObject().put("operatorName", "Hacked").put("reason", "try"))
                .compose(resp -> {
                    ctx.verify(() -> assertEquals(403, resp.statusCode(), resp.bodyAsString()));
                    return webClient.put("/api/operators/OP77")
                        .putHeader("Authorization", "Bearer " + token)
                        .sendJsonObject(new JsonObject().put("operatorName", "Renamed").put("reason", "ok"));
                }))
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(200, resp.statusCode(), resp.bodyAsString());
                assertEquals("Renamed", resp.bodyAsJsonObject().getString("operatorName"));
                ctx.completeNow();
            })));
    }

    @Test
    void adminCanModifyLocalConfig(VertxTestContext ctx) {
        pool.query("UPDATE np_operators SET is_local = TRUE WHERE operator_code = 'OP01'").execute()
            .compose(v -> login("admin", "admin123"))
            .compose(token -> webClient.put("/api/operators/OP01")
                .putHeader("Authorization", "Bearer " + token)
                .sendJsonObject(new JsonObject().put("operatorName", "Local Renamed").put("reason", "admin")))
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(200, resp.statusCode(), resp.bodyAsString());
                assertEquals("Local Renamed", resp.bodyAsJsonObject().getString("operatorName"));
                ctx.completeNow();
            })));
    }

    @Test
    void usersCrudFlow(VertxTestContext ctx) {
        JsonObject create = new JsonObject()
            .put("username", "noc1").put("password", "password123")
            .put("roles", new JsonArray().add("USER")).put("displayName", "Noc One");
        webClient.post("/api/users")
            .putHeader("Authorization", adminAuth())
            .sendJsonObject(create)
            .compose(resp -> {
                ctx.verify(() -> {
                    assertEquals(200, resp.statusCode(), resp.bodyAsString());
                    JsonObject body = resp.bodyAsJsonObject();
                    assertEquals("noc1", body.getString("username"));
                    assertEquals("Noc One", body.getString("displayName"));
                    assertEquals(new JsonArray().add("USER"), body.getJsonArray("roles"));
                });
                return webClient.get("/api/users")
                    .putHeader("Authorization", adminAuth()).send();
            })
            .compose(resp -> {
                ctx.verify(() -> {
                    JsonObject noc1 = findUser(resp.bodyAsJsonArray(), "noc1");
                    assertNotNull(noc1);
                    assertEquals(new JsonArray().add("USER"), noc1.getJsonArray("roles"));
                    assertTrue(noc1.getBoolean("isActive"));
                });
                return webClient.put("/api/users/noc1")
                    .putHeader("Authorization", adminAuth())
                    .sendJsonObject(new JsonObject()
                        .put("roles", new JsonArray().add("MANAGER")).put("isActive", false));
            })
            .compose(resp -> {
                ctx.verify(() -> assertEquals(200, resp.statusCode(), resp.bodyAsString()));
                return webClient.get("/api/users")
                    .putHeader("Authorization", adminAuth()).send();
            })
            .compose(resp -> {
                ctx.verify(() -> {
                    JsonObject noc1 = findUser(resp.bodyAsJsonArray(), "noc1");
                    assertEquals(new JsonArray().add("MANAGER"), noc1.getJsonArray("roles"));
                    assertFalse(noc1.getBoolean("isActive"));
                });
                return pool.preparedQuery("""
                        SELECT action, COUNT(*) AS c FROM np_audit_events
                        WHERE entity_type = 'USER' AND entity_id = 'noc1'
                          AND action IN ('USER_CREATED', 'USER_UPDATED')
                        GROUP BY action
                        """).execute();
            })
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                java.util.Map<String, Long> counts = new java.util.HashMap<>();
                rows.forEach(row -> counts.put(row.getString("action"), row.getLong("c")));
                assertEquals(1L, counts.get("USER_CREATED"));
                assertEquals(1L, counts.get("USER_UPDATED"));
                ctx.completeNow();
            })));
    }

    @Test
    void createUserValidation(VertxTestContext ctx) {
        webClient.post("/api/users")
            .putHeader("Authorization", adminAuth())
            .sendJsonObject(new JsonObject().put("username", "noc2").put("password", "password123"))
            .compose(resp -> {
                ctx.verify(() -> assertEquals(400, resp.statusCode(), resp.bodyAsString()));
                return webClient.post("/api/users")
                    .putHeader("Authorization", adminAuth())
                    .sendJsonObject(new JsonObject().put("username", "noc2").put("password", "short")
                        .put("roles", new JsonArray().add("USER")));
            })
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(400, resp.statusCode(), resp.bodyAsString());
                ctx.completeNow();
            })));
    }

    @Test
    void createDuplicateUser(VertxTestContext ctx) {
        JsonObject body = new JsonObject()
            .put("username", "noc3").put("password", "password123")
            .put("roles", new JsonArray().add("USER"));
        webClient.post("/api/users")
            .putHeader("Authorization", adminAuth())
            .sendJsonObject(body)
            .compose(resp -> {
                ctx.verify(() -> assertEquals(200, resp.statusCode(), resp.bodyAsString()));
                return webClient.post("/api/users")
                    .putHeader("Authorization", adminAuth())
                    .sendJsonObject(body);
            })
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(409, resp.statusCode(), resp.bodyAsString());
                ctx.completeNow();
            })));
    }

    @Test
    void userManagementRequiresAdmin(VertxTestContext ctx) {
        seedUser("manager1", "manager123", "MANAGER")
            .compose(v -> login("manager1", "manager123"))
            .compose(token -> webClient.get("/api/users")
                .putHeader("Authorization", "Bearer " + token).send())
            .compose(resp -> {
                ctx.verify(() -> assertEquals(403, resp.statusCode(), resp.bodyAsString()));
                return seedUser("user1", "user12345", "USER")
                    .compose(v -> login("user1", "user12345"));
            })
            .compose(token -> webClient.get("/api/users")
                .putHeader("Authorization", "Bearer " + token).send())
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(403, resp.statusCode(), resp.bodyAsString());
                ctx.completeNow();
            })));
    }

    @Test
    void localSettingsReturnsPublicBaseUrl(VertxTestContext ctx) {
        webClient.get("/api/settings/local")
            .putHeader("Authorization", adminAuth())
            .send()
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(200, resp.statusCode(), resp.bodyAsString());
                assertTrue(resp.bodyAsJsonObject().containsKey("publicBaseUrl"));
                ctx.completeNow();
            })));
    }

    @Test
    void legacyRoleHeaderNoLongerAuthenticates(VertxTestContext ctx) {
        webClient.post("/api/operators")
            .putHeader("X-User-Role", "ADMIN")
            .putHeader("X-User-Id", "attacker")
            .sendJsonObject(new JsonObject().put("operatorCode", "EVIL").put("operatorName", "Evil"))
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(401, resp.statusCode());
                ctx.completeNow();
            })));
    }

    @Test
    void tokenSignedWithOtherSecretRejected(VertxTestContext ctx) {
        String forged = new JwtService("attacker-chosen-secret-attacker-chosen")
            .issue(TEST_ADMIN, java.util.List.of("ADMIN"), Duration.ofMinutes(5));
        webClient.get("/api/operators")
            .putHeader("Authorization", "Bearer " + forged)
            .send()
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(401, resp.statusCode());
                ctx.completeNow();
            })));
    }

    @Test
    void unsignedAlgNoneTokenRejected(VertxTestContext ctx) {
        java.util.Base64.Encoder b64 = java.util.Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes());
        String payload = b64.encodeToString(new JsonObject().put("sub", TEST_ADMIN)
            .put("roles", new JsonArray().add("ADMIN"))
            .put("exp", System.currentTimeMillis() / 1000 + 600).encode().getBytes());
        webClient.get("/api/operators")
            .putHeader("Authorization", "Bearer " + header + "." + payload + ".")
            .send()
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(401, resp.statusCode());
                ctx.completeNow();
            })));
    }

    @Test
    void deactivatedUserTokenStopsWorkingImmediately(VertxTestContext ctx) {
        seedUser("leaver", "leaver-password", "MANAGER")
            .compose(v -> login("leaver", "leaver-password"))
            .compose(token -> pool.query("UPDATE np_users SET is_active = FALSE WHERE username = 'leaver'").execute()
                .compose(v -> webClient.get("/api/operators").putHeader("Authorization", "Bearer " + token).send()))
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(401, resp.statusCode());
                ctx.completeNow();
            })));
    }

    @Test
    void revokedRoleTakesEffectBeforeTokenExpiry(VertxTestContext ctx) {
        // token still claims ADMIN, but the database now says USER only
        seedUser("demoted", "demoted-password", "USER")
            .compose(v -> webClient.post("/api/operators")
                .putHeader("Authorization", bearer("demoted", "ADMIN"))
                .sendJsonObject(new JsonObject().put("operatorCode", "OP66").put("operatorName", "Six")))
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(403, resp.statusCode());
                ctx.completeNow();
            })));
    }

    @Test
    void managerCannotCreateLocalOperator(VertxTestContext ctx) {
        seedUser("mgr2", "manager-password", "MANAGER")
            .compose(v -> login("mgr2", "manager-password"))
            .compose(token -> webClient.post("/api/operators")
                .putHeader("Authorization", "Bearer " + token)
                .sendJsonObject(new JsonObject().put("operatorCode", "OWN").put("operatorName", "Takeover")
                    .put("isLocal", true)))
            .compose(resp -> {
                ctx.verify(() -> assertEquals(403, resp.statusCode(), resp.bodyAsString()));
                return pool.query("SELECT COUNT(*) AS c FROM np_operators WHERE operator_code = 'OWN'").execute();
            })
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertEquals(0L, rows.iterator().next().getLong("c"));
                ctx.completeNow();
            })));
    }

    @Test
    void malformedJsonBodyIsBadRequest(VertxTestContext ctx) {
        webClient.post("/api/operators")
            .putHeader("Authorization", adminAuth())
            .putHeader("Content-Type", "application/json")
            .sendBuffer(io.vertx.core.buffer.Buffer.buffer("{not json"))
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(400, resp.statusCode(), resp.bodyAsString());
                ctx.completeNow();
            })));
    }

    @Test
    void nonNumericPagingIsBadRequest(VertxTestContext ctx) {
        webClient.get("/api/workflows?page=abc")
            .putHeader("Authorization", adminAuth())
            .send()
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(400, resp.statusCode(), resp.bodyAsString());
                ctx.completeNow();
            })));
    }

    @Test
    void repeatedBadLoginsAreThrottled(VertxTestContext ctx) {
        // dedicated account: the throttle is shared by every test in this class
        JsonObject bad = new JsonObject().put("username", "bruteforced").put("password", "wrong");
        Future<Integer> chain = seedUser("bruteforced", "correct-password", "USER").map(0);
        for (int i = 0; i < 10; i++) { // default per-account limit
            chain = chain.compose(x -> webClient.post("/api/auth/login").sendJsonObject(bad).map(r -> r.statusCode()));
        }
        chain.compose(last -> webClient.post("/api/auth/login")
                .sendJsonObject(new JsonObject().put("username", "bruteforced").put("password", "correct-password")))
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                // locked even with the right password until the window passes
                assertEquals(429, resp.statusCode(), resp.bodyAsString());
                ctx.completeNow();
            })));
    }

    @Test
    void throttleCannotBeBypassedWithCaseOrWhitespaceVariants(VertxTestContext ctx) {
        // all resolve to the same account under utf8mb4_unicode_ci (case, trailing space, accents)
        String[] variants = {"Sprayed", "SPRAYED", "sprayed ", "spräyed", "SPRÄYED"};
        Future<Integer> chain = seedUser("sprayed", "correct-password", "USER").map(0);
        for (String variant : java.util.stream.Stream.of(variants, variants).flatMap(java.util.Arrays::stream).toList()) {
            chain = chain.compose(x -> webClient.post("/api/auth/login")
                .sendJsonObject(new JsonObject().put("username", variant).put("password", "wrong"))
                .map(r -> r.statusCode()));
        }
        chain.compose(last -> webClient.post("/api/auth/login")
                .sendJsonObject(new JsonObject().put("username", "sprayed").put("password", "correct-password")))
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(429, resp.statusCode(), resp.bodyAsString());
                ctx.completeNow();
            })));
    }

    @Test
    void sprayingAnAccountDoesNotLockOutItsOwnersTrustedDevice(VertxTestContext ctx) {
        JwtService jwt = JwtService.fromEnvironment();
        cloud.cloudonix.npg.security.DeviceTrust trust =
            new cloud.cloudonix.npg.security.DeviceTrust(jwt, Duration.ofDays(30), java.time.Clock.systemUTC());
        AuthApiHandler handler = new AuthApiHandler(vertx, pool, jwt, trust,
            new cloud.cloudonix.npg.security.LoginThrottle(100, Duration.ofMinutes(5), java.time.Clock.systemUTC()),
            new cloud.cloudonix.npg.security.LoginThrottle(3, Duration.ofMinutes(15), java.time.Clock.systemUTC()),
            16);
        JsonObject good = new JsonObject().put("username", "owner").put("password", "owner-password");
        JsonObject bad = new JsonObject().put("username", "owner").put("password", "guess");
        java.util.concurrent.atomic.AtomicReference<String> ownerDevice = new java.util.concurrent.atomic.AtomicReference<>();
        seedUser("owner", "owner-password", "USER")
            .compose(v -> handler.login(good, "10.0.0.1", null))                 // owner's browser
            .onSuccess(r -> ownerDevice.set(r.deviceToken()))
            .compose(v -> handler.login(bad, "10.0.0.2", null).recover(e -> Future.succeededFuture()))
            .compose(v -> handler.login(bad, "10.0.0.3", null).recover(e -> Future.succeededFuture()))
            .compose(v -> handler.login(bad, "10.0.0.4", null).recover(e -> Future.succeededFuture()))
            .compose(v -> statusOf(handler.login(good, "10.0.0.5", null)))       // account limit reached
            .compose(status -> {
                ctx.verify(() -> assertEquals(429, status));
                return handler.login(good, "10.0.0.6", ownerDevice.get());         // trusted device still works
            })
            .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                assertNotNull(result.body().getString("token"));
                ctx.completeNow();
            })));
    }

    @Test
    void loginSetsHttpOnlyDeviceCookie(VertxTestContext ctx) {
        webClient.post("/api/auth/login")
            .sendJsonObject(new JsonObject().put("username", "admin").put("password", "admin123"))
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(200, resp.statusCode(), resp.bodyAsString());
                String cookie = resp.cookies().stream().filter(c -> c.startsWith("npg_device=")).findFirst().orElse("");
                String attributes = cookie.toLowerCase(java.util.Locale.ROOT); // attribute names are case-insensitive
                assertTrue(attributes.contains("httponly"), cookie);
                assertTrue(attributes.contains("samesite=strict"), cookie);
                assertTrue(attributes.contains("path=/api/auth"), cookie);
                ctx.completeNow();
            })));
    }

    private static Future<Integer> statusOf(Future<?> attempt) {
        return attempt.map(ok -> 200).recover(e -> Future.succeededFuture(((ApiError) e).status));
    }

    @Test
    void portInBatchSplitsIntoOneWorkflowPerNumber(VertxTestContext ctx) {
        pool.query("INSERT INTO np_operators (operator_code, operator_name, is_local) VALUES ('LOCX', 'Local', TRUE), ('DONX', 'Donor', FALSE)").execute()
            .compose(v -> webClient.post("/api/porting").putHeader("Authorization", adminAuth())
                .sendJsonObject(new JsonObject().put("phoneNumbers", new JsonArray().add("0812345670").add("0812345671"))
                    .put("numberType", "FIXED").put("donorOperatorCode", "DONX").put("identificationValue", "123456782")))
            .compose(resp -> {
                ctx.verify(() -> {
                    assertEquals(200, resp.statusCode(), resp.bodyAsString());
                    JsonArray results = resp.bodyAsJsonObject().getJsonArray("results");
                    assertEquals(2, results.size());
                    assertNotNull(results.getJsonObject(0).getString("workflowUuid"));
                });
                // the same number again while its port-in is open
                return webClient.post("/api/porting").putHeader("Authorization", adminAuth())
                    .sendJsonObject(new JsonObject().put("phoneNumber", "0812345670").put("numberType", "FIXED")
                        .put("donorOperatorCode", "DONX"));
            })
            .compose(resp -> {
                ctx.verify(() -> assertEquals(409, resp.statusCode(), resp.bodyAsString()));
                return pool.query("SELECT workflow_uuid FROM np_workflows WHERE phone_number = '0812345670'").execute();
            })
            .compose(rows -> webClient.post("/api/workflows/" + rows.iterator().next().getString("workflow_uuid") + "/cancel")
                .putHeader("Authorization", adminAuth()).sendJsonObject(new JsonObject().put("reason", "too early")))
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(409, resp.statusCode(), "cannot cancel before the Request is approved: " + resp.bodyAsString());
                ctx.completeNow();
            })));
    }

    @Test
    void portInValidatesInputAndRole(VertxTestContext ctx) {
        seedUser("viewer", "viewer-password", "USER")
            .compose(v -> webClient.post("/api/porting").putHeader("Authorization", bearer("viewer", "USER"))
                .sendJsonObject(new JsonObject().put("phoneNumber", "0812345670").put("numberType", "FIXED")
                    .put("donorOperatorCode", "OP01")))
            .compose(resp -> {
                ctx.verify(() -> assertEquals(403, resp.statusCode()));
                return webClient.post("/api/porting").putHeader("Authorization", adminAuth())
                    .sendJsonObject(new JsonObject().put("phoneNumber", "12345").put("numberType", "FIXED")
                        .put("donorOperatorCode", "OP01"));
            })
            .compose(resp -> {
                ctx.verify(() -> assertEquals(400, resp.statusCode(), resp.bodyAsString()));
                return webClient.post("/api/porting").putHeader("Authorization", adminAuth())
                    .sendJsonObject(new JsonObject().put("phoneNumber", "0812345670").put("numberType", "SATELLITE")
                        .put("donorOperatorCode", "OP01"));
            })
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(400, resp.statusCode(), resp.bodyAsString());
                ctx.completeNow();
            })));
    }

    @Test
    void unknownDeliveryCanBeConfirmedManuallyAndIsAudited(VertxTestContext ctx) {
        String outboxUuid = UUID.randomUUID().toString();
        seedMessage("Request", "OUTBOUND", "REQ-UD", "TX-UD", null)
            .compose(messageUuid -> pool.preparedQuery("""
                    INSERT INTO np_outbox (outbox_uuid, message_id, destination_operator_id, item_type, status, due_at)
                    SELECT ?, id, 1, 'Request', 'UNKNOWN_DELIVERY', UTC_TIMESTAMP(6) FROM np_messages WHERE message_uuid = ?
                    """).execute(Tuple.of(outboxUuid, messageUuid)))
            .compose(v -> webClient.post("/api/outbox/" + outboxUuid + "/mark-delivered").putHeader("Authorization", adminAuth())
                .sendJsonObject(new JsonObject()))
            .compose(resp -> {
                ctx.verify(() -> assertEquals(400, resp.statusCode(), "a reason is required"));
                return webClient.post("/api/outbox/" + outboxUuid + "/mark-delivered").putHeader("Authorization", adminAuth())
                    .sendJsonObject(new JsonObject().put("reason", "peer confirmed receipt by phone"));
            })
            .compose(resp -> {
                ctx.verify(() -> assertEquals(200, resp.statusCode(), resp.bodyAsString()));
                return webClient.post("/api/outbox/" + outboxUuid + "/mark-delivered").putHeader("Authorization", adminAuth())
                    .sendJsonObject(new JsonObject().put("reason", "again"));
            })
            .compose(resp -> {
                ctx.verify(() -> assertEquals(409, resp.statusCode(), "already delivered"));
                return pool.query("""
                    SELECT o.status, m.technical_status,
                      (SELECT COUNT(*) FROM np_audit_events a WHERE a.action = 'OUTBOX_MARKED_DELIVERED' AND a.actor_id = 'tester') AS audits
                    FROM np_outbox o JOIN np_messages m ON m.id = o.message_id""").execute();
            })
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                var row = rows.iterator().next();
                assertEquals("SUCCEEDED", row.getString("status"));
                assertEquals("DELIVERED", row.getString("technical_status"));
                assertEquals(1L, row.getLong("audits"));
                ctx.completeNow();
            })));
    }

    @Test
    void manualTaskLifecycleIsAudited(VertxTestContext ctx) {
        String taskUuid = UUID.randomUUID().toString();
        pool.preparedQuery("INSERT INTO np_manual_tasks (task_uuid, task_type, description) VALUES (?, 'TEST', 'check this')")
            .execute(Tuple.of(taskUuid))
            .compose(v -> webClient.post("/api/manual-tasks/" + taskUuid + "/assign").putHeader("Authorization", adminAuth())
                .sendJsonObject(new JsonObject().put("assignee", "noc-alice")))
            .compose(resp -> webClient.post("/api/manual-tasks/" + taskUuid + "/notes").putHeader("Authorization", adminAuth())
                .sendJsonObject(new JsonObject().put("note", "called the peer")))
            .compose(resp -> webClient.post("/api/manual-tasks/" + taskUuid + "/resolve").putHeader("Authorization", adminAuth())
                .sendJsonObject(new JsonObject().put("resolution", "peer re-sent")))
            .compose(resp -> {
                ctx.verify(() -> assertEquals(200, resp.statusCode(), resp.bodyAsString()));
                return webClient.get("/api/manual-tasks/" + taskUuid).putHeader("Authorization", adminAuth()).send();
            })
            .compose(resp -> {
                ctx.verify(() -> {
                    JsonObject task = resp.bodyAsJsonObject();
                    assertEquals("CLOSED", task.getString("status"));
                    assertEquals("noc-alice", task.getString("assignedTo"));
                    assertEquals(1, task.getJsonArray("notes").size());
                });
                return webClient.post("/api/manual-tasks/" + UUID.randomUUID() + "/resolve").putHeader("Authorization", adminAuth())
                    .sendJsonObject(new JsonObject().put("resolution", "x"));
            })
            .compose(resp -> {
                ctx.verify(() -> assertEquals(404, resp.statusCode()));
                return pool.query("SELECT COUNT(*) AS c FROM np_audit_events WHERE entity_type = 'MANUAL_TASK'").execute();
            })
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertEquals(3L, rows.iterator().next().getLong("c"), "assign, note and resolve");
                ctx.completeNow();
            })));
    }

    @Test
    void operatorAvailabilityChangesAreAudited(VertxTestContext ctx) {
        webClient.post("/api/operators/OP01/mark-down").putHeader("Authorization", adminAuth())
                .sendJsonObject(new JsonObject().put("reason", "peer called: maintenance"))
            .compose(resp -> {
                ctx.verify(() -> assertEquals(200, resp.statusCode(), resp.bodyAsString()));
                return webClient.post("/api/operators/OP01/mark-up").putHeader("Authorization", adminAuth())
                    .sendJsonObject(new JsonObject().put("reason", "back"));
            })
            .compose(resp -> {
                ctx.verify(() -> assertEquals(200, resp.statusCode(), resp.bodyAsString()));
                return webClient.post("/api/operators/NOPE/mark-down").putHeader("Authorization", adminAuth())
                    .sendJsonObject(new JsonObject().put("reason", "x"));
            })
            .compose(resp -> {
                ctx.verify(() -> assertEquals(404, resp.statusCode()));
                return pool.query("SELECT availability_status, (SELECT COUNT(*) FROM np_audit_events WHERE entity_id = 'OP01' "
                    + "AND action IN ('OPERATOR_MARKED_DOWN', 'OPERATOR_MARKED_UP')) AS audits FROM np_operators WHERE operator_code = 'OP01'").execute();
            })
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertEquals("UP", rows.iterator().next().getString("availability_status"));
                assertEquals(2L, rows.iterator().next().getLong("audits"));
                ctx.completeNow();
            })));
    }

    @Test
    void readOnlyUsersCannotOperateQueues(VertxTestContext ctx) {
        seedUser("reader2", "reader-password", "USER")
            .compose(v -> webClient.post("/api/outbox/" + UUID.randomUUID() + "/retry").putHeader("Authorization", bearer("reader2", "USER"))
                .sendJsonObject(new JsonObject().put("reason", "x")))
            .compose(resp -> {
                ctx.verify(() -> assertEquals(403, resp.statusCode()));
                return webClient.get("/api/outbox?status=MANUAL_REVIEW").putHeader("Authorization", bearer("reader2", "USER")).send();
            })
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(200, resp.statusCode(), "reading is allowed");
                ctx.completeNow();
            })));
    }

    @Test
    void numberRangesAreAdminManagedAndValidated(VertxTestContext ctx) {
        webClient.post("/api/number-ranges").putHeader("Authorization", adminAuth())
                .sendJsonObject(new JsonObject().put("operatorCode", "OP01").put("prefix", "0812").put("description", "block A"))
            .compose(resp -> {
                ctx.verify(() -> assertEquals(200, resp.statusCode(), resp.bodyAsString()));
                return webClient.post("/api/number-ranges").putHeader("Authorization", adminAuth())
                    .sendJsonObject(new JsonObject().put("operatorCode", "OP01").put("prefix", "0812"));
            })
            .compose(resp -> {
                ctx.verify(() -> assertEquals(409, resp.statusCode(), resp.bodyAsString()));
                return webClient.post("/api/number-ranges").putHeader("Authorization", adminAuth())
                    .sendJsonObject(new JsonObject().put("operatorCode", "OP01").put("prefix", "08%"));
            })
            .onComplete(ctx.succeeding(resp -> ctx.verify(() -> {
                assertEquals(400, resp.statusCode(), "wildcards are not prefixes");
                ctx.completeNow();
            })));
    }

    /** Real signed token for the seeded ADMIN user — the API re-checks the user in the DB. */
    private static String adminAuth() {
        return bearer(TEST_ADMIN, "ADMIN");
    }

    private static String bearer(String username, String... roles) {
        return "Bearer " + JwtService.fromEnvironment().issue(username, java.util.List.of(roles), Duration.ofMinutes(10));
    }

    private static JsonObject findUser(JsonArray users, String username) {
        for (int i = 0; i < users.size(); i++) {
            JsonObject user = users.getJsonObject(i);
            if (username.equals(user.getString("username"))) {
                return user;
            }
        }
        return null;
    }

    private Future<String> login(String username, String password) {
        return webClient.post("/api/auth/login")
            .sendJsonObject(new JsonObject().put("username", username).put("password", password))
            .compose(resp -> resp.statusCode() == 200
                ? Future.succeededFuture(resp.bodyAsJsonObject().getString("token"))
                : Future.failedFuture("login failed: " + resp.statusCode() + " " + resp.bodyAsString()));
    }

    private Future<Void> seedUser(String username, String rawPassword, String role) {
        return pool.preparedQuery(
                "INSERT INTO np_users (username, display_name, password_hash) VALUES (?, ?, ?)")
            .execute(Tuple.of(username, username, BCrypt.hashpw(rawPassword, BCrypt.gensalt())))
            .compose(v -> pool.preparedQuery("""
                    INSERT INTO np_user_roles (user_id, role_id)
                    SELECT u.id, r.id FROM np_users u JOIN np_roles r ON r.role_name = ?
                    WHERE u.username = ?
                    """)
                .execute(Tuple.of(role, username)))
            .mapEmpty();
    }

    private Future<String> seedMessage(String messageType, String direction, String requestId,
                                       String transactionId, String correlationId) {
        String uuid = UUID.randomUUID().toString();
        return pool.preparedQuery("""
                INSERT INTO np_messages (message_uuid, message_type, direction, request_id, transaction_id,
                                         correlation_id, idempotency_key, technical_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'RECEIVED')
                """)
            .execute(Tuple.of(uuid, messageType, direction, requestId, transactionId, correlationId,
                UUID.randomUUID().toString()))
            .map(uuid);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("No free port available", e);
        }
    }

    private Future<String> seedWorkflow(String requestId) {
        String uuid = UUID.randomUUID().toString();
        return pool.preparedQuery("""
                INSERT INTO np_workflows (workflow_uuid, workflow_type, request_id, phone_number, current_state)
                VALUES (?, 'PORTING', ?, '972501234567', 'NEW')
                """)
            .execute(Tuple.of(uuid, requestId))
            .map(uuid);
    }
}
