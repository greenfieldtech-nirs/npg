package cloud.cloudonix.npg.outbound;

import cloud.cloudonix.npg.audit.XmlAuditService;
import cloud.cloudonix.npg.repository.ManualTaskRepository;
import cloud.cloudonix.npg.repository.OutboxRepository;
import cloud.cloudonix.npg.retry.RetryDecision;
import cloud.cloudonix.npg.retry.RetryPolicyService;
import cloud.cloudonix.npg.retry.UnknownDeliveryPolicy;
import io.vertx.core.Future;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.List;

/**
 * Sends claimed outbox rows and records the outcome. Every status change is fenced by the row's
 * claim token.
 *
 * <p>The crash-safety rule: a failure before the network call is an ordinary retry; once the call
 * has started, the row stays SENDING until the result is recorded. If the dispatcher dies in that
 * window the lock expires and {@link #recoverUnknownDeliveries} decides — re-send only message
 * types that tolerate duplicates, otherwise hold for manual review.
 */
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final Pool pool;
    private final OutboxRepository outbox;
    private final XmlAuditService xmlAuditService;
    private final TransportAdapter transport;
    private final RetryPolicyService retryPolicy;
    private final UnknownDeliveryPolicy unknownDeliveryPolicy;
    private final int lockSeconds;

    public OutboxDispatcher(Pool pool, XmlAuditService xmlAuditService, TransportAdapter transport,
                            RetryPolicyService retryPolicy, UnknownDeliveryPolicy unknownDeliveryPolicy,
                            int lockSeconds) {
        this.pool = pool;
        this.outbox = new OutboxRepository(pool);
        this.xmlAuditService = xmlAuditService;
        this.transport = transport;
        this.retryPolicy = retryPolicy;
        this.unknownDeliveryPolicy = unknownDeliveryPolicy;
        this.lockSeconds = lockSeconds;
    }

    public Future<Void> dispatchAll(Iterable<Row> rows) {
        List<Future<Void>> all = new ArrayList<>();
        for (Row row : rows) {
            all.add(dispatchOne(row).recover(err -> {
                log.error("Outbox item " + row.getLong("id") + " could not be settled", err);
                return Future.succeededFuture();
            }));
        }
        return Future.join(all).mapEmpty();
    }

    Future<Void> dispatchOne(Row row) {
        Long id = row.getLong("id");
        String token = row.getString("locked_by");
        return outbox.markSending(id, token, lockSeconds).compose(owned -> {
            if (!owned) {
                return Future.succeededFuture();
            }
            return prepare(row).transform(prepared -> {
                if (prepared.failed()) {
                    // nothing reached the network yet: an ordinary, safe retry
                    return retryOrEscalate(row, token, "PREPARE_FAILED", prepared.cause().getMessage());
                }
                Prepared send = prepared.result();
                if (send.endpointUrl() == null) {
                    return escalate(row, token, "NO_ENDPOINT",
                        "No active NPG_INBOUND endpoint configured for destination operator "
                            + row.getLong("destination_operator_id"));
                }
                // from here the peer may receive the message: failures recording the outcome leave
                // the row SENDING for the unknown-delivery sweep instead of guessing
                return transport.send(send.endpointUrl(), send.rawXml())
                    .compose(result -> recordResult(row, token, result));
            });
        });
    }

    private Future<Void> recordResult(Row row, String token, OutboundResult result) {
        Long id = row.getLong("id");
        if (result.success() && "ACK".equals(result.ackStatus())) {
            return outbox.markSent(id, token).mapEmpty();
        }
        if ("NACK".equals(result.ackStatus())) {
            // a technical rejection will not change on retry: stop and tell the NOC
            return escalate(row, token, "NACK_" + result.nackCode(),
                "Peer rejected " + row.getString("item_type") + " with NACK " + result.nackCode()
                    + (result.errorMessage() != null ? ": " + result.errorMessage() : ""));
        }
        return retryOrEscalate(row, token, result.errorCode(), result.errorMessage());
    }

    private Future<Void> retryOrEscalate(Row row, String token, String errorCode, String errorMessage) {
        int attemptNumber = row.getInteger("attempts") + 1;
        RetryDecision decision = retryPolicy.decide(row.getString("item_type"), null, attemptNumber,
            row.getInteger("max_attempts"), null, null, false, errorCode, false);
        if (decision.type() == RetryDecision.RetryDecisionType.RETRY_LATER) {
            return outbox.markRetryableFailure(row.getLong("id"), token, errorCode, errorMessage,
                decision.nextDueAt()).mapEmpty();
        }
        return escalate(row, token, errorCode, decision.reason() + ": " + errorMessage);
    }

    /** MANUAL_REVIEW and its manual task commit together. */
    private Future<Void> escalate(Row row, String token, String code, String reason) {
        log.warn("Outbox item " + row.getLong("id") + " needs manual review (" + code + "): " + reason);
        return pool.withTransaction(tx -> new OutboxRepository(tx)
            .markManualReview(row.getLong("id"), token, code, reason)
            .compose(owned -> owned ? openTask(tx, row, "OUTBOUND_DELIVERY_FAILED", reason) : Future.succeededFuture()));
    }

    /**
     * Sweeps SENDING rows whose lock expired. Safe-to-resend types go back to the queue; the rest
     * are held in MANUAL_REVIEW with a task describing the uncertainty.
     */
    public Future<Integer> recoverUnknownDeliveries(String workerId, int batchSize) {
        return outbox.sweepStaleSending(workerId, batchSize).compose(rows -> {
            List<Future<Void>> settled = new ArrayList<>();
            for (Row row : rows) {
                String token = row.getString("locked_by");
                String type = row.getString("item_type");
                if (unknownDeliveryPolicy.isSafeToResend(type)) {
                    log.warn("Outbox item " + row.getLong("id") + " (" + type + ") delivery unknown; re-sending (safe type)");
                    settled.add(outbox.requeueUnknownDelivery(row.getLong("id"), token).mapEmpty());
                } else {
                    log.warn("Outbox item " + row.getLong("id") + " (" + type + ") delivery unknown; holding for review");
                    settled.add(pool.withTransaction(tx -> new OutboxRepository(tx)
                        .holdUnknownDeliveryForReview(row.getLong("id"), token)
                        .compose(owned -> owned
                            ? openTask(tx, row, "UNKNOWN_DELIVERY",
                                "The dispatcher stopped while sending " + type + "; it may or may not have reached the peer. "
                                    + "Confirm with the peer operator, then mark the item delivered or retry it.")
                            : Future.succeededFuture())));
                }
            }
            return Future.join(settled).map(rows.size());
        });
    }

    private Future<Void> openTask(SqlClient tx, Row row, String taskType, String description) {
        return new ManualTaskRepository(tx).open(row.getLong("workflow_id"), row.getLong("message_id"),
            taskType, ManualTaskRepository.SEVERITY_HIGH, description).mapEmpty();
    }

    private record Prepared(String rawXml, String endpointUrl) {
    }

    private Future<Prepared> prepare(Row row) {
        return pool.preparedQuery("SELECT message_uuid FROM np_messages WHERE id = ?")
            .execute(Tuple.of(row.getLong("message_id")))
            .compose(rows -> {
                if (!rows.iterator().hasNext()) {
                    return Future.failedFuture("Message " + row.getLong("message_id") + " not found");
                }
                return xmlAuditService.findByMessageUuid(rows.iterator().next().getString("message_uuid"));
            })
            .compose(doc -> {
                if (doc == null || doc.getString("rawXml") == null) {
                    return Future.failedFuture("Raw XML audit document missing for outbox " + row.getLong("id"));
                }
                return resolveEndpoint(pool, row.getLong("destination_operator_id"))
                    .map(url -> new Prepared(doc.getString("rawXml"), url));
            });
    }

    static Future<String> resolveEndpoint(SqlClient client, Long destinationOperatorId) {
        return client.preparedQuery("""
                SELECT endpoint_url FROM np_operator_endpoints
                WHERE operator_id = ? AND endpoint_type = 'NPG_INBOUND' AND is_active = TRUE
                ORDER BY id ASC LIMIT 1
                """)
            .execute(Tuple.of(destinationOperatorId))
            .map(rows -> rows.iterator().hasNext() ? rows.iterator().next().getString("endpoint_url") : null);
    }
}
