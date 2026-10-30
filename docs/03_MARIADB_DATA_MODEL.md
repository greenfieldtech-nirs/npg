# 03 - MariaDB / MySQL Data Model

## Design Principles

* Use MariaDB/MySQL as the canonical source of workflow truth.
* Use UUID or ULID public identifiers for workflows and messages.
* Use numeric internal primary keys where convenient.
* Enforce idempotency with unique constraints.
* Avoid relying on Java in-memory state.
* Use explicit event history tables.
* Use database transactions for state transitions.
* Store MongoDB document references, not raw XML, in MariaDB.
* Store SHA-256 hashes in MariaDB for audit linking.

## Recommended Tables

### `np_operators`

Stores licensed operator identity.

```sql
CREATE TABLE np_operators (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  operator_code VARCHAR(32) NOT NULL UNIQUE,
  operator_name VARCHAR(255) NOT NULL,
  license_type VARCHAR(64) NULL,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  availability_status VARCHAR(32) NOT NULL DEFAULT 'UP',
  last_down_at DATETIME(6) NULL,
  last_up_at DATETIME(6) NULL,
  contact_name VARCHAR(255) NULL,
  contact_phone VARCHAR(64) NULL,
  contact_email VARCHAR(255) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL
);
```

### `np_operator_endpoints`

```sql
CREATE TABLE np_operator_endpoints (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  operator_id BIGINT NOT NULL,
  endpoint_type VARCHAR(64) NOT NULL,
  endpoint_url VARCHAR(1024) NOT NULL,
  protocol_version VARCHAR(32) NOT NULL DEFAULT '4.0',
  auth_mode VARCHAR(64) NOT NULL DEFAULT 'MTLS',
  tls_subject VARCHAR(512) NULL,
  ip_allowlist TEXT NULL,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_endpoint_operator FOREIGN KEY (operator_id) REFERENCES np_operators(id)
);
```

### `np_numbers`

```sql
CREATE TABLE np_numbers (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  phone_number VARCHAR(32) NOT NULL UNIQUE,
  number_type VARCHAR(64) NOT NULL,
  original_operator_id BIGINT NULL,
  current_operator_id BIGINT NULL,
  previous_operator_id BIGINT NULL,
  status VARCHAR(64) NOT NULL DEFAULT 'UNKNOWN',
  last_ported_at DATETIME(6) NULL,
  last_returned_at DATETIME(6) NULL,
  last_published_at DATETIME(6) NULL,
  source_message_id BIGINT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  INDEX idx_numbers_current_operator (current_operator_id),
  INDEX idx_numbers_status (status)
);
```

### `np_number_ownership_history`

```sql
CREATE TABLE np_number_ownership_history (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  phone_number VARCHAR(32) NOT NULL,
  previous_operator_id BIGINT NULL,
  new_operator_id BIGINT NULL,
  change_reason VARCHAR(64) NOT NULL,
  workflow_id BIGINT NULL,
  message_id BIGINT NULL,
  effective_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  INDEX idx_history_phone (phone_number),
  INDEX idx_history_workflow (workflow_id)
);
```

### `np_workflows`

```sql
CREATE TABLE np_workflows (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  workflow_uuid CHAR(36) NOT NULL UNIQUE,
  workflow_type VARCHAR(64) NOT NULL,
  request_id VARCHAR(128) NOT NULL,
  phone_number VARCHAR(32) NOT NULL,
  customer_batch_id VARCHAR(128) NULL,
  recipient_operator_id BIGINT NULL,
  donor_operator_id BIGINT NULL,
  original_operator_id BIGINT NULL,
  current_operator_id BIGINT NULL,
  current_state VARCHAR(64) NOT NULL,
  requested_port_time DATETIME(6) NULL,
  approved_port_time DATETIME(6) NULL,
  executed_at DATETIME(6) NULL,
  completed_at DATETIME(6) NULL,
  cancelled_at DATETIME(6) NULL,
  failed_at DATETIME(6) NULL,
  failure_code VARCHAR(64) NULL,
  failure_reason TEXT NULL,
  created_by VARCHAR(128) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  UNIQUE KEY uq_workflow_request_phone (request_id, phone_number),
  INDEX idx_workflow_phone (phone_number),
  INDEX idx_workflow_state (current_state),
  INDEX idx_workflow_type_state (workflow_type, current_state)
);
```

