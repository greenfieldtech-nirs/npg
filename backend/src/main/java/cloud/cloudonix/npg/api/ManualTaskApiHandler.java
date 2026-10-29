package cloud.cloudonix.npg.api;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;

public class ManualTaskApiHandler {

    private final Pool pool;

    public ManualTaskApiHandler(Pool pool) {
        this.pool = pool;
    }

    public Future<JsonArray> listTasks(String status) {
        String sql = "SELECT * FROM np_manual_tasks" + (status != null ? " WHERE status = ?" : "") + " ORDER BY created_at DESC";
        return pool.preparedQuery(sql)
            .execute(status != null ? Tuple.of(status) : Tuple.tuple())
            .map(RowJson::rowsToJsonArray);
    }

    public Future<Void> resolveTask(String taskUuid, String resolution) {
        return pool.preparedQuery("""
                UPDATE np_manual_tasks SET status = 'CLOSED', resolution = ?, closed_at = UTC_TIMESTAMP(6),
                                           updated_at = UTC_TIMESTAMP(6) WHERE task_uuid = ?
                """)
            .execute(Tuple.of(resolution, taskUuid))
            .mapEmpty();
    }
}
