package cloud.cloudonix.npg.workflow.handler;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;

/**
 * Business processing for one inbox item. All database effects must go through {@code tx}: the
 * inbox processor commits them together with the fenced SUCCEEDED update, so they apply exactly once.
 */
public interface WorkflowHandler {
    Future<Void> handle(SqlConnection tx, Row inboxItem);
}