### `np_workflow_events`

```sql
CREATE TABLE np_workflow_events (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  workflow_id BIGINT NOT NULL,
  event_type VARCHAR(128) NOT NULL,
  from_state VARCHAR(64) NULL,
  to_state VARCHAR(64) NULL,
  message_id BIGINT NULL,
  details_json JSON NULL,
  created_by VARCHAR(128) NULL,
  created_at DATETIME(6) NOT NULL,
  INDEX idx_events_workflow (workflow_id),
  CONSTRAINT fk_events_workflow FOREIGN KEY (workflow_id) REFERENCES np_workflows(id)
);
```

### `np_messages`

```sql
CREATE TABLE np_messages (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  message_uuid CHAR(36) NOT NULL UNIQUE,
  workflow_id BIGINT NULL,
  message_type VARCHAR(128) NOT NULL,
  direction VARCHAR(16) NOT NULL,
  sender_operator_id BIGINT NULL,
  receiver_operator_id BIGINT NULL,
  request_id VARCHAR(128) NULL,
  transaction_id VARCHAR(128) NULL,
  correlation_id VARCHAR(128) NULL,
  idempotency_key VARCHAR(255) NOT NULL,
  mongo_document_id VARCHAR(128) NULL,
  xml_sha256 CHAR(64) NULL,
  schema_version VARCHAR(32) NULL,
  technical_status VARCHAR(64) NOT NULL DEFAULT 'RECEIVED',
  business_status VARCHAR(64) NULL,
  ack_status VARCHAR(64) NULL,
  nack_code VARCHAR(64) NULL,
  sent_at DATETIME(6) NULL,
  received_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  UNIQUE KEY uq_message_idempotency (idempotency_key),
  INDEX idx_messages_workflow (workflow_id),
  INDEX idx_messages_request (request_id),
  INDEX idx_messages_type_direction (message_type, direction),
  INDEX idx_messages_correlation (correlation_id)
);
```

### `np_inbox`

```sql
CREATE TABLE np_inbox (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  inbox_uuid CHAR(36) NOT NULL UNIQUE,
  message_id BIGINT NOT NULL,
  workflow_id BIGINT NULL,
  item_type VARCHAR(128) NOT NULL,
  status VARCHAR(64) NOT NULL DEFAULT 'PENDING',
  priority INT NOT NULL DEFAULT 100,
  due_at DATETIME(6) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 3,
  locked_by VARCHAR(128) NULL,
  locked_until DATETIME(6) NULL,
  last_error_code VARCHAR(64) NULL,
  last_error_message TEXT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  UNIQUE KEY uq_inbox_message (message_id),
  INDEX idx_inbox_claim (status, due_at, priority),
  INDEX idx_inbox_lock (locked_until),
  CONSTRAINT fk_inbox_message FOREIGN KEY (message_id) REFERENCES np_messages(id)
);
```

### `np_outbox`

```sql
CREATE TABLE np_outbox (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  outbox_uuid CHAR(36) NOT NULL UNIQUE,
  message_id BIGINT NOT NULL,
  workflow_id BIGINT NULL,
  destination_operator_id BIGINT NOT NULL,
  item_type VARCHAR(128) NOT NULL,
  status VARCHAR(64) NOT NULL DEFAULT 'PENDING',
  priority INT NOT NULL DEFAULT 100,
  due_at DATETIME(6) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 3,
  locked_by VARCHAR(128) NULL,
  locked_until DATETIME(6) NULL,
  last_error_code VARCHAR(64) NULL,
  last_error_message TEXT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  UNIQUE KEY uq_outbox_message_destination (message_id, destination_operator_id),
  INDEX idx_outbox_claim (status, due_at, priority),
  INDEX idx_outbox_lock (locked_until),
  CONSTRAINT fk_outbox_message FOREIGN KEY (message_id) REFERENCES np_messages(id)
);
```

### `np_timers`

