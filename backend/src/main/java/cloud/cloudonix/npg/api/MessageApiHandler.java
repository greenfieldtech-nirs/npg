package cloud.cloudonix.npg.api;

import cloud.cloudonix.npg.audit.XmlAuditService;
import cloud.cloudonix.npg.protocol.ProtocolCatalogLoader;
import cloud.cloudonix.npg.repository.AuditEventRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;

public class MessageApiHandler {

    private final Pool pool;
    private final XmlAuditService xmlAuditService;
    private final AuditEventRepository auditEventRepository;
    private final ProtocolCatalogLoader catalogLoader;

    public MessageApiHandler(Pool pool, XmlAuditService xmlAuditService, ProtocolCatalogLoader catalogLoader) {
        this.pool = pool;
        this.xmlAuditService = xmlAuditService;
        this.auditEventRepository = new AuditEventRepository(pool);
        this.catalogLoader = catalogLoader;
    }

    public Future<JsonArray> searchMessages(String requestId, String messageType, String direction,
                                            String technicalStatus, String dateFrom, String dateTo,
                                            int page, int size) {
        StringBuilder sql = new StringBuilder("SELECT * FROM np_messages WHERE 1=1");
        Tuple params = Tuple.tuple();
        if (requestId != null && !requestId.isBlank()) {
            sql.append(" AND request_id LIKE ?");
            params.addValue("%" + requestId + "%");
        }
        if (messageType != null && !messageType.isBlank()) {
            sql.append(" AND message_type = ?");
            params.addValue(messageType);
        }
        if (direction != null && !direction.isBlank()) {
            sql.append(" AND direction = ?");
            params.addValue(direction);
        }
        if (technicalStatus != null && !technicalStatus.isBlank()) {
            sql.append(" AND technical_status = ?");
            params.addValue(technicalStatus);
        }
        if (dateFrom != null && !dateFrom.isBlank()) {
            sql.append(" AND created_at >= ?");
            params.addValue(dateFrom + " 00:00:00");
        }
        if (dateTo != null && !dateTo.isBlank()) {
            sql.append(" AND created_at < ?");
            params.addValue(dateTo + " 23:59:59.999999");
        }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.addValue(size);
        params.addValue(page * size);
        return pool.preparedQuery(sql.toString())
            .execute(params)
            .map(RowJson::rowsToJsonArray);
    }

    public Future<JsonObject> getMessage(String messageUuid) {
        return pool.preparedQuery("SELECT * FROM np_messages WHERE message_uuid = ?")
            .execute(Tuple.of(messageUuid))
            .map(rows -> rows.iterator().hasNext() ? RowJson.rowToJson(rows.iterator().next()) : null);
    }

    public Future<JsonObject> getMessagePair(String messageUuid) {
        return getMessage(messageUuid)
            .compose(msg -> {
                if (msg == null) {
                    return Future.succeededFuture(null);
                }
                String transactionId = msg.getString("transactionId");
                String correlationId = msg.getString("correlationId");
                if (correlationId != null && !correlationId.isBlank()) {
                    // This message is a response; its request is the one with transaction_id = correlation_id.
                    return findByColumn("transaction_id", correlationId)
                        .compose(request -> enrichWithBodyFields(request)
                            .compose(enrichedRequest -> enrichWithBodyFields(msg)
                                .map(enrichedResponse -> pair(enrichedRequest, enrichedResponse))));
                }
                // This message is a request; its response is the one with correlation_id = transaction_id.
                return findByColumn("correlation_id", transactionId)
                    .compose(response -> enrichWithBodyFields(msg)
                        .compose(enrichedRequest -> enrichWithBodyFields(response)
                            .map(enrichedResponse -> pair(enrichedRequest, enrichedResponse))));
            });
    }

    // ponytail: extract phoneNumber/donor from the stored body XML and resolve the donor's
    // full name from np_operators; gracefully degrades to no enrichment when XML is absent
    private Future<JsonObject> enrichWithBodyFields(JsonObject msg) {
        if (msg == null) {
            return Future.succeededFuture(null);
        }
        String uuid = msg.getString("meessageUuid");
        if (uuid == null) {
            return Future.succeededFuture(msg);
        }
        return xmlAuditService.findByMessageUuid(uuid)
            .compose(doc -> {
                if (doc == null || doc.getString("rawXml") == null) {
                    return Future.succeededFuture(msg);
                }
                var parsed = new cloud.cloudonix.npg.xml.SecureXmlParser().parse(doc.getString("rawXml"));
                if (!parsed.success()) {
                    return Future.succeededFuture(msg);
                }
                JsonObject fields = catalogLoader.extractBody(parsed.document(),
                    msg.getString("meessageType"), java.util.Map.of()).toJson();
                JsonObject enriched = msg.copy();
                String phoneNumber = fields.getString("phoneNumber");
                String donor = fields.getString("donor");
                if (phoneNumber != null) {
                    enriched.put("phoneNumber", phoneNumber);
                }
                if (donor != null) {
                    enriched.put("donorOperatorCode", donor);
                }
                if (donor == null) {
                    return Future.succeededFuture(enriched);
                }
                return pool.preparedQuery("SELECT operator_name FROM np_operators WHERE operator_code = ?")
                    .execute(Tuple.of(donor))
                    .map(rows -> {
                        if (rows.iterator().hasNext()) {
                            enriched.put("donorOperatorName", rows.iterator().next().getString("operator_name"));
                        }
                        return enriched;
                    });
            });
    }

    private JsonObject pair(JsonObject request, JsonObject response) {
        return new JsonObject()
            .put("request", request)
            .put("response", response);
    }

    private Future<JsonObject> findByColumn(String column, String value) {
        return pool.preparedQuery("SELECT * FROM np_messages WHERE " + column + " = ? ORDER BY id ASC LIMIT 1")
            .execute(Tuple.of(value))
            .map(rows -> rows.iterator().hasNext() ? RowJson.rowToJson(rows.iterator().next()) : null);
    }

    public Future<String> getMessageXml(String messageUuid, String actorId, String remoteIp) {
        return xmlAuditService.findByMessageUuid(messageUuid)
            .compose(doc -> {
                if (doc == null) {
                    return Future.failedFuture(new ApiError(404, "No XML document stored for message " + messageUuid));
                }
                JsonObject details = new JsonObject().put("direction", doc.getString("direction"));
                return auditEventRepository.insertAuditEvent("USER", actorId, "XML_RETRIEVED",
                        "MESSAGE", messageUuid, details.encode(), remoteIp)
                    .map(v -> doc.getString("rawXml"));
            });
    }
}
