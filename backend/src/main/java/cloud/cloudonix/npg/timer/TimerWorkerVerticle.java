package cloud.cloudonix.npg.timer;

import cloud.cloudonix.npg.config.Env;
import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.workflow.WorkflowEngine;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.sqlclient.Row;

public class TimerWorkerVerticle extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(TimerWorkerVerticle.class);

    private long periodicTimerId;

    @Override
    public void start(Promise<Void> startPromise) {
        DatabaseConfig.configure(vertx)
            .onSuccess(pool -> {
                TimerService timerService = new TimerService(pool);
                WorkflowEngine workflowEngine = new WorkflowEngine(pool);
                TimerActionDispatcher dispatcher = new TimerActionDispatcher(timerService, workflowEngine);

                String workerId = Env.get("WORKER_ID", "timer-worker-" + System.currentTimeMillis());
                int batchSize = Integer.parseInt(Env.get("QUEUE_CLAIM_BATCH_SIZE", "10"));
                int lockSeconds = Integer.parseInt(Env.get("QUEUE_LOCK_SECONDS", "60"));

                periodicTimerId = vertx.setPeriodic(5000, id -> claimAndProcess(timerService, dispatcher, workerId, batchSize, lockSeconds));
                startPromise.complete();
            })
            .onFailure(startPromise::fail);
    }

    private void claimAndProcess(TimerService timerService, TimerActionDispatcher dispatcher,
                                 String workerId, int batchSize, int lockSeconds) {
        timerService.claimDueTimers(workerId, batchSize, lockSeconds)
            .compose(rows -> {
                Future<Void> chain = Future.succeededFuture();
                for (Row row : rows) {
                    chain = chain.compose(v -> dispatcher.dispatch(row)
                        .onFailure(err -> log.error("Timer dispatch failed for timer " + row.getLong("id"), err)));
                }
                return chain;
            })
            .onFailure(err -> log.error("Timer claim failed", err));
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        vertx.cancelTimer(periodicTimerId);
        stopPromise.complete();
    }
}
