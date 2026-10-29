# 08 - Docker, Operations, Security, and Observability

## Container Services

Minimum development stack:

```yaml
services:
  nginx:
  frontend:
  backend:
  mariadb:
  mongodb:
  redis:
```

Production-like stack:

```yaml
services:
  nginx:
  frontend:
  api:
  inbound-gateway:
  worker:
  timer-worker:
  dispatcher:
  mariadb:
  mongodb:
  redis:
```

No RabbitMQ, Kafka, Redis queue, or external durable broker.

Redis may be used for non-durable cache and ephemeral pub/sub only.

## Backend Runtime Modes

Use one image, multiple roles:

```text
APP_ROLE=api
APP_ROLE=inbound
APP_ROLE=worker
APP_ROLE=timer
APP_ROLE=dispatcher
APP_ROLE=all
```

## Configuration

Use environment variables:

```text
DB_HOST
DB_PORT
DB_NAME
DB_USER
DB_PASSWORD
MONGO_URI
MONGO_DB
APP_ROLE
APP_PUBLIC_BASE_URL
TLS_ENABLED
MTLS_ENABLED
LOG_LEVEL
WORKER_ID
QUEUE_CLAIM_BATCH_SIZE
QUEUE_LOCK_SECONDS
```

## Health Checks

Expose:

```text
GET /health/live
GET /health/ready
GET /metrics
```

Readiness should check:

* MariaDB connectivity
* MongoDB connectivity
* migration state
* role-specific dependencies

## Logging

Use structured JSON logs.

Fields:

* timestamp
* level
* service
* role
* workflowUuid
* messageUuid
* requestId
* phoneNumber, masked where needed
* operatorCode
* event
* errorCode

Do not log full XML by default.

## Security Requirements

External:

* TLS.
* mTLS support.
* IP allowlist support.
* XML parser hardening.
* replay/idempotency validation.
* request size limit.
* rate limiting at NGINX or app layer.

Internal:

* authentication (JWT login, bcrypt-hashed users in `np_users`).
* role-based authorization (ADMIN / MANAGER / USER) enforced on every route; local configuration is ADMIN-only.
* audit every manual action.
* audit every XML view/download.
* no default admin credentials in production (seeded `admin` account must be overridden via `NPG_ADMIN_USERNAME` / `NPG_ADMIN_PASSWORD`; `NPG_JWT_SECRET` must be set).
* secrets from environment or Docker secrets.

## Backup

Backup:

* MariaDB
* MongoDB
* configuration
* XSD/WSDL/protocol catalogs

Restore tests must prove that:

* workflows can resume
* outbox/inbox rows are intact
* XML document links are intact
* timers can resume

## Deployment Notes

Local development can use Docker Compose.

Production can use Docker Compose, Swarm, Kubernetes, or equivalent. The application must not assume a specific orchestrator.
