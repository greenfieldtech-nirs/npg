package cloud.cloudonix.npg.repository;

import io.vertx.core.Future;
import io.vertx.mysqlclient.MySQLClient;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

public class MessageRepository {

    private final SqlClient sqlClient;

    public MessageRepository(SqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    public Future<Long> insertMessage(String messageUuid, String messageType, String direction,
                                        Long senderOperatorId, Long receiverOperatorId,
                                        String requestId, String transactionId, String correlationId,
                                        String idempotencyKey, String mongoDocumentId, String xmlSha256,
                                        String schemaVersion) {
        return sqlClient.preparedQuery("""
                INSERT INTO np_messages (message_uuid, message_type, direction, sender_operator_id,
                                         receiver_operator_id, request_id, transaction_id, correlation_id,
                                         idempotency_key, mongo_document_id, xml_sha256, schema_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
            .execute(Tuple.of(messageUuid, messageType, direction, senderOperatorId, receiverOperatorId,
                requestId, transactionId, correlationId, idempotencyKey, mongoDocumentId, xmlSha256, schemaVersion))
            .map(rs -> rs.property(MySQLClient.LAST_INSERTED_ID));
    }

    public Future<RowSet<Row>> findByIdempotencyKey(String idempotencyKey) {
        return sqlClient.preparedQuery("SELECT * FROM np_messages WHERE idempotency_key = ?")
            .execute(Tuple.of(idempotencyKey));
    }
}
