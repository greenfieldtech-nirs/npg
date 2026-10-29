package cloud.cloudonix.npg.workflow.handler;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;

public interface WorkflowHandler {
    Future<Void> handle(Row inboxItem);
}
