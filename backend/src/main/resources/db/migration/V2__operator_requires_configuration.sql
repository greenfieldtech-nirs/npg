ALTER TABLE np_operators
  ADD COLUMN requires_configuration BOOLEAN NOT NULL DEFAULT FALSE AFTER is_active;
