# Runbook: MongoDB Outage

## Symptoms

- `GET :9090/health/ready` on any backend container reports `"mongodb":"DOWN"`.
- The inbound gateway answers `500 NACK_INTERNAL_ERROR`: raw XML must be stored before an ACK,
  so nothing is ACKed without MongoDB (docs/04). Peers keep their messages and retry.
- Outbound preparation fails, so new workflow steps are not committed (they roll back as a whole)
  and the dispatcher retries delivery of already-queued items later.

## Steps

1. Restore MongoDB (container, disk, credentials). Do not bypass the audit store.
2. Confirm readiness on the backend containers. Peers' retries are accepted as new messages, or
   as duplicates if they were accepted before the outage.
3. Check **Queues & Timers** for `FAILED_FINAL` inbox items with missing-raw-XML errors (only
   possible if data was lost). If the peer can re-send them, ask it to; otherwise resolve them
   manually.
4. If data was lost, restore from the latest `docker/backup-mongodb.sh` archive
   (`docker/restore-mongodb.sh`).
