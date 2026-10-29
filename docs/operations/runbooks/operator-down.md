# Runbook: Operator Down

## Symptoms

- Operator availability status is `DOWN`.
- Outbound messages to that operator accumulate in `np_outbox` with status `WAITING`.

## Steps

1. Check `np_operator_status_events` for the reason.
2. If the operator sent `Down_system`, wait for `Up_system`.
3. If no `Up_system` arrives within the maintenance window timeout, contact the operator NOC.
4. Do not manually mark the operator up unless you have confirmed service restoration.
5. After marking up, dispatcher workers will resume eligible retries automatically.
