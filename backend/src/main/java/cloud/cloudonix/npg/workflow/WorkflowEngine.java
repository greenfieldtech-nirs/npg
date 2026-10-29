package cloud.cloudonix.npg.workflow;

import cloud.cloudonix.npg.repository.AuditEventRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.mysqlclient.MySQLClient;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Durable workflow state (docs/05). Every method runs on the caller's connection, so a state
 * change, the outbound messages it causes and the timers it starts or stops commit together.
 * Callers lock the workflow first ({@link #lock} / {@link #findForUpdate}); transitions are then
 * checked against the workflow's state machine and recorded as np_workflow_events.
 */
public class WorkflowEngine {

    /** Columns a workflow step may set besides the state (whitelist: names go into SQL). */
    private static final Set<String> UPDATABLE = Set.of("approved_port_time", "requested_port_time", "executed_at",
        "current_operator_id", "original_operator_id", "recipient_operator_id", "donor_operator_id",
        "failure_code", "failure_reason");

    /**
     * @param counterpartyOperatorId the peer this process is conducted with (who started it, or whom
     *                               we started it with); part of the workflow's identity
     */
    public record NewWorkflow(String type, String role, Long counterpartyOperatorId, String requestId, String phoneNumber,
                              Long recipientOperatorId, Long donorOperatorId, Long originalOperatorId,
                              LocalDateTime requestedPortTime, JsonObject context, String customerBatchId) {
    }

    /** Creates a workflow in its machine's initial state. */
    public Future<Workflow> create(SqlClient tx, NewWorkflow spec, String actor) {
        StateMachines.Machine machine = StateMachines.of(spec.type(), spec.role());
        String uuid = UUID.randomUUID().toString();
        return tx.preparedQuery("""
                INSERT INTO np_workflows (workflow_uuid, workflow_type, local_role, counterparty_operator_id, request_id,
                                          phone_number, customer_batch_id, recipient_operator_id, donor_operator_id,
                                          original_operator_id, requested_port_time, current_state,
                                          context_json, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
            .execute(Tuple.from(new Object[]{uuid, spec.type(), spec.role(), spec.counterpartyOperatorId(),
                spec.requestId(), spec.phoneNumber(),
                spec.customerBatchId(), spec.recipientOperatorId(), spec.donorOperatorId(), spec.originalOperatorId(),
                spec.requestedPortTime(), machine.initial(),
                (spec.context() != null ? spec.context() : new JsonObject()).encode(), actor}))
            .compose(rs -> {
                long id = rs.property(MySQLClient.LAST_INSERTED_ID);
                return new WorkflowEventService(tx).recordEvent(id, "WORKFLOW_CREATED", null, machine.initial(), null,
                        new JsonObject().put("requestId", spec.requestId()).put("role", spec.role()), actor)
                    .compose(v -> lock(tx, id));
            });
    }

    /** Reads and row-locks a workflow for the rest of the transaction. */
    public Future<Workflow> lock(SqlClient tx, long workflowId) {
        return tx.preparedQuery("SELECT * FROM np_workflows WHERE id = ? FOR UPDATE")
            .execute(Tuple.of(workflowId))
            .compose(rows -> rows.iterator().hasNext()
                ? Future.succeededFuture(Workflow.from(rows.iterator().next()))
                : Future.failedFuture(new WorkflowNotFoundException("Workflow " + workflowId + " not found")));
    }

    public Future<Workflow> lockByUuid(SqlClient tx, String workflowUuid) {
        return tx.preparedQuery("SELECT * FROM np_workflows WHERE workflow_uuid = ? FOR UPDATE")
            .execute(Tuple.of(workflowUuid))
            .compose(rows -> rows.iterator().hasNext()
                ? Future.succeededFuture(Workflow.from(rows.iterator().next()))
                : Future.failedFuture(new WorkflowNotFoundException("Workflow " + workflowUuid + " not found")));
    }

    /**
     * The workflow of this type/role for a protocol REQUEST_ID conducted with {@code counterpartyId},
     * locked; null when there is none. A REQUEST_ID never identifies a workflow on its own: it is
     * chosen by a peer, so another peer must not be able to reach the process by reusing it.
     */
    public Future<Workflow> findForUpdate(SqlClient tx, String requestId, String type, String role, Long counterpartyId) {
        return tx.preparedQuery("""
                SELECT * FROM np_workflows
                WHERE request_id = ? AND workflow_type = ? AND local_role = ? AND counterparty_operator_id <=> ?
                ORDER BY id DESC LIMIT 1 FOR UPDATE
                """)
            .execute(Tuple.of(requestId, type, role, counterpartyId))
            .map(rows -> rows.iterator().hasNext() ? Workflow.from(rows.iterator().next()) : null);
    }

    /**
     * Automatic transition. Fails with {@link IllegalTransitionException} when the state machine
     * does not allow it, leaving the workflow untouched.
     */
    public Future<Workflow> transition(SqlClient tx, Workflow wf, String to, String eventType, Long messageId,
                                       JsonObject details, String actor) {
        if (!wf.machine().allows(wf.state(), to)) {
            return Future.failedFuture(new IllegalTransitionException(
                "Illegal transition " + wf.state() + " -> " + to + " for " + wf.type() + "/" + wf.role()));
        }
        return applyState(tx, wf, to, eventType, messageId, details, actor);
    }

    /**
     * Operator override from the management API: any state of the workflow's machine, a mandatory
     * reason, an event and an audit record (docs/05 "Manual Transitions").
     */
    public Future<Workflow> manualTransition(SqlClient tx, Workflow wf, String to, String reason, String actor,
                                             String remoteIp) {
        if (reason == null || reason.isBlank()) {
            return Future.failedFuture(new IllegalArgumentException("A reason is required for manual transitions"));
        }
        if (!wf.machine().states().contains(to)) {
            return Future.failedFuture(new IllegalTransitionException(
                to + " is not a state of " + wf.type() + "/" + wf.role() + "; valid: " + wf.machine().states()));
        }
        JsonObject details = new JsonObject().put("reason", reason).put("manual", true);
        return applyState(tx, wf, to, "MANUAL_TRANSITION", null, details, actor)
            .compose(updated -> new AuditEventRepository(tx).insertAuditEvent("USER", actor, "WORKFLOW_MANUAL_TRANSITION",
                    "WORKFLOW", wf.uuid(), details.copy().put("fromState", wf.state()).put("toState", to).encode(), remoteIp)
                .map(updated));
    }

    /** Records a workflow event without changing state (e.g. a port-time update applied in place). */
    public Future<Void> recordEvent(SqlClient tx, Workflow wf, String eventType, Long messageId, JsonObject details,
                                    String actor) {
        return new WorkflowEventService(tx).recordEvent(wf.id(), eventType, wf.state(), wf.state(), messageId,
            details, actor).mapEmpty();
    }

    /** Sets whitelisted columns on the workflow row. */
    public Future<Void> update(SqlClient tx, Workflow wf, Map<String, Object> columns) {
        if (columns.isEmpty()) {
            return Future.succeededFuture();
        }
        StringBuilder sql = new StringBuilder("UPDATE np_workflows SET ");
        Tuple params = Tuple.tuple();
        for (Map.Entry<String, Object> column : columns.entrySet()) {
            if (!UPDATABLE.contains(column.getKey())) {
                return Future.failedFuture(new IllegalArgumentException("Column not updatable: " + column.getKey()));
            }
            sql.append(column.getKey()).append(" = ?, ");
            params.addValue(column.getValue());
        }
        sql.append("updated_at = UTC_TIMESTAMP(6) WHERE id = ?");
        params.addValue(wf.id());
        return tx.preparedQuery(sql.toString()).execute(params).mapEmpty();
    }

    /** Merges keys into the workflow's context_json. */
    public Future<Workflow> mergeContext(SqlClient tx, Workflow wf, JsonObject values) {
        JsonObject merged = wf.context().copy().mergeIn(values);
        return tx.preparedQuery("UPDATE np_workflows SET context_json = ?, updated_at = UTC_TIMESTAMP(6) WHERE id = ?")
            .execute(Tuple.of(merged.encode(), wf.id()))
            .map(rs -> new Workflow(wf.id(), wf.uuid(), wf.type(), wf.role(), wf.counterpartyOperatorId(),
                wf.requestId(), wf.phoneNumber(),
                wf.state(), wf.recipientOperatorId(), wf.donorOperatorId(), wf.originalOperatorId(),
                wf.requestedPortTime(), wf.approvedPortTime(), merged));
    }

    private Future<Workflow> applyState(SqlClient tx, Workflow wf, String to, String eventType, Long messageId,
                                        JsonObject details, String actor) {
        StateMachines.Machine machine = wf.machine();
        String stamp = machine.isTerminal(to) || StateMachines.FAILED.equals(to)
            ? switch (to) {
                case "CANCELLED", "PUBLICATION_CANCELLED" -> ", cancelled_at = UTC_TIMESTAMP(6)";
                case "FAILED", "CHECK_REJECTED", "REQUEST_REJECTED", "RETURN_REJECTED", "SYNC_REJECTED",
                     "PUBLICATION_REJECTED" -> ", failed_at = UTC_TIMESTAMP(6)";
                default -> ", completed_at = UTC_TIMESTAMP(6)";
            }
            : "COMPLETED".equals(to) ? ", completed_at = UTC_TIMESTAMP(6)" : "";
        return tx.preparedQuery("UPDATE np_workflows SET current_state = ?, updated_at = UTC_TIMESTAMP(6)" + stamp
                + " WHERE id = ?")
            .execute(Tuple.of(to, wf.id()))
            .compose(u -> new WorkflowEventService(tx).recordEvent(wf.id(), eventType, wf.state(), to, messageId,
                details, actor))
            .map(v -> wf.withState(to));
    }

    public static class IllegalTransitionException extends RuntimeException {
        public IllegalTransitionException(String message) {
            super(message);
        }
    }

    public static class WorkflowNotFoundException extends RuntimeException {
        public WorkflowNotFoundException(String message) {
            super(message);
        }
    }
}
