package cloud.cloudonix.npg.api;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;

public final class RowJson {

    private RowJson() {
    }

    public static JsonArray rowsToJsonArray(RowSet<Row> rows) {
        JsonArray array = new JsonArray();
        for (Row row : rows) {
            array.add(rowToJson(row));
        }
        return array;
    }

    public static JsonObject rowToJson(Row row) {
        JsonObject json = new JsonObject();
        for (int i = 0; i < row.size(); i++) {
            json.put(toCamelCase(row.getColumnName(i)), convert(row.getValue(i)));
        }
        return json;
    }

    private static Object convert(Object value) {
        if (value == null) {
            return null;
        }
        // ponytail: MySQL BOOLEAN/TINYINT(1) arrives as Byte; schema uses it only for booleans
        if (value instanceof Byte b) {
            return b != 0;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof String) {
            return value;
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    static String toCamelCase(String columnName) {
        StringBuilder sb = new StringBuilder(columnName.length());
        boolean upperNext = false;
        for (int i = 0; i < columnName.length(); i++) {
            char c = columnName.charAt(i);
            if (c == '_') {
                upperNext = true;
            } else if (upperNext) {
                sb.append(Character.toUpperCase(c));
                upperNext = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
