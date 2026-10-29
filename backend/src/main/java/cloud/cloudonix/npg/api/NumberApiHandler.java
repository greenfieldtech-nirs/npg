package cloud.cloudonix.npg.api;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;

public class NumberApiHandler {

    private final Pool pool;

    public NumberApiHandler(Pool pool) {
        this.pool = pool;
    }

    public Future<JsonObject> getNumber(String phoneNumber) {
        return pool.preparedQuery("""
                SELECT n.*, o.operator_code AS current_operator_code FROM np_numbers n
                LEFT JOIN np_operators o ON n.current_operator_id = o.id
                WHERE n.phone_number = ?
                """)
            .execute(Tuple.of(phoneNumber))
            .map(rows -> rows.iterator().hasNext() ? RowJson.rowToJson(rows.iterator().next()) : null);
    }

    public Future<JsonArray> searchByPrefix(String prefix, int size) {
        return pool.preparedQuery("""
                SELECT n.*, o.operator_code AS current_operator_code FROM np_numbers n
                LEFT JOIN np_operators o ON n.current_operator_id = o.id
                WHERE n.phone_number LIKE ?
                ORDER BY n.phone_number
                LIMIT ?
                """)
            .execute(Tuple.of(prefix + "%", size))
            .map(RowJson::rowsToJsonArray);
    }

    public Future<JsonArray> getNumberHistory(String phoneNumber) {
        return pool.preparedQuery("""
                SELECT h.*, po.operator_code AS previous_operator_code, no.operator_code AS new_operator_code
                FROM np_number_ownership_history h
                LEFT JOIN np_operators po ON h.previous_operator_id = po.id
                LEFT JOIN np_operators no ON h.new_operator_id = no.id
                WHERE h.phone_number = ?
                ORDER BY h.effective_at DESC
                """)
            .execute(Tuple.of(phoneNumber))
            .map(RowJson::rowsToJsonArray);
    }
}
