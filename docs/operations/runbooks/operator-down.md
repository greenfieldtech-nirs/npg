# Runbook: Operator Down

## Symptoms

- The operator shows `DOWN` on **Operators**, and the dashboard counts it under *Operators Down*.
- Outbound messages to it wait in the outbox as `PENDING` with last error `OPERATOR_DOWN`.
  Holding them does not spend retry attempts.
- A `RESPONSE_TIMEOUT` task may appear if the operator stayed down past
  `MAINTENANCE_WINDOW_TIMEOUT` (default 24h; set it in `np_system_parameters`).

## Steps

1. Check why: the operator's maintenance workflow (search workflows by type `MAINTENANCE`) holds
   the reason and expected duration from its `Down_system`.
2. If it announced maintenance, wait for `Up_system`. When it arrives the operator is marked up and
   held messages are released immediately.
3. If no `Up_system` arrives in time, contact the operator's NOC.
4. Only after confirming that service is restored, use **Operators → Mark up** with the
   confirmation as the reason (`POST /api/operators/{code}/mark-up`). This releases held messages.
5. To hold traffic for an operator that announced maintenance by phone, use **Mark down**.
