package cloud.cloudonix.npg.workflow;

import cloud.cloudonix.npg.audit.XmlAuditService;
import cloud.cloudonix.npg.number.NumberOwnershipService;
import cloud.cloudonix.npg.outbound.OutboundMessageService;
import cloud.cloudonix.npg.protocol.ProtocolCatalogLoader;
import cloud.cloudonix.npg.timer.TimerScheduler;
import io.vertx.core.Vertx;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.sqlclient.Pool;

/** Everything a workflow step needs, built the same way by the inbox worker, timer worker and API. */
public record WorkflowServices(Pool pool, WorkflowEngine engine, TimerScheduler timers,
                               OutboundMessageService outbound, XmlAuditService xmlAudit,
                               ProtocolCatalogLoader catalog, NumberOwnershipService numbers,
                               OperatorDirectory operators) {

    public static WorkflowServices create(Vertx vertx, Pool pool, MongoClient mongo, String catalogPath) {
        ProtocolCatalogLoader catalog = new ProtocolCatalogLoader(vertx, catalogPath);
        XmlAuditService xmlAudit = new XmlAuditService(mongo);
        return new WorkflowServices(pool, new WorkflowEngine(), new TimerScheduler(catalog),
            new OutboundMessageService(xmlAudit), xmlAudit, catalog, new NumberOwnershipService(pool),
            new OperatorDirectory());
    }
}
