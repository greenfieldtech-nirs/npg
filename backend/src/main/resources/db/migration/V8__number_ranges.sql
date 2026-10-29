-- Number blocks allocated to each operator (national format prefixes, e.g. '0812'). The native
-- operator of a number nobody ever ported is the operator with the longest matching prefix;
-- without this, a gateway cannot tell its own never-ported numbers from anyone else's.
CREATE TABLE IF NOT EXISTS np_number_ranges (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  operator_id BIGINT NOT NULL,
  prefix VARCHAR(32) NOT NULL,
  description VARCHAR(255) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_number_range_prefix (prefix),
  CONSTRAINT fk_number_range_operator FOREIGN KEY (operator_id) REFERENCES np_operators(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
