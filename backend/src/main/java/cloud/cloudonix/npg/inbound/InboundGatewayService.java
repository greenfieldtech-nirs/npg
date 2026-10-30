package cloud.cloudonix.npg.inbound;

import cloud.cloudonix.npg.acknack.AckNackBuilder;
import cloud.cloudonix.npg.acknack.TechnicalAck;
import cloud.cloudonix.npg.audit.XmlAuditService;
import cloud.cloudonix.npg.audit.XmlTransportMetadata;
import cloud.cloudonix.npg.audit.XmlValidationMetadata;
import cloud.cloudonix.npg.protocol.ProtocolBody;
import cloud.cloudonix.npg.protocol.ProtocolCatalogLoader;
import cloud.cloudonix.npg.protocol.ProtocolHeader;
import cloud.cloudonix.npg.repository.AuditEventRepository;
import cloud.cloudonix.npg.repository.InboxRepository;
import cloud.cloudonix.npg.repository.MessageRepository;
import cloud.cloudonix.npg.repository.OperatorRepository;
import cloud.cloudonix.npg.xml.SecureXmlParser;
import cloud.cloudonix.npg.xml.XmlParseResult;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mysqlclient.MySQLException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

public class InboundGatewayService {

    private final Pool pool;
    private final MessageRepository messageRepository;
    private final InboxRepository inboxRepository;
    private final XmlAuditService xmlAuditService;
    private final ProtocolCatalogLoader catalogLoader;
    private final SecureXmlParser xmlParser;

    private final PeerAuthenticator peerAuthenticator;

    public InboundGatewayService(Pool pool, XmlAuditService xmlAuditService,
                                 ProtocolCatalogLoader catalogLoader) {
        this(pool, xmlAuditService, catalogLoader, PeerAuthenticator.fromEnvironment());
    }

    public InboundGatewayService(Pool pool, XmlAuditService xmlAuditService,
                                 ProtocolCatalogLoader catalogLoader, PeerAuthenticator peerAuthenticator) {
        this.peerAuthenticator = peerAuthenticator;
        this.pool = pool;
        this.messageRepository = new MessageRepository(pool);
        this.inboxRepository = new InboxRepository(pool);
        this.xmlAuditService = xmlAuditService;
        this.catalogLoader = catalogLoader;
        this.xmlParser = new SecureXmlParser();
    }

    public Future<TechnicalAck> receive(String rawXml, XmlTransportMetadata transport) {
        return receive(rawXml, transport, java.util.Map.of());
    }

    public Future<TechnicalAck> receive(String rawXml, XmlTransportMetadata transport,
                                        java.util.Map<String, String> transportHeaders) {
        XmlParseResult parseResult = xmlParser.parse(rawXml);
        if (!parseResult.success()) {
            return storeInvalidAndNack(null, rawXml, transport, "NACK_INVALID_XML", parseResult.errorMessage());
        }

        ProtocolHeader header = catalogLoader.extractHeader(parseResult.document(), transportHeaders);
        ProtocolBody body = catalogLoader.extractBody(parseResult.document(), header.messageType(), transportHeaders);

        Optional<JsonObject> messageCatalog = catalogLoader.findMessage(header.messageType());
        if (messageCatalog.isEmpty()) {
            return storeInvalidAndNack(header, rawXml, transport, "NACK_UNKNOWN_MESSAGE_TYPE",
                "Unknown message type: " + header.messageType());
        }

        if (header.senderOperator() == null || header.receiverOperator() == null || header.requestId() == null
            || header.transactionId() == null || header.messageType() == null) {
            return storeInvalidAndNack(header, rawXml, transport, "NACK_MISSING_HEADER",
                "Missing required header field");
        }
        // responses must say which request they answer (requestTrxNo), or they cannot be correlated
        if (catalogLoader.isResponse(header.messageType()) && (header.correlationId() == null || header.correlationId().isBlank())) {
            return storeInvalidAndNack(header, rawXml, transport, "NACK_MISSING_HEADER",
                header.messageType() + " must reference the request it answers (requestTrxNo)");
        }

        String idempotencyKey = header.buildIdempotencyKey();

        return peerAuthenticator.authorize(pool, header, transport).compose(decision -> {
            if (!decision.allowed()) {
                // an unauthenticated request changes no state: unknown codes are not registered (an admin
                // creates operators); the refused message itself is still stored and logged with its codes
                return storeInvalidAndNack(header, rawXml, transport, decision.nackCode(), decision.reason());
            }
            return acceptDurably(header, rawXml, transport, idempotencyKey)
                // two identical messages racing past the duplicate check: the unique key decides, and
                // the loser gets the same duplicate NACK instead of an internal error
                .recover(err -> isDuplicateKey(err)
                    ? Future.succeededFuture(duplicateNack(header))
                    : Future.failedFuture(err));
        });
    }

    private static TechnicalAck duplicateNack(ProtocolHeader header) {
        return AckNackBuilder.nack(header, "NACK_DUPLICATE_MESSAGE", null, "Duplicate idempotency key", "IDEMPOTENCY", true);
    }

    static boolean isDuplicateKey(Throwable err) {
        return err instanceof MySQLException mysql && mysql.getErrorCode() == 1062; // ER_DUP_ENTRY
    }

