package cloud.cloudonix.npg.workflow;

import cloud.cloudonix.npg.domain.Operator;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.List;

/** Operator lookups for workflow steps, always on the caller's connection. */
public class OperatorDirectory {

    private static final String COLUMNS =
        "id, operator_code, operator_name, license_type, is_active, availability_status";

    /** This gateway's own operator identity (np_operators.is_local); required to send anything. */
    public Future<Operator> local(SqlClient tx) {
        return tx.query("SELECT " + COLUMNS + " FROM np_operators WHERE is_local = TRUE LIMIT 1").execute()
            .compose(rows -> rows.iterator().hasNext()
                ? Future.succeededFuture(map(rows.iterator().next()))
                : Future.failedFuture(new IllegalStateException(
                    "Local operator identity is not configured (Local Setup in the console)")));
    }

    public Future<Operator> byId(SqlClient tx, Long id) {
        if (id == null) {
            return Future.succeededFuture(null);
        }
        return tx.preparedQuery("SELECT " + COLUMNS + " FROM np_operators WHERE id = ?").execute(Tuple.of(id))
            .map(rows -> rows.iterator().hasNext() ? map(rows.iterator().next()) : null);
    }

    public Future<Operator> byCode(SqlClient tx, String code) {
        if (code == null) {
            return Future.succeededFuture(null);
        }
        return tx.preparedQuery("SELECT " + COLUMNS + " FROM np_operators WHERE operator_code = ?").execute(Tuple.of(code))
            .map(rows -> rows.iterator().hasNext() ? map(rows.iterator().next()) : null);
    }

    /** Every active, configured peer except the given ones: the audience of a publication or broadcast. */
    public Future<List<Operator>> peersExcept(SqlClient tx, Long... excludedIds) {
        return tx.query("SELECT " + COLUMNS + " FROM np_operators "
                + "WHERE is_local = FALSE AND is_active = TRUE AND requires_configuration = FALSE ORDER BY operator_code")
            .execute()
            .map(rows -> {
                List<Long> excluded = java.util.Arrays.asList(excludedIds);
                List<Operator> peers = new ArrayList<>();
                for (Row row : rows) {
                    if (!excluded.contains(row.getLong("id"))) {
                        peers.add(map(row));
                    }
                }
                return peers;
            });
    }

    private static Operator map(Row row) {
        return new Operator(row.getLong("id"), row.getString("operator_code"), row.getString("operator_name"),
            row.getString("license_type"), Boolean.TRUE.equals(row.getBoolean("is_active")),
            row.getString("availability_status"));
    }
}
