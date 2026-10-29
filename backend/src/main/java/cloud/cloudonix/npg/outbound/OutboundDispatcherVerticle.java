package cloud.cloudonix.npg.outbound;

import cloud.cloudonix.npg.audit.XmlAuditService;
import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.config.Env;
import cloud.cloudonix.npg.config.MongoConfig;
import cloud.cloudonix.npg.repository.OutboxRepository;
import cloud.cloudonix.npg.retry.RetryPolicyService;
import cloud.cloudonix.npg.retry.UnknownDeliveryPolicy;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.sqlclient.Pool;

public class OutboundDispatcherVerticle extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(OutboundDispatcherVerticle.class);

    private long periodicTimerId;
    // skips a tick while the previous one is still in flight; efficiency only, claims are DB-fenced
    private boolean busy;

    @Override
    public void start(Promise<Void> startPromise) {
        Future<Pool> poolFuture = DatabaseConfig.configure(vertx);
        Future<MongoClient> mongoFuture = MongoConfig.configure(vertx);

        Future.all(poolFuture, mongoFuture)
            .onSuccess(result -> {
                Pool pool = poolFuture.result();
                String workerId = Env.get("WORKER_ID", "dispatcher-" + System.currentTimeMillis());
                int batchSize = Env.getInt("QUEUE_CLAIM_BATCH_SIZE", 10);
                int lockSeconds = Env.getInt("QUEUE_LOCK_SECONDS", 60);
                OutboxRepository outbox = new OutboxRepository(pool);
                OutboxDispatcher dispatcher = new OutboxDispatcher(pool, new XmlAuditService(mongoFuture.result()),
                    new HttpXmlTransportAdapter(vertx), new RetryPolicyService(), UnknownDeliveryPolicy.fromEnvironment(),
                    lockSeconds);

                periodicTimerId = vertx.setPeriodic(2000, id -> tick(outbox, dispatcher, workerId, batchSize, lockSeconds));
                startPromise.complete();
            })
            .onFailure(startPromise::fail);
    }

    private void tick(OutboxRepository outbox, OutboxDispatcher dispatcher, String workerId, int batchSize, int lockSeconds) {
        if (busy) {
            return;
        }
        busy = true;
        dispatcher.recoverUnknownDeliveries(workerId, batchSize)
            .compose(recovered -> outbox.claimBatch(workerId, batchSize, lockSeconds))
            .compose(dispatcher::dispatchAll)
            .onFailure(err -> log.error("Outbox dispatch cycle failed", err))
            .onComplete(ar -> busy = false);
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        vertx.cancelTimer(periodicTimerId);
        stopPromise.complete();
    }
}
