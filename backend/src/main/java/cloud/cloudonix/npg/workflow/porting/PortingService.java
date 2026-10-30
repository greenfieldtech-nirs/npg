package cloud.cloudonix.npg.workflow.porting;

import cloud.cloudonix.npg.config.Env;
import cloud.cloudonix.npg.domain.Operator;
import cloud.cloudonix.npg.number.NumberOwnershipService;
import cloud.cloudonix.npg.protocol.ProtocolIds;
import cloud.cloudonix.npg.repository.AuditEventRepository;
import cloud.cloudonix.npg.repository.ManualTaskRepository;
import cloud.cloudonix.npg.workflow.BusinessRuleException;
import cloud.cloudonix.npg.workflow.InboundMessage;
import cloud.cloudonix.npg.workflow.ProtocolBodies;
import cloud.cloudonix.npg.workflow.PublicationTracker;
import cloud.cloudonix.npg.workflow.StateMachines;
import cloud.cloudonix.npg.workflow.Workflow;
import cloud.cloudonix.npg.workflow.WorkflowEngine;
import cloud.cloudonix.npg.workflow.WorkflowServices;
import cloud.cloudonix.npg.workflow.WorkflowSupport;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The porting process (docs/05, message flow from the live trace) from both sides.
 *
 * <p><b>As recipient</b> (a customer moves to us): the NOC starts a port-in; we send Check, then
 * Request on approval, schedule the port at the approved time, send Execute when it is due, take
 * the number over when the donor confirms the disconnection, and publish the new routing to every
 * other operator. Update, Cancel and Cancel_Publish are NOC actions.
 *
 * <p><b>As donor</b> (a customer leaves): we answer Check and Request according to
 * {@link PortOutPolicy} (automatically, or by NOC decision), accept Update/Cancel/KD_update, and
 * on Execute release the number to the recipient and confirm the disconnection.
 */
public class PortingService {

    static final String PROCESS = "PORT";
    static final String EXECUTE_TIMER = "PORT_EXECUTION_DUE";
    static final String EXECUTE_ACTION = "EXECUTE_PORT";
    static final String DECISION_TASK = "PORT_OUT_DECISION";
    private static final Pattern PHONE = Pattern.compile("0\\d{8,9}|972\\d{8,9}");

    /** A NOC request to port a number in. */
    public record PortInRequest(String phoneNumber, String numberType, String donorOperatorCode,
                                String identificationValue, LocalDateTime requestedPortTimeUtc, String customerBatchId) {
    }

    private final WorkflowServices services;
    private final WorkflowSupport support;
    private final PublicationTracker publication;
    private final PortOutPolicy policy;
    private final WorkflowEngine engine;

    public PortingService(WorkflowServices services, PortOutPolicy policy) {
        this.services = services;
        this.support = new WorkflowSupport(services);
        this.publication = new PublicationTracker(support);
        this.policy = policy;
        this.engine = services.engine();
    }

    public PublicationTracker publication() {
        return publication;
    }

    // =================================================================== recipient: NOC actions

    /** Starts one port-in: creates the workflow and sends Check. One number per workflow (docs/01 rule 1). */
    public Future<JsonObject> startPortIn(PortInRequest request, String actor, String remoteIp) {
        String phone = request.phoneNumber() != null ? request.phoneNumber().trim() : null;
        if (phone == null || !PHONE.matcher(phone).matches()) {
            return Future.failedFuture(BusinessRuleException.badRequest(
                "phoneNumber must be an Israeli number (0XXXXXXXXX or 972XXXXXXXXX)"));
        }
        String numberType = request.numberType() != null ? request.numberType().trim().toUpperCase(java.util.Locale.ROOT) : null;
        if (!"FIXED".equals(numberType) && !"MOBILE".equals(numberType)) {
            return Future.failedFuture(BusinessRuleException.badRequest("numberType must be FIXED or MOBILE"));
        }
        if (request.requestedPortTimeUtc() != null && PortOutPolicy.portTimeProblem(request.requestedPortTimeUtc()) != null) {
            return Future.failedFuture(BusinessRuleException.badRequest(
                PortOutPolicy.portTimeProblem(request.requestedPortTimeUtc())));
        }
        return services.pool().withTransaction(tx -> services.operators().local(tx)
            .compose(local -> services.operators().byCode(tx, request.donorOperatorCode()).compose(donor -> {
                if (donor == null || donor.id().equals(local.id()) || !donor.active()) {
                    return Future.failedFuture(BusinessRuleException.badRequest(
                        "Unknown or inactive donor operator: " + request.donorOperatorCode()));
                }
                return activePortingFor(tx, phone).compose(active -> {
                    if (active) {
                        return Future.failedFuture(BusinessRuleException.conflict(
                            "A porting process for " + phone + " is already in progress"));
                    }
                    return ProtocolIds.nextRequestId(tx, local.operatorCode(), donor.operatorCode())
                        .compose(requestId -> engine.create(tx, new WorkflowEngine.NewWorkflow(StateMachines.PORTING,
                            StateMachines.RECIPIENT, donor.id(), requestId, phone, local.id(), donor.id(), null,
                            request.requestedPortTimeUtc(),
                            new JsonObject().put("numberType", numberType)
                                .put("identificationValue", request.identificationValue()),
                            request.customerBatchId()), actor))
                        .compose(wf -> support.send(tx, wf, "Check", PROCESS, donor, null,
                                ProtocolBodies.numberBlock(numberType, phone, request.identificationValue()))
                            .compose(messageId -> engine.transition(tx, wf, "CHECK_SENT", "CHECK_SENT", messageId, null, actor))
                            .compose(sent -> services.timers().start(tx, sent, "CHECK_RESPONSE_TIMEOUT", "RESPONSE_TIMEOUT")
                                .map(sent)))
                        .compose(wf -> audit(tx, actor, "PORT_IN_STARTED", wf, new JsonObject()
                                .put("phoneNumber", phone).put("donor", donor.operatorCode()), remoteIp)
                            .map(new JsonObject().put("workflowUuid", wf.uuid()).put("requestId", wf.requestId())));
                });
            })));
    }

