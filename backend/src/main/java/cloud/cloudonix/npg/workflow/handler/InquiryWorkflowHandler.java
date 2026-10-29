package cloud.cloudonix.npg.workflow.handler;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;

public class InquiryWorkflowHandler implements WorkflowHandler {
    @Override
    public Future<Void> handle(SqlConnection tx, Row inboxItem) {
        // ponytail: skeleton until protocol extraction confirms field semantics
        return Future.succeededFuture();
    }
}
