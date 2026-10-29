# Runbook: MariaDB Outage

## Symptoms

- All backend roles report `/health/ready` failures.
- Inbound requests are rejected.

## Steps

1. Do not ACK inbound traffic; durable state cannot be guaranteed.
2. Check MariaDB container/service health.
3. Restore from the latest backup.
4. Verify migrations are up to date.
5. Resume traffic only after `/health/ready` passes.