    /** Asks the donor to move the scheduled port to another time. */
    public Future<Void> updatePortTime(String workflowUuid, LocalDateTime newPortTimeUtc, String actor, String remoteIp) {
        String problem = PortOutPolicy.portTimeProblem(newPortTimeUtc);
        if (problem != null) {
            return Future.failedFuture(BusinessRuleException.badRequest(problem));
        }
        return services.pool().withTransaction(tx -> recipientWorkflow(tx, workflowUuid, "PORT_SCHEDULED")
            .compose(wf -> donorOf(tx, wf)
                .compose(donor -> support.send(tx, wf, "Update", PROCESS, donor, null,
                    new JsonObject().put("portingDateTime", ProtocolBodies.timestamp(newPortTimeUtc))))
                .compose(messageId -> services.timers().cancel(tx, wf.id(), EXECUTE_TIMER)
                    .compose(v -> engine.mergeContext(tx, wf, new JsonObject().put("pendingPortTime", newPortTimeUtc.toString())))
                    .compose(withPending -> engine.transition(tx, withPending, "UPDATE_SENT", "UPDATE_SENT", messageId, null, actor)))
                .compose(sent -> services.timers().start(tx, sent, "UPDATE_RESPONSE_TIMEOUT", "RESPONSE_TIMEOUT"))
                .compose(v -> audit(tx, actor, "PORT_TIME_UPDATE_REQUESTED", wf,
                    new JsonObject().put("newPortTime", newPortTimeUtc.toString()), remoteIp))));
    }

    /** Asks the donor to cancel an approved port. */
    public Future<Void> cancelPort(String workflowUuid, String reason, String actor, String remoteIp) {
        if (reason == null || reason.isBlank()) {
            return Future.failedFuture(BusinessRuleException.badRequest("A reason is required to cancel a port"));
        }
        return services.pool().withTransaction(tx -> engine.lockByUuid(tx, workflowUuid).compose(wf -> {
            if (!StateMachines.RECIPIENT.equals(wf.role()) || !wf.machine().allows(wf.state(), "CANCEL_SENT")) {
                return Future.failedFuture(BusinessRuleException.conflict(
                    "A port can only be cancelled after the Request was approved and before execution (state " + wf.state() + ")"));
            }
            return donorOf(tx, wf)
                .compose(donor -> support.send(tx, wf, "Cancel", PROCESS, donor, null, new JsonObject().put("reason", reason)))
                .compose(messageId -> services.timers().cancel(tx, wf.id(), EXECUTE_TIMER)
                    .compose(v -> engine.transition(tx, wf, "CANCEL_SENT", "CANCEL_SENT", messageId,
                        new JsonObject().put("reason", reason), actor)))
                .compose(sent -> services.timers().start(tx, sent, "CANCEL_RESPONSE_TIMEOUT", "RESPONSE_TIMEOUT"))
                .compose(v -> audit(tx, actor, "PORT_CANCEL_REQUESTED", wf, new JsonObject().put("reason", reason), remoteIp));
        }));
    }

