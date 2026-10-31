import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Box, Card, FormControl, InputLabel, MenuItem, Select, Stack, Tab, Tabs } from '@mui/material'
import ReplayIcon from '@mui/icons-material/Replay'
import DoneAllIcon from '@mui/icons-material/DoneAll'
import CancelOutlinedIcon from '@mui/icons-material/CancelOutlined'
import BoltIcon from '@mui/icons-material/Bolt'
import SearchIcon from '@mui/icons-material/Search'
import { DataGrid, GridColDef } from '@mui/x-data-grid'
import { queueApi } from '../api/client'
import { InboxItem, OutboxItem, TimerItem } from '../api/types'
import PageHeader from '../components/PageHeader'
import RefreshButton from '../components/RefreshButton'
import ReasonDialog from '../components/ReasonDialog'
import RowAction from '../components/RowAction'
import { useAuth } from '../auth/AuthContext'

const STATUSES: Record<string, string[]> = {
  outbox: ['MANUAL_REVIEW', 'UNKNOWN_DELIVERY', 'FAILED_RETRYABLE', 'PENDING', 'SENDING', 'SUCCEEDED', 'FAILED_FINAL', 'CANCELLED'],
  inbox: ['FAILED_FINAL', 'FAILED_RETRYABLE', 'PENDING', 'PROCESSING', 'SUCCEEDED'],
  timers: ['PENDING', 'FAILED', 'SUCCEEDED', 'CANCELLED']
}

interface PendingAction { title: string; description: string; run: (reason: string) => Promise<unknown>; danger?: boolean }

const date = (v: string | null) => (v ? v.replace('T', ' ').slice(0, 19) : '—')