```sql
CREATE TABLE np_timers (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  timer_uuid CHAR(36) NOT NULL UNIQUE,
  workflow_id BIGINT NULL,
  message_id BIGINT NULL,
  timer_name VARCHAR(128) NOT NULL,
  timer_type VARCHAR(64) NOT NULL,
  status VARCHAR(64) NOT NULL DEFAULT 'PENDING',
  starts_at DATETIME(6) NOT NULL,
  due_at DATETIME(6) NOT NULL,
  fired_at DATETIME(6) NULL,
  cancelled_at DATETIME(6) NULL,
  attempts INT NOT NULL DEFAULT 0,
  locked_by VARCHAR(128) NULL,
  locked_until DATETIME(6) NULL,
  action_json JSON NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  INDEX idx_timers_claim (status, due_at),
  INDEX idx_timers_workflow (workflow_id),
  INDEX idx_timers_message (message_id)
);
```

### `np_publication_recipients`

```sql
CREATE TABLE np_publication_recipients (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  workflow_id BIGINT NOT NULL,
  message_id BIGINT NULL,
  operator_id BIGINT NOT NULL,
  status VARCHAR(64) NOT NULL DEFAULT 'PENDING',
  ack_status VARCHAR(64) NULL,
  response_status VARCHAR(64) NULL,
  sent_at DATETIME(6) NULL,
  ack_at DATETIME(6) NULL,
  response_at DATETIME(6) NULL,
  attempts INT NOT NULL DEFAULT 0,
  last_error_code VARCHAR(64) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  UNIQUE KEY uq_publication_workflow_operator (workflow_id, operator_id)
);
```

### `np_manual_tasks`

```sql
CREATE TABLE np_manual_tasks (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_uuid CHAR(36) NOT NULL UNIQUE,
  workflow_id BIGINT NULL,
  message_id BIGINT NULL,
  task_type VARCHAR(128) NOT NULL,
  severity VARCHAR(32) NOT NULL DEFAULT 'MEDIUM',
  status VARCHAR(64) NOT NULL DEFAULT 'OPEN',
  assigned_to VARCHAR(128) NULL,
  description TEXT NOT NULL,
  resolution TEXT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  closed_at DATETIME(6) NULL,
  INDEX idx_manual_status (status, severity),
  INDEX idx_manual_workflow (workflow_id)
);
```

### `np_audit_events`

```sql
CREATE TABLE np_audit_events (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_uuid CHAR(36) NOT NULL UNIQUE,
  actor_type VARCHAR(64) NOT NULL,
  actor_id VARCHAR(128) NULL,
  action VARCHAR(128) NOT NULL,
  entity_type VARCHAR(128) NOT NULL,
  entity_id VARCHAR(128) NOT NULL,
  details_json JSON NULL,
  remote_ip VARCHAR(64) NULL,
  created_at DATETIME(6) NOT NULL,
  INDEX idx_audit_entity (entity_type, entity_id),
  INDEX idx_audit_created (created_at)
);
```

## Queue Status Values

Use explicit status values:

```text
PENDING
CLAIMED
PROCESSING
WAITING
SUCCEEDED
FAILED_RETRYABLE
FAILED_FINAL
CANCELLED
MANUAL_REVIEW
```

## Workflow State Values

Store as strings to allow protocol refinement:

```text
NEW
CHECK_SENT
CHECK_APPROVED
CHECK_REJECTED
REQUEST_SENT
REQUEST_APPROVED
REQUEST_REJECTED
PORT_SCHEDULED
UPDATE_SENT
UPDATE_APPROVED
UPDATE_REJECTED
CANCEL_SENT
CANCELLED
EXECUTION_DUE
EXECUTION_SENT
EXECUTION_CONFIRMED
PUBLISH_PENDING
PUBLISH_SENT
PUBLISHED
RETURN_PENDING
RETURN_SENT
RETURNED
FAILED
EXPIRED
MANUAL_REVIEW
COMPLETED
```

The final state list must be generated from the protocol extraction phase.

## Schema evolution

Post-V1 migrations:

- **V2** — `np_operators.requires_configuration BOOLEAN NOT NULL DEFAULT FALSE` — marks operators auto-discovered from accepted inbound messages and pending configuration. Refused (unauthenticated) messages never create operators.
- **V3** — `np_operators.is_local BOOLEAN NOT NULL DEFAULT FALSE` — marks the local operator identity (at most one active at a time, enforced at the application layer).
- **V4** — `np_users.password_hash VARCHAR(255) NOT NULL DEFAULT ''` — bcrypt hash for console login.

The users/roles tables (`np_users`, `np_roles`, `np_user_roles`) exist from V1 and are used by the management console RBAC (roles: ADMIN, MANAGER, USER).
