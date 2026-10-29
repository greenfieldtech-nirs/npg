import { useState } from 'react'
import Loader from '../components/Loader'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import {
  Alert, Box, Button, Card, CardContent, Chip, Dialog, DialogActions,
  DialogContent, DialogTitle, FormControl, Grid, InputLabel,
  MenuItem, Select, Table, TableBody, TableCell, TableHead, TableRow,
  TextField, Typography
} from '@mui/material'
import axios from 'axios'
import { workflowApi, messageApi, operatorApi } from '../api/client'
import { Workflow } from '../api/types'
import PageHeader from '../components/PageHeader'
import { useAuth } from '../auth/AuthContext'

function formatDate(value: string | null): string {
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

const TRANSITION_STATES = [
  'NEW', 'CHECK_SENT', 'CHECK_APPROVED', 'CHECK_REJECTED', 'REQUEST_SENT',
  'REQUEST_APPROVED', 'REQUEST_REJECTED', 'PORT_SCHEDULED', 'UPDATE_SENT',
  'CANCEL_SENT', 'CANCELLED', 'EXECUTION_SENT', 'EXECUTION_CONFIRMED',
  'PUBLISH_PENDING', 'PUBLISH_SENT', 'PUBLISHED', 'COMPLETED', 'FAILED', 'MANUAL_REVIEW'
]

export default function WorkflowDetail() {
  const { uuid } = useParams()
  const { hasRole } = useAuth()
  const queryClient = useQueryClient()
  const [transitionDialog, setTransitionDialog] = useState(false)
  const [toState, setToState] = useState('')
  const [reason, setReason] = useState('')
  const [transitionError, setTransitionError] = useState<string | null>(null)

  const workflow = useQuery({ queryKey: ['workflow', uuid], queryFn: () => workflowApi.get(uuid!), retry: false })
  const events = useQuery({
    queryKey: ['workflowEvents', uuid],
    queryFn: () => workflowApi.getEvents(uuid!),
    enabled: !!workflow.data
  })
  const operators = useQuery({ queryKey: ['operators'], queryFn: operatorApi.list })
  const messages = useQuery({
    queryKey: ['workflowMessages', workflow.data?.requestId],
    queryFn: () => messageApi.search({ requestId: workflow.data!.requestId, size: 50 }),
    enabled: !!workflow.data?.requestId
  })

  const transition = useMutation({
    mutationFn: () => workflowApi.manualTransition(uuid!, toState, reason),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['workflow', uuid] })
      queryClient.invalidateQueries({ queryKey: ['workflowEvents', uuid] })
      setTransitionDialog(false)
      setTransitionError(null)
    },
    onError: err => {
      setTransitionError(axios.isAxiosError(err) ? (err.response?.data?.error ?? err.message) : 'Transition failed')
    }
  })

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
  const operatorCode = (id: number | null) =>
    id == null ? '—' : (operators.data?.find(o => o.id === id)?.operatorCode ?? `#${id}`)

  const openTransition = () => {
    setToState('')
    setReason('')
    setTransitionError(null)
    setTransitionDialog(true)
  }

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between' }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
          <PageHeader title={`${data.workflowType} — ${data.phoneNumber}`} subtitle={data.workflowUuid} />
          <Chip label={data.currentState} color="primary" size="small" sx={{ mb: 3 }} />
        </Box>
        {hasRole('ADMIN', 'MANAGER') && (
          <Button variant="outlined" onClick={openTransition} sx={{ mt: 1 }}>
            Manual Transition
          </Button>
        )}
      </Box>

      <Card sx={{ mb: 2 }}>
        <CardContent>
          <Typography variant="subtitle2" sx={{ mb: 2 }}>Overview</Typography>
          <Grid container spacing={2}>
            <DetailItem label="Workflow UUID" value={data.workflowUuid} />
            <DetailItem label="Request ID" value={data.requestId} />
            <DetailItem label="Phone Number" value={data.phoneNumber} />
            <DetailItem label="Workflow Type" value={data.workflowType} />
            <DetailItem label="Current State" value={data.currentState} />
            <DetailItem label="Customer Batch" value={data.customerBatchId ?? '—'} />
            <DetailItem label="Recipient Operator" value={operatorCode(data.recipientOperatorId)} />
            <DetailItem label="Donor Operator" value={operatorCode(data.donorOperatorId)} />
            <DetailItem label="Original Operator" value={operatorCode(data.originalOperatorId)} />
            <DetailItem label="Current Operator" value={operatorCode(data.currentOperatorId)} />
            <DetailItem label="Requested Port Time" value={formatDate(data.requestedPortTime)} />
            <DetailItem label="Approved Port Time" value={formatDate(data.approvedPortTime)} />
            <DetailItem label="Executed At" value={formatDate(data.executedAt)} />
            <DetailItem label="Completed At" value={formatDate(data.completedAt)} />
            <DetailItem label="Cancelled At" value={formatDate(data.cancelledAt)} />
            <DetailItem label="Failed At" value={formatDate(data.failedAt)} />
            <DetailItem label="Failure Code" value={data.failureCode ?? '—'} />
            <DetailItem label="Failure Reason" value={data.failureReason ?? '—'} />
            <DetailItem label="Created By" value={data.createdBy ?? '—'} />
            <DetailItem label="Created At" value={formatDate(data.createdAt)} />
            <DetailItem label="Updated At" value={formatDate(data.updatedAt)} />
          </Grid>
        </CardContent>
      </Card>

      <Card sx={{ mb: 2 }}>
        <CardContent>
          <Typography variant="subtitle2" sx={{ mb: 1 }}>Events</Typography>
          {events.isLoading && <Loader />}
          {events.data && events.data.length === 0 && (
            <Alert severity="info">No events recorded yet.</Alert>
          )}
          {events.data && events.data.length > 0 && (
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Date</TableCell>
                  <TableCell>Event</TableCell>
                  <TableCell>From</TableCell>
                  <TableCell>To</TableCell>
                  <TableCell>Actor</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {events.data.map(event => (
                  <TableRow key={event.id}>
                    <TableCell>{formatDate(event.createdAt)}</TableCell>
                    <TableCell>{event.eventType}</TableCell>
                    <TableCell>{event.fromState ?? '—'}</TableCell>
                    <TableCell>{event.toState ?? '—'}</TableCell>
                    <TableCell>{event.createdBy ?? 'system'}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardContent>
          <Typography variant="subtitle2" sx={{ mb: 1 }}>Related Messages</Typography>
          {messages.isLoading && <Loader />}
          {messages.data && messages.data.length === 0 && (
            <Alert severity="info">No messages linked to this request.</Alert>
          )}
          {messages.data && messages.data.length > 0 && (
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Type</TableCell>
                  <TableCell>Direction</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell>Transaction ID</TableCell>
                  <TableCell>Created</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {messages.data.map(message => (
                  <TableRow key={message.messageUuid}>
                    <TableCell>
                      <Link to={`/messages/${message.messageUuid}`}>{message.messageType}</Link>
                    </TableCell>
                    <TableCell>{message.direction}</TableCell>
                    <TableCell>{message.technicalStatus}</TableCell>
                    <TableCell>{message.transactionId ?? '—'}</TableCell>
                    <TableCell>{formatDate(message.createdAt)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <Dialog
        open={transitionDialog}
        onClose={() => setTransitionDialog(false)}
        maxWidth="xs"
        fullWidth
        PaperProps={{ sx: { borderRadius: '12px' } }}
      >
        <DialogTitle>
          <Typography variant="h6">Manual Transition</Typography>
        </DialogTitle>
        <DialogContent sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: 2 }}>
          {transitionError && <Alert severity="error">{transitionError}</Alert>}
          <Typography variant="body2" color="text.secondary">
            Current state: {data.currentState}. This action is audited.
          </Typography>
          <FormControl fullWidth required>
            <InputLabel>To State</InputLabel>
            <Select label="To State" value={toState} onChange={e => setToState(e.target.value)}>
              {TRANSITION_STATES.map(state => (
                <MenuItem key={state} value={state} disabled={state === data.currentState}>
                  {state}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
          <TextField
            label="Reason" fullWidth multiline minRows={2}
            value={reason} onChange={e => setReason(e.target.value)}
          />
        </DialogContent>
        <DialogActions sx={{ justifyContent: 'flex-end', px: 3, pb: 2 }}>
          <Button onClick={() => setTransitionDialog(false)}>Cancel</Button>
          <Button
            variant="contained"
            disabled={!toState || transition.isPending}
            onClick={() => transition.mutate()}
          >
            Apply
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}