/** The durable queues and timers, with the operator actions from the runbooks. */
export default function QueuesPage() {
  const { hasRole } = useAuth()
  const canOperate = hasRole('ADMIN', 'MANAGER')
  const queryClient = useQueryClient()
  const [tab, setTab] = useState<'outbox' | 'inbox' | 'timers'>('outbox')
  const [status, setStatus] = useState('MANUAL_REVIEW')
  const [pending, setPending] = useState<PendingAction | null>(null)

  const outbox = useQuery({ queryKey: ['outbox', status], queryFn: () => queueApi.outbox(status), enabled: tab === 'outbox' })
  const inbox = useQuery({ queryKey: ['inbox', status], queryFn: () => queueApi.inbox(status), enabled: tab === 'inbox' })
  const timers = useQuery({ queryKey: ['timers', status], queryFn: () => queueApi.timers(status), enabled: tab === 'timers' })
  const active = tab === 'outbox' ? outbox : tab === 'inbox' ? inbox : timers
  const refresh = () => queryClient.invalidateQueries({ queryKey: [tab] })

  const act = (title: string, description: string, call: (reason: string) => Promise<unknown>, danger = false) =>
    setPending({ title, description, danger, run: reason => call(reason).then(refresh) })

  const workflowLink = (uuid: string | null | undefined) => (uuid
    ? <RowAction label="Open workflow" icon={<SearchIcon fontSize="small" />} color="primary" to={`/workflows/${uuid}`} />
    : '—')

  const outboxColumns: GridColDef<OutboxItem>[] = [
    { field: 'updatedAt', headerName: 'Updated', width: 160, valueFormatter: (v: string) => date(v) },
    { field: 'itemType', headerName: 'Message', width: 150 },
    { field: 'destination', headerName: 'To', width: 80 },
    { field: 'status', headerName: 'Status', width: 150 },
    { field: 'attempts', headerName: 'Tries', width: 70 },
    { field: 'lastErrorCode', headerName: 'Last error', width: 170 },
    { field: 'workflowUuid', headerName: 'Workflow', width: 90, renderCell: p => workflowLink(p.row.workflowUuid) },
    {
      field: 'actions', headerName: '', width: 130, sortable: false,
      renderCell: p => canOperate && (
        <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center', height: '100%' }}>
          {['MANUAL_REVIEW', 'FAILED_FINAL', 'UNKNOWN_DELIVERY'].includes(p.row.status) &&
            <RowAction label="Retry delivery" icon={<ReplayIcon fontSize="small" />} color="primary"
              onClick={() => act('Retry delivery', `Re-send ${p.row.itemType} to ${p.row.destination} with a fresh attempt budget.`,
                reason => queueApi.retryOutbox(p.row.outboxUuid, reason))} />}
          {['MANUAL_REVIEW', 'UNKNOWN_DELIVERY'].includes(p.row.status) &&
            <RowAction label="Mark delivered" icon={<DoneAllIcon fontSize="small" />} color="primary"
              onClick={() => act('Mark delivered', `Only after ${p.row.destination} confirmed it received ${p.row.itemType}.`,
                reason => queueApi.markDelivered(p.row.outboxUuid, reason))} />}
          {['PENDING', 'FAILED_RETRYABLE', 'MANUAL_REVIEW', 'UNKNOWN_DELIVERY'].includes(p.row.status) &&
            <RowAction label="Cancel delivery" icon={<CancelOutlinedIcon fontSize="small" />} color="error"
              onClick={() => act('Cancel delivery', `${p.row.itemType} will never be sent.`,
                reason => queueApi.cancelOutbox(p.row.outboxUuid, reason), true)} />}
        </Stack>
      )
    }
  ]

  const inboxColumns: GridColDef<InboxItem>[] = [
    { field: 'updatedAt', headerName: 'Updated', width: 160, valueFormatter: (v: string) => date(v) },
    { field: 'itemType', headerName: 'Message', width: 150 },
    { field: 'sender', headerName: 'From', width: 80 },
    { field: 'requestId', headerName: 'Request', width: 220 },
    { field: 'status', headerName: 'Status', width: 140 },
    { field: 'lastErrorMessage', headerName: 'Last error', flex: 1, minWidth: 220 },
    {
      field: 'actions', headerName: '', width: 110, sortable: false,
      renderCell: p => canOperate && p.row.status === 'FAILED_FINAL' && (
        <RowAction label="Re-process message" icon={<ReplayIcon fontSize="small" />} color="primary"
          onClick={() => act('Re-process message', `Process ${p.row.itemType} again (after fixing its cause).`,
            reason => queueApi.retryInbox(p.row.inboxUuid, reason))} />
      )
    }
  ]

  const timerColumns: GridColDef<TimerItem>[] = [
    { field: 'dueAt', headerName: 'Due (UTC)', width: 160, valueFormatter: (v: string) => date(v) },
    { field: 'timerName', headerName: 'Timer', width: 240 },
    { field: 'status', headerName: 'Status', width: 110 },
    { field: 'currentState', headerName: 'Workflow state', width: 170 },
    { field: 'phoneNumber', headerName: 'Number', width: 120 },
    { field: 'workflowUuid', headerName: 'Workflow', width: 90, renderCell: p => workflowLink(p.row.workflowUuid) },
    {
      field: 'actions', headerName: '', width: 100, sortable: false,
      renderCell: p => canOperate && p.row.status === 'PENDING' && (
        <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center', height: '100%' }}>
          <RowAction label="Fire timer now" icon={<BoltIcon fontSize="small" />} color="primary"
            onClick={() => act('Fire timer now', `${p.row.timerName} fires immediately.`,
              reason => queueApi.fireTimer(p.row.timerUuid, reason))} />
          <RowAction label="Cancel timer" icon={<CancelOutlinedIcon fontSize="small" />} color="error"
            onClick={() => act('Cancel timer', `${p.row.timerName} will never fire.`,
              reason => queueApi.cancelTimer(p.row.timerUuid, reason), true)} />
        </Stack>
      )
    }
  ]

  const grid = tab === 'outbox'
    ? <DataGrid rows={outbox.data ?? []} columns={outboxColumns} getRowId={r => r.outboxUuid} loading={outbox.isLoading} />
    : tab === 'inbox'
      ? <DataGrid rows={inbox.data ?? []} columns={inboxColumns} getRowId={r => r.inboxUuid} loading={inbox.isLoading} />
      : <DataGrid rows={timers.data ?? []} columns={timerColumns} getRowId={r => r.timerUuid} loading={timers.isLoading} />

  return (
    <Box>
      <PageHeader title="Queues & Timers" subtitle="Durable inbox, outbox and timers — retry, confirm or cancel stuck items" />
      <Card sx={{ p: 2 }}>
        <Stack direction="row" spacing={2} sx={{ mb: 2, alignItems: 'center', flexWrap: 'wrap' }}>
          <Tabs value={tab} onChange={(_, v) => { setTab(v); setStatus(STATUSES[v][0]) }}>
            <Tab value="outbox" label="Outbox" />
            <Tab value="inbox" label="Inbox" />
            <Tab value="timers" label="Timers" />
          </Tabs>
          <FormControl size="small" sx={{ minWidth: 200 }}>
            <InputLabel>Status</InputLabel>
            <Select label="Status" value={status} onChange={e => setStatus(e.target.value)}>
              {STATUSES[tab].map(s => <MenuItem key={s} value={s}>{s}</MenuItem>)}
            </Select>
          </FormControl>
          <Box sx={{ ml: 'auto !important' }}>
            <RefreshButton label={`Refresh ${tab}`} onRefresh={() => active.refetch()} refreshing={active.isFetching} />
          </Box>
        </Stack>
        <Box sx={{ height: 560 }}>{grid}</Box>
      </Card>
      <ReasonDialog
        open={!!pending} title={pending?.title ?? ''} description={pending?.description} danger={pending?.danger}
        onClose={() => setPending(null)} onConfirm={reason => pending!.run(reason)}
      />
    </Box>
  )
}
