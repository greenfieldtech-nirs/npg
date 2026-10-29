package cloud.cloudonix.npg.e2e;

import cloud.cloudonix.npg.acknack.TechnicalAck;
import cloud.cloudonix.npg.audit.XmlAuditService;
import cloud.cloudonix.npg.audit.XmlTransportMetadata;
import cloud.cloudonix.npg.inbound.InboundGatewayService;
import cloud.cloudonix.npg.inbound.PeerAuthenticator;
import cloud.cloudonix.npg.repository.InboxRepository;
import cloud.cloudonix.npg.support.TestDatabase;
import cloud.cloudonix.npg.timer.TimerActionDispatcher;
import cloud.cloudonix.npg.timer.TimerProcessorAccess;
import cloud.cloudonix.npg.timer.TimerService;
import cloud.cloudonix.npg.worker.InboxProcessor;
import cloud.cloudonix.npg.workflow.WorkflowServices;
import cloud.cloudonix.npg.workflow.handler.WorkflowHandlerRegistry;
import cloud.cloudonix.npg.workflow.porting.PortOutPolicy;
import cloud.cloudonix.npg.workflow.porting.PortingService;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Plays the peer operators against this gateway: injects inbound protocol messages through the
 * real gateway, inbox processor and handlers; fires timers on demand; and reads back what the
 * gateway queued for sending (XML from the audit store plus the X-NP-* headers).
 *
 * <p>Operators: LOC is this gateway; DON, P1 and P2 are peers.
 */
public class ProtocolHarness {

    /** An outbound message the gateway queued. */
    public record Sent(long messageId, String type, String to, String requestId, String trxNo, String correlationTrxNo,
                       String xml, JsonObject headers) {
    }

    public final Pool pool;
    public final WorkflowServices services;
    public final PortingService porting;
    private final InboundGatewayService gateway;
    private final InboxProcessor inboxProcessor;
    private final TimerProcessorAccess timerProcessor;

    public ProtocolHarness(Vertx vertx, Pool pool, MongoClient mongo, PortOutPolicy.Mode mode) {
        this.pool = pool;
        System.setProperty("PORT_OUT_DECISION_MODE", mode.name());
        this.services = WorkflowServices.create(vertx, pool, mongo, "../docs/protocol");
        WorkflowHandlerRegistry registry = new WorkflowHandlerRegistry(services);
        System.clearProperty("PORT_OUT_DECISION_MODE");
        this.porting = new PortingService(services, new PortOutPolicy(mode));
        this.gateway = new InboundGatewayService(pool, services.xmlAudit(), services.catalog(),
            new PeerAuthenticator(PeerAuthenticator.Mode.PERMISSIVE));
        this.inboxProcessor = new InboxProcessor(pool, registry::handle, 60);
        TimerActionDispatcher dispatcher = new TimerActionDispatcher(services.engine(), registry);
        this.timerProcessor = new TimerProcessorAccess(pool, dispatcher::dispatch);
    }

    public Future<Void> reset() {
        return TestDatabase.clean(pool)
            .compose(v -> pool.query("DELETE FROM np_operator_endpoints").execute())
            .compose(v -> pool.query("DELETE FROM np_operator_status_events").execute())
            .compose(v -> pool.query("UPDATE np_operators SET is_local = FALSE").execute())
            .compose(v -> pool.query("DELETE FROM np_operators WHERE operator_code IN ('LOC', 'DON', 'P1', 'P2')").execute())
            .compose(v -> pool.query("""
                    INSERT INTO np_operators (operator_code, operator_name, is_local) VALUES
                      ('LOC', 'Local', TRUE), ('DON', 'Donor', FALSE), ('P1', 'Peer One', FALSE), ('P2', 'Peer Two', FALSE)
                    """).execute())
            // any other operators left by other test classes must not receive our publications
            .compose(v -> pool.query("UPDATE np_operators SET is_active = FALSE WHERE operator_code NOT IN ('LOC', 'DON', 'P1', 'P2')").execute())
            .mapEmpty();
    }

    public Future<Long> operatorId(String code) {
        return pool.preparedQuery("SELECT id FROM np_operators WHERE operator_code = ?").execute(Tuple.of(code))
            .map(rows -> rows.iterator().next().getLong("id"));
    }

