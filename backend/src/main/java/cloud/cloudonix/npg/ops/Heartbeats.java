package cloud.cloudonix.npg.ops;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;

/** Liveness of each backend process, recorded in MariaDB so any role can see every other one. */
public final class Heartbeats {

    /** A process that has not reported for this long is considered stale. */
    public static final int STALE_AFTER_SECONDS = 60;
    static final long INTERVAL_MS = 15_000;
    private static final int PRUNE_AFTER_HOURS = 24;

    private Heartbeats() {
    }

    /** Records that this process is alive, and prunes processes gone for a day. */
    public static Future<Void> beat(Pool pool, String instanceId, String role, String hostname) {
        return pool.preparedQuery("""
                INSERT INTO np_instance_heartbeats (instance_id, role, hostname, started_at, last_seen_at)
                VALUES (?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE last_seen_at = UTC_TIMESTAMP(6)
                """).execute(Tuple.of(instanceId, role, hostname))
            .compose(v -> pool.preparedQuery(
                    "DELETE FROM np_instance_heartbeats WHERE last_seen_at < UTC_TIMESTAMP(6) - INTERVAL ? HOUR")
                .execute(Tuple.of(PRUNE_AFTER_HOURS)))
            .mapEmpty();
    }
}
