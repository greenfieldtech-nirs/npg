# Open Questions and Assumptions

## Protocol questions

1. Is the official transport SOAP over HTTPS, raw XML over HTTPS, or both?
2. Are machine-readable XSD/WSDL files available?
3. Is ACK sent as an HTTP response or as a separate XML message?
4. What exact HTTP status codes should be returned for ACK/NACK cases?
5. Which message fields compose the official unique key?
6. What are the final exact timer values?
7. What is the authoritative business calendar?
8. Are Ministry of Defense messages mandatory for all operators?
9. Is there currently a central clearinghouse?
10. Is FTP or SFTP required for DB synchronization files?
11. Is cancellation allowed immediately after request approval (REQUEST_APPROVED → CANCEL_SENT), or only after port scheduling (PORT_SCHEDULED)?
12. Can Cancel_Publish be initiated from PARTIALLY_PUBLISHED state, or only from fully PUBLISHED state? Current implementation assumes PUBLISHED only.
13. Should Down_system include a correlation_id that Up_system references? Up_system currently lists correlation_id as a required header field, while Down_system does not. Clarify whether Up_system correlation_id references the prior Down_system message or is self-generated.

## Operational questions

1. What is the required retention period for XML documents?
2. Are raw XML documents considered sensitive PII?
3. Who can access raw XML?
4. Who can perform manual transitions?
5. Should dangerous manual actions require dual approval?
6. What are the NOC escalation paths?
7. What operator endpoint/certificate data is available?

## Implementation assumptions until answered

- Transport is implemented through an adapter interface; default adapter is HTTPS raw XML.
- SOAP envelope parsing is supported as a future adapter.
- All timer values are stored in configuration tables, not hardcoded.
- Message catalog is loaded from YAML.
- Operator endpoints are configurable.
- Retry policies are configurable.
- Working-calendar logic is pluggable.
- Cancellation from REQUEST_APPROVED is treated as allowed until the official spec clarifies otherwise.
- Cancel_Publish is allowed only from PUBLISHED state until the official spec clarifies whether PARTIALLY_PUBLISHED should also be allowed.
- Up_system correlation_id is treated as self-generated unless the official spec confirms it must reference the prior Down_system message.

## Wire envelope format (observed 2026-10-27)

Real operator trace shows header fields (MSG_TYPE, FROM, TO, REQUEST_ID, TRX_NO, PROCESS_TYPE, VERSION_NO, RETRY_NO, RETRY_DATE) delivered separately from the `<npMessageBody>` XML body. Exact wire packaging is unconfirmed — the gateway currently accepts both an XML envelope (`<npMessage>` with a header child) and bare `<npMessageBody>` with `X-NP-*` HTTP headers. **Open:** confirm the official envelope/header transport with the MoC specification or a peer operator before production. ACK response body shape (`NP_ACK` with `ACK_CODE=Ack00`, `ACK_DATE`) is observed in traces but its XML serialization is unconfirmed — our AckNack XML is a placeholder.

## Implementation assumptions added 2026-10-30 (confirm before production)

Each assumption below is implemented as a configurable placeholder or an isolated rule.

14. **Execute direction.** Corrected from the trace: the recipient sends `Execute` (empty body) and the
    donor answers `Execute_response` with `positiveApproval/disconnectDateTime`. The original
    catalog had it reversed.
15. **Negative business responses.** The trace only shows `positiveApproval/approvalInd=Y`. Rejections
    are sent as `negativeApproval/approvalInd=N` with `rejectReasonCode` (from
    `rejection-codes.yml`) and optional `rejectReasonText`. Confirm the element names.
16. **Mobile numbers.** The trace only shows `fixed/fixedNumberSingle`. Mobile numbers use
    `mobile/mobileNumberSingle` with `networkType=M`, by analogy.
17. **REQUEST_ID / TRX_NO formats.** Generated in the trace's shape: `NP` + recipient + donor +
    yyMMdd + 9 digits, and sender code + 12 digits.
18. **Field names for messages missing from the trace** (Update, Cancel, KD_update, Return,
    Inquire_number, Down_system/Up_system, DB_synch_*):
    - `portingDateTime` for port times;
    - `reason`, `expectedDuration`, `syncType`;
    - `currentOperator` (inquiry answer);
    - `fileReference` / `recordCount` (sync answer);
    - `originalOperator` (return publication).
19. **PROCESS_TYPE values.** `PORT` comes from the trace. `RETURN`, `INQUIRY`, `MAINTENANCE` and
    `SYNC` are placeholders.
20. **Timer durations and working calendar.** Placeholders, seeded in `np_system_parameters`
    (`timer.<NAME>.seconds`, `calendar.*`). Holidays go in `np_working_calendar`.
21. **Messages safe to re-send after an unknown delivery** (`OUTBOUND_SAFE_RESEND_TYPES`). The
    specification does not say which messages tolerate duplicates. A peer's duplicate NACK (or 409)
    is treated as delivered.
22. **Number return cooling period** (`RETURN_COOLING_PERIOD`, placeholder 30 days).
23. **DB synchronization transfer channel.** Still open (FTP/SFTP?). We answer with a reference to a
    snapshot stored in MongoDB (`npg-sync:<id>`); nothing fetches peer files yet.
24. **Publications for numbers we never recorded.** A third party cannot verify the first-ever
    publication of another operator's native number unless that operator's number ranges are
    configured (`np_number_ranges`). Without ranges, such a Publish is accepted from the
    authenticated publisher when it names a donor. Configure every operator's ranges to close this
    gap.
25. **Port-out decisions.** The specification leaves the donor's eligibility rules to the
    operator's business (customer identity, contract, debt), and BSS integration is out of scope.
    `PORT_OUT_DECISION_MODE=MANUAL` (production default) hands each decision to the NOC;
    `AUTO_APPROVE` approves whatever passes the hard rules.
