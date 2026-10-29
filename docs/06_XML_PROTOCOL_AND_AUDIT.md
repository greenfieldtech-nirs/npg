# 06 - XML Protocol and MongoDB Audit Specification

## Secure XML Parsing

The XML parser must:

* disable external entities
* disable DTDs unless explicitly required by the official spec
* prevent XXE
* limit document size
* limit element depth
* limit entity expansion
* reject malformed XML safely
* not log full XML payloads in standard logs

## XML Validation Flow

```text
raw XML
→ store raw document in MongoDB
→ secure parse
→ identify message type
→ validate against XSD if available
→ validate header
→ validate body
→ validate idempotency key
→ produce ACK or NACK
```

## Header Fields

The exact fields must be finalized from the official XSD/WSDL and scanned specification. The implementation should model at minimum:

* sender operator
* receiver operator
* message type
* request ID
* transaction/message ID
* timestamp
* protocol version
* correlation ID
* sequence or sub-request ID where applicable

## Body Fields

Body fields are message-specific and must be generated from the protocol extraction phase.

## ACK/NACK Model

ACK/NACK is technical. It indicates whether the message was accepted into processing.

ACK does not mean:

* porting approved
* eligibility approved
* cancellation approved
* publication accepted as business result

NACK means the message did not enter business processing.

## Business Response Model

Business response is separate. Examples:

* Check_response
* Request_response
* Update_response
* Cancel_response
* Execute_response
* Publish_response
* Return_response
* Inquire_number_response
* DB_synch_response

## MongoDB Collection: `xml_messages`

Suggested structure:

```json
{
  "_id": "ObjectId",
  "messageUuid": "uuid",
  "workflowUuid": "uuid",
  "direction": "INBOUND",
  "messageType": "Check",
  "senderOperatorCode": "OP_A",
  "receiverOperatorCode": "OP_B",
  "requestId": "REQ-123",
  "transactionId": "TX-123",
  "correlationId": "CORR-123",
  "schemaVersion": "4.0",
  "rawXml": "<xml>...</xml>",
  "canonicalSha256": "sha256...",
  "validation": {
    "status": "VALID",
    "errors": []
  },
  "transport": {
    "protocol": "HTTPS",
    "remoteIp": "1.2.3.4",
    "tlsSubject": "CN=operator",
    "headers": {}
  },
  "createdAt": "2026-01-01T00:00:00Z"
}
```

## Indexes

Create indexes for:

* `messageUuid`
* `workflowUuid`
* `messageType`
* `senderOperatorCode`
* `receiverOperatorCode`
* `requestId`
* `transactionId`
* `canonicalSha256`
* `createdAt`

## Immutability

Application code must not update `rawXml`.

If validation status must be added after first insert, use a separate metadata update or design two-step write:

1. Insert raw XML and hash.
2. Update validation subdocument only.

Never alter raw XML content.
