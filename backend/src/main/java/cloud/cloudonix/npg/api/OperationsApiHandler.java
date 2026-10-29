package cloud.cloudonix.npg.api;

import cloud.cloudonix.npg.repository.AuditEventRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.Set;

/**
 * NOC operations on the durable queues, timers and manual tasks (docs/05 "dangerous manual
 * actions", docs/operations/runbooks). Every change is guarded by the allowed current statuses
 * (409 otherwise, 404 when absent), requires a reason, and writes an audit event.
 */
public class OperationsApiHandler {

    private static final Set<String> TASK_STATUSES = Set.of("OPEN", "IN_PROGRESS", "CLOSED");

    private final Pool pool;

    public OperationsApiHandler(Pool pool) {
        this.pool = pool;
    }

    // ================================================================= manual tasks

    public Future<JsonArray> listTasks(String status) {
        if (status != null && !TASK_STATUSES.contains(status)) {
            return Future.failedFuture(new ApiError(400, "status must be one of " + TASK_STATUSES));
        }
        String sql = """
            SELECT t.*, w.workflow_uuid, w.request_id, w.phone_number FROM np_manual_tasks t
            LEFT JOIN np_workflows w ON w.id = t.workflow_id
            """ + (status != null ? " WHERE t.status = ?" : "") + " ORDER BY t.created_at DESC LIMIT 500";
        return pool.preparedQuery(sql).execute(status != null ? Tuple.of(status) : Tuple.tuple()).map(RowJson::rowsToJsonArray);
    }

    public Future<JsonObject> getTask(String taskUuid) {
        return pool.preparedQuery("""
                SELECT t.*, w.workflow_uuid, w.request_id, w.phone_number FROM np_manual_tasks t
                LEFT JOIN np_workflows w ON w.id = t.workflow_id WHERE t.task_uuid = ?
                """).execute(Tuple.of(taskUuid))
            .compose(rows -> {
                if (!rows.iterator().hasNext()) {
                    return Future.succeededFuture(null);
                }
                JsonObject task = RowJson.rowToJson(rows.iterator().next());
                return pool.preparedQuery("SELECT note, created_by, created_at FROM np_manual_task_notes WHERE task_id = "
                        + "(SELECT id FROM np_manual_tasks WHERE task_uuid = ?) ORDER BY created_at")
                    .execute(Tuple.of(taskUuid))
                    .map(notes -> task.put("notes", RowJson.rowsToJsonArray(notes)));
            });
    }

    public Future<Void> assignTask(String taskUuid, JsonObject body, String actor, String remoteIp) {
        String assignee = body.getString("assignee", actor);
        return change(tx -> tx.preparedQuery("UPDATE np_manual_tasks SET assigned_to = ?, status = 'IN_PROGRESS', "
                + "updated_at = UTC_TIMESTAMP(6) WHERE task_uuid = ? AND status IN ('OPEN', 'IN_PROGRESS')")
                .execute(Tuple.of(assignee, taskUuid)),
            "np_manual_tasks", "task_uuid", taskUuid, "MANUAL_TASK_ASSIGNED", "MANUAL_TASK",
            new JsonObject().put("assignee", assignee), actor, remoteIp);
    }

    public Future<Void> addNote(String taskUuid, JsonObject body, String actor, String remoteIp) {
        String note = body.getString("note");
        if (note == null || note.isBlank()) {
            return Future.failedFuture(new ApiError(400, "note is required"));
        }
        return pool.withTransaction(tx -> tx.preparedQuery("""
                INSERT INTO np_manual_task_notes (task_id, note, created_by)
                SELECT id, ?, ? FROM np_manual_tasks WHERE task_uuid = ?
                """).execute(Tuple.of(note, actor, taskUuid))
            .compose(rs -> rs.rowCount() == 0 ? Future.failedFuture(new ApiError(404, "Task not found"))
                : audit(tx, "MANUAL_TASK_NOTE_ADDED", "MANUAL_TASK", taskUuid, new JsonObject().put("note", note), actor, remoteIp)));
    }

