-- A protocol REQUEST_ID is chosen by the peer that starts the process, so it only identifies a
-- workflow together with that peer. Binding every workflow to its counterparty stops another
-- (authenticated) peer from acting on someone else's process by reusing its REQUEST_ID.
ALTER TABLE np_workflows
  ADD COLUMN counterparty_operator_id BIGINT NULL AFTER local_role,
  DROP INDEX uq_workflow_request,
  ADD UNIQUE KEY uq_workflow_request (request_id, workflow_type, local_role, counterparty_operator_id, phone_number);
