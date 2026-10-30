import { ProtocolFamily } from './types'

// Support levels and examples are audited against the gateway's code (backend workflow handlers,
// ProtocolBodies, OutboundMessageService) and its end-to-end tests. Bodies are shown formatted;
// on the wire NPG sends them on one line after an XML declaration. Numbers are fictitious or masked.

export const protocolSource = 'Ministry of Communications number-portability IT specification, version 4.0'

const headers = (from: string, to: string, type: string, requestId: string, trx: string, process: string) =>
  `Content-Type: application/xml
X-NP-FROM: ${from}
X-NP-TO: ${to}
X-NP-MSG-TYPE: ${type}
X-NP-REQUEST-ID: ${requestId}
X-NP-TRX-NO: ${trx}
X-NP-VERSION-NO: 1
X-NP-PROCESS-TYPE: ${process}`

const PORT_ID = 'NPBZHL261102000000004'

export const families: ProtocolFamily[] = [
  {
    id: 'porting',
    title: 'Porting',
    summary: 'Moving a number from the donor operator to the recipient: eligibility, the request, scheduling, changes, cancellation and execution. NPG plays both roles.',
    messages: [
      {
        name: 'Check',
        direction: 'Recipient → Donor',
        level: 'full',
        wire: 'confirmed',
        explanation: 'Asks the donor whether a number can be ported. It opens every port-in.',
        sends: 'As recipient, when the NOC starts a port-in (one number or a customer batch). NPG waits for the answer on a durable timer.',
        receives: 'As donor: opens a port-out, refuses it when the number is served elsewhere or already being ported, then approves automatically or hands the decision to the NOC.',
        limitations: ['Mobile numbers use mobile/mobileNumberSingle by analogy; the trace only shows fixed numbers.'],
        example: `# BZ (recipient) asks HL (donor) · body formatted for reading
${headers('BZ', 'HL', 'Check', PORT_ID, 'BZ000000000001', 'PORT')}

<npMessageBody>
  <Check>
    <fixed>
      <networkType>F</networkType>
      <fixedNumberSingle>
        <numberType>S</numberType>
        <number>08XXXXXXX</number>
      </fixedNumberSingle>
      <identificationValue>XXXXXXXXX</identificationValue>
      <identificationValue2nd/>
      <identificationValue3rd/>
    </fixed>
  </Check>
</npMessageBody>`
      },
      {
        name: 'Check_response',
        direction: 'Donor → Recipient',
        level: 'full',
        wire: 'confirmed',
        explanation: 'The donor\'s answer to Check: the number can, or cannot, be ported.',
        sends: 'As donor, automatically or after the NOC decides in the console. A refusal carries a reason code.',
        receives: 'As recipient: an approval sends the Request on its own; a refusal ends the port-in with the donor\'s reason. Answers from any other operator are refused.',
        limitations: [
          'The refusal form (negativeApproval with rejectReasonCode) is inferred; the trace only shows approvals.',
          'NPG does not send the optional requestRetryDate seen in the trace.'
        ],
        example: `# HL (donor) approves BZ's Check
${headers('HL', 'BZ', 'Check_response', PORT_ID, 'HL000000000001', 'PORT')}

<npMessageBody>
  <Check_response>
    <requestTrxNo>BZ000000000001</requestTrxNo>
    <positiveApproval>
      <approvalInd>Y</approvalInd>
    </positiveApproval>
  </Check_response>
</npMessageBody>

# a refusal has the same shape
<negativeApproval>
  <approvalInd>N</approvalInd>
  <rejectReasonCode>REJECTED_NUMBER_NOT_FOUND</rejectReasonCode>
  <rejectReasonText>The number is not served by this operator</rejectReasonText>
</negativeApproval>`
      },
      {
        name: 'Request',
        direction: 'Recipient → Donor',
        level: 'full',
        wire: 'confirmed',
        explanation: 'The formal porting request, with the port time the recipient asks for.',
        sends: 'As recipient, as soon as the Check is approved, with the NOC\'s requested time or the configured lead time.',
        receives: 'As donor: requires an approved Check, refuses a port time in the past or more than 90 days ahead, then approves or waits for the NOC.',
        limitations: ['In manual decision mode, a Request that arrives before the NOC approved the Check is refused.'],
        example: `# BZ requests the port for a time 15 minutes ahead
${headers('BZ', 'HL', 'Request', PORT_ID, 'BZ000000000002', 'PORT')}

<npMessageBody>
  <Request>
    <portingDateTime>2026-11-02T10:15:00+02:00</portingDateTime>
  </Request>
</npMessageBody>`
      },
      {
        name: 'Request_response',
        direction: 'Donor → Recipient',
        level: 'full',
        wire: 'confirmed',
        explanation: 'The donor approves the port, which schedules it, or refuses it.',
        sends: 'As donor: an approval schedules the port at the requested time; a refusal carries a reason code.',
        receives: 'As recipient: an approval schedules the Execute on a durable timer at the approved time; a refusal ends the port-in.',
        limitations: [
          'The refusal form is inferred, and the refusal path is not yet covered by tests.',
          'NPG puts requestTrxNo before positiveApproval; the trace shows both orders.'
        ],
        example: `${headers('HL', 'BZ', 'Request_response', PORT_ID, 'HL000000000002', 'PORT')}

<npMessageBody>
  <Request_response>
    <requestTrxNo>BZ000000000002</requestTrxNo>
    <positiveApproval>
      <approvalInd>Y</approvalInd>
    </positiveApproval>
  </Request_response>
</npMessageBody>`
      },
      {
        name: 'Update',
        direction: 'Recipient → Donor',
        level: 'partial',
        wire: 'inferred',
        explanation: 'Moves an already scheduled port to a new time.',
        sends: 'As recipient, when the NOC moves the port time of a scheduled port-in. The original time stays in force until the donor approves.',
        receives: 'As donor: checks the new time and approves it automatically.',
        limitations: [
          'The donor approves automatically, even when port-out decisions are set to manual.',
          'Can only be sent once the port is scheduled.',
          'The donor side is not yet covered by tests.'
        ],
        example: `${headers('BZ', 'HL', 'Update', PORT_ID, 'BZ000000000003', 'PORT')}

<npMessageBody>
  <Update>
    <portingDateTime>2026-11-03T09:00:00+02:00</portingDateTime>
  </Update>
</npMessageBody>`
      },
      {
        name: 'Update_response',
        direction: 'Donor → Recipient',
        level: 'partial',
        wire: 'inferred',
        explanation: 'The donor accepts or refuses the new port time.',
        sends: 'As donor: approves a plausible time, refuses one in the past or too far ahead, or when no port is scheduled.',
        receives: 'As recipient: an approval re-arms the Execute at the new time; a refusal keeps the original time and opens a manual task.',
        limitations: ['Only the recipient\'s handling of an approval is covered by tests.'],
        example: `${headers('HL', 'BZ', 'Update_response', PORT_ID, 'HL000000000003', 'PORT')}

<npMessageBody>
  <Update_response>
    <requestTrxNo>BZ000000000003</requestTrxNo>
    <positiveApproval>
      <approvalInd>Y</approvalInd>
    </positiveApproval>
  </Update_response>
</npMessageBody>`
      },
      {
        name: 'Cancel',
        direction: 'Recipient → Donor',
        level: 'full',
        wire: 'inferred',
        explanation: 'Cancels an approved port before it is executed.',
        sends: 'As recipient, when the NOC cancels an approved or scheduled port-in, with an audited reason. The scheduled Execute is stopped.',
        receives: 'As donor: cancels the port-out and closes any pending NOC decision, or refuses once the cancellation window has closed.',
        limitations: [
          'Whether a port can be cancelled straight after approval, before it is scheduled, is open in the specification; NPG allows it.',
          'A Cancel that arrives while the donor\'s NOC is still deciding on the Check is refused.'
        ],
        example: `${headers('BZ', 'HL', 'Cancel', PORT_ID, 'BZ000000000004', 'PORT')}

<npMessageBody>
  <Cancel>
    <reason>Customer withdrew the request</reason>
  </Cancel>
</npMessageBody>`
      },
      {
        name: 'Cancel_response',
        direction: 'Donor → Recipient',
        level: 'full',
        wire: 'inferred',
        explanation: 'The donor confirms the cancellation, or refuses it.',
        sends: 'As donor: approves, or refuses with REJECTED_CANCELLATION_WINDOW_CLOSED.',
        receives: 'As recipient: an approval cancels the port-in and its timers; a refusal restores the scheduled Execute and opens a high-severity manual task.',
        limitations: ['The refusal path is not yet covered by tests.'],
        example: `${headers('HL', 'BZ', 'Cancel_response', PORT_ID, 'HL000000000004', 'PORT')}

<npMessageBody>
  <Cancel_response>
    <requestTrxNo>BZ000000000004</requestTrxNo>
    <positiveApproval>
      <approvalInd>Y</approvalInd>
    </positiveApproval>
  </Cancel_response>
</npMessageBody>`
      },
      {
        name: 'KD_update',
        direction: 'Recipient → Donor',
        level: 'development',
        wire: 'inferred',
        explanation: 'A Ministry of Defense related update between Request approval and execution. The specification leaves its content and whether it is mandatory open.',
        sends: 'As recipient, only when PORTING_KD_UPDATE_REQUIRED is enabled (off by default), with an empty body.',
        receives: 'As donor: acknowledges it and answers positively when a port-out is open, without reading any content or changing state.',
        limitations: [
          'Its business content is not modelled; the body is empty.',
          'Disabled by default and not covered by tests.'
        ],
        example: `# sent only with PORTING_KD_UPDATE_REQUIRED=true
${headers('BZ', 'HL', 'KD_update', PORT_ID, 'BZ000000000005', 'PORT')}

<npMessageBody>
  <KD_update/>
</npMessageBody>`
      },
      {
        name: 'KD_update_response',
        direction: 'Donor → Recipient',
        level: 'development',
        wire: 'inferred',
        explanation: 'The donor\'s answer to KD_update.',
        sends: 'As donor: approves when a port-out is open for the request, otherwise refuses.',
        receives: 'As recipient: an approval schedules the Execute; a refusal moves the port-in to manual review.',
        limitations: ['Reachable only when KD_update is enabled; not covered by tests.'],
        example: `${headers('HL', 'BZ', 'KD_update_response', PORT_ID, 'HL000000000005', 'PORT')}

<npMessageBody>
  <KD_update_response>
    <requestTrxNo>BZ000000000005</requestTrxNo>
    <positiveApproval>
      <approvalInd>Y</approvalInd>
    </positiveApproval>
  </KD_update_response>
</npMessageBody>`
      },
      {
        name: 'Execute',
        direction: 'Recipient → Donor',
        level: 'full',
        wire: 'confirmed',
        explanation: 'At the approved port time the recipient tells the donor to release the number. The body is empty.',
        sends: 'As recipient, fired by a durable timer at the approved time, so it survives restarts.',
        receives: 'As donor: locks the number, re-checks that it is still ours, transfers ownership to the recipient and confirms with the disconnection time. If the number moved meanwhile it refuses and escalates.',
        limitations: ['The donor does not check that the approved time has arrived; it executes whenever the port is scheduled.'],
        example: `${headers('BZ', 'HL', 'Execute', PORT_ID, 'BZ000000000006', 'PORT')}

<npMessageBody>
  <Execute/>
</npMessageBody>`
      },
      {
        name: 'Execute_response',
        direction: 'Donor → Recipient',
        level: 'full',
        wire: 'confirmed',
        explanation: 'The donor confirms the number was disconnected, and when.',
        sends: 'As donor, with disconnectDateTime once ownership has been transferred; refusals are escalated to the NOC.',
        receives: 'As recipient: takes ownership of the number and publishes the change to every other operator.',
        limitations: ['The recipient\'s handling of a refusal is not yet covered by tests.'],
        example: `${headers('HL', 'BZ', 'Execute_response', PORT_ID, 'HL000000000006', 'PORT')}

<npMessageBody>
  <Execute_response>
    <requestTrxNo>BZ000000000006</requestTrxNo>
    <positiveApproval>
      <approvalInd>Y</approvalInd>
      <disconnectDateTime>2026-11-02T10:15:02+02:00</disconnectDateTime>
    </positiveApproval>
  </Execute_response>
</npMessageBody>`
      }
    ]
  },
  {
    id: 'publication',
    title: 'Publication',
    summary: 'After a port or a return, the operator that now serves the number tells every other operator, so they can route calls to it. NPG publishes and observes.',
    messages: [
      {
        name: 'Publish',
        direction: 'Current operator → All operators',
        level: 'full',
        wire: 'confirmed',
        explanation: 'Announces that a number has moved, so every operator updates its routing.',
        sends: 'Automatically after a completed port-in (to every peer except the donor) and after an approved number return, tracking each recipient\'s answer.',
        receives: 'As observer: updates routing only when the claim matches our records or the operators\' number ranges. A contradiction is refused and opens a high-severity task.',
        limitations: [
          'The return form (publishType Return with originalOperator) is inferred; the trace shows port publications.',
          'A first publication for a number we have no record or range for is accepted from the authenticated publisher; configure every operator\'s ranges.'
        ],
        example: `# BZ publishes its port-in to HI (one message per operator)
${headers('BZ', 'HI', 'Publish', PORT_ID, 'BZ000000000007', 'PORT')}

<npMessageBody>
  <Publish>
    <donor>HL</donor>
    <connectDateTime>2026-11-02T10:15:12+02:00</connectDateTime>
    <publishType>Port</publishType>
    <disconnectDateTime>2026-11-02T10:15:02+02:00</disconnectDateTime>
    <fixed>
      <fixedNumberSingle>
        <numberType>S</numberType>
        <number>08XXXXXXX</number>
      </fixedNumberSingle>
    </fixed>
  </Publish>
</npMessageBody>`
      },
      {
        name: 'Publish_response',
        direction: 'Each operator → Publisher',
        level: 'full',
        wire: 'confirmed',
        explanation: 'Each operator confirms it has applied the new routing, and when.',
        sends: 'As observer, with routeDateTime once routing is updated, or a refusal when the publication contradicts our records.',
        receives: 'As publisher: records each operator\'s answer. All approved completes the process; refusals or silence leave it partially published, with a task for the NOC.',
        limitations: ['Answers to return publications are sent with process type PORT.'],
        example: `${headers('HI', 'BZ', 'Publish_response', PORT_ID, 'HI000000000001', 'PORT')}

<npMessageBody>
  <Publish_response>
    <requestTrxNo>BZ000000000007</requestTrxNo>
    <positiveApproval>
      <approvalInd>Y</approvalInd>
      <routeDateTime>2026-11-02T10:15:20</routeDateTime>
    </positiveApproval>
  </Publish_response>
</npMessageBody>`
      },
      {
        name: 'Cancel_Publish',
        direction: 'Current operator → All operators',
        level: 'partial',
        wire: 'inferred',
        explanation: 'Withdraws a publication, so operators restore the previous routing.',
        sends: 'As publisher, when the NOC withdraws a completed port-in\'s publication (audited), to the operators that applied it.',
        receives: 'As observer: restores the previous routing when the sender is still the recorded owner; otherwise refuses.',
        limitations: [
          'Only from a fully published port-in; not after a partial publication or for number returns.',
          'Sending is not yet covered by tests.',
          'The NOC\'s reason is audited but not carried on the wire.'
        ],
        example: `${headers('BZ', 'HI', 'Cancel_Publish', PORT_ID, 'BZ000000000008', 'PORT')}

<npMessageBody>
  <Cancel_Publish>
    <fixed>
      <fixedNumberSingle>
        <numberType>S</numberType>
        <number>08XXXXXXX</number>
      </fixedNumberSingle>
    </fixed>
  </Cancel_Publish>
</npMessageBody>`
      },
      {
        name: 'Cancel_Publish_response',
        direction: 'Each operator → Publisher',
        level: 'partial',
        wire: 'inferred',
        explanation: 'Each operator confirms it has restored the previous routing.',
        sends: 'As observer: approves after restoring routing, or refuses.',
        receives: 'As publisher: completes the withdrawal; refusals or silence open a manual task.',
        limitations: ['The publisher\'s handling is not yet covered by tests.'],
        example: `${headers('HI', 'BZ', 'Cancel_Publish_response', PORT_ID, 'HI000000000002', 'PORT')}

<npMessageBody>
  <Cancel_Publish_response>
    <requestTrxNo>BZ000000000008</requestTrxNo>
    <positiveApproval>
      <approvalInd>Y</approvalInd>
    </positiveApproval>
  </Cancel_Publish_response>
</npMessageBody>`
      }
    ]
  },
  {
    id: 'return',
    title: 'Number return',
    summary: 'When a ported-in number is disconnected, it goes back to the operator it was originally allocated to, after a cooling period.',
    messages: [
      {
        name: 'Return',
        direction: 'Current operator → Original operator',
        level: 'full',
        wire: 'inferred',
        explanation: 'Hands a disconnected ported-in number back to its original operator.',
        sends: 'When the NOC returns a number we serve after a port-in, once the cooling period (a durable timer) has passed.',
        receives: 'As original operator: takes the number back when we are its recorded original operator and the sender serves it; otherwise refuses.',
        limitations: ['The cooling period is a configurable placeholder (30 days) until the specification\'s value is confirmed.'],
        example: `${headers('BZ', 'HL', 'Return', 'NPBZHL261102000000012', 'BZ000000000012', 'RETURN')}

<npMessageBody>
  <Return>
    <fixed>
      <networkType>F</networkType>
      <fixedNumberSingle>
        <numberType>S</numberType>
        <number>08XXXXXXX</number>
      </fixedNumberSingle>
    </fixed>
  </Return>
</npMessageBody>`
      },
      {
        name: 'Return_response',
        direction: 'Original operator → Current operator',
        level: 'full',
        wire: 'inferred',
        explanation: 'The original operator accepts or refuses the returned number.',
        sends: 'As original operator, after taking the number back, or with a refusal.',
        receives: 'As current operator: an approval transfers the number back and publishes the return to every other operator.',
        limitations: ['A refusal ends the return without opening a manual task, and is not yet covered by tests.'],
        example: `${headers('HL', 'BZ', 'Return_response', 'NPBZHL261102000000012', 'HL000000000012', 'RETURN')}

<npMessageBody>
  <Return_response>
    <requestTrxNo>BZ000000000012</requestTrxNo>
    <positiveApproval>
      <approvalInd>Y</approvalInd>
    </positiveApproval>
  </Return_response>
</npMessageBody>`
      }
    ]
  },
  {
    id: 'inquiry',
    title: 'Number inquiry',
    summary: 'Any operator can ask another which operator serves a number.',
    messages: [
      {
        name: 'Inquire_number',
        direction: 'Any operator → Any operator',
        level: 'partial',
        wire: 'inferred',
        explanation: 'Asks an operator who currently serves a number.',
        sends: 'When the NOC asks a peer from the console.',
        receives: 'As responder: looks the number up in our porting records and answers.',
        limitations: ['Mobile numbers are sent in the fixed-line block (networkType F).'],
        example: `${headers('BZ', 'HL', 'Inquire_number', 'NPBZHL261102000000014', 'BZ000000000014', 'INQUIRY')}

<npMessageBody>
  <Inquire_number>
    <fixed>
      <networkType>F</networkType>
      <fixedNumberSingle>
        <numberType>S</numberType>
        <number>0520000555</number>
      </fixedNumberSingle>
    </fixed>
  </Inquire_number>
</npMessageBody>`
      },
      {
        name: 'Inquire_number_response',
        direction: 'Any operator → Any operator',
        level: 'partial',
        wire: 'inferred',
        explanation: 'Names the operator that serves the number, or says it is not known.',
        sends: 'As responder: the current operator from our porting records, or REJECTED_NUMBER_NOT_FOUND.',
        receives: 'As requester: stores the answer on the inquiry, shown in the console.',
        limitations: [
          'A number we serve natively but never ported has no porting record and is answered "not found"; number ranges are not consulted.',
          'Receiving a "not found" answer is not yet covered by tests.'
        ],
        example: `${headers('HL', 'BZ', 'Inquire_number_response', 'NPBZHL261102000000014', 'HL000000000014', 'INQUIRY')}

<npMessageBody>
  <Inquire_number_response>
    <requestTrxNo>BZ000000000014</requestTrxNo>
    <positiveApproval>
      <approvalInd>Y</approvalInd>
      <currentOperator>HL</currentOperator>
    </positiveApproval>
  </Inquire_number_response>
</npMessageBody>`
      }
    ]
  },
  {
    id: 'maintenance',
    title: 'Maintenance',
    summary: 'Operators announce planned downtime and recovery, so peers hold traffic instead of failing it. These are notifications with no business response.',
    messages: [
      {
        name: 'Down_system',
        direction: 'Any operator → All operators',
        level: 'full',
        wire: 'inferred',
        explanation: 'Announces that an operator\'s gateway is going down for maintenance.',
        sends: 'When an ADMIN announces our maintenance, to every peer (audited).',
        receives: 'As observer: marks the peer down and holds its outgoing traffic without using up retries. A durable timer escalates a peer that stays down too long.',
        example: `# broadcast to every peer
${headers('BZ', 'HL', 'Down_system', 'NPBZBZ261102000000016', 'BZ000000000016', 'MAINTENANCE')}

<npMessageBody>
  <Down_system>
    <reason>Planned upgrade</reason>
    <expectedDuration>PT2H</expectedDuration>
  </Down_system>
</npMessageBody>`
      },
      {
        name: 'Up_system',
        direction: 'Any operator → All operators',
        level: 'full',
        wire: 'inferred',
        explanation: 'Announces that the operator is back.',
        sends: 'When an ADMIN ends our announced maintenance, with the same request ID as the Down_system.',
        receives: 'As observer: marks the peer up and releases its held traffic immediately.',
        limitations: ['Whether Up_system must reference the Down_system is open in the specification; NPG reuses its request ID.'],
        example: `${headers('BZ', 'HL', 'Up_system', 'NPBZBZ261102000000016', 'BZ000000000017', 'MAINTENANCE')}

<npMessageBody>
  <Up_system>
    <reason>Upgrade complete</reason>
  </Up_system>
</npMessageBody>`
      }
    ]
  },
  {
    id: 'sync',
    title: 'Database synchronization',
    summary: 'An operator asks a peer for a snapshot of its porting records, to reconcile the two databases.',
    messages: [
      {
        name: 'DB_synch_request',
        direction: 'Any operator → Any operator',
        level: 'partial',
        wire: 'inferred',
        explanation: 'Requests a synchronization file from a peer.',
        sends: 'When the NOC requests a sync from the console.',
        receives: 'As responder: exports our porting records to a snapshot and answers with its reference.',
        limitations: [
          'No file is transferred: the transfer channel (FTP, SFTP or other) is still open in the specification.',
          'The requested sync type is ignored; the snapshot is always full.'
        ],
        example: `${headers('BZ', 'HL', 'DB_synch_request', 'NPBZHL261102000000018', 'BZ000000000018', 'SYNC')}

<npMessageBody>
  <DB_synch_request>
    <syncType>FULL</syncType>
  </DB_synch_request>
</npMessageBody>`
      },
      {
        name: 'DB_synch_response',
        direction: 'Any operator → Any operator',
        level: 'partial',
        wire: 'inferred',
        explanation: 'Answers with a reference to the synchronization file.',
        sends: 'As responder, always positive, with an internal snapshot reference and the record count.',
        receives: 'As requester: records the reference. Nothing is downloaded or reconciled yet.',
        limitations: ['The npg-sync: reference points into NPG\'s own audit store; peers cannot fetch it.'],
        example: `${headers('HL', 'BZ', 'DB_synch_response', 'NPBZHL261102000000018', 'HL000000000018', 'SYNC')}

<npMessageBody>
  <DB_synch_response>
    <requestTrxNo>BZ000000000018</requestTrxNo>
    <positiveApproval>
      <approvalInd>Y</approvalInd>
      <fileReference>npg-sync:6721f0c2a1b2c3d4e5f60718</fileReference>
      <recordCount>1</recordCount>
    </positiveApproval>
  </DB_synch_response>
</npMessageBody>`
      }
    ]
  },
  {
    id: 'receipt',
    title: 'Technical receipt',
    summary: 'Every message is answered at once with a technical receipt. It only confirms the message was stored; business approval always comes later, as its own message.',
    messages: [
      {
        name: 'ACK',
        direction: 'Receiver → Sender (HTTP response)',
        level: 'partial',
        wire: 'inferred',
        explanation: 'Confirms a message was received and stored durably, before any business processing.',
        sends: 'For every accepted message, as the HTTP 200 response, once the raw XML is in the audit store and the message is queued in the same transaction.',
        receives: 'From peers: NPG accepts its own AckNack form and the trace\'s ACK_CODE Ack00 form, and treats a duplicate NACK as delivered.',
        limitations: ['NPG\'s AckNack XML is a placeholder; the trace shows NP_ACK with ACK_CODE and ACK_DATE, whose XML form is unconfirmed.'],
        example: `# HTTP/1.1 200 OK, Content-Type: application/xml (formatted for reading)
<AckNack>
  <Status>ACK</Status>
  <AckId>3f7c2a9e-5b1d-4e8a-9c3f-2d6b8a1e4f70</AckId>
  <CorrelationId></CorrelationId>
  <OriginalTransactionId>BZ000000000001</OriginalTransactionId>
  <Timestamp>2026-11-02T08:00:01.123456Z</Timestamp>
</AckNack>`
      },
      {
        name: 'NACK',
        direction: 'Receiver → Sender (HTTP response)',
        level: 'partial',
        wire: 'inferred',
        explanation: 'Refuses a message technically, with a reason code, before it reaches any business process.',
        sends: 'For malformed or unsafe XML, unknown message types, missing header fields, duplicates (HTTP 409), unauthenticated senders (403) or the wrong receiver. A temporary failure answers 500 so the peer retries.',
        receives: 'From peers: server errors, timeouts and throttling are retried with backoff; other refusals go to the NOC.',
        limitations: [
          'The AckNack XML is a placeholder, as for ACK.',
          'There is no XSD validation yet, because no official XSD is available.',
          'A body over 1 MB is refused by the HTTP layer without an AckNack body.'
        ],
        example: `# HTTP/1.1 409 Conflict — the same message received twice
<AckNack>
  <Status>NACK</Status>
  <AckId>8a1e4f70-2d6b-4c3f-9e5b-1d3f7c2a9e5b</AckId>
  <CorrelationId></CorrelationId>
  <OriginalTransactionId>BZ000000000001</OriginalTransactionId>
  <Timestamp>2026-11-02T08:00:03.456789Z</Timestamp>
  <NackReason>
    <Code>NACK_DUPLICATE_MESSAGE</Code>
    <Message>Duplicate idempotency key</Message>
  </NackReason>
</AckNack>`
      }
    ]
  }
]
