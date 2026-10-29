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
        return insertMessage(messageUuid, null, messageType, direction, senderOperatorId, receiverOperatorId,
            requestId, transactionId, correlationId, idempotencyKey, mongoDocumentId, xmlSha256, schemaVersion);
    }

    public Future<Long> insertMessage(String messageUuid, Long workflowId, String messageType, String direction,
                                        Long senderOperatorId, Long receiverOperatorId,
                                        String requestId, String transactionId, String correlationId,
                                        String idempotencyKey, String mongoDocumentId, String xmlSha256,
                                        String schemaVersion) {
        String technicalStatus = "OUTBOUND".equals(direction) ? "QUEUED" : "RECEIVED";
        return sqlClient.preparedQuery("""
                INSERT INTO np_messages (message_uuid, workflow_id, message_type, direction, sender_operator_id,
                                         receiver_operator_id, request_id, transaction_id, correlation_id,
                                         idempotency_key, mongo_document_id, xml_sha256, schema_version,
                                         technical_status, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        IF(? = 'INBOUND', UTC_TIMESTAMP(6), NULL))
                """)
            .execute(Tuple.from(new Object[]{messageUuid, workflowId, messageType, direction, senderOperatorId,
                receiverOperatorId, requestId, transactionId, correlationId, idempotencyKey, mongoDocumentId,
                xmlSha256, schemaVersion, technicalStatus, direction}))
            .map(rs -> rs.property(MySQLClient.LAST_INSERTED_ID));
    }

    public Future<RowSet<Row>> findByIdempotencyKey(String idempotencyKey) {
        return sqlClient.preparedQuery("SELECT * FROM np_messages WHERE idempotency_key = ?")
            .execute(Tuple.of(idempotencyKey));
    }

    /** Records the technical outcome of an outbound delivery on the message row. */
    public Future<Void> recordDelivery(Long messageId, String ackStatus, String nackCode) {
        return sqlClient.preparedQuery("""
                UPDATE np_messages
                SET technical_status = ?, ack_status = ?, nack_code = ?, sent_at = UTC_TIMESTAMP(6)
                WHERE id = ?
                """)
            .execute(Tuple.of("ACK".equals(ackStatus) ? "DELIVERED" : "REJECTED", ackStatus, nackCode, messageId))
            .mapEmpty();
    }
}
