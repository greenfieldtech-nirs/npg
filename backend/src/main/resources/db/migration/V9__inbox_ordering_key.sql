-- Messages of one protocol process (same sender and REQUEST_ID) must be processed in arrival
-- order: a Request processed before its Check commits would be answered as if no Check existed.
-- Workers only claim the oldest unfinished inbox row per ordering key; unrelated processes still
-- run in parallel.
ALTER TABLE np_inbox
  ADD COLUMN ordering_key VARCHAR(255) NULL AFTER item_type,
  ADD INDEX idx_inbox_ordering (ordering_key, status, id);

UPDATE np_inbox i JOIN np_messages m ON m.id = i.message_id
SET i.ordering_key = CONCAT(COALESCE(m.sender_operator_id, ''), '|', COALESCE(m.request_id, ''))
WHERE i.ordering_key IS NULL;
