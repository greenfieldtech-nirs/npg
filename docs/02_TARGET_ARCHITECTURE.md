# 02 - Target Architecture

## Architectural Style

The system is an event-driven, durable workflow gateway. It uses Vert.x for asynchronous processing but does not rely on in-memory eventing for durable business state.

## Runtime Roles

The same backend artifact may run in different modes:

* `api` - internal management API.
* `inbound` - external XML receiver.
* `worker` - inbox business processor.
* `timer` - durable timer scheduler.
* `dispatcher` - outbound outbox dispatcher.
* `all` - local development mode.

Runtime mode is selected using:

```text
APP_ROLE=api|inbound|worker|timer|dispatcher|all
```

## Component Diagram

```text
External Operators
    |
    v
NGINX / TLS / mTLS
    |
    v
Inbound XML Verticle
    |
    +--> MongoDB XML Audit Store
    +--> MariaDB message metadata
    +--> ACK/NACK generator
    +--> MariaDB inbox queue
             |
             v
       Inbox Worker Verticles
             |
             v
       Workflow Engine
             |
             +--> Timer Scheduler
             +--> Number Ownership Service
             +--> Operator Registry Service
             +--> Outbox Writer
                         |
                         v
                  MariaDB outbox queue
                         |
                         v
                  Outbound Dispatcher
                         |
                         v
                  External Operators
```

## Storage Responsibilities

### MariaDB/MySQL

Canonical data:

* operators
* endpoints
* workflow state
* message metadata
* inbox/outbox queue rows
* timers
* retries
* number ownership
* manual tasks
* audit events
* user/role data

### MongoDB

Document audit data:

* raw inbound XML
* raw outbound XML
* raw invalid XML where possible
* XML validation errors
* transport metadata
* hashes
* synchronization file metadata

## Redis

Ephemeral cache and coordination:

* read-mostly lookup cache (operator registry, system parameters).
* cache invalidation broadcasts.
* worker wake signals (timer, dispatcher, inbox workers).

Redis is not a durable store. All durable state remains in MariaDB/MySQL and MongoDB.

## Why No External Broker Is Acceptable

A MariaDB/MySQL outbox/inbox pattern is acceptable if implemented strictly:

1. Every inbound message is persisted before ACK.
2. Every outbound message is persisted before send.
3. Queue claiming is transactional and concurrency-safe.
4. Processing is idempotent.
5. Retry state is durable.
6. Stale claims are recoverable.
7. Message uniqueness is enforced with database constraints.
8. Workers can crash and resume safely.

## Key Service Boundaries

### InboundGatewayService

Receives external XML and produces technical ACK/NACK.

### XmlAuditService

Stores raw XML in MongoDB and creates immutable hash records.

### XmlValidationService

Performs secure XML parsing and schema validation.

### InboxService

Creates and claims inbound processing work.

### WorkflowEngine

Applies business state transitions.

### TimerService

Creates, claims, fires, and cancels durable timers.

### OutboxService

Creates outbound work items.

### OutboundDispatcher

Sends outbound XML and records technical results.

### RetryPolicyService

Determines when automatic retry is allowed.

### ManualTaskService

Creates and manages operational exceptions.

### ManagementApiService

Exposes JSON APIs to the React frontend.

## Deployment Topology

Development:

```text
nginx
frontend
backend-all
mariadb
mongodb
```

Production-like:

```text
nginx
frontend
api
inbound-gateway
worker-1..N
timer-worker-1..N
dispatcher-1..N
mariadb
mongodb
```

MariaDB and MongoDB can be external managed services in production, but Docker Compose must support local containerized versions.
