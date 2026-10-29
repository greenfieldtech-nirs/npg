package cloud.cloudonix.npg.worker;

import cloud.cloudonix.npg.config.Env;
import cloud.cloudonix.npg.audit.XmlAuditService;
import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.config.MongoConfig;
import cloud.cloudonix.npg.number.NumberOwnershipService;
import cloud.cloudonix.npg.protocol.ProtocolCatalogLoader;
import cloud.cloudonix.npg.repository.InboxRepository;
import cloud.cloudonix.npg.workflow.WorkflowEngine;
import cloud.cloudonix.npg.workflow.handler.WorkflowHandlerRegistry;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;

public class InboxWorkerVerticle extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(InboxWorkerVerticle.class);

    private long periodicTimerId;

    @Override
    public void start(Promise<Void> startPromise) {
        String catalogPath = Env.get("CATALOG_PATH", "docs/protocol");

        Future<Pool> poolFuture = DatabaseConfig.configure(vertx);
        Future<MongoClient> mongoFuture = MongoConfig.configure(vertx);

        Future.all(poolFuture, mongoFuture)
            .onSuccess(result -> {
                Pool pool = poolFuture.result();
                XmlAuditService xmlAuditService = new XmlAuditService(mongoFuture.result());
                ProtocolCatalogLoader catalogLoader = new ProtocolCatalogLoader(vertx, catalogPath);
                WorkflowEngine workflowEngine = new WorkflowEngine(pool);
                NumberOwnershipService ownershipService = new NumberOwnershipService(pool);

                InboxRepository inboxRepository = new InboxRepository(pool);
                WorkflowHandlerRegistry registry = new WorkflowHandlerRegistry(pool, xmlAuditService,
                    catalogLoader, workflowEngine, ownershipService);
                String workerId = Env.get("WORKER_ID", "worker-" + System.currentTimeMillis());
                int batchSize = Integer.parseInt(Env.get("QUEUE_CLAIM_BATCH_SIZE", "10"));
                int lockSeconds = Integer.parseInt(Env.get("QUEUE_LOCK_SECONDS", "60"));

                periodicTimerId = vertx.setPeriodic(2000, id -> claimAndProcess(inboxRepository, registry, workerId, batchSize, lockSeconds));
                startPromise.complete();
            })
            .onFailure(startPromise::fail);
    }

    private void claimAndProcess(InboxRepository inboxRepository, WorkflowHandlerRegistry registry,
                                 String workerId, int batchSize, int lockSeconds) {
        inboxRepository.claimBatch(workerId, batchSize, lockSeconds)
            .onSuccess(rows -> {
                for (Row row : rows) {
                    Long inboxId = row.getLong("id");
                    inboxRepository.markProcessing(inboxId)
                        .compose(v -> registry.handle(row))
                        .compose(v -> inboxRepository.markSucceeded(inboxId))
                        .onFailure(err -> {
                            log.error("Inbox item processing failed: " + inboxId, err);
                            inboxRepository.markFinalFailure(inboxId, "HANDLER_ERROR", err.getMessage());
                        });
                }
            })
            .onFailure(err -> log.error("Inbox claim failed", err));
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        vertx.cancelTimer(periodicTimerId);
        stopPromise.complete();
    }
}