    /** Withdraws the publication of a completed port-in from every operator that accepted it. */
    public Future<Void> cancelPublication(String workflowUuid, String reason, String actor, String remoteIp) {
        if (reason == null || reason.isBlank()) {
            return Future.failedFuture(BusinessRuleException.badRequest("A reason is required to cancel a publication"));
        }
        return services.pool().withTransaction(tx -> recipientWorkflow(tx, workflowUuid, "COMPLETED")
            .compose(wf -> publication.approvedRecipients(tx, wf)
                .compose(ids -> operatorsById(tx, ids))
                .compose(recipients -> publication.publish(tx, wf, PublicationTracker.CANCEL_PUBLISH, "Cancel_Publish",
                    PROCESS, recipients, publicationNumber(wf)))
                .compose(count -> engine.transition(tx, wf, "CANCEL_PUBLISH_SENT", "CANCEL_PUBLISH_SENT", null,
                        new JsonObject().put("reason", reason).put("recipients", count), actor)
                    // the number goes back to the donor's routing
                    .compose(sent -> NumberOwnershipService.transfer(tx, wf.phoneNumber(), wf.context().getString("numberType"),
                            wf.donorOperatorId(), "PORTED", "PUBLICATION_CANCELLED", wf.id(), null)
                        .compose(v -> count == 0
                            ? publication.outcomeAfterCancel(tx, sent)
                            : services.timers().start(tx, sent, "CANCEL_PUBLISH_RESPONSE_TIMEOUT", "PUBLICATION_TIMEOUT").map(sent))))
                .compose(v -> audit(tx, actor, "PUBLICATION_CANCEL_REQUESTED", wf, new JsonObject().put("reason", reason), remoteIp))));
    }

    // =================================================================== recipient: responses and timers

    Future<Void> onCheckResponse(SqlClient tx, InboundMessage response) {
        return support.workflowForResponse(tx, response, "Check").compose(wf -> {
            if (!"CHECK_SENT".equals(wf.state())) {
                return lateResponse(tx, wf, response);
            }
            return services.timers().cancel(tx, wf.id(), "CHECK_RESPONSE_TIMEOUT").compose(v -> {
                if (!response.approved()) {
                    return reject(tx, wf, "CHECK_REJECTED", response);
                }
                return engine.transition(tx, wf, "CHECK_APPROVED", "CHECK_APPROVED", response.messageId(), null, "system")
                    .compose(approved -> sendRequest(tx, approved));
            });
        });
    }

    private Future<Void> sendRequest(SqlClient tx, Workflow wf) {
        LocalDateTime portTime = wf.requestedPortTime() != null ? wf.requestedPortTime()
            : LocalDateTime.now(ZoneOffset.UTC).plusMinutes(Env.getInt("PORTING_DEFAULT_LEAD_MINUTES", 15));
        return donorOf(tx, wf)
            .compose(donor -> support.send(tx, wf, "Request", PROCESS, donor, null,
                new JsonObject().put("portingDateTime", ProtocolBodies.timestamp(portTime))))
            .compose(messageId -> engine.update(tx, wf, Map.of("requested_port_time", portTime))
                .compose(v -> engine.transition(tx, wf, "REQUEST_SENT", "REQUEST_SENT", messageId, null, "system")))
            .compose(sent -> services.timers().start(tx, sent, "REQUEST_RESPONSE_TIMEOUT", "RESPONSE_TIMEOUT"))
            .mapEmpty();
    }

    Future<Void> onRequestResponse(SqlClient tx, InboundMessage response) {
        return support.workflowForResponse(tx, response, "Request").compose(wf -> {
            if (!"REQUEST_SENT".equals(wf.state())) {
                return lateResponse(tx, wf, response);
            }
            return services.timers().cancel(tx, wf.id(), "REQUEST_RESPONSE_TIMEOUT").compose(v -> {
                if (!response.approved()) {
                    return reject(tx, wf, "REQUEST_REJECTED", response);
                }
                LocalDateTime approvedTime = firstNonNull(ProtocolBodies.parseUtc(response.body().getString("portingDateTime")),
                    wf.requestedPortTime());
                return engine.update(tx, wf, Map.of("approved_port_time", approvedTime))
                    .compose(u -> engine.transition(tx, wf, "REQUEST_APPROVED", "REQUEST_APPROVED", response.messageId(),
                        new JsonObject().put("approvedPortTime", approvedTime.toString()), "system"))
                    .compose(approved -> Env.getBoolean("PORTING_KD_UPDATE_REQUIRED", false)
                        ? sendKdUpdate(tx, approved)
                        : schedule(tx, approved, approvedTime));
            });
        });
    }

    private Future<Void> sendKdUpdate(SqlClient tx, Workflow wf) {
        return donorOf(tx, wf)
            .compose(donor -> support.send(tx, wf, "KD_update", PROCESS, donor, null, new JsonObject()))
            .compose(messageId -> engine.transition(tx, wf, "KD_UPDATE_SENT", "KD_UPDATE_SENT", messageId, null, "system"))
            .compose(sent -> services.timers().start(tx, sent, "KD_UPDATE_RESPONSE_TIMEOUT", "RESPONSE_TIMEOUT"))
            .mapEmpty();
    }

