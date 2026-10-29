# Protocol State Machines

Source: אפיון IT עבור ניידות מספרים, גרסה 4.0 (scanned PDF)

## Porting workflow

```text
NEW
  → CHECK_SENT
  → CHECK_APPROVED / CHECK_REJECTED
  → REQUEST_SENT
  → REQUEST_APPROVED / REQUEST_REJECTED
  → KD_UPDATE_SENT
  → PORT_SCHEDULED
  → (optional) UPDATE_SENT → UPDATE_APPROVED / UPDATE_REJECTED → PORT_SCHEDULED
  → (optional) CANCEL_SENT → CANCELLED / PORT_SCHEDULED
  → (optional, pending OQ#11) REQUEST_APPROVED → CANCEL_SENT → CANCELLED / PORT_SCHEDULED
  → EXECUTION_SENT
  → EXECUTION_CONFIRMED
  → PUBLISH_PENDING
  → PUBLISH_SENT
  → PUBLISHED / PARTIALLY_PUBLISHED
  → (optional, from PUBLISHED only) CANCEL_PUBLISH_SENT → PUBLICATION_CANCELLED
  → (on failure) PUBLISH_SENT → PUBLISH_FAILED
  → COMPLETED
```

Allowed manual/failure transitions:

- ANY_ALLOWED_STATE → FAILED
- ANY_EXCEPTION_STATE → MANUAL_REVIEW

## Publication per-recipient state

These are internal per-recipient tracking states stored in `np_publication_recipients`, not the workflow-level states used in `message-catalog.yml`.

```text
PENDING → SENT → ACKED → RESPONDED_APPROVED / RESPONDED_REJECTED
PENDING → SENT → NACKED → RETRYING / FAILED_FINAL / MANUAL_REVIEW
PENDING → SENT → TIMEOUT → RETRYING / FAILED_FINAL / MANUAL_REVIEW
```

## Publication cancellation workflow

```text
CANCEL_PUBLISH_SENT → PUBLICATION_CANCELLED
```

## Number return workflow

```text
NUMBER_DISCONNECTED
  → RETURN_COOLING_PERIOD
  → RETURN_READY
  → RETURN_SENT
  → RETURN_APPROVED
  → PUBLISH_PENDING (return publication)
  → PUBLISH_SENT
  → PUBLISHED
  → RETURNED
```

Publication after return is performed using the standard `Publish` / `Publish_response` messages.

- The transition `RETURN_COOLING_PERIOD → RETURN_READY` is triggered by expiry of the `RETURN_COOLING_PERIOD` timer.

## Inquiry workflow (outbound)

```text
INQUIRY_SENT → INQUIRY_COMPLETED
```

The pre-send state `INQUIRY_SENT` and completion are set automatically by the outbound dispatcher and response handler.

## Maintenance workflow

```text
OPERATOR_MARKED_DOWN
OPERATOR_MARKED_UP
```

These states are set automatically upon receipt of `Down_system` and `Up_system` messages.

## Data synchronization workflow

```text
SYNC_REQUESTED → SYNC_COMPLETED
```

## Internal / auto transitions

The following transitions are driven automatically by the system rather than by manual operator actions:

- `PUBLISH_PENDING → PUBLISH_SENT`: triggered by the outbound dispatcher claiming and sending the publication outbox item.
- `PUBLISHED / PARTIALLY_PUBLISHED → COMPLETED`: triggered by workflow completion logic after all publication recipients have responded or timed out (porting workflow). The return workflow instead transitions `PUBLISHED → RETURNED`.
- `INQUIRY_SENT → INQUIRY_COMPLETED`: triggered by receipt and processing of the inquiry response.
- `OPERATOR_MARKED_DOWN`: triggered by receipt of a `Down_system` message.
- `OPERATOR_MARKED_UP`: triggered by receipt of an `Up_system` message.

## Notes

- The state list above is the canonical Phase 0 extraction; any additions require updates to message-catalog.yml, timer-matrix.yml, and this state machine.
- All transitions are recorded in `np_workflow_events`.
- Manual transitions require a reason and an audit event.
- Global workflow states include the terminal states: FAILED, CANCELLED, COMPLETED, MANUAL_REVIEW.
- Global publication states are: PUBLISH_PENDING, PUBLISH_SENT, PARTIALLY_PUBLISHED, PUBLISHED, PUBLISH_FAILED, MANUAL_REVIEW.
- Terminal states: CHECK_REJECTED, REQUEST_REJECTED, CANCELLED, PUBLICATION_CANCELLED, RETURNED, COMPLETED, PUBLISH_FAILED, FAILED, MANUAL_REVIEW.
