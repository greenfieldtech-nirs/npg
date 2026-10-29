# 09 - Testing and QA Specification

## Test Pyramid

### Unit Tests

Cover:

* XML parser hardening
* XSD validation
* ACK/NACK generation
* field validation
* idempotency key calculation
* state transitions
* retry policy
* timer due-time calculation
* number normalization
* operator lookup

### Integration Tests

Use Testcontainers for:

* MariaDB/MySQL
* MongoDB

Cover:

* migrations
* message insert and uniqueness
* inbox/outbox claim concurrency
* stale lock recovery
* XML audit storage
* workflow state persistence
* timer claiming

### End-to-End Tests

Simulate at least:

* Operator A as recipient
* Operator B as donor
* Operator C as publication recipient

Scenarios:

1. successful port
2. eligibility rejected
3. request rejected
4. update porting time
5. cancellation
6. execution and publication
7. partial publication failure
8. number return
9. ownership inquiry
10. operator down/up
11. duplicate inbound message
12. missing ACK
13. late business response
14. invalid XML
15. invalid sender
16. manual reconciliation

## Chaos Tests

Scenarios:

* backend crash after inbound ACK
* dispatcher crash after outbound send before DB update
* timer worker crash while firing timer
* MariaDB restart
* MongoDB restart
* network timeout to remote operator
* duplicate HTTP delivery
* delayed business response

## Performance Tests

Measure:

* inbound messages per second
* ACK latency
* workflow processing latency
* outbox dispatch throughput
* timer backlog handling
* dashboard query latency
* XML lookup latency

## Security Tests

Required:

* XXE payload
* billion laughs / XML bomb
* oversized XML
* invalid certificate
* replayed message
* duplicate request ID
* unauthorized XML view
* manual action without permission
* SQL injection attempts
* stored XSS attempts in notes and XML viewer
