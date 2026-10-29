# 07 - Management API and Frontend

> **Status note (2026-10-29):** This is the original specification document — some listed endpoints (e.g., `mark-up`, `assign`, `/parsed`, `/retry`) are design intent and not yet implemented. For the implemented API surface, see the OpenAPI spec at `backend/src/main/resources/openapi/npg-api.yaml` and the endpoint table in the project README. Specification

## Management API Principles

* Internal APIs are JSON REST APIs.
* External operator APIs are XML/SOAP/HTTP depending on final protocol transport.
* Management APIs must be role-protected.
* XML retrieval must be audited.
* Dangerous actions require reason text.

## API Groups

### Workflow API

```text
GET /api/workflows
GET /api/workflows/{workflowUuid}
GET /api/workflows/{workflowUuid}/events
GET /api/workflows/{workflowUuid}/messages
GET /api/workflows/{workflowUuid}/timers
POST /api/workflows/{workflowUuid}/manual-transition
```

### Message API

```text
GET /api/messages
GET /api/messages/{messageUuid}
GET /api/messages/{messageUuid}/xml
GET /api/messages/{messageUuid}/parsed
POST /api/messages/{messageUuid}/retry
```

### Operator API

```text
GET /api/operators
POST /api/operators
GET /api/operators/{operatorCode}
PUT /api/operators/{operatorCode}
POST /api/operators/{operatorCode}/mark-down
POST /api/operators/{operatorCode}/mark-up
```

### Manual Task API

```text
GET /api/manual-tasks
GET /api/manual-tasks/{taskUuid}
POST /api/manual-tasks/{taskUuid}/assign
POST /api/manual-tasks/{taskUuid}/resolve
POST /api/manual-tasks/{taskUuid}/notes
```

### Timer API

```text
GET /api/timers
GET /api/timers/{timerUuid}
POST /api/timers/{timerUuid}/cancel
POST /api/timers/{timerUuid}/reschedule
```

### Number API

```text
GET /api/numbers/{phoneNumber}
GET /api/numbers/{phoneNumber}/history
```

## Frontend Screens

### Dashboard

Cards:

* active workflows
* workflows waiting for ACK
* workflows waiting for business response
* open manual tasks
* failed workflows
* operators down
* publication backlog
* timers due soon

### Workflow Search

MUI DataGrid with filters:

* phone number
* request ID
* workflow state
* operator
* date range
* message type
* error code

### Workflow Detail

Tabs:

* Overview
* Timeline
* Messages
* Timers
* Publication recipients
* XML
* Manual tasks
* Audit

### XML Viewer

Features:

* raw XML
* pretty XML
* parsed fields
* validation result
* copy/download
* hash display
* access audit notice

### Operator Management

Features:

* operator status
* endpoint configuration
* last down/up events
* certificates/auth metadata
* contact details

### Manual Operations Queue

Features:

* assignment
* notes
* resolution
* retry if allowed
* mark external coordination complete
* close task

## Frontend Stack

* React
* TypeScript
* Vite
* MUI
* MUI DataGrid
* React Router
* TanStack Query
* Axios or Fetch wrapper
* i18n-ready label structure

## Accessibility

Must support:

* keyboard navigation
* visible focus
* sufficient contrast
* table screen-reader labels
* accessible dialogs
* no color-only status indication
