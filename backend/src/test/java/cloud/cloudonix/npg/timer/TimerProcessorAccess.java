package cloud.cloudonix.npg.timer;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;

import java.util.function.BiFunction;

/** Test bridge exposing {@link TimerProcessor} limits to tests in other packages. */
public final class TimerProcessorAccess {

    public static final int MAX_ATTEMPTS = TimerProcessor.MAX_ATTEMPTS;

    private final TimerProcessor processor;

    public TimerProcessorAccess(Pool pool, BiFunction<io.vertx.sqlclient.SqlConnection, Row, Future<Void>> action) {
        this.processor = new TimerProcessor(pool, action);
    }

    public Future<Void> fireAll(Iterable<Row> rows) {
        return processor.fireAll(rows);
    }
}
