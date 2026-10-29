package cloud.cloudonix.npg.repository;

import io.vertx.core.Future;
import io.vertx.mysqlclient.MySQLClient;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

public class OperatorRepository {

    private final SqlClient sqlClient;

    public OperatorRepository(SqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    public Future<Long> insertOperator(String operatorCode, String operatorName, String licenseType) {
        return insertOperator(operatorCode, operatorName, licenseType, false);
    }

    public Future<Long> insertOperator(String operatorCode, String operatorName, String licenseType, boolean isLocal) {
        return sqlClient.preparedQuery("""
                INSERT INTO np_operators (operator_code, operator_name, license_type, is_local)
                VALUES (?, ?, ?, ?)
                """)
            .execute(Tuple.of(operatorCode, operatorName, licenseType, isLocal))
            .map(rs -> rs.property(MySQLClient.LAST_INSERTED_ID));
    }

    public Future<Long> insertAutoDiscovered(String operatorCode) {
        return sqlClient.preparedQuery("""
                INSERT INTO np_operators (operator_code, operator_name, requires_configuration)
                VALUES (?, ?, TRUE)
                """)
            .execute(Tuple.of(operatorCode, operatorCode))
            .map(rs -> rs.property(MySQLClient.LAST_INSERTED_ID));
    }

    public Future<RowSet<Row>> findByCode(String operatorCode) {
        return sqlClient.preparedQuery("SELECT * FROM np_operators WHERE operator_code = ?")
            .execute(Tuple.of(operatorCode));
    }

    public Future<RowSet<Row>> updateOperator(String operatorCode, String operatorName, String licenseType,
                                              String contactName, String contactPhone, String contactEmail,
                                              Boolean isActive) {
        // ponytail: Tuple.of rejects nulls; contact fields are nullable
        Tuple params = Tuple.tuple()
            .addString(operatorName).addString(licenseType).addString(contactName)
            .addString(contactPhone).addString(contactEmail).addBoolean(isActive)
            .addString(operatorCode);
        return sqlClient.preparedQuery("""
                UPDATE np_operators SET operator_name = ?, license_type = ?, contact_name = ?,
                                        contact_phone = ?, contact_email = ?, is_active = ?,
                                        requires_configuration = FALSE,
                                        updated_at = UTC_TIMESTAMP(6)
                WHERE operator_code = ?
                """)
            .execute(params);
    }
}
