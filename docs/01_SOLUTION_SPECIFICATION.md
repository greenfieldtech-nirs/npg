# 01 - Solution Specification

## Product Name

Israeli Number Portability Gateway

## Business Purpose

The system enables a licensed telecom operator to participate in Israeli number portability message exchange with other licensed operators. It supports technical receipt, validation, ACK/NACK, business workflow processing, outbound messaging, audit storage, retries, timers, manual reconciliation, and operational management.

## Source Specification Summary

The uploaded Hebrew document is the Israeli IT specification for number portability between licensed operators, version 4.0, January 2008. It defines XML-based communication, operator roles, message categories, ACK/NACK behavior, timers, message headers/bodies, negative responses, and workflows for number portability, cancellation, execution, publication, number return, query, maintenance, and synchronization.

Because the PDF is scanned, the implementation must include a Phase 0 protocol extraction step before finalizing message schemas and timer values.

## Mandatory Requirements

### Backend

* Java 21 or newer.
* Vert.x.
* Non-blocking HTTP server.
* Secure XML parser.
* XSD validation support.
* MariaDB/MySQL persistence.
* MongoDB XML audit storage.
* No external message broker.

### Database

* MariaDB or MySQL is mandatory.
* It is the canonical source of truth for workflow state, message metadata, timers, queue state, operator registry, number ownership, and manual operations.
* It must implement the durable inbox/outbox pattern.

### XML Audit Store

* MongoDB is mandatory for raw XML documents.
* Store inbound and outbound XML exactly as received/sent.
* Store XML hash, metadata, validation result, and transport metadata.

### Frontend

* React.
* TypeScript.
* MUI / Material Design.
* Operational console, not merely admin CRUD.

### Runtime

* Docker container stack.
* Docker Compose for development and staging.
* Production-compatible container architecture.

## Critical Domain Rules

1. One inter-operator message represents one phone number.
2. Local customer-level batch actions must split into one workflow per number.
3. ACK/NACK is technical only.
4. Business response is separate from ACK/NACK.
5. All timers are durable and database-backed.
6. Workflow state is authoritative in MariaDB/MySQL.
7. Raw XML is authoritative for audit in MongoDB.
8. Manual operator coordination is part of the operational model.
9. Publication is multi-recipient and must be tracked per receiving operator.
10. Retry behavior must be message-type and workflow-state aware.

## Main Actors

* Recipient / gaining operator.
* Donor / losing operator.
* Original operator.
* Current operator.
* Ministry of Communications.
* Ministry of Defense.
* Optional clearinghouse.
* Internal operator/NOC user.
* Auditor.
* System administrator.

## Main Message Families

* Eligibility check.
* Porting request.
* Porting time update.
* Cancellation.
* Ministry of Defense update.
* Execution/disconnection.
* Publication.
* Publication cancellation.
* Number return.
* Number ownership inquiry.
* System down/up maintenance.
* Database synchronization.

Known message names from the specification include:

* Check
* Check_response
* Request
* Request_response
* Update
* Update_response
* Cancel
* Cancel_response
* KD_update
* KD_update_response
* Execute
* Execute_response
* Publish
* Publish_response
* Cancel_Publish
* Cancel_Publish_response
* Return
* Return_response
* Inquire_number
* Inquire_number_response
* Down_system
* Up_system
* DB_synch_request
* DB_synch_response

## Out of Scope for First Production Cut

Unless explicitly required later:

* Actual BSS/OSS customer onboarding UI.
* Actual network routing switch configuration.
* Billing integration.
* External clearinghouse integration beyond modeled interfaces.
* Full production SSO integration if local auth is sufficient for early testing.
* Immutable WORM storage beyond MongoDB and database hash records.

## Assumptions to Validate

1. Final transport type: SOAP over HTTPS, raw XML over HTTPS, or both.
2. Availability of official machine-readable XSD/WSDL.
3. Required operator authentication model.
4. Whether mTLS is mandatory.
5. Exact retention period for XML audit documents.
6. Working-hours calendar and holiday authority.
7. Current real-world operator code list.
8. Exact behavior of ACK delivery: synchronous HTTP response, separate message, or both.
