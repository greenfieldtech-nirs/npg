package cloud.cloudonix.npg.repository;

import io.vertx.core.Future;
import io.vertx.mysqlclient.MySQLClient;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.UUID;

public class AuditEventRepository {

    private final SqlClient sqlClient;

    public AuditEventRepository(SqlClient sqlClient) {
        this.sqlClient = sqlClient;
    }

    public Future<Long> insertAuditEvent(String actorType, String actorId, String action,
                                          String entityType, String entityId, String detailsJson, String remoteIp) {
        String eventUuid = UUID.randomUUID().toString();
        return sqlClient.preparedQuery("""
                INSERT INTO np_audit_events (event_uuid, actor_type, actor_id, action, entity_type, entity_id, details_json, remote_ip)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)
            .execute(Tuple.of(eventUuid, actorType, actorId, action, entityType, entityId, detailsJson, remoteIp))
            .map(rs -> rs.property(MySQLClient.LAST_INSERTED_ID));
    }
}
