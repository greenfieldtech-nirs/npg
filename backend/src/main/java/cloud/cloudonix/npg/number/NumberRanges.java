package cloud.cloudonix.npg.number;

import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

/**
 * Native (range-holder) operator of a number: from the configured np_number_ranges, else from the
 * national numbering plan through each operator's numbering_plan_name.
 */
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

    /**
     * Operator holding the longest configured prefix that matches the number; without one, the operator
     * mapped to the plan range that holds it. Null when neither source knows the number.
     */
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
            .compose(rows -> rows.iterator().hasNext()
                ? Future.succeededFuture(rows.iterator().next().getLong("operator_id"))
                : planOperator(tx, number));
    }

    /**
     * The plan's most specific range decides the holder first, and only then is its name mapped to an
     * operator: an unmapped holder means "unknown", never a broader range's operator.
     */
    static Future<Long> planOperator(SqlClient tx, String nationalNumber) {
        if (!nationalNumber.matches("0\\d{8,9}")) {
            return Future.succeededFuture(null);
        }
        String nsn = nationalNumber.substring(1);
        return tx.preparedQuery("""
                SELECT o.id FROM (
                    SELECT allocated_to FROM np_numbering_plan
                    WHERE nsn_length = ? AND ? LIKE CONCAT(nsn_prefix, '%')
                    ORDER BY CHAR_LENGTH(nsn_prefix) DESC LIMIT 1
                ) holder JOIN np_operators o ON o.numbering_plan_name = holder.allocated_to
                """)
            .execute(Tuple.of(nsn.length(), nsn))
            .map(rows -> rows.iterator().hasNext() ? rows.iterator().next().getLong("id") : null);
    }
}
