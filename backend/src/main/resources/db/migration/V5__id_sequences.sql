-- Monotonic counters for protocol identifiers (TRX_NO, REQUEST_ID). Incremented atomically with
-- UPDATE ... SET next_value = LAST_INSERT_ID(next_value + 1), which works on MariaDB and MySQL alike.
CREATE TABLE IF NOT EXISTS np_id_sequences (
  sequence_name VARCHAR(64) PRIMARY KEY,
  next_value BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO np_id_sequences (sequence_name, next_value) VALUES ('TRX_NO', 0), ('REQUEST_ID', 0)
  ON DUPLICATE KEY UPDATE sequence_name = sequence_name;
