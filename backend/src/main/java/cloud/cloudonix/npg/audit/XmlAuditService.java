package cloud.cloudonix.npg.audit;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.mongo.IndexOptions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

public class XmlAuditService {

    public static final String COLLECTION = "xml_messages";

    private final MongoClient mongoClient;

    public XmlAuditService(MongoClient mongoClient) {
        this.mongoClient = mongoClient;
    }

    public MongoClient mongoClient() {
        return mongoClient;
    }

    public Future<Void> ensureIndexes() {
        return mongoClient.createIndexWithOptions(COLLECTION, new JsonObject().put("messageUuid", 1),
                new IndexOptions().unique(true))
            .compose(v -> mongoClient.createIndex(COLLECTION, new JsonObject().put("workflowUuid", 1)))
            .compose(v -> mongoClient.createIndex(COLLECTION, new JsonObject().put("messageType", 1)))
            .compose(v -> mongoClient.createIndex(COLLECTION, new JsonObject().put("senderOperatorCode", 1)))
            .compose(v -> mongoClient.createIndex(COLLECTION, new JsonObject().put("receiverOperatorCode", 1)))
            .compose(v -> mongoClient.createIndex(COLLECTION, new JsonObject().put("requestId", 1)))
            .compose(v -> mongoClient.createIndex(COLLECTION, new JsonObject().put("transactionId", 1)))
            .compose(v -> mongoClient.createIndex(COLLECTION, new JsonObject().put("canonicalSha256", 1)))
            .compose(v -> mongoClient.createIndex(COLLECTION, new JsonObject().put("createdAt", 1)))
            .mapEmpty();
    }

    public Future<String> storeInboundRawXml(String messageUuid, String workflowUuid, String messageType,
                                              String senderOperatorCode, String receiverOperatorCode,
                                              String requestId, String transactionId, String correlationId,
                                              String schemaVersion, String rawXml,
                                              XmlTransportMetadata transport) {
        return storeRawXml(messageUuid, workflowUuid, "INBOUND", messageType, senderOperatorCode, receiverOperatorCode,
            requestId, transactionId, correlationId, schemaVersion, rawXml, XmlValidationMetadata.valid(), transport);
    }

    public Future<String> storeOutboundRawXml(String messageUuid, String workflowUuid, String messageType,
                                               String senderOperatorCode, String receiverOperatorCode,
                                               String requestId, String transactionId, String correlationId,
                                               String schemaVersion, String rawXml,
                                               XmlTransportMetadata transport) {
        return storeRawXml(messageUuid, workflowUuid, "OUTBOUND", messageType, senderOperatorCode, receiverOperatorCode,
            requestId, transactionId, correlationId, schemaVersion, rawXml, XmlValidationMetadata.valid(), transport);
    }

    public Future<String> storeInvalidRawXml(String messageUuid, String direction, String messageType,
                                              String senderOperatorCode, String receiverOperatorCode,
                                              String schemaVersion, String rawXml,
                                              XmlValidationMetadata validation,
                                              XmlTransportMetadata transport) {
        return storeRawXml(messageUuid, null, direction, messageType, senderOperatorCode, receiverOperatorCode,
            null, null, null, schemaVersion, rawXml, validation, transport);
    }

    private Future<String> storeRawXml(String messageUuid, String workflowUuid, String direction, String messageType,
                                       String senderOperatorCode, String receiverOperatorCode,
                                       String requestId, String transactionId, String correlationId,
                                       String schemaVersion, String rawXml,
                                       XmlValidationMetadata validation,
                                       XmlTransportMetadata transport) {
        String sha256 = sha256(rawXml);
        StoredXmlDocument doc = new StoredXmlDocument(
            messageUuid, workflowUuid, direction, messageType,
            senderOperatorCode, receiverOperatorCode,
            requestId, transactionId, correlationId,
            schemaVersion, rawXml, sha256, validation, transport, Instant.now()
        );
        return mongoClient.insert(COLLECTION, doc.toJson());
    }

    public Future<JsonObject> findByMessageUuid(String messageUuid) {
        return mongoClient.findOne(COLLECTION, new JsonObject().put("messageUuid", messageUuid), new JsonObject());
    }

    public Future<Boolean> verifyHash(String mongoDocumentId, String expectedSha256) {
        return mongoClient.findOne(COLLECTION, new JsonObject().put("_id", mongoDocumentId),
                new JsonObject().put("canonicalSha256", 1))
            .map(doc -> doc != null && expectedSha256.equals(doc.getString("canonicalSha256")));
    }

    public Future<String> updateValidationMetadata(String mongoDocumentId, XmlValidationMetadata validation) {
        return mongoClient.updateCollection(COLLECTION,
                new JsonObject().put("_id", mongoDocumentId),
                new JsonObject().put("$set", new JsonObject().put("validation", validation.toJson())))
            .map(v -> mongoDocumentId);
    }

    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