    Future<Void> onKdUpdateResponse(SqlClient tx, InboundMessage response) {
        return support.workflowForResponse(tx, response, "KD_update").compose(wf -> {
            if (!"KD_UPDATE_SENT".equals(wf.state())) {
                return lateResponse(tx, wf, response);
            }
            return services.timers().cancel(tx, wf.id(), "KD_UPDATE_RESPONSE_TIMEOUT").compose(v -> response.approved()
                ? engine.lock(tx, wf.id()).compose(locked -> schedule(tx, locked, locked.approvedPortTime()))
                : support.escalate(tx, wf, response.messageId(), "KD_UPDATE_REJECTED",
                    "The KD_update for " + wf.phoneNumber() + " was rejected: " + WorkflowSupport.rejectionOf(response)).mapEmpty());
        });
    }

    /** PORT_SCHEDULED with the execution timer at the approved time (or now, if that passed). */
    private Future<Void> schedule(SqlClient tx, Workflow wf, LocalDateTime approvedUtc) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime due = approvedUtc == null || approvedUtc.isBefore(now) ? now : approvedUtc;
        Future<Workflow> scheduled = "PORT_SCHEDULED".equals(wf.state()) ? Future.succeededFuture(wf)
            : engine.transition(tx, wf, "PORT_SCHEDULED", "PORT_SCHEDULED", null,
                new JsonObject().put("executeAt", due.toString()), "system");
        return scheduled.compose(s -> services.timers().cancel(tx, s.id(), EXECUTE_TIMER)
            .compose(v -> services.timers().startAt(tx, s, EXECUTE_TIMER, EXECUTE_ACTION, due)))
            .mapEmpty();
    }

    /** The approved port time arrived: ask the donor to disconnect (trace: recipient sends Execute). */
    Future<Void> execute(SqlClient tx, Workflow wf) {
        return donorOf(tx, wf)
            .compose(donor -> support.send(tx, wf, "Execute", PROCESS, donor, null, new JsonObject()))
            .compose(messageId -> engine.transition(tx, wf, "EXECUTION_SENT", "EXECUTION_SENT", messageId, null, "system"))
            .compose(sent -> services.timers().start(tx, sent, "EXECUTION_RESPONSE_TIMEOUT", "RESPONSE_TIMEOUT"))
            .mapEmpty();
    }

    Future<Void> onExecuteResponse(SqlClient tx, InboundMessage response) {
        return support.workflowForResponse(tx, response, "Execute").compose(wf -> {
            if (!"EXECUTION_SENT".equals(wf.state())) {
                return lateResponse(tx, wf, response);
            }
            return services.timers().cancel(tx, wf.id(), "EXECUTION_RESPONSE_TIMEOUT").compose(v -> {
                if (!response.approved()) {
                    return support.escalate(tx, wf, response.messageId(), "EXECUTION_REJECTED",
                        "The donor refused to disconnect " + wf.phoneNumber() + ": " + WorkflowSupport.rejectionOf(response))
                        .mapEmpty();
                }
                LocalDateTime disconnectedAt = firstNonNull(
                    ProtocolBodies.parseUtc(response.body().getString("disconnectDateTime")), LocalDateTime.now(ZoneOffset.UTC));
                return services.operators().local(tx).compose(local -> engine.update(tx, wf, Map.of("executed_at", disconnectedAt,
                        "current_operator_id", local.id()))
                    .compose(u -> engine.transition(tx, wf, "EXECUTION_CONFIRMED", "EXECUTION_CONFIRMED", response.messageId(),
                        new JsonObject().put("disconnectDateTime", disconnectedAt.toString()), "system"))
                    // the number is ours from here on
                    .compose(confirmed -> NumberOwnershipService.transfer(tx, wf.phoneNumber(),
                            wf.context().getString("numberType"), local.id(), wf.donorOperatorId(), "PORTED", "PORT_IN",
                            wf.id(), response.messageId())
                        .compose(o -> publishPortIn(tx, confirmed, local, disconnectedAt))));
            });
        });
    }

    /** Announces the new routing to every other operator (trace: all operators except the donor). */
    private Future<Void> publishPortIn(SqlClient tx, Workflow wf, Operator local, LocalDateTime disconnectedAt) {
        return Future.all(services.operators().peersExcept(tx, local.id(), wf.donorOperatorId()), donorOf(tx, wf))
            .compose(all -> {
                List<Operator> recipients = all.resultAt(0);
                Operator donor = all.resultAt(1);
                JsonObject body = new JsonObject()
                    .put("donor", donor.operatorCode())
                    .put("connectDateTime", ProtocolBodies.now())
                    .put("publishType", "Port")
                    .put("disconnectDateTime", ProtocolBodies.timestamp(disconnectedAt))
                    .mergeIn(publicationNumber(wf));
                return publication.publish(tx, wf, PublicationTracker.PUBLISH, "Publish", PROCESS, recipients, body)
                    .compose(count -> engine.transition(tx, wf, "PUBLISH_SENT", "PUBLISH_SENT", null,
                            new JsonObject().put("recipients", count), "system")
                        .compose(sent -> count == 0
                            ? publication.outcomeAfterPublish(tx, sent)
                            : services.timers().start(tx, sent, "PUBLICATION_RESPONSE_TIMEOUT", "PUBLICATION_TIMEOUT").map(sent)));
            })
            .mapEmpty();
    }

    Future<Void> onUpdateResponse(SqlClient tx, InboundMessage response) {
        return support.workflowForResponse(tx, response, "Update").compose(wf -> {
            if (!"UPDATE_SENT".equals(wf.state())) {
                return lateResponse(tx, wf, response);
            }
            LocalDateTime pending = wf.context().getString("pendingPortTime") != null
                ? LocalDateTime.parse(wf.context().getString("pendingPortTime")) : null;
            return services.timers().cancel(tx, wf.id(), "UPDATE_RESPONSE_TIMEOUT").compose(v -> {
                if (response.approved() && pending != null) {
                    return engine.update(tx, wf, Map.of("approved_port_time", pending))
                        .compose(u -> schedule(tx, wf, pending));
                }
                // rejected: the original time stands
                return schedule(tx, wf, wf.approvedPortTime())
                    .compose(s -> support.task(tx, wf, response.messageId(), "PORT_TIME_UPDATE_REJECTED",
                        ManualTaskRepository.SEVERITY_LOW, "The donor rejected moving the port of " + wf.phoneNumber()
                            + "; it stays scheduled for " + wf.approvedPortTime() + " UTC. " + WorkflowSupport.rejectionOf(response)));
            });
        });
    }

    Future<Void> onCancelResponse(SqlClient tx, InboundMessage response) {
        return support.workflowForResponse(tx, response, "Cancel").compose(wf -> {
            if (!"CANCEL_SENT".equals(wf.state())) {
                return lateResponse(tx, wf, response);
            }
            if (response.approved()) {
                return services.timers().cancelAll(tx, wf.id())
                    .compose(v -> engine.transition(tx, wf, "CANCELLED", "CANCELLED", response.messageId(), null, "system"))
                    .mapEmpty();
            }
            return services.timers().cancel(tx, wf.id(), "CANCEL_RESPONSE_TIMEOUT")
                .compose(v -> schedule(tx, wf, wf.approvedPortTime()))
                .compose(v -> support.task(tx, wf, response.messageId(), "PORT_CANCEL_REJECTED", ManualTaskRepository.SEVERITY_HIGH,
                    "The donor rejected cancelling the port of " + wf.phoneNumber() + "; it will still execute at "
                        + wf.approvedPortTime() + " UTC unless resolved. " + WorkflowSupport.rejectionOf(response)));
        });
    }

    // =================================================================== donor

    Future<Void> onCheck(SqlClient tx, InboundMessage check) {
        String phone = check.body().getString("phoneNumber");
        return engine.findForUpdate(tx, check.requestId(), StateMachines.PORTING, StateMachines.DONOR, check.senderOperatorId())
            .compose(existing -> {
                if (existing != null) {
                    return engine.recordEvent(tx, existing, "DUPLICATE_CHECK_IGNORED", check.messageId(), null, "system");
                }
                if (phone == null || phone.isBlank()) {
                    return support.respond(tx, null, check, "Check_response", PROCESS,
                        ProtocolBodies.reject(check.trxNo(), "REJECTED_NUMBER_NOT_FOUND", "No number in the Check")).mapEmpty();
                }
                String numberType = switch (check.body().getString("networkType", "")) {
                    case "F" -> "FIXED";
                    case "M" -> "MOBILE";
                    default -> "UNKNOWN";
                };
                return engine.create(tx, new WorkflowEngine.NewWorkflow(StateMachines.PORTING, StateMachines.DONOR,
                        check.senderOperatorId(), check.requestId(), phone, check.senderOperatorId(), check.receiverOperatorId(),
                        null, null, new JsonObject().put("numberType", numberType)
                            .put("identificationValue", check.body().getString("identificationValue"))
                            .put("pending", pending(check)), null), "system")
                    .compose(wf -> services.operators().local(tx)
                        .compose(local -> policy.onCheck(tx, wf, local.id()))
                        .compose(decision -> decide(tx, wf, check, decision)));
            });
    }

    Future<Void> onRequest(SqlClient tx, InboundMessage request) {
        return donorWorkflow(tx, request, "CHECK_APPROVED").compose(wf -> {
            if (wf == null) {
                return support.respond(tx, null, request, "Request_response", PROCESS, ProtocolBodies.reject(request.trxNo(),
                    "REJECTED_OTHER", "No approved Check for this request")).mapEmpty();
            }
            LocalDateTime requested = ProtocolBodies.parseUtc(request.body().getString("portingDateTime"));
            Map<String, Object> columns = requested != null ? Map.of("requested_port_time", requested) : Map.of();
            return engine.update(tx, wf, columns)
                .compose(v -> engine.lock(tx, wf.id()))  // fresh snapshot including the requested time
                .compose(fresh -> engine.mergeContext(tx, fresh, new JsonObject().put("pending", pending(request))))
                .compose(withPending -> engine.transition(tx, withPending, "REQUEST_RECEIVED", "REQUEST_RECEIVED",
                    request.messageId(), null, "system"))
                .compose(received -> decide(tx, received, request, policy.onRequest(requested)));
        });
    }

    /** Applies an approve/reject/manual decision to a pending Check or Request. */
    private Future<Void> decide(SqlClient tx, Workflow wf, InboundMessage pending, PortOutPolicy.Decision decision) {
        return switch (decision.outcome()) {
            case MANUAL -> support.task(tx, wf, pending.messageId(), DECISION_TASK, ManualTaskRepository.SEVERITY_HIGH,
                "Decide whether " + wf.phoneNumber() + " may port out (" + pending.messageType() + " "
                    + pending.requestId() + "). Approve or reject it from the workflow page before the recipient's "
                    + "response deadline." + (decision.reasonText() != null ? " " + decision.reasonText() : ""));
            case APPROVE -> answer(tx, wf, pending, true, null, null);
            case REJECT -> answer(tx, wf, pending, false, decision.reasonCode(), decision.reasonText());
        };
    }

    /** Sends the donor's Check_response / Request_response and moves the workflow on. */
    private Future<Void> answer(SqlClient tx, Workflow wf, InboundMessage pending, boolean approve, String code, String text) {
        boolean isCheck = "Check".equals(pending.messageType());
        String responseType = isCheck ? "Check_response" : "Request_response";
        JsonObject body = approve ? ProtocolBodies.approve(pending.trxNo(), null) : ProtocolBodies.reject(pending.trxNo(), code, text);
        String target = approve ? (isCheck ? "CHECK_APPROVED" : "PORT_SCHEDULED") : (isCheck ? "CHECK_REJECTED" : "REQUEST_REJECTED");
        Map<String, Object> columns = new java.util.HashMap<>();
        if (!approve) {
            columns.put("failure_code", code);
            columns.put("failure_reason", text);
        } else if (!isCheck && wf.requestedPortTime() != null) {
            columns.put("approved_port_time", wf.requestedPortTime());
        }
        return support.respond(tx, wf, pending, responseType, PROCESS, body)
            .compose(messageId -> engine.update(tx, wf, columns)
                .compose(v -> engine.transition(tx, wf, target, target, messageId,
                    approve ? null : new JsonObject().put("reasonCode", code).put("reasonText", text), "system")))
            .compose(v -> closeDecisionTask(tx, wf, approve ? "Approved" : "Rejected: " + code));
    }

    /** NOC decision on a port-out that {@link PortOutPolicy} left for manual review. */
    public Future<Void> decidePortOut(String workflowUuid, boolean approve, String reasonCode, String reasonText,
                                      String actor, String remoteIp) {
        if (!approve && (reasonCode == null || reasonCode.isBlank())) {
            return Future.failedFuture(BusinessRuleException.badRequest("A rejection needs a reasonCode"));
        }
        return services.pool().withTransaction(tx -> engine.lockByUuid(tx, workflowUuid).compose(wf -> {
            if (!StateMachines.DONOR.equals(wf.role())
                || !("CHECK_RECEIVED".equals(wf.state()) || "REQUEST_RECEIVED".equals(wf.state()))) {
                return Future.failedFuture(BusinessRuleException.conflict("No port-out decision is pending (state " + wf.state() + ")"));
            }
            JsonObject p = wf.context().getJsonObject("pending");
            if (p == null) {
                return Future.failedFuture(BusinessRuleException.conflict("The pending request was not recorded"));
            }
            InboundMessage pendingMessage = new InboundMessage(p.getLong("messageId"), null, p.getString("messageType"),
                wf.requestId(), p.getString("trxNo"), null, wf.counterpartyOperatorId(), null, new JsonObject());
            return answer(tx, wf, pendingMessage, approve, reasonCode, reasonText)
                .compose(v -> audit(tx, actor, approve ? "PORT_OUT_APPROVED" : "PORT_OUT_REJECTED", wf,
                    new JsonObject().put("reasonCode", reasonCode).put("reasonText", reasonText), remoteIp));
        }));
    }

    Future<Void> onUpdate(SqlClient tx, InboundMessage update) {
        return donorWorkflow(tx, update, "PORT_SCHEDULED").compose(wf -> {
            LocalDateTime newTime = ProtocolBodies.parseUtc(update.body().getString("portingDateTime"));
            String problem = wf == null ? "No scheduled port for this request" : PortOutPolicy.portTimeProblem(newTime);
            if (problem != null) {
                return support.respond(tx, wf, update, "Update_response", PROCESS, ProtocolBodies.reject(update.trxNo(),
                    wf == null ? "REJECTED_OTHER" : "REJECTED_INVALID_PORT_TIME", problem)).mapEmpty();
            }
            return engine.update(tx, wf, Map.of("approved_port_time", newTime))
                .compose(v -> engine.recordEvent(tx, wf, "PORT_TIME_UPDATED", update.messageId(),
                    new JsonObject().put("approvedPortTime", newTime.toString()), "system"))
                .compose(v -> support.respond(tx, wf, update, "Update_response", PROCESS,
                    ProtocolBodies.approve(update.trxNo(), null)))
                .mapEmpty();
        });
    }

    Future<Void> onCancel(SqlClient tx, InboundMessage cancel) {
        return engine.findForUpdate(tx, cancel.requestId(), StateMachines.PORTING, StateMachines.DONOR, cancel.senderOperatorId())
            .compose(wf -> {
                if (wf == null || !wf.machine().allows(wf.state(), "CANCELLED")) {
                    return support.respond(tx, wf, cancel, "Cancel_response", PROCESS, ProtocolBodies.reject(cancel.trxNo(),
                        "REJECTED_CANCELLATION_WINDOW_CLOSED", wf == null ? "Unknown request" : "The port is " + wf.state()))
                        .mapEmpty();
                }
                return support.respond(tx, wf, cancel, "Cancel_response", PROCESS, ProtocolBodies.approve(cancel.trxNo(), null))
                    .compose(messageId -> engine.transition(tx, wf, "CANCELLED", "CANCELLED", cancel.messageId(),
                        new JsonObject().put("reason", cancel.body().getString("reason")), "system"))
                    .compose(v -> closeDecisionTask(tx, wf, "Cancelled by the recipient"));
            });
    }

    Future<Void> onKdUpdate(SqlClient tx, InboundMessage kd) {
        return engine.findForUpdate(tx, kd.requestId(), StateMachines.PORTING, StateMachines.DONOR, kd.senderOperatorId())
            .compose(wf -> {
                boolean open = wf != null && !wf.machine().isTerminal(wf.state());
                return (open ? engine.recordEvent(tx, wf, "KD_UPDATE_RECEIVED", kd.messageId(), null, "system")
                        : Future.<Void>succeededFuture())
                    .compose(v -> support.respond(tx, wf, kd, "KD_update_response", PROCESS, open
                        ? ProtocolBodies.approve(kd.trxNo(), null)
                        : ProtocolBodies.reject(kd.trxNo(), "REJECTED_OTHER", "No open porting process for this request")))
                    .mapEmpty();
            });
    }

    /** The recipient executes the port: release the number and confirm the disconnection. */
    Future<Void> onExecute(SqlClient tx, InboundMessage execute) {
        return donorWorkflow(tx, execute, "PORT_SCHEDULED").compose(wf -> {
            if (wf == null) {
                return support.respond(tx, null, execute, "Execute_response", PROCESS, ProtocolBodies.reject(execute.trxNo(),
                    "REJECTED_OTHER", "No scheduled port for this request")).mapEmpty();
            }
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            return services.operators().local(tx).compose(local -> NumberOwnershipService.lockOwnership(tx, wf.phoneNumber())
                .compose(recorded -> {
                    // re-check under the row lock: the number may have moved since the Check was approved
                    if (recorded != null && recorded.currentOperatorId() != null && !recorded.currentOperatorId().equals(local.id())) {
                        return support.respond(tx, wf, execute, "Execute_response", PROCESS, ProtocolBodies.reject(execute.trxNo(),
                                "REJECTED_NUMBER_NOT_FOUND", "The number is no longer served by this operator"))
                            .compose(m -> support.escalate(tx, wf, execute.messageId(), "PORT_OUT_OWNERSHIP_CHANGED",
                                "Execute for " + wf.phoneNumber() + " refused: the number is no longer ours."))
                            .<Void>mapEmpty()
                            .compose(v -> Future.failedFuture(new AlreadyAnswered()));
                    }
                    return NumberOwnershipService.transfer(tx, wf.phoneNumber(), wf.context().getString("numberType"),
                        execute.senderOperatorId(), local.id(), "PORTED", "PORT_OUT", wf.id(), execute.messageId());
                }))
                .compose(v -> support.respond(tx, wf, execute, "Execute_response", PROCESS, ProtocolBodies.approve(execute.trxNo(),
                    new JsonObject().put("disconnectDateTime", ProtocolBodies.timestamp(now)))))
                .compose(messageId -> engine.update(tx, wf, Map.of("executed_at", now,
                        "current_operator_id", execute.senderOperatorId()))
                    .compose(v -> engine.transition(tx, wf, "COMPLETED", "PORT_OUT_EXECUTED", execute.messageId(), null, "system")))
                .<Void>mapEmpty()
                .recover(err -> err instanceof AlreadyAnswered ? Future.succeededFuture() : Future.failedFuture(err));
        });
    }

    // =================================================================== helpers

    /** The donor-side workflow for an inbound request, if it is in {@code expectedState}; else null. */
    private Future<Workflow> donorWorkflow(SqlClient tx, InboundMessage message, String expectedState) {
        return engine.findForUpdate(tx, message.requestId(), StateMachines.PORTING, StateMachines.DONOR, message.senderOperatorId())
            .map(wf -> wf != null && expectedState.equals(wf.state()) ? wf : null);
    }

    private Future<Workflow> recipientWorkflow(SqlClient tx, String workflowUuid, String requiredState) {
        return engine.lockByUuid(tx, workflowUuid).compose(wf -> {
            if (!StateMachines.PORTING.equals(wf.type()) || !StateMachines.RECIPIENT.equals(wf.role())) {
                return Future.failedFuture(BusinessRuleException.badRequest("Not a port-in workflow"));
            }
            if (!requiredState.equals(wf.state())) {
                return Future.failedFuture(BusinessRuleException.conflict(
                    "This action needs state " + requiredState + "; the workflow is " + wf.state()));
            }
            return Future.succeededFuture(wf);
        });
    }

    private Future<Operator> donorOf(SqlClient tx, Workflow wf) {
        return services.operators().byId(tx, wf.donorOperatorId());
    }

    private Future<List<Operator>> operatorsById(SqlClient tx, List<Long> ids) {
        Future<List<Operator>> all = Future.succeededFuture(new java.util.ArrayList<>());
        for (Long id : ids) {
            all = all.compose(list -> services.operators().byId(tx, id).map(op -> {
                if (op != null) {
                    list.add(op);
                }
                return list;
            }));
        }
        return all;
    }

    private Future<Boolean> activePortingFor(SqlClient tx, String phone) {
        return tx.preparedQuery("""
                SELECT COUNT(*) AS c FROM np_workflows WHERE workflow_type = 'PORTING' AND phone_number = ?
                  AND current_state NOT IN ('COMPLETED', 'CANCELLED', 'CHECK_REJECTED', 'REQUEST_REJECTED',
                                            'PUBLICATION_CANCELLED', 'FAILED')
                """).execute(Tuple.of(phone))
            .map(rows -> rows.iterator().next().getLong("c") > 0);
    }

    /** Number element of a Publish / Cancel_Publish (trace: number block without identification). */
    private static JsonObject publicationNumber(Workflow wf) {
        JsonObject block = ProtocolBodies.numberBlock(wf.context().getString("numberType"), wf.phoneNumber(), null);
        block.forEach(e -> ((JsonObject) e.getValue()).remove("networkType"));
        return block;
    }

    private static JsonObject pending(InboundMessage message) {
        return new JsonObject().put("messageType", message.messageType()).put("trxNo", message.trxNo())
            .put("messageId", message.messageId());
    }

    private Future<Void> reject(SqlClient tx, Workflow wf, String state, InboundMessage response) {
        JsonObject rejection = WorkflowSupport.rejectionOf(response);
        return engine.update(tx, wf, Map.of("failure_code", rejection.getString("rejectReasonCode"),
                "failure_reason", String.valueOf(rejection.getString("rejectReasonText"))))
            .compose(v -> engine.transition(tx, wf, state, state, response.messageId(), rejection, "system"))
            .mapEmpty();
    }

    /** A response for a step the workflow already left (duplicate, or after a timeout/manual action). */
    private Future<Void> lateResponse(SqlClient tx, Workflow wf, InboundMessage response) {
        return engine.recordEvent(tx, wf, "LATE_RESPONSE_IGNORED", response.messageId(),
            new JsonObject().put("messageType", response.messageType()).put("approved", response.approved()), "system");
    }

    private Future<Void> closeDecisionTask(SqlClient tx, Workflow wf, String resolution) {
        return tx.preparedQuery("""
                UPDATE np_manual_tasks SET status = 'CLOSED', resolution = ?, closed_at = UTC_TIMESTAMP(6),
                                           updated_at = UTC_TIMESTAMP(6)
                WHERE workflow_id = ? AND task_type = ? AND status = 'OPEN'
                """).execute(Tuple.of(resolution, wf.id(), DECISION_TASK)).mapEmpty();
    }

    private Future<Void> audit(SqlClient tx, String actor, String action, Workflow wf, JsonObject details, String remoteIp) {
        return new AuditEventRepository(tx).insertAuditEvent("USER", actor, action, "WORKFLOW", wf.uuid(),
            details.copy().put("requestId", wf.requestId()).encode(), remoteIp).mapEmpty();
    }

    private static <T> T firstNonNull(T a, T b) {
        return a != null ? a : b;
    }

    /** Short-circuit marker: the request was already answered (and escalated) inside the chain. */
    private static final class AlreadyAnswered extends RuntimeException {
        AlreadyAnswered() {
            super(null, null, false, false);
        }
    }
}
