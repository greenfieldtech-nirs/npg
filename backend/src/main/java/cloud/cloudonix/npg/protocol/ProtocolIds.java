package cloud.cloudonix.npg.protocol;

import io.vertx.core.Future;
import io.vertx.mysqlclient.MySQLClient;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Generates protocol identifiers in the shape observed in the live operator trace:
 * <ul>
 *   <li>TRX_NO — sender operator code + 12-digit counter, e.g. {@code HL000163454039};</li>
 *   <li>REQUEST_ID — {@code NP} + recipient + donor + yyMMdd + 9-digit counter, e.g.
 *       {@code NPHLBZ260924417320100}.</li>
 * </ul>
 * The exact REQUEST_ID composition is inferred from the trace, not confirmed by the specification
 * (docs/protocol/open-questions.md). Counters live in np_id_sequences and are incremented atomically.
 */
public final class ProtocolIds {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyMMdd");
    private static final ZoneId ISRAEL = ZoneId.of("Asia/Jerusalem");

    private ProtocolIds() {
    }

    public static Future<String> nextTrxNo(SqlClient client, String senderCode) {
        return next(client, "TRX_NO").map(n -> senderCode + String.format("%012d", n % 1_000_000_000_000L));
    }

    public static Future<String> nextRequestId(SqlClient client, String recipientCode, String donorCode) {
        return next(client, "REQUEST_ID").map(n -> "NP" + recipientCode + donorCode
            + LocalDate.now(ISRAEL).format(DATE) + String.format("%09d", n % 1_000_000_000L));
    }

    private static Future<Long> next(SqlClient client, String sequence) {
        return client.preparedQuery(
                "UPDATE np_id_sequences SET next_value = LAST_INSERT_ID(next_value + 1) WHERE sequence_name = ?")
            .execute(Tuple.of(sequence))
            .compose(rs -> rs.rowCount() == 1
                ? Future.succeededFuture(rs.property(MySQLClient.LAST_INSERTED_ID))
                : Future.failedFuture("Missing id sequence " + sequence));
    }
}
