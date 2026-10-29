package cloud.cloudonix.npg.repository;

import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.support.TestDatabase;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
class OperatorRepositoryTest {

    private static Pool pool;
    private static SqlClient sqlClient;
    private static OperatorRepository repository;

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
            .onComplete(ctx.succeeding(p -> {
                pool = p;
                sqlClient = p;
                repository = new OperatorRepository(p);
                ctx.completeNow();
            }));
    }

    @AfterAll
    static void tearDownClass(VertxTestContext ctx) {
        Future<Void> close = (pool != null) ? pool.close().mapEmpty() : Future.succeededFuture();
        close.onComplete(v -> {
            TestDatabase.stop();
            ctx.completeNow();
        });
    }

    @BeforeEach
    void cleanTable(VertxTestContext ctx) {
        sqlClient.query("SET FOREIGN_KEY_CHECKS = 0").execute()
            .compose(v -> sqlClient.query("TRUNCATE TABLE np_operators").execute())
            .compose(v -> sqlClient.query("SET FOREIGN_KEY_CHECKS = 1").execute())
            .onComplete(ctx.succeedingThenComplete());
    }

    @Test
    void shouldInsertAndFindOperator(VertxTestContext ctx) {
        repository.insertOperator("OP01", "Operator One", "MOBILE")
            .compose(id -> repository.findByCode("OP01"))
            .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                assertTrue(rows.iterator().hasNext());
                assertEquals("OP01", rows.iterator().next().getString("operator_code"));
                ctx.completeNow();
            })));
    }
}