    private Future<TechnicalAck> acceptDurably(ProtocolHeader header, String rawXml, XmlTransportMetadata transport,
                                               String idempotencyKey) {
        return pool.withTransaction(tx -> {
            MessageRepository txMessageRepo = new MessageRepository(tx);
            InboxRepository txInboxRepo = new InboxRepository(tx);
            return txMessageRepo.findByIdempotencyKey(idempotencyKey)
                .compose(existing -> {
                    if (existing.size() > 0) {
                        return Future.succeededFuture(duplicateNack(header));
                    }
                    String messageUuid = UUID.randomUUID().toString();
                    return ensureOperatorsExist(tx, header)
                        .compose(operatorIds -> xmlAuditService.storeInboundRawXml(messageUuid, null, header.messageType(),
                            header.senderOperator(), header.receiverOperator(), header.requestId(),
                            header.transactionId(), header.correlationId(), header.protocolVersion(),
                            rawXml, transport)
                        .compose(mongoDocId -> txMessageRepo.insertMessage(messageUuid, header.messageType(),
                                "INBOUND", operatorIds.get(header.senderOperator()),
                                operatorIds.get(header.receiverOperator()), header.requestId(), header.transactionId(),
                                header.correlationId(), idempotencyKey, mongoDocId,
                                XmlAuditService.sha256(rawXml), header.protocolVersion()))
                        .compose(messageId -> txInboxRepo.createInboxItem(messageId, null, header.messageType(),
                                InboxRepository.orderingKey(operatorIds.get(header.senderOperator()), header.requestId()),
                                null, 100, 5)
                            .map(inboxId -> AckNackBuilder.ack(header))));
                });
        });
    }

    /**
     * Resolves both operator codes of an accepted (authenticated, or dev-permissive) message to ids,
     * registering unknown codes as "requires configuration" entries (audited) for the console.
     */
    private Future<java.util.Map<String, Long>> ensureOperatorsExist(SqlClient tx, ProtocolHeader header) {
        OperatorRepository operatorRepository = new OperatorRepository(tx);
        AuditEventRepository auditEvents = new AuditEventRepository(tx);
        java.util.Set<String> codes = new java.util.LinkedHashSet<>();
        if (header.senderOperator() != null && !header.senderOperator().isBlank()) {
            codes.add(header.senderOperator());
        }
        if (header.receiverOperator() != null && !header.receiverOperator().isBlank()) {
            codes.add(header.receiverOperator());
        }
        java.util.Map<String, Long> ids = new java.util.HashMap<>();
        Future<Void> chain = Future.succeededFuture();
        for (String code : codes) {
            chain = chain.compose(v -> operatorRepository.findByCode(code)
                .compose(rows -> {
                    if (rows.size() > 0) {
                        ids.put(code, rows.iterator().next().getLong("id"));
                        return Future.succeededFuture();
                    }
                    return operatorRepository.insertAutoDiscovered(code)
                        .compose(id -> {
                            ids.put(code, id);
                            return auditEvents.insertAuditEvent("SYSTEM", null,
                                "OPERATOR_AUTO_DISCOVERED", "OPERATOR", code,
                                new JsonObject().put("reason", "unknown operator code in inbound message")
                                    .put("messageType", header.messageType()).encode(), null);
                        })
                        .mapEmpty();
                }));
        }
        return chain.map(ids);
    }

    private Future<TechnicalAck> storeInvalidAndNack(ProtocolHeader header, String rawXml,
                                                     XmlTransportMetadata transport, String code, String message) {
        String messageUuid = UUID.randomUUID().toString();
        String messageType = header != null ? header.messageType() : "Unknown";
        String sender = header != null ? header.senderOperator() : null;
        String receiver = header != null ? header.receiverOperator() : null;
        return xmlAuditService.storeInvalidRawXml(messageUuid, "INBOUND", messageType, sender, receiver,
                "4.0", rawXml,
                XmlValidationMetadata.invalid(new JsonArray().add(new JsonObject().put("error", message))),
                transport)
            // record the rejected message too (docs/04), so it is visible in the console log;
            // it gets no inbox row, and a failure here must not turn the NACK into an error
            .compose(docId -> pool.preparedQuery("""
                    INSERT INTO np_messages (message_uuid, message_type, direction, request_id, transaction_id,
                                             idempotency_key, mongo_document_id, xml_sha256, technical_status,
                                             ack_status, nack_code, received_at)
                    VALUES (?, ?, 'INBOUND', ?, ?, ?, ?, ?, 'REJECTED', 'NACK', ?, UTC_TIMESTAMP(6))
                    """)
                .execute(io.vertx.sqlclient.Tuple.from(new Object[]{messageUuid, messageType,
                    header != null ? header.requestId() : null, header != null ? header.transactionId() : null,
                    "REJECTED|" + messageUuid, docId, XmlAuditService.sha256(rawXml), code}))
                .<Void>mapEmpty()
                .recover(err -> Future.succeededFuture()))
            .map(v -> AckNackBuilder.nack(header, code, null, message,
                code.equals("NACK_DUPLICATE_MESSAGE") ? "IDEMPOTENCY" : "TRANSPORT",
                code.equals("NACK_DUPLICATE_MESSAGE")));
    }
}