    /** Delivers a message from {@code from} to LOC and runs business processing; returns the ACK. */
    public Future<TechnicalAck> deliver(String from, String type, String requestId, String bodyXml) {
        Map<String, String> headers = new HashMap<>();
        headers.put("x-np-from", from);
        headers.put("x-np-to", "LOC");
        headers.put("x-np-request-id", requestId);
        headers.put("x-np-trx-no", from + String.format("%012d", Math.abs(UUID.randomUUID().getMostSignificantBits()) % 1_000_000_000_000L));
        String xml = "<npMessageBody><" + type + ">" + bodyXml + "</" + type + "></npMessageBody>";
        return gateway.receive(xml, new XmlTransportMetadata("HTTPS", "10.0.0.1", null, new JsonObject()), headers)
            .compose(ack -> processInbox().map(ack));
    }

    /** Positive response from {@code from} to the request we sent it. */
    public Future<TechnicalAck> approve(String from, String type, Sent request, String extraXml) {
        return deliver(from, type, request.requestId(), "<requestTrxNo>" + request.trxNo() + "</requestTrxNo>"
            + "<positiveApproval><approvalInd>Y</approvalInd>" + (extraXml != null ? extraXml : "") + "</positiveApproval>");
    }

    public Future<TechnicalAck> rejectWith(String from, String type, Sent request, String code) {
        return deliver(from, type, request.requestId(), "<requestTrxNo>" + request.trxNo() + "</requestTrxNo>"
            + "<negativeApproval><approvalInd>N</approvalInd><rejectReasonCode>" + code + "</rejectReasonCode></negativeApproval>");
    }

    public Future<Void> processInbox() {
        return new InboxRepository(pool).claimBatch("harness", 50, 60).compose(inboxProcessor::processAll);
    }

    /** Makes the named pending timers due and fires them. */
    public Future<Void> fire(String timerName) {
        return pool.preparedQuery("UPDATE np_timers SET due_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND "
                + "WHERE timer_name = ? AND status = 'PENDING'").execute(Tuple.of(timerName))
            .compose(v -> new TimerService(pool).claimDueTimers("harness", 50, 60))
            .compose(timerProcessor::fireAll);
    }

    /** Most recent outbound message of a type (optionally to one operator). */
    public Future<Sent> lastSent(String type, String to) {
        return pool.preparedQuery("""
                SELECT m.id, m.message_uuid, m.message_type, m.request_id, m.transaction_id, m.correlation_id,
                       o.operator_code AS to_code
                FROM np_messages m JOIN np_operators o ON o.id = m.receiver_operator_id
                WHERE m.direction = 'OUTBOUND' AND m.message_type = ? AND (? IS NULL OR o.operator_code = ?)
                ORDER BY m.id DESC LIMIT 1
                """).execute(Tuple.of(type, to, to))
            .compose(rows -> {
                if (!rows.iterator().hasNext()) {
                    return Future.failedFuture("Nothing of type " + type + " was sent" + (to != null ? " to " + to : ""));
                }
                Row row = rows.iterator().next();
                return services.xmlAudit().findByMessageUuid(row.getString("message_uuid")).map(doc -> new Sent(
                    row.getLong("id"), row.getString("message_type"), row.getString("to_code"), row.getString("request_id"),
                    row.getString("transaction_id"), row.getString("correlation_id"), doc.getString("rawXml"),
                    doc.getJsonObject("transport").getJsonObject("headers")));
            });
    }

    public Future<Long> count(String sql, Object... params) {
        return pool.preparedQuery(sql).execute(Tuple.from(params)).map(rows -> rows.iterator().next().getLong(0));
    }

    public Future<Row> workflow(String uuidOrRequestId) {
        return pool.preparedQuery("SELECT * FROM np_workflows WHERE workflow_uuid = ? OR request_id = ? ORDER BY id DESC LIMIT 1")
            .execute(Tuple.of(uuidOrRequestId, uuidOrRequestId))
            .map(rows -> rows.iterator().next());
    }

    public Future<Row> number(String phone) {
        return pool.preparedQuery("""
                SELECT n.*, c.operator_code AS current_code, og.operator_code AS original_code
                FROM np_numbers n LEFT JOIN np_operators c ON c.id = n.current_operator_id
                LEFT JOIN np_operators og ON og.id = n.original_operator_id WHERE n.phone_number = ?
                """).execute(Tuple.of(phone))
            .map(rows -> rows.iterator().hasNext() ? rows.iterator().next() : null);
    }

    public static String checkBody(String phone) {
        return "<fixed><networkType>F</networkType><fixedNumberSingle><numberType>S</numberType><number>" + phone
            + "</number></fixedNumberSingle><identificationValue>123456782</identificationValue></fixed>";
    }

    public XmlAuditService audit() {
        return services.xmlAudit();
    }
}
