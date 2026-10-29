# Runbook: Unknown Delivery

## Symptoms

- `np_outbox` row has status `UNKNOWN_DELIVERY`.
- Dispatcher crashed after send but before recording the result.

## Steps

1. Check MongoDB raw XML for the message UUID.
2. Contact the destination operator to confirm whether the message was received.
3. If the operator confirms receipt, manually mark the row as `SUCCEEDED` with a reason.
4. If the operator did not receive it and the message type is idempotent, trigger a retry.
5. If the message type is not idempotent, escalate to manual review.
