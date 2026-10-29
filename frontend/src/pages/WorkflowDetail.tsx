import { ReactNode, useState } from 'react'
import Loader from '../components/Loader'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import {
  Alert, Box, Button, Card, CardContent, Chip, FormControl, Grid, InputLabel, MenuItem, Select, Stack,
  Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography
} from '@mui/material'
import { workflowApi, operatorApi } from '../api/client'
import { Workflow } from '../api/types'
import PageHeader from '../components/PageHeader'
import ReasonDialog from '../components/ReasonDialog'
import { useAuth } from '../auth/AuthContext'

function formatDate(value: string | null | undefined): string {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 19)
}

function DetailItem({ label, value }: { label: string; value: string }) {
  return (
    <Grid item xs={12} sm={6} md={4}>
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography variant="body2" sx={{ wordBreak: 'break-all' }}>{value}</Typography>
    </Grid>
  )
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Card sx={{ mb: 2 }}>
      <CardContent>
        <Typography variant="subtitle2" sx={{ mb: 1 }}>{title}</Typography>
        {children}
      </CardContent>
    </Card>
  )
}

function SimpleTable({ headers, rows, empty }: { headers: string[]; rows: ReactNode[][]; empty: string }) {
  if (rows.length === 0) return <Alert severity="info">{empty}</Alert>
  return (
    <Table size="small">
      <TableHead><TableRow>{headers.map(h => <TableCell key={h}>{h}</TableCell>)}</TableRow></TableHead>
      <TableBody>
        {rows.map((cells, i) => <TableRow key={i}>{cells.map((c, j) => <TableCell key={j}>{c}</TableCell>)}</TableRow>)}
      </TableBody>
    </Table>
  )
}

type Action = 'transition' | 'portTime' | 'cancel' | 'cancelPublication' | 'approve' | 'reject' | null

