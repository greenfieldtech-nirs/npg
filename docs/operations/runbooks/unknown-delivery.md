# Runbook: Unknown Delivery

## Symptoms

- A manual task of type `UNKNOWN_DELIVERY` is open.
- The outbox item is in `MANUAL_REVIEW` (or `UNKNOWN_DELIVERY` while the sweep runs).
- Cause: a dispatcher stopped after it started sending but before it recorded the peer's answer.
  The peer may or may not hold the message.

Message types that tolerate duplicates (`OUTBOUND_SAFE_RESEND_TYPES`, default: Check,
Inquire_number, Inquire_number_response, Down_system, Up_system, DB_synch_request) are re-sent
automatically. If the peer already has the message, its duplicate NACK counts as delivered. Only
the remaining types reach this runbook.

## Steps

1. Open the task from **Manual Tasks** and follow the link to the workflow. The **Messages**
   section shows the message, its TRX number and its outbox status.
2. Open the message's raw XML (audited) if you need to read it to the peer.
3. Ask the destination operator whether it received the message with that TRX number.
4. If it did: **Queues & Timers → Outbox → Mark delivered**, with the peer's confirmation as the
   reason (`POST /api/outbox/{uuid}/mark-delivered`).
5. If it did not: **Retry** (`POST /api/outbox/{uuid}/retry`). The item gets a fresh attempt budget.
6. If the message must no longer be sent (the process was cancelled meanwhile): **Cancel**
   (`POST /api/outbox/{uuid}/cancel`).
7. Resolve the manual task with what you did.

Every action needs a reason and is recorded in `np_audit_events`.
