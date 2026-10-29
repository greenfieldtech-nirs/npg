package cloud.cloudonix.npg.workflow;

import cloud.cloudonix.npg.audit.XmlAuditService;
import cloud.cloudonix.npg.protocol.ProtocolCatalogLoader;
import cloud.cloudonix.npg.worker.PermanentProcessingException;
import cloud.cloudonix.npg.xml.SecureXmlParser;
import cloud.cloudonix.npg.xml.XmlParseResult;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.Map;

/**
 * An accepted inbound message as a workflow step sees it: the np_messages row plus the body fields
 * parsed from the raw XML in the audit store (leaf elements flattened, e.g. number, approvalInd).
 */
public record InboundMessage(long messageId, String messageUuid, String messageType, String requestId,
                             String trxNo, String correlationTrxNo, Long senderOperatorId, Long receiverOperatorId,
                             JsonObject body) {

    public static Future<InboundMessage> load(SqlClient tx, XmlAuditService audit, ProtocolCatalogLoader catalog,
                                              Row inboxItem) {
        long messageId = inboxItem.getLong("message_id");
        return tx.preparedQuery("SELECT * FROM np_messages WHERE id = ?").execute(Tuple.of(messageId))
            .compose(rows -> {
                if (!rows.iterator().hasNext()) {
                    return Future.failedFuture(new PermanentProcessingException("Message " + messageId + " not found"));
                }
                Row m = rows.iterator().next();
                return audit.findByMessageUuid(m.getString("message_uuid")).compose(doc -> {
                    if (doc == null || doc.getString("rawXml") == null) {
                        return Future.failedFuture(new PermanentProcessingException(
                            "Raw XML for message " + m.getString("message_uuid") + " is missing from the audit store"));
                    }
                    XmlParseResult parsed = new SecureXmlParser().parse(doc.getString("rawXml"));
                    if (!parsed.success()) {
                        return Future.failedFuture(new PermanentProcessingException(
                            "Stored XML no longer parses: " + parsed.errorMessage()));
                    }
                    JsonObject body = catalog.extractBody(parsed.document(), m.getString("message_type"), Map.of()).toJson();
                    return Future.succeededFuture(new InboundMessage(messageId, m.getString("message_uuid"),
                        m.getString("message_type"), m.getString("request_id"), m.getString("transaction_id"),
                        m.getString("correlation_id"), m.getLong("sender_operator_id"), m.getLong("receiver_operator_id"),
                        body));
                });
            });
    }

    /** Positive business response? ({@code <positiveApproval><approvalInd>Y</approvalInd>}). */
    public boolean approved() {
        return "Y".equalsIgnoreCase(body.getString("approvalInd"));
    }
}
