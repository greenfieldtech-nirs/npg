# Runbook: MongoDB Outage

## Symptoms

- Inbound requests receive `NACK_INTERNAL_ERROR`.
- Health check `/health/ready` fails on MongoDB dependency.

## Steps

1. Do not ACK inbound traffic if MongoDB is down; raw XML cannot be audited.
2. Check MongoDB container/service health.
3. Restore from backup if necessary.
4. Verify MariaDB message rows still reference existing MongoDB documents.
5. Resume traffic only after `/health/ready` passes.
