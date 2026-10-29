package cloud.cloudonix.npg.number;

import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

/** Native (range-holder) operator of a number, from np_number_ranges. */
public final class NumberRanges {

    private NumberRanges() {
    }

    /** National format: 972XXXXXXXX → 0XXXXXXXX; anything else unchanged. */
    public static String national(String phoneNumber) {
        if (phoneNumber == null) {
            return null;
        }
        String digits = phoneNumber.trim();
        return digits.startsWith("972") ? "0" + digits.substring(3) : digits;
    }

    /** Operator holding the longest prefix that matches the number, or null when no range matches. */
    public static Future<Long> nativeOperator(SqlClient tx, String phoneNumber) {
        String number = national(phoneNumber);
        if (number == null) {
            return Future.succeededFuture(null);
        }
        return tx.preparedQuery("""
                SELECT operator_id FROM np_number_ranges WHERE ? LIKE CONCAT(prefix, '%')
                ORDER BY CHAR_LENGTH(prefix) DESC LIMIT 1
                """)
            .execute(Tuple.of(number))
            .map(rows -> rows.iterator().hasNext() ? rows.iterator().next().getLong("operator_id") : null);
    }
}
