package cloud.cloudonix.npg.workflow.handler;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;

public class SynchronizationWorkflowHandler implements WorkflowHandler {
    @Override
    public Future<Void> handle(Row inboxItem) {
        // ponytail: skeleton until protocol extraction confirms field semantics
        return Future.succeededFuture();
    }
}
