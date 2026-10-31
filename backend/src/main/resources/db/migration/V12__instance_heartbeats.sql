-- Every backend process reports in here (ops.Heartbeats, every 15 s), so the console can show which
-- runtime roles are alive across containers. Small: one row per process, stale rows pruned.
CREATE TABLE IF NOT EXISTS np_instance_heartbeats (
  instance_id VARCHAR(64) PRIMARY KEY,
  role VARCHAR(32) NOT NULL,
  hostname VARCHAR(255) NULL,
  started_at DATETIME(6) NOT NULL,
  last_seen_at DATETIME(6) NOT NULL,
  KEY idx_np_instance_heartbeats_last_seen (last_seen_at)
);
