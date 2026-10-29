# 05 - Workflow and State Machine Specification

## Core Principle

The protocol is modeled as a collection of durable workflow instances. Each workflow has:

* a workflow type
* a phone number
* a request ID
* participating operators
* current state
* message history
* timer history
* audit events
* manual tasks

## Workflow Types

```text
PORTING
PUBLICATION
PUBLICATION_CANCELLATION
NUMBER_RETURN
NUMBER_INQUIRY
MAINTENANCE
DATA_SYNCHRONIZATION
```

## Main Porting State Machine

```text
NEW
→ CHECK_SENT
→ CHECK_APPROVED
→ REQUEST_SENT
→ REQUEST_APPROVED
→ PORT_SCHEDULED
→ EXECUTION_SENT
→ EXECUTION_CONFIRMED
→ PUBLISH_PENDING
→ PUBLISH_SENT
→ PUBLISHED
→ COMPLETED
```

Failure paths:

```text
CHECK_SENT → CHECK_REJECTED
CHECK_SENT → CHECK_TIMEOUT
REQUEST_SENT → REQUEST_REJECTED
REQUEST_SENT → REQUEST_TIMEOUT
PORT_SCHEDULED → CANCEL_SENT → CANCELLED
PORT_SCHEDULED → UPDATE_SENT → UPDATE_APPROVED → PORT_SCHEDULED
PORT_SCHEDULED → UPDATE_SENT → UPDATE_REJECTED → PORT_SCHEDULED or MANUAL_REVIEW
ANY_ALLOWED_STATE → FAILED
ANY_EXCEPTION_STATE → MANUAL_REVIEW
```

## Publication State

Publication must be modeled per recipient.

Global publication state:

```text
PUBLISH_PENDING
PUBLISH_SENT
PARTIALLY_PUBLISHED
PUBLISHED
PUBLISH_FAILED
MANUAL_REVIEW
```

Per recipient state:

```text
PENDING
SENT
ACKED
NACKED
RESPONDED_APPROVED
RESPONDED_REJECTED
TIMEOUT
RETRYING
FAILED_FINAL
MANUAL_REVIEW
```

## Number Return Workflow

```text
NUMBER_DISCONNECTED
→ RETURN_COOLING_PERIOD
→ RETURN_READY
→ RETURN_SENT
→ RETURN_APPROVED
→ RETURN_PUBLISH_PENDING
→ RETURN_PUBLISHED
→ RETURNED
```

## Inquiry Workflow

Inbound:

```text
INQUIRY_RECEIVED
→ INQUIRY_VALIDATED
→ INQUIRY_ANSWERED
```

Outbound:

```text
INQUIRY_CREATED
→ INQUIRY_SENT
→ INQUIRY_RESPONSE_RECEIVED
→ INQUIRY_COMPLETED
```

## Maintenance Workflow

```text
DOWN_SYSTEM_RECEIVED
→ OPERATOR_MARKED_DOWN
→ UP_SYSTEM_RECEIVED
→ OPERATOR_MARKED_UP
```

## State Transition Rules

Every transition must verify:

* current state
* workflow type
* message type
* sender/receiver role
* required timers
* number ownership state
* duplicate conditions
* operator availability
* manual lock status

## Event History

Every transition creates `np_workflow_events`.

Event example:

```json
{
  "eventType": "REQUEST_RESPONSE_APPROVED",
  "fromState": "REQUEST_SENT",
  "toState": "REQUEST_APPROVED",
  "messageId": 123,
  "details": {
    "responseCode": "APPROVED"
  }
}
```

## Manual Transitions

Manual transitions are allowed only through the management API, must be role-protected, and must write audit events.

Dangerous manual actions:

* force workflow completion
* force cancellation
* replay outbound message
* suppress retry
* override number ownership
* mark publication recipient as completed
* resolve unknown delivery state

These should require elevated role and a mandatory reason.
