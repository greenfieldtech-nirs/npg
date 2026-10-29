-- Workflows are modelled from this gateway's point of view: the same protocol process looks
-- different to the recipient (gaining operator), the donor and a third party receiving a
-- publication, so each workflow records the local role it is executed in.
ALTER TABLE np_workflows
  ADD COLUMN local_role VARCHAR(32) NOT NULL DEFAULT 'RECIPIENT' AFTER workflow_type,
  ADD COLUMN context_json JSON NULL AFTER failure_reason,
  MODIFY COLUMN phone_number VARCHAR(32) NULL,
  DROP INDEX uq_workflow_request_phone,
  ADD UNIQUE KEY uq_workflow_request (request_id, workflow_type, local_role, phone_number);

-- Per-recipient publication tracking needs to find the row for an incoming Publish_response.
ALTER TABLE np_publication_recipients
  ADD COLUMN purpose VARCHAR(32) NOT NULL DEFAULT 'PUBLISH' AFTER operator_id,
  DROP INDEX uq_publication_workflow_operator,
  ADD UNIQUE KEY uq_publication_workflow_operator (workflow_id, operator_id, purpose),
  ADD INDEX idx_publication_message (message_id);

-- Timer durations and the working calendar. The official values are unconfirmed
-- (docs/protocol/open-questions.md): these are configurable placeholders, edit them here.
INSERT INTO np_system_parameters (param_key, param_value, description) VALUES
  ('timer.CHECK_RESPONSE_TIMEOUT.seconds', '3600', 'Donor must answer Check (working time)'),
  ('timer.REQUEST_RESPONSE_TIMEOUT.seconds', '3600', 'Donor must answer Request (working time)'),
  ('timer.UPDATE_RESPONSE_TIMEOUT.seconds', '3600', 'Donor must answer Update (working time)'),
  ('timer.CANCEL_RESPONSE_TIMEOUT.seconds', '3600', 'Donor must answer Cancel (working time)'),
  ('timer.KD_UPDATE_RESPONSE_TIMEOUT.seconds', '3600', 'Donor must answer KD_update (working time)'),
  ('timer.EXECUTION_RESPONSE_TIMEOUT.seconds', '1800', 'Donor must answer Execute (working time)'),
  ('timer.PUBLICATION_RESPONSE_TIMEOUT.seconds', '3600', 'Operators must answer Publish (working time)'),
  ('timer.CANCEL_PUBLISH_RESPONSE_TIMEOUT.seconds', '3600', 'Operators must answer Cancel_Publish (working time)'),
  ('timer.RETURN_RESPONSE_TIMEOUT.seconds', '86400', 'Original operator must answer Return (working time)'),
  ('timer.RETURN_COOLING_PERIOD.seconds', '2592000', 'Quarantine before a disconnected ported number is returned (calendar time)'),
  ('timer.INQUIRY_RESPONSE_TIMEOUT.seconds', '3600', 'Peer must answer Inquire_number (working time)'),
  ('timer.MAINTENANCE_WINDOW_TIMEOUT.seconds', '86400', 'Alert if a peer stays down longer (calendar time)'),
  ('timer.SYNCH_RESPONSE_TIMEOUT.seconds', '86400', 'Peer must answer DB_synch_request (working time)'),
  ('calendar.zone', 'Asia/Jerusalem', 'Time zone of the working calendar'),
  ('calendar.workday.start', '08:00', 'Start of the working day'),
  ('calendar.workday.end', '20:00', 'End of the working day'),
  ('calendar.weekend', 'FRIDAY,SATURDAY', 'Non-working weekdays; holidays go in np_working_calendar')
ON DUPLICATE KEY UPDATE param_key = param_key;
