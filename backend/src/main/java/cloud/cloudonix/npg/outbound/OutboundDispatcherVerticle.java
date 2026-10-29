package cloud.cloudonix.npg.outbound;

import cloud.cloudonix.npg.audit.XmlAuditService;
import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.config.MongoConfig;
import cloud.cloudonix.npg.repository.OutboxRepository;
import cloud.cloudonix.npg.retry.RetryDecision;
import cloud.cloudonix.npg.retry.RetryPolicyService;
import cloud.cloudonix.npg.retry.RetryScheduler;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

public class OutboundDispatcherVerticle extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(OutboundDispatcherVerticle.class);

    private long periodicTimerId;

    @Override
    public void start(Promise<Void> startPromise) {
        Future<Pool> poolFuture = DatabaseConfig.configure(vertx);
        Future<MongoClient> mongoFuture = MongoConfig.configure(vertx);

        Future.all(poolFuture, mongoFuture)
            .onSuccess(result -> {
                Pool pool = poolFuture.result();
                MongoClient mongoClient = mongoFuture.result();
                XmlAuditService xmlAuditService = new XmlAuditService(mongoClient);
                OutboxRepository outboxRepository = new OutboxRepository(pool);
                RetryPolicyService retryPolicyService = new RetryPolicyService();
                RetryScheduler retryScheduler = new RetryScheduler(pool);
                TransportAdapter transportAdapter = new HttpXmlTransportAdapter(vertx);

                String workerId = System.getenv().getOrDefault("WORKER_ID", "dispatcher-" + System.currentTimeMillis());
                int batchSize = Integer.parseInt(System.getenv().getOrDefault("QUEUE_CLAIM_BATCH_SIZE", "10"));
                int lockSeconds = Integer.parseInt(System.getenv().getOrDefault("QUEUE_LOCK_SECONDS", "60"));

                periodicTimerId = vertx.setPeriodic(2000, id -> claimAndSend(pool, outboxRepository, xmlAuditService,
                    retryPolicyService, retryScheduler, transportAdapter, workerId, batchSize, lockSeconds));
                startPromise.complete();
            })
            .onFailure(startPromise::fail);
    }

    private void claimAndSend(Pool pool, OutboxRepository outboxRepository,
                              XmlAuditService xmlAuditService,
                              RetryPolicyService retryPolicyService, RetryScheduler retryScheduler,
                              TransportAdapter transportAdapter, String workerId, int batchSize, int lockSeconds) {
        outboxRepository.claimBatch(workerId, batchSize, lockSeconds)
            .onSuccess(rows -> {
                for (Row row : rows) {
                    processOne(pool, outboxRepository, xmlAuditService, retryPolicyService, retryScheduler,
                        transportAdapter, row);
                }
            })
            .onFailure(err -> log.error("Outbox claim failed", err));
    }

    private void processOne(Pool pool, OutboxRepository outboxRepository, XmlAuditService xmlAuditService,
                            RetryPolicyService retryPolicyService, RetryScheduler retryScheduler,
                            TransportAdapter transportAdapter, Row row) {
        Long outboxId = row.getLong("id");
        Long messageId = row.getLong("message_id");
        int attempts = row.getInteger("attempts");
        int maxAttempts = row.getInteger("max_attempts");

        outboxRepository.markSending(outboxId)
            .compose(v -> findMessageUuid(pool, messageId))
            .compose(messageUuid -> {
                if (messageUuid == null) {
                    return Future.failedFuture("Message not found for outbox " + outboxId);
                }
                return xmlAuditService.findByMessageUuid(messageUuid);
            })
            .compose(doc -> {
                if (doc == null) {
                    return Future.failedFuture("XML audit document not found for outbox " + outboxId);
                }
                Long destinationOperatorId = row.getLong("destination_operator_id");
                return resolveEndpoint(pool, destinationOperatorId)
                    .compose(endpointUrl -> {
                        if (endpointUrl == null) {
                            return retryScheduler.escalateToManualReview(outboxId,
                                    "No NPG_INBOUND endpoint configured for operator " + destinationOperatorId)
                                .map((OutboundResult) null);
                        }
                        return transportAdapter.send(endpointUrl, doc.getString("rawXml"));
                    });
            })
            .compose(result -> {
                if (result == null) {
                    return Future.succeededFuture(); // already escalated to manual review
                }
                if (result.success() && "ACK".equals(result.ackStatus())) {
                    return outboxRepository.markSent(outboxId).mapEmpty();
                }
                if ("NACK".equals(result.ackStatus())) {
                    return retryScheduler.escalateToManualReview(outboxId, "NACK: " + result.nackCode());
                }
                RetryDecision decision = retryPolicyService.decide(
                    row.getString("item_type"), null, attempts + 1, maxAttempts,
                    result.ackStatus(), null, false, result.errorCode(), false);
                if (decision.type() == RetryDecision.RetryDecisionType.RETRY_LATER) {
                    return retryScheduler.scheduleRetry(outboxId, decision.nextDueAt(),
                        result.errorCode(), result.errorMessage());
                }
                return retryScheduler.escalateToManualReview(outboxId, decision.reason());
            })
            .onFailure(err -> {
                log.error("Outbound dispatch failed for outbox " + outboxId, err);
                outboxRepository.markUnknownDelivery(outboxId)
                    .onFailure(e -> log.error("Failed to mark unknown delivery", e));
            });
    }

    // package-private static for direct testing of the resolution SQL
    static Future<String> resolveEndpoint(Pool pool, Long destinationOperatorId) {
        return pool.preparedQuery("""
                SELECT endpoint_url FROM np_operator_endpoints
                WHERE operator_id = ? AND endpoint_type = 'NPG_INBOUND' AND is_active = TRUE
                ORDER BY id ASC LIMIT 1
                """)
            .execute(Tuple.of(destinationOperatorId))
            .map(rows -> rows.iterator().hasNext() ? rows.iterator().next().getString("endpoint_url") : null);
    }

    private Future<String> findMessageUuid(Pool pool, Long messageId) {
        return pool.preparedQuery("SELECT message_uuid FROM np_messages WHERE id = ?")
            .execute(Tuple.of(messageId))
            .map(rows -> rows.iterator().hasNext() ? rows.iterator().next().getString("message_uuid") : null);
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        vertx.cancelTimer(periodicTimerId);
        stopPromise.complete();
    }
}
