package cloud.cloudonix.npg.workflow.handler;

import cloud.cloudonix.npg.workflow.InboundMessage;
import cloud.cloudonix.npg.workflow.StateMachines;
import cloud.cloudonix.npg.workflow.WorkflowEngine;
import cloud.cloudonix.npg.workflow.WorkflowServices;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;

/** Porting messages. */
public class PortingWorkflowHandler implements WorkflowHandler {

    private final WorkflowServices services;

    public PortingWorkflowHandler(WorkflowServices services) {
        this.services = services;
    }

    @Override
    public Future<Void> handle(SqlConnection tx, Row inboxItem) {
        return InboundMessage.load(tx, services.xmlAudit(), services.catalog(), inboxItem)
            .compose(message -> switch (message.messageType()) {
                case "Check" -> onCheck(tx, message);
                default -> Future.succeededFuture();
            });
    }

    /** A recipient asks whether one of our numbers may port out: we are the donor. */
    private Future<Void> onCheck(SqlConnection tx, InboundMessage message) {
        String phone = message.body().getString("phoneNumber");
        String numberType = switch (message.body().getString("networkType", "")) {
            case "F" -> "FIXED";
            case "M" -> "MOBILE";
            default -> "UNKNOWN";
        };
        return services.engine().findForUpdate(tx, message.requestId(), StateMachines.PORTING, StateMachines.DONOR)
            .compose(existing -> existing != null
                ? Future.<Void>succeededFuture()
                : services.engine().create(tx, new WorkflowEngine.NewWorkflow(StateMachines.PORTING, StateMachines.DONOR,
                        message.requestId(), phone, message.senderOperatorId(), message.receiverOperatorId(), null, null,
                        new JsonObject().put("numberType", numberType).put("checkTrxNo", message.trxNo()), null), "system")
                    .mapEmpty());
    }
}
