-- Israel numbering plan (Ministry of Communications, published on data.gov.il): the original
-- allocation of every number range to an operator, before any porting. One row per range.
CREATE TABLE IF NOT EXISTS np_numbering_plan (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  ndc VARCHAR(4) NOT NULL,
  e164_pattern VARCHAR(32) NOT NULL,
  national_pattern VARCHAR(32) NOT NULL,
  nsn_prefix VARCHAR(16) NOT NULL,          -- national significant number digits before the X run
  nsn_length SMALLINT NOT NULL,             -- digits after +972
  range_size BIGINT NOT NULL,               -- numbers in the range (10 ^ number of X)
  allocated_to VARCHAR(255) NOT NULL,
  notes VARCHAR(255) NULL,
  number_type VARCHAR(32) NOT NULL,         -- libphonenumber PhoneNumberType of the range's numbers
  area VARCHAR(128) NULL,                   -- libphonenumber geocoder description (geographic numbers)
  example_international VARCHAR(32) NULL,
  example_national VARCHAR(32) NULL,
  libphonenumber_valid BOOLEAN NOT NULL,    -- does Google's metadata accept numbers of this range?
  UNIQUE KEY uq_numbering_plan_prefix (nsn_prefix, nsn_length),
  INDEX idx_numbering_plan_operator (allocated_to),
  INDEX idx_numbering_plan_ndc (ndc)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS np_numbering_plan_imports (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  source VARCHAR(512) NOT NULL,
  sha256 CHAR(64) NOT NULL,
  range_count INT NOT NULL,
  operator_count INT NOT NULL,
  warnings_json JSON NULL,
  imported_by VARCHAR(128) NULL,
  imported_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