    public Future<Void> resolveTask(String taskUuid, JsonObject body, String actor, String remoteIp) {
        String resolution = body.getString("resolution");
        if (resolution == null || resolution.isBlank()) {
            return Future.failedFuture(new ApiError(400, "resolution is required"));
        }
        return change(tx -> tx.preparedQuery("UPDATE np_manual_tasks SET status = 'CLOSED', resolution = ?, "
                + "closed_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6) WHERE task_uuid = ? AND status <> 'CLOSED'")
                .execute(Tuple.of(resolution, taskUuid)),
            "np_manual_tasks", "task_uuid", taskUuid, "MANUAL_TASK_RESOLVED", "MANUAL_TASK",
            new JsonObject().put("resolution", resolution), actor, remoteIp);
    }

    // ================================================================= outbox

    public Future<JsonArray> listOutbox(String status, int page, int size) {
        return list("""
            SELECT o.outbox_uuid, o.status, o.item_type, o.attempts, o.max_attempts, o.due_at, o.last_error_code,
                   o.last_error_message, o.created_at, o.updated_at, m.message_uuid, m.request_id, m.transaction_id,
                   d.operator_code AS destination, w.workflow_uuid
            FROM np_outbox o JOIN np_messages m ON m.id = o.message_id
            LEFT JOIN np_operators d ON d.id = o.destination_operator_id
            LEFT JOIN np_workflows w ON w.id = o.workflow_id
            """, "o.status", status, "o.updated_at", page, size);
    }

    /** Re-queues an outbox item the dispatcher gave up on (fresh attempt budget). */
    public Future<Void> retryOutbox(String outboxUuid, JsonObject body, String actor, String remoteIp) {
        return withReason(body).compose(reason -> change(tx -> tx.preparedQuery("""
                UPDATE np_outbox SET status = 'PENDING', due_at = UTC_TIMESTAMP(6), attempts = 0, locked_by = NULL,
                       locked_until = NULL, last_error_code = 'MANUAL_RETRY', updated_at = UTC_TIMESTAMP(6)
                WHERE outbox_uuid = ? AND status IN ('MANUAL_REVIEW', 'FAILED_FINAL', 'UNKNOWN_DELIVERY')
                """).execute(Tuple.of(outboxUuid)),
            "np_outbox", "outbox_uuid", outboxUuid, "OUTBOX_RETRIED", "OUTBOX", reasonJson(reason), actor, remoteIp));
    }

    /** The NOC confirmed with the peer that an uncertain delivery did arrive (unknown-delivery runbook). */
    public Future<Void> markOutboxDelivered(String outboxUuid, JsonObject body, String actor, String remoteIp) {
        return withReason(body).compose(reason -> change(tx -> tx.preparedQuery("""
                UPDATE np_outbox SET status = 'SUCCEEDED', locked_by = NULL, locked_until = NULL,
                       last_error_code = 'MARKED_DELIVERED', updated_at = UTC_TIMESTAMP(6)
                WHERE outbox_uuid = ? AND status IN ('MANUAL_REVIEW', 'UNKNOWN_DELIVERY')
                """).execute(Tuple.of(outboxUuid))
                .compose(rs -> rs.rowCount() == 0 ? Future.succeededFuture(rs)
                    : tx.preparedQuery("""
                        UPDATE np_messages SET technical_status = 'DELIVERED', ack_status = 'CONFIRMED_MANUALLY',
                               sent_at = COALESCE(sent_at, UTC_TIMESTAMP(6))
                        WHERE id = (SELECT message_id FROM np_outbox WHERE outbox_uuid = ?)
                        """).execute(Tuple.of(outboxUuid)).map(rs)),
            "np_outbox", "outbox_uuid", outboxUuid, "OUTBOX_MARKED_DELIVERED", "OUTBOX", reasonJson(reason), actor, remoteIp));
    }

    public Future<Void> cancelOutbox(String outboxUuid, JsonObject body, String actor, String remoteIp) {
        return withReason(body).compose(reason -> change(tx -> tx.preparedQuery("""
                UPDATE np_outbox SET status = 'CANCELLED', locked_by = NULL, locked_until = NULL,
                       last_error_code = 'CANCELLED_MANUALLY', updated_at = UTC_TIMESTAMP(6)
                WHERE outbox_uuid = ? AND status IN ('PENDING', 'FAILED_RETRYABLE', 'MANUAL_REVIEW', 'UNKNOWN_DELIVERY')
                """).execute(Tuple.of(outboxUuid)),
            "np_outbox", "outbox_uuid", outboxUuid, "OUTBOX_CANCELLED", "OUTBOX", reasonJson(reason), actor, remoteIp));
    }

