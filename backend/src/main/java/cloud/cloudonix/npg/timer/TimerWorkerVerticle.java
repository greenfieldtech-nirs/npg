package cloud.cloudonix.npg.timer;

import cloud.cloudonix.npg.config.Env;
import cloud.cloudonix.npg.config.DatabaseConfig;
import cloud.cloudonix.npg.workflow.WorkflowEngine;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;

public class TimerWorkerVerticle extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(TimerWorkerVerticle.class);

    private long periodicTimerId;
    // skips a tick while the previous batch is still in flight; efficiency only, claims are DB-fenced
    private boolean busy;

    @Override
    public void start(Promise<Void> startPromise) {
        DatabaseConfig.configure(vertx)
            .onSuccess(pool -> {
                TimerService timerService = new TimerService(pool);
                WorkflowEngine workflowEngine = new WorkflowEngine(pool);
                TimerActionDispatcher dispatcher = new TimerActionDispatcher(timerService, workflowEngine);
                TimerProcessor processor = new TimerProcessor(pool, dispatcher::dispatch);

                String workerId = Env.get("WORKER_ID", "timer-worker-" + System.currentTimeMillis());
                int batchSize = Env.getInt("QUEUE_CLAIM_BATCH_SIZE", 10);
                int lockSeconds = Env.getInt("QUEUE_LOCK_SECONDS", 60);

                periodicTimerId = vertx.setPeriodic(5000, id -> tick(timerService, processor, workerId, batchSize, lockSeconds));
                startPromise.complete();
            })
            .onFailure(startPromise::fail);
    }

    private void tick(TimerService timerService, TimerProcessor processor, String workerId, int batchSize, int lockSeconds) {
        if (busy) {
            return;
        }
        busy = true;
        timerService.claimDueTimers(workerId, batchSize, lockSeconds)
            .compose(processor::fireAll)
            .onFailure(err -> log.error("Timer claim failed", err))
            .onComplete(ar -> busy = false);
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        vertx.cancelTimer(periodicTimerId);
        stopPromise.complete();
    }
}