export default function WorkflowDetail() {
  const { uuid } = useParams()
  const { hasRole } = useAuth()
  const queryClient = useQueryClient()
  const [action, setAction] = useState<Action>(null)
  const [toState, setToState] = useState('')
  const [portTime, setPortTime] = useState('')
  const [rejectCode, setRejectCode] = useState('REJECTED_INELIGIBLE')

  const workflow = useQuery({ queryKey: ['workflow', uuid], queryFn: () => workflowApi.get(uuid!), retry: false })
  const loaded = !!workflow.data
  const events = useQuery({ queryKey: ['workflowEvents', uuid], queryFn: () => workflowApi.getEvents(uuid!), enabled: loaded })
  const timers = useQuery({ queryKey: ['workflowTimers', uuid], queryFn: () => workflowApi.timers(uuid!), enabled: loaded })
  const messages = useQuery({ queryKey: ['workflowMessages', uuid], queryFn: () => workflowApi.messages(uuid!), enabled: loaded })
  const publication = useQuery({ queryKey: ['workflowPublication', uuid], queryFn: () => workflowApi.publication(uuid!), enabled: loaded })
  const operators = useQuery({ queryKey: ['operators'], queryFn: operatorApi.list })

  if (workflow.isLoading) return <Loader />
  if (workflow.isError) {
    return (
      <Box>
        <PageHeader title="Workflow Detail" subtitle={uuid ?? ''} />
        <Alert severity="info">Workflow not found.</Alert>
      </Box>
    )
  }

  const data: Workflow = workflow.data!
  const canOperate = hasRole('ADMIN', 'MANAGER')
  const isRecipient = data.workflowType === 'PORTING' && data.localRole === 'RECIPIENT'
  const isDonor = data.workflowType === 'PORTING' && data.localRole === 'DONOR'
  const decisionPending = isDonor && (data.currentState === 'CHECK_RECEIVED' || data.currentState === 'REQUEST_RECEIVED')
  const operatorCode = (id: number | null) =>
    id == null ? '—' : (operators.data?.find(o => o.id === id)?.operatorCode ?? `#${id}`)

  const refresh = () => {
    for (const key of ['workflow', 'workflowEvents', 'workflowTimers', 'workflowMessages', 'workflowPublication']) {
      queryClient.invalidateQueries({ queryKey: [key, uuid] })
    }
  }
  const run = (call: Promise<unknown>) => call.then(refresh)

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', flexWrap: 'wrap', gap: 1 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
          <PageHeader title={`${data.workflowType} — ${data.phoneNumber ?? data.requestId}`} subtitle={data.workflowUuid} />
          <Chip label={data.localRole} size="small" variant="outlined" sx={{ mb: 3 }} />
          <Chip label={data.currentState} color={data.currentState === 'MANUAL_REVIEW' ? 'warning' : 'primary'} size="small" sx={{ mb: 3 }} />
        </Box>
        {canOperate && (
          <Stack direction="row" spacing={1} sx={{ mt: 1, flexWrap: 'wrap' }}>
            {decisionPending && <Button variant="contained" color="success" onClick={() => setAction('approve')}>Approve port-out</Button>}
            {decisionPending && <Button variant="outlined" color="error" onClick={() => setAction('reject')}>Reject port-out</Button>}
            {isRecipient && data.currentState === 'PORT_SCHEDULED' && <Button variant="outlined" onClick={() => setAction('portTime')}>Move port time</Button>}
            {isRecipient && ['REQUEST_APPROVED', 'PORT_SCHEDULED'].includes(data.currentState) &&
              <Button variant="outlined" color="error" onClick={() => setAction('cancel')}>Cancel port</Button>}
            {isRecipient && data.currentState === 'COMPLETED' &&
              <Button variant="outlined" color="error" onClick={() => setAction('cancelPublication')}>Cancel publication</Button>}
            <Button variant="outlined" onClick={() => { setToState(''); setAction('transition') }}>Manual transition</Button>
          </Stack>
        )}
      </Box>

      {data.currentState === 'MANUAL_REVIEW' && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          This workflow needs a NOC decision — see its manual tasks, then resume it with a manual transition.
        </Alert>
      )}

      <Section title="Overview">
        <Grid container spacing={2}>
          <DetailItem label="Request ID" value={data.requestId} />
          <DetailItem label="Phone Number" value={data.phoneNumber ?? '—'} />
          <DetailItem label="Counterparty" value={data.counterpartyCode ?? '—'} />
          <DetailItem label="Recipient Operator" value={operatorCode(data.recipientOperatorId)} />
          <DetailItem label="Donor Operator" value={operatorCode(data.donorOperatorId)} />
          <DetailItem label="Original Operator" value={operatorCode(data.originalOperatorId)} />
          <DetailItem label="Requested Port Time (UTC)" value={formatDate(data.requestedPortTime)} />
          <DetailItem label="Approved Port Time (UTC)" value={formatDate(data.approvedPortTime)} />
          <DetailItem label="Executed At" value={formatDate(data.executedAt)} />
          <DetailItem label="Completed At" value={formatDate(data.completedAt)} />
          <DetailItem label="Cancelled At" value={formatDate(data.cancelledAt)} />
          <DetailItem label="Failure" value={data.failureCode ? `${data.failureCode} ${data.failureReason ?? ''}` : '—'} />
          <DetailItem label="Customer Batch" value={data.customerBatchId ?? '—'} />
          <DetailItem label="Created" value={`${formatDate(data.createdAt)} by ${data.createdBy ?? 'system'}`} />
          <DetailItem label="Updated" value={formatDate(data.updatedAt)} />
        </Grid>
      </Section>

      <Section title="Messages">
        <SimpleTable
          headers={['Created', 'Type', 'Direction', 'From → To', 'TRX', 'Status', 'Outbox']}
          empty="No messages yet."
          rows={(messages.data ?? []).map(m => [
            formatDate(m.createdAt),
            <Link key="t" to={`/messages/${m.messageUuid}`}>{m.messageType}</Link>,
            m.direction,
            `${m.sender ?? '—'} → ${m.receiver ?? '—'}`,
            m.transactionId ?? '—',
            [m.technicalStatus, m.ackStatus, m.nackCode].filter(Boolean).join(' / '),
            m.outboxStatus ?? '—'
          ])}
        />
      </Section>

      {(publication.data?.length ?? 0) > 0 && (
        <Section title="Publication recipients">
          <SimpleTable
            headers={['Purpose', 'Operator', 'Status', 'Response', 'Sent', 'Answered']}
            empty=""
            rows={publication.data!.map(p => [p.purpose, p.operatorCode, p.status, p.responseStatus ?? '—',
              formatDate(p.sentAt), formatDate(p.responseAt)])}
          />
        </Section>
      )}

      <Section title="Timers">
        <SimpleTable
          headers={['Timer', 'Status', 'Due (UTC)', 'Fired', 'Attempts']}
          empty="No timers."
          rows={(timers.data ?? []).map(t => [t.timerName, t.status, formatDate(t.dueAt), formatDate(t.firedAt), t.attempts])}
        />
      </Section>

      <Section title="Events">
        <SimpleTable
          headers={['Date', 'Event', 'From', 'To', 'Actor']}
          empty="No events recorded yet."
          rows={(events.data ?? []).map(e => [formatDate(e.createdAt), e.eventType, e.fromState ?? '—', e.toState ?? '—',
            e.createdBy ?? 'system'])}
        />
      </Section>

      <ReasonDialog
        open={action === 'transition'} title="Manual transition" confirmLabel="Apply" danger
        description={`Overrides the state machine (current: ${data.currentState}). Use only after resolving the situation with the peer.`}
        onClose={() => setAction(null)}
        onConfirm={reason => toState ? run(workflowApi.manualTransition(uuid!, toState, reason)) : Promise.reject(new Error('Choose a state'))}
      >
        <FormControl fullWidth required>
          <InputLabel>To state</InputLabel>
          <Select label="To state" value={toState} onChange={e => setToState(e.target.value)}>
            {(data.machineStates ?? []).map(state => (
              <MenuItem key={state} value={state} disabled={state === data.currentState}>{state}</MenuItem>
            ))}
          </Select>
        </FormControl>
      </ReasonDialog>

      <ReasonDialog
        open={action === 'portTime'} title="Move port time" confirmLabel="Send Update" reasonRequired={false}
        reasonLabel="Note (optional)"
        description="Sends Update to the donor; the port re-schedules once the donor approves."
        onClose={() => setAction(null)}
        onConfirm={() => portTime ? run(workflowApi.updatePortTime(uuid!, new Date(portTime).toISOString())) : Promise.reject(new Error('Choose a time'))}
      >
        <TextField type="datetime-local" label="New port time (your local time)" InputLabelProps={{ shrink: true }}
          value={portTime} onChange={e => setPortTime(e.target.value)} />
      </ReasonDialog>

      <ReasonDialog
        open={action === 'cancel'} title="Cancel port" confirmLabel="Send Cancel" danger
        description="Sends Cancel to the donor and stops the scheduled execution."
        onClose={() => setAction(null)} onConfirm={reason => run(workflowApi.cancel(uuid!, reason))}
      />

      <ReasonDialog
        open={action === 'cancelPublication'} title="Cancel publication" confirmLabel="Send Cancel_Publish" danger
        description="Withdraws the publication from every operator that accepted it and restores the donor's routing."
        onClose={() => setAction(null)} onConfirm={reason => run(workflowApi.cancelPublication(uuid!, reason))}
      />

      <ReasonDialog
        open={action === 'approve'} title="Approve port-out" confirmLabel="Approve" reasonRequired={false}
        reasonLabel="Note (optional)"
        description={`Answers the recipient's ${data.currentState === 'CHECK_RECEIVED' ? 'Check' : 'Request'} positively.`}
        onClose={() => setAction(null)} onConfirm={note => run(workflowApi.decide(uuid!, true, undefined, note || undefined))}
      />

      <ReasonDialog
        open={action === 'reject'} title="Reject port-out" confirmLabel="Reject" danger reasonLabel="Explanation (sent to the recipient)"
        onClose={() => setAction(null)} onConfirm={text => run(workflowApi.decide(uuid!, false, rejectCode, text))}
      >
        <FormControl fullWidth>
          <InputLabel>Rejection code</InputLabel>
          <Select label="Rejection code" value={rejectCode} onChange={e => setRejectCode(e.target.value)}>
            {['REJECTED_INELIGIBLE', 'REJECTED_NUMBER_NOT_FOUND', 'REJECTED_INVALID_PORT_TIME', 'REJECTED_ALREADY_PORTED', 'REJECTED_OTHER']
              .map(code => <MenuItem key={code} value={code}>{code}</MenuItem>)}
          </Select>
        </FormControl>
      </ReasonDialog>
    </Box>
  )
}