    // ================================================================= inbox

    public Future<JsonArray> listInbox(String status, int page, int size) {
        return list("""
            SELECT i.inbox_uuid, i.status, i.item_type, i.attempts, i.max_attempts, i.due_at, i.last_error_code,
                   i.last_error_message, i.created_at, i.updated_at, m.message_uuid, m.request_id, m.transaction_id,
                   s.operator_code AS sender
            FROM np_inbox i JOIN np_messages m ON m.id = i.message_id
            LEFT JOIN np_operators s ON s.id = m.sender_operator_id
            """, "i.status", status, "i.updated_at", page, size);
    }

    /** Re-processes an inbound message that failed permanently (after the cause was fixed). */
    public Future<Void> retryInbox(String inboxUuid, JsonObject body, String actor, String remoteIp) {
        return withReason(body).compose(reason -> change(tx -> tx.preparedQuery("""
                UPDATE np_inbox SET status = 'PENDING', due_at = UTC_TIMESTAMP(6), attempts = 0, locked_by = NULL,
                       locked_until = NULL, last_error_code = 'MANUAL_RETRY', updated_at = UTC_TIMESTAMP(6)
                WHERE inbox_uuid = ? AND status = 'FAILED_FINAL'
                """).execute(Tuple.of(inboxUuid)),
            "np_inbox", "inbox_uuid", inboxUuid, "INBOX_RETRIED", "INBOX", reasonJson(reason), actor, remoteIp));
    }

    // ================================================================= timers

    public Future<JsonArray> listTimers(String status, int page, int size) {
        return list("""
            SELECT t.timer_uuid, t.timer_name, t.timer_type, t.status, t.due_at, t.fired_at, t.cancelled_at, t.attempts,
                   w.workflow_uuid, w.request_id, w.phone_number, w.current_state
            FROM np_timers t LEFT JOIN np_workflows w ON w.id = t.workflow_id
            """, "t.status", status, "t.due_at", page, size);
    }

    public Future<Void> cancelTimer(String timerUuid, JsonObject body, String actor, String remoteIp) {
        return withReason(body).compose(reason -> change(tx -> tx.preparedQuery("""
                UPDATE np_timers SET status = 'CANCELLED', cancelled_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6)
                WHERE timer_uuid = ? AND status = 'PENDING'
                """).execute(Tuple.of(timerUuid)),
            "np_timers", "timer_uuid", timerUuid, "TIMER_CANCELLED", "TIMER", reasonJson(reason), actor, remoteIp));
    }

    /** Makes a pending timer due now (e.g. execute a port early after confirming with the donor). */
    public Future<Void> fireTimerNow(String timerUuid, JsonObject body, String actor, String remoteIp) {
        return withReason(body).compose(reason -> change(tx -> tx.preparedQuery("""
                UPDATE np_timers SET due_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6)
                WHERE timer_uuid = ? AND status = 'PENDING'
                """).execute(Tuple.of(timerUuid)),
            "np_timers", "timer_uuid", timerUuid, "TIMER_FIRED_MANUALLY", "TIMER", reasonJson(reason), actor, remoteIp));
    }

    // ================================================================= workflow detail views

    public Future<JsonArray> workflowTimers(String workflowUuid) {
        return pool.preparedQuery("""
                SELECT t.timer_uuid, t.timer_name, t.timer_type, t.status, t.due_at, t.fired_at, t.cancelled_at, t.attempts
                FROM np_timers t JOIN np_workflows w ON w.id = t.workflow_id WHERE w.workflow_uuid = ? ORDER BY t.id
                """).execute(Tuple.of(workflowUuid)).map(RowJson::rowsToJsonArray);
    }

