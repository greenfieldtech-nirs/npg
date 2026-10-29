package cloud.cloudonix.npg.workflow.handler;

import cloud.cloudonix.npg.workflow.Workflow;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;

/**
 * Business processing for one workflow family. All database effects must go through {@code tx}:
 * the inbox and timer processors commit them together with their fenced completion update, so
 * they apply exactly once.
 */
public interface WorkflowHandler {

    /** Processes one inbound message (np_inbox row). */
    Future<Void> handle(SqlConnection tx, Row inboxItem);

    /**
     * Reacts to a timer of a workflow this handler owns. The workflow is locked and still in the
     * state the timer guards. Return false to fall back to the generic response-timeout handling
     * (MANUAL_REVIEW plus a manual task).
     */
    default Future<Boolean> onTimer(SqlConnection tx, Workflow workflow, Row timer) {
        return Future.succeededFuture(false);
    }
}
