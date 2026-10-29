# Protocol State Machines

Source: אפיון IT עבור ניידות מספרים, גרסה 4.0 (scanned PDF), with the message flow confirmed
against a live operator trace (see `open-questions.md`). The executable definition is
`backend/src/main/java/cloud/cloudonix/npg/workflow/StateMachines.java`; this file mirrors it.

Workflows are modelled **from this gateway's point of view**. One protocol process is a different
machine depending on the role we play in it, so every workflow has a type and a `local_role`, and
is bound to its counterparty operator. A `REQUEST_ID` is chosen by a peer, so it identifies a
workflow only together with that peer.

Universal rules (every machine):

- Any non-terminal state may escalate to `MANUAL_REVIEW` (timeouts, rejected executions,
  conflicts) or `FAILED`.
- Only an audited manual transition leaves `MANUAL_REVIEW`. It needs a reason, may target any state
  of the same machine, and writes `np_audit_events`.
- Every transition writes `np_workflow_events`. Each timer records the state it guards; if it fires
  after the workflow has left that state, it does nothing.

## PORTING / RECIPIENT — we gain the number (NOC-initiated)

```text
NEW ─Check→ CHECK_SENT ─Check_response(Y)→ CHECK_APPROVED ─Request→ REQUEST_SENT
CHECK_SENT ─Check_response(N)→ CHECK_REJECTED                                  [terminal]
REQUEST_SENT ─Request_response(N)→ REQUEST_REJECTED                            [terminal]
REQUEST_SENT ─Request_response(Y)→ REQUEST_APPROVED
REQUEST_APPROVED → PORT_SCHEDULED            (or → KD_UPDATE_SENT → PORT_SCHEDULED when KD_update is required)
PORT_SCHEDULED ─(PORT_EXECUTION_DUE timer)─Execute→ EXECUTION_SENT
PORT_SCHEDULED ─Update→ UPDATE_SENT ─Update_response→ PORT_SCHEDULED  (new time if approved)
REQUEST_APPROVED | PORT_SCHEDULED ─Cancel→ CANCEL_SENT ─Cancel_response(Y)→ CANCELLED   [terminal]
                                                        ─Cancel_response(N)→ PORT_SCHEDULED
EXECUTION_SENT ─Execute_response(Y)→ EXECUTION_CONFIRMED   (number is now ours)
EXECUTION_CONFIRMED ─Publish ×N→ PUBLISH_SENT
PUBLISH_SENT → PUBLISHED → COMPLETED        (all recipients approved)
PUBLISH_SENT → PARTIALLY_PUBLISHED | PUBLISH_FAILED   (rejections / timeout; manual task)
COMPLETED ─Cancel_Publish ×N→ CANCEL_PUBLISH_SENT → PUBLICATION_CANCELLED   [terminal]
```

Timers: `CHECK_RESPONSE_TIMEOUT`, `REQUEST_RESPONSE_TIMEOUT`, `KD_UPDATE_RESPONSE_TIMEOUT`,
`UPDATE_RESPONSE_TIMEOUT`, `CANCEL_RESPONSE_TIMEOUT`, `EXECUTION_RESPONSE_TIMEOUT` (each
escalates to `MANUAL_REVIEW` when it expires); `PORT_EXECUTION_DUE` (at the approved port time);
`PUBLICATION_RESPONSE_TIMEOUT` and `CANCEL_PUBLISH_RESPONSE_TIMEOUT` (unanswered recipients
become `TIMEOUT`).

Publication is tracked per recipient in `np_publication_recipients` (`SENT`,
`RESPONDED_APPROVED`, `RESPONDED_REJECTED`, `TIMEOUT`). As in the trace, every active peer except
the donor is a recipient.

## PORTING / DONOR — we lose the number

```text
CHECK_RECEIVED ─(policy: approve)→ Check_response(Y) → CHECK_APPROVED
CHECK_RECEIVED ─(policy: reject)→  Check_response(N) → CHECK_REJECTED           [terminal]
CHECK_RECEIVED ─(policy: manual)→  PORT_OUT_DECISION task; NOC decides via the API
CHECK_APPROVED ─Request→ REQUEST_RECEIVED ─(policy)→ PORT_SCHEDULED | REQUEST_REJECTED
PORT_SCHEDULED ─Update→ (port time updated, answered) PORT_SCHEDULED
CHECK_APPROVED | REQUEST_RECEIVED | PORT_SCHEDULED ─Cancel→ CANCELLED            [terminal]
PORT_SCHEDULED ─Execute→ (number released, Execute_response with disconnectDateTime) COMPLETED [terminal]
```

`PortOutPolicy` applies hard rules first: the number is served by another operator (recorded, or
by number range); another port-out of the number is in progress; the port time is not plausible.
Then the configured mode (`PORT_OUT_DECISION_MODE`: `MANUAL` or `AUTO_APPROVE`) decides.
KD_update is answered without a state change.

## PUBLICATION / OBSERVER — another operator publishes a change

```text
PUBLICATION_RECEIVED → PUBLICATION_APPLIED (routing updated, Publish_response Y)
PUBLICATION_RECEIVED → PUBLICATION_REJECTED (contradicts our records; PUBLICATION_CONFLICT task)  [terminal]
PUBLICATION_APPLIED ─Cancel_Publish→ PUBLICATION_CANCELLED (previous routing restored)            [terminal]
```

Ownership checks use our records and number ranges, never the body's claims (see
`open-questions.md` for the remaining limitation).

## NUMBER_RETURN / CURRENT_OPERATOR — we return a ported-in number

```text
NUMBER_DISCONNECTED → RETURN_COOLING_PERIOD ─(timer)─Return→ RETURN_SENT
RETURN_SENT ─Return_response(N)→ RETURN_REJECTED                               [terminal]
RETURN_SENT ─Return_response(Y)→ RETURN_APPROVED (number back to original) ─Publish(Return) ×N→ RETURN_PUBLISH_SENT
RETURN_PUBLISH_SENT → RETURNED | PARTIALLY_PUBLISHED | PUBLISH_FAILED
```

## NUMBER_RETURN / ORIGINAL_OPERATOR — a number comes back to us

```text
RETURN_RECEIVED → RETURN_ACCEPTED (we originally owned it and the sender serves it) | RETURN_REJECTED
```

## NUMBER_INQUIRY

```text
REQUESTER: INQUIRY_SENT ─Inquire_number_response→ INQUIRY_COMPLETED     (result in context)
RESPONDER: INQUIRY_RECEIVED → INQUIRY_ANSWERED                          (currentOperator, or REJECTED_NUMBER_NOT_FOUND)
```

## MAINTENANCE

```text
OBSERVER:  OPERATOR_MARKED_DOWN (Down_system; traffic held) ─Up_system→ OPERATOR_MARKED_UP (traffic released)
ANNOUNCER: DOWN_ANNOUNCED (Down_system to all peers) → UP_ANNOUNCED (Up_system to all peers)
```

`MAINTENANCE_WINDOW_TIMEOUT` escalates a peer that stays down to the NOC.

## DATA_SYNCHRONIZATION

```text
REQUESTER: SYNC_REQUESTED → SYNC_COMPLETED (fileReference) | SYNC_REJECTED
RESPONDER: SYNC_RECEIVED → SYNC_ANSWERED   (snapshot stored in MongoDB sync_exports, fileReference npg-sync:<id>)
```

## Message processing order

Messages of one process (same sender, same `REQUEST_ID`) are processed strictly in arrival order;
independent processes run in parallel (`np_inbox.ordering_key`).
