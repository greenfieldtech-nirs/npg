package cloud.cloudonix.npg.outbound;

import cloud.cloudonix.npg.audit.XmlAuditService;
import cloud.cloudonix.npg.audit.XmlTransportMetadata;
import cloud.cloudonix.npg.config.Env;
import cloud.cloudonix.npg.domain.Operator;
import cloud.cloudonix.npg.protocol.ProtocolIds;
import cloud.cloudonix.npg.repository.MessageRepository;
import cloud.cloudonix.npg.repository.OutboxRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;

import java.util.UUID;

/**
 * Creates outbound protocol messages in two steps so they can share a transaction with the
 * workflow change that caused them (docs/04 outbound boundary):
 * <ol>
 *   <li>{@link #prepare} allocates the TRX_NO, builds the XML and stores it — raw XML plus the X-NP-*
 *       transport headers — in MongoDB <em>before</em> anything is queued;</li>
 *   <li>{@link #enqueue} inserts the np_messages and np_outbox rows inside the caller's transaction.</li>
 * </ol>
 * If the transaction rolls back, the Mongo document is an unreferenced audit record, never a send.
 */
public class OutboundMessageService {

    /** An outbound message whose raw XML is durably stored and ready to be queued. */
    public record Prepared(String messageUuid, Long workflowId, String messageType, Operator from, Operator to,
                           String requestId, String trxNo, String correlationTrxNo, String mongoDocumentId,
                           String xmlSha256) {
    }

    private final XmlAuditService xmlAuditService;
    private final OutboundMessageBuilder builder = new OutboundMessageBuilder();
    private final String versionNo;

    public OutboundMessageService(XmlAuditService xmlAuditService) {
        this.xmlAuditService = xmlAuditService;
        this.versionNo = Env.get("PROTOCOL_VERSION_NO", "1");
    }

    /**
     * @param correlationTrxNo for responses, the TRX_NO of the request being answered (also put in
     *                         the body as requestTrxNo by the caller); null for requests
     */
    public Future<Prepared> prepare(SqlClient client, Long workflowId, String messageType, String processType,
                                    Operator from, Operator to, String requestId, String correlationTrxNo,
                                    JsonObject body) {
        return ProtocolIds.nextTrxNo(client, from.operatorCode()).compose(trxNo -> {
            String messageUuid = UUID.randomUUID().toString();
            String rawXml = builder.build(messageType, body);
            JsonObject headers = new JsonObject()
                .put("X-NP-FROM", from.operatorCode())
                .put("X-NP-TO", to.operatorCode())
                .put("X-NP-MSG-TYPE", messageType)
                .put("X-NP-REQUEST-ID", requestId)
                .put("X-NP-TRX-NO", trxNo)
                .put("X-NP-VERSION-NO", versionNo)
                .put("X-NP-PROCESS-TYPE", processType);
            return xmlAuditService.storeOutboundRawXml(messageUuid, null, messageType, from.operatorCode(),
                    to.operatorCode(), requestId, trxNo, correlationTrxNo, versionNo, rawXml,
                    new XmlTransportMetadata("HTTPS", null, null, headers))
                .map(mongoId -> new Prepared(messageUuid, workflowId, messageType, from, to, requestId, trxNo,
                    correlationTrxNo, mongoId, XmlAuditService.sha256(rawXml)));
        });
    }

    /** Ids of a queued outbound message. */
    public record Queued(long messageId, long outboxId) {
    }

    /** Inserts the message and outbox rows; call inside the transaction that decided to send. */
    public Future<Queued> enqueue(SqlClient tx, Prepared p) {
        String idempotencyKey = String.join("|", "OUTBOUND", p.from().operatorCode(), p.to().operatorCode(),
            p.messageType(), p.requestId(), p.trxNo());
        return new MessageRepository(tx).insertMessage(p.messageUuid(), p.workflowId(), p.messageType(), "OUTBOUND",
                p.from().id(), p.to().id(), p.requestId(), p.trxNo(), p.correlationTrxNo(), idempotencyKey,
                p.mongoDocumentId(), p.xmlSha256(), versionNo)
            .compose(messageId -> new OutboxRepository(tx).createOutboxItem(messageId, p.workflowId(), p.to().id(),
                    p.messageType(), null, 100, 5)
                .map(outboxId -> new Queued(messageId, outboxId)));
    }

    /** Prepare and enqueue in one go, for callers with no surrounding transaction. */
    public Future<Queued> send(Pool pool, Long workflowId, String messageType, String processType, Operator from,
                             Operator to, String requestId, String correlationTrxNo, JsonObject body) {
        return prepare(pool, workflowId, messageType, processType, from, to, requestId, correlationTrxNo, body)
            .compose(prepared -> pool.withTransaction(tx -> enqueue(tx, prepared)));
    }
}
