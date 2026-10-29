# 04 - MariaDB Inbox / Outbox Pattern

## Purpose

This design replaces an external durable broker. MariaDB/MySQL becomes the durable queue, but only if queue rows, idempotency, locks, timers, and retries are implemented carefully.

## Inbound Flow

```text
HTTP request arrives
→ Store raw XML in MongoDB
→ Insert message metadata into np_messages
→ Validate technical envelope
→ Insert np_inbox row in same DB transaction
→ Return ACK
→ Worker later claims np_inbox row
→ Workflow engine performs business processing
```

If technical validation fails:

```text
HTTP request arrives
→ Store raw XML in MongoDB if possible
→ Insert np_messages with invalid technical status if possible
→ Return NACK
→ Do not create business-processing inbox row
```

## Outbound Flow

```text
Workflow decides outbound message is needed
→ Build XML
→ Store raw XML in MongoDB
→ Insert np_messages
→ Insert np_outbox row in same DB transaction
→ Dispatcher claims np_outbox row
→ Send XML to destination operator
→ Record ACK/NACK/transport result
→ Create timers or retries as needed
```

## Claiming Algorithm

Do not use `SELECT then UPDATE` without locking.

Preferred generic claim method:

1. Generate a unique worker ID.
2. Start transaction.
3. Claim a limited batch:

```sql
UPDATE np_outbox
SET
  status = 'CLAIMED',
  locked_by = ?,
  locked_until = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 60 SECOND),
  updated_at = UTC_TIMESTAMP(6)
WHERE status IN ('PENDING', 'FAILED_RETRYABLE')
  AND due_at <= UTC_TIMESTAMP(6)
  AND (locked_until IS NULL OR locked_until < UTC_TIMESTAMP(6))
ORDER BY priority ASC, due_at ASC
LIMIT ?;
```

4. Select rows by worker ID:

```sql
SELECT *
FROM np_outbox
WHERE locked_by = ?
  AND status = 'CLAIMED'
ORDER BY priority ASC, due_at ASC;
```

5. Commit.
6. Process rows idempotently.

This pattern avoids relying on `SKIP LOCKED`, which may differ across MySQL/MariaDB versions.

## Stale Lock Recovery

A claimed row whose `locked_until` is older than now is eligible for re-claim.

Workers must extend locks only while actively processing.

## Idempotency

Every message must have an `idempotency_key`.

Suggested inbound key:

```text
direction + sender_operator + receiver_operator + message_type + request_id + transaction_id
```

Suggested outbound key:

```text
direction + sender_operator + receiver_operator + message_type + workflow_id + protocol_step + attempt_generation
```

## Transaction Boundaries

### Inbound ACK Transaction

Before ACK is returned, the following must be durable:

* MongoDB raw XML document.
* MariaDB `np_messages` row.
* MariaDB `np_inbox` row for valid messages.
* Validation result.

### Outbound Creation Transaction

Before a dispatcher can send, the following must be durable:

* MongoDB raw XML document.
* MariaDB `np_messages` row.
* MariaDB `np_outbox` row.

## Failure Handling

### MongoDB Down

If MongoDB is down, do not process normal protocol traffic because XML audit cannot be guaranteed. Return a controlled technical failure or NACK according to configured policy.

### MariaDB Down

If MariaDB is down, do not ACK inbound messages because processing cannot be guaranteed.

### Dispatcher Crash After Send Before DB Update

This is the hardest scenario in any outbox pattern.

Mitigation:

* Use idempotency keys.
* Store outbound message before sending.
* Mark row as `SENDING` before network call.
* On restart, rows stuck in `SENDING` become `UNKNOWN_DELIVERY`.
* `UNKNOWN_DELIVERY` requires protocol-specific handling:
  * safe retry if message type allows duplicate/idempotent send
  * otherwise manual review

## Retry Model

Retry policy must consider:

* message type
* workflow state
* whether ACK was received
* whether a business response was received
* point of no return
* destination operator status
* configured retry count
* configured retry delay

## Inbox Worker Behavior

Each inbox item should process in this pattern:

```text
claim row
→ mark PROCESSING
→ load message metadata and XML ref
→ run business handler
→ update workflow state in transaction
→ create outbox/timers/manual tasks as needed
→ mark SUCCEEDED
```

If handler fails:

```text
retryable exception → FAILED_RETRYABLE with due_at
non-retryable exception → FAILED_FINAL or MANUAL_REVIEW
```

## Outbox Worker Behavior

Each outbox item should process in this pattern:

```text
claim row
→ mark SENDING
→ send to remote endpoint
→ record transport result
→ if ACK received: mark ACKED / SENT
→ if NACK received: mark FAILED_FINAL or MANUAL_REVIEW
→ if no ACK: schedule retry or ACK timeout timer
```

## Timer Worker Behavior

Timers use the same claim pattern as outbox rows.

Timer action must be idempotent. If a timer fires twice due to crash/retry, the second execution must detect the workflow state and no-op safely.

## Required Tests

1. Two workers claim outbox concurrently; no duplicate row processing.
2. Worker crashes after claim; stale lock recovery works.
3. Inbound duplicate message creates only one inbox item.
4. Dispatcher crash after send moves row to unknown delivery/manual review.
5. Timer fires once logically even if two workers attempt execution.
