# Runbook: MariaDB Outage

## Symptoms

- `GET :9090/health/ready` reports `"mariadb":"DOWN"`. Containers restart if it persists.
- The inbound gateway cannot commit, so it does not ACK (peers retry). Nothing is lost.
- Workers, timers and the dispatcher stop claiming. Claims are leases (`locked_until`), so work
  claimed before the outage is picked up again after the lock expires.

## Steps

1. Restore MariaDB. All workflow, queue and timer state is durable there; the backend needs no
   state repair.
2. After recovery:
   - Inbox items left in PROCESSING and timers left CLAIMED are reclaimed when their lock expires
     (`QUEUE_LOCK_SECONDS`, default 60s).
   - Outbox items left in SENDING become unknown deliveries: re-sent if safe, otherwise held for
     review (see `unknown-delivery.md`).
3. Watch `npg_timers_overdue` on `/metrics`. Timers that came due during the outage fire on
   recovery and act on the workflow's current state.
4. To restore from backup, stop the backend roles, then run `docker/restore-mariadb.sh
   <backup.sql.gz>` (credentials from the environment) and start them again.
