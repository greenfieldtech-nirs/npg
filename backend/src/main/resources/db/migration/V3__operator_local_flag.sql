ALTER TABLE np_operators
  ADD COLUMN is_local BOOLEAN NOT NULL DEFAULT FALSE AFTER requires_configuration;