    public Future<JsonArray> workflowMessages(String workflowUuid) {
        return pool.preparedQuery("""
                SELECT m.message_uuid, m.message_type, m.direction, m.request_id, m.transaction_id, m.correlation_id,
                       m.technical_status, m.ack_status, m.nack_code, m.created_at, m.sent_at,
                       s.operator_code AS sender, r.operator_code AS receiver, o.status AS outbox_status, o.outbox_uuid
                FROM np_messages m JOIN np_workflows w ON w.id = m.workflow_id
                LEFT JOIN np_operators s ON s.id = m.sender_operator_id
                LEFT JOIN np_operators r ON r.id = m.receiver_operator_id
                LEFT JOIN np_outbox o ON o.message_id = m.id
                WHERE w.workflow_uuid = ?
                UNION ALL
                SELECT m.message_uuid, m.message_type, m.direction, m.request_id, m.transaction_id, m.correlation_id,
                       m.technical_status, m.ack_status, m.nack_code, m.created_at, m.sent_at,
                       s.operator_code, r.operator_code, NULL, NULL
                FROM np_messages m JOIN np_workflows w ON w.request_id = m.request_id
                LEFT JOIN np_operators s ON s.id = m.sender_operator_id
                LEFT JOIN np_operators r ON r.id = m.receiver_operator_id
                WHERE w.workflow_uuid = ? AND m.direction = 'INBOUND' AND m.workflow_id IS NULL
                  AND m.sender_operator_id <=> w.counterparty_operator_id
                ORDER BY created_at
                """).execute(Tuple.of(workflowUuid, workflowUuid)).map(RowJson::rowsToJsonArray);
    }

    public Future<JsonArray> workflowPublication(String workflowUuid) {
        return pool.preparedQuery("""
                SELECT p.purpose, p.status, p.response_status, p.sent_at, p.response_at, p.attempts, o.operator_code
                FROM np_publication_recipients p JOIN np_workflows w ON w.id = p.workflow_id
                JOIN np_operators o ON o.id = p.operator_id WHERE w.workflow_uuid = ? ORDER BY p.purpose, o.operator_code
                """).execute(Tuple.of(workflowUuid)).map(RowJson::rowsToJsonArray);
    }

    // ================================================================= helpers

    private Future<JsonArray> list(String select, String statusColumn, String status, String orderColumn, int page, int size) {
        if (size < 1 || size > 500 || page < 0) {
            return Future.failedFuture(new ApiError(400, "page must be >= 0 and size between 1 and 500"));
        }
        Tuple params = Tuple.tuple();
        String where = "";
        if (status != null) {
            where = " WHERE " + statusColumn + " = ?";
            params.addValue(status);
        }
        params.addValue(size).addValue(page * size);
        return pool.preparedQuery(select + where + " ORDER BY " + orderColumn + " DESC LIMIT ? OFFSET ?")
            .execute(params).map(RowJson::rowsToJsonArray);
    }

    private interface Update {
        Future<io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row>> apply(SqlClient tx);
    }

    /** Guarded update + audit in one transaction; distinguishes 404 (absent) from 409 (wrong status). */
    private Future<Void> change(Update update, String table, String uuidColumn, String uuid, String action,
                                String entityType, JsonObject details, String actor, String remoteIp) {
        return pool.withTransaction(tx -> update.apply(tx).compose(rs -> {
            if (rs.rowCount() > 0) {
                return audit(tx, action, entityType, uuid, details, actor, remoteIp);
            }
            return tx.preparedQuery("SELECT status FROM " + table + " WHERE " + uuidColumn + " = ?").execute(Tuple.of(uuid))
                .compose(rows -> Future.failedFuture(rows.iterator().hasNext()
                    ? new ApiError(409, "Not allowed while the item is " + rows.iterator().next().getString("status"))
                    : new ApiError(404, "Not found")));
        }));
    }

    private static Future<Void> audit(SqlClient tx, String action, String entityType, String entityId, JsonObject details,
                                      String actor, String remoteIp) {
        return new AuditEventRepository(tx).insertAuditEvent("USER", actor, action, entityType, entityId,
            details.encode(), remoteIp).mapEmpty();
    }

    private static Future<String> withReason(JsonObject body) {
        String reason = body.getString("reason");
        return reason == null || reason.isBlank()
            ? Future.failedFuture(new ApiError(400, "A reason is required"))
            : Future.succeededFuture(reason);
    }

    private static JsonObject reasonJson(String reason) {
        return new JsonObject().put("reason", reason);
    }
}
