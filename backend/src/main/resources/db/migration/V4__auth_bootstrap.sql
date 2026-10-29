ALTER TABLE np_users
  ADD COLUMN password_hash VARCHAR(255) NOT NULL DEFAULT '' AFTER display_name;
