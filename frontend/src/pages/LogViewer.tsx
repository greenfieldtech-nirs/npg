import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { DataGrid, GridColDef } from '@mui/x-data-grid'
import axios from 'axios'
import { messageApi } from '../api/client'
import { Message } from '../api/types'
import PageHeader from '../components/PageHeader'
import RefreshButton from '../components/RefreshButton'
import Loader from '../components/Loader'
import XmlBlock from '../components/XmlBlock'
import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle,
  FormControl, Grid, InputLabel, MenuItem, Paper, Select, TextField, Typography
} from '@mui/material'

const MESSAGE_TYPES = [
  'Check', 'Check_response', 'Request', 'Request_response', 'Update', 'Update_response',
  'Cancel', 'Cancel_response', 'KD_update', 'KD_update_response', 'Execute', 'Execute_response',
  'Publish', 'Publish_response', 'Cancel_Publish', 'Cancel_Publish_response', 'Return',
  'Return_response', 'Inquire_number', 'Inquire_number_response', 'Down_system', 'Up_system',
  'DB_synch_request', 'DB_synch_response'
]

interface Filters {
  messageType: string
  direction: string
  technicalStatus: string
  requestId: string
  dateFrom: string
  dateTo: string
}

const EMPTY_FILTERS: Filters = {
  messageType: '',
  direction: '',
  technicalStatus: '',
  requestId: '',
  dateFrom: '',
  dateTo: ''
}

function formatDate(value: string | null): string {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 19)
}

function truncate(value: unknown): string {
  if (value == null) return ''
  const s = String(value)
  return s.length > 12 ? s.slice(0, 12) + '…' : s
}

const columns: GridColDef<Message>[] = [
  { field: 'messageUuid', headerName: 'UUID', width: 280 },
  { field: 'messageType', headerName: 'Type', width: 150 },
  { field: 'direction', headerName: 'Direction', width: 100 },
  { field: 'technicalStatus', headerName: 'Status', width: 120 },
  { field: 'correlationId', headerName: 'Correlated', width: 140, valueFormatter: truncate },
  { field: 'createdAt', headerName: 'Created', width: 180,
    valueFormatter: (v: string | null) => formatDate(v) }
]

function DetailItem({ label, value }: { label: string; value: string }) {
  return (
    <Grid item xs={12} sm={6} md={4}>
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography variant="body2">{value}</Typography>
    </Grid>
  )
}

function MessageXml({ uuid }: { uuid: string }) {
  const { data, isLoading, error } = useQuery({
    queryKey: ['xml', uuid],
    queryFn: () => messageApi.getXml(uuid),
    retry: false
  })

  if (isLoading) return <Loader />
  if (error) {
    return (
      <Alert severity="info">
        {axios.isAxiosError(error) && error.response?.status === 404
          ? 'This message has no stored XML document.'
          : 'Failed to load XML'}
      </Alert>
    )
  }
  return data ? <XmlBlock xml={data} /> : null
}

function Lightbox({ uuid, rows, onClose, onNavigate }: {
  uuid: string
  rows: Message[]
  onClose: () => void
  onNavigate: (uuid: string) => void
}) {
  const { data: pair, isLoading, error } = useQuery({
    queryKey: ['pair', uuid],
    queryFn: () => messageApi.getPair(uuid),
    retry: false
  })

  const idx = rows.findIndex(r => r.messageUuid === uuid)
  const sides = pair ? [pair.request, pair.response] : []
  const enriched = sides.find(m => m?.messageUuid === uuid) ?? null
  const message = enriched ?? (idx >= 0 ? rows[idx] : null)
  const counterpart = sides.find(m => m && m.messageUuid !== uuid) ?? null
  const counterpartInList = counterpart != null && rows.some(r => r.messageUuid === counterpart.messageUuid)

  return (
    <Dialog open onClose={onClose} maxWidth="lg" fullWidth>
      <DialogTitle sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
        {message ? message.messageType : 'Message'}
        {message && <Chip label={message.direction} size="small" />}
      </DialogTitle>
      <DialogContent dividers>
        {isLoading && <Loader />}
        {error && !isLoading && (
          <Alert severity={axios.isAxiosError(error) && error.response?.status === 404 ? 'info' : 'error'}>
            {axios.isAxiosError(error) && error.response?.status === 404
              ? 'Message not found.'
              : 'Failed to load message details.'}
          </Alert>
        )}
        {message && (
          <Box>
            <Grid container spacing={2} sx={{ mb: 2 }}>
              <DetailItem label="Type" value={message.messageType} />
              <DetailItem label="Direction" value={message.direction} />
              <DetailItem label="Status" value={message.technicalStatus} />
              <DetailItem label="Request ID" value={message.requestId ?? '—'} />
              <DetailItem label="Transaction ID" value={message.transactionId ?? '—'} />
              <DetailItem label="Correlation ID" value={message.correlationId ?? '—'} />
              <DetailItem label="Created" value={formatDate(message.createdAt)} />
              {message.phoneNumber && <DetailItem label="Phone Number" value={message.phoneNumber} />}
              {message.donorOperatorCode && (
                <DetailItem
                  label="Donor Operator"
                  value={message.donorOperatorName
                    ? `${message.donorOperatorCode} — ${message.donorOperatorName}`
                    : message.donorOperatorCode}
                />
              )}
            </Grid>
            <MessageXml uuid={message.messageUuid} />
          </Box>
        )}
      </DialogContent>
      <DialogActions>
        <Button disabled={idx <= 0} onClick={() => onNavigate(rows[idx - 1].messageUuid)}>
          Previous
        </Button>
        <Button
          disabled={idx < 0 || idx >= rows.length - 1}
          onClick={() => onNavigate(rows[idx + 1].messageUuid)}
        >
          Next
        </Button>
        {counterpartInList && counterpart && (
          <Button variant="contained" onClick={() => onNavigate(counterpart.messageUuid)}>
            View correlated
          </Button>
        )}
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  )
}

export default function LogViewer() {
  const { uuid } = useParams()
  const [draft, setDraft] = useState<Filters>(EMPTY_FILTERS)
  const [applied, setApplied] = useState<Filters>(EMPTY_FILTERS)
  const [openUuid, setOpenUuid] = useState<string | null>(uuid ?? null)

  useEffect(() => {
    if (uuid) setOpenUuid(uuid)
  }, [uuid])

  const { data, isLoading, isFetching, refetch } = useQuery({
    queryKey: ['messages', applied],
    queryFn: () => messageApi.search(
      Object.fromEntries(Object.entries(applied).filter(([, v]) => v !== ''))
    )
  })

  const rows = data || []

  const set = (key: keyof Filters) => (value: string) =>
    setDraft(prev => ({ ...prev, [key]: value }))

  const apply = () => setApplied(draft)
  const clear = () => {
    setDraft(EMPTY_FILTERS)
    setApplied(EMPTY_FILTERS)
  }

  return (
    <Box>
      <PageHeader title="Log Viewer" subtitle="Inbound and outbound protocol messages" />
      <Paper sx={{ p: 2, mb: 2, display: 'flex', flexWrap: 'wrap', gap: 2, alignItems: 'center' }}>
        <FormControl size="small" sx={{ minWidth: 200 }}>
          <InputLabel>Type</InputLabel>
          <Select
            label="Type"
            value={draft.messageType}
            onChange={e => set('messageType')(e.target.value)}
          >
            <MenuItem value="">All</MenuItem>
            {MESSAGE_TYPES.map(t => <MenuItem key={t} value={t}>{t}</MenuItem>)}
          </Select>
        </FormControl>
        <FormControl size="small" sx={{ minWidth: 140 }}>
          <InputLabel>Direction</InputLabel>
          <Select
            label="Direction"
            value={draft.direction}
            onChange={e => set('direction')(e.target.value)}
          >
            <MenuItem value="">All</MenuItem>
            <MenuItem value="INBOUND">INBOUND</MenuItem>
            <MenuItem value="OUTBOUND">OUTBOUND</MenuItem>
          </Select>
        </FormControl>
        <FormControl size="small" sx={{ minWidth: 140 }}>
          <InputLabel>Status</InputLabel>
          <Select
            label="Status"
            value={draft.technicalStatus}
            onChange={e => set('technicalStatus')(e.target.value)}
          >
            <MenuItem value="">All</MenuItem>
            <MenuItem value="RECEIVED">RECEIVED</MenuItem>
            <MenuItem value="SENT">SENT</MenuItem>
            <MenuItem value="ACKED">ACKED</MenuItem>
            <MenuItem value="NACKED">NACKED</MenuItem>
          </Select>
        </FormControl>
        <TextField
          size="small"
          label="Request ID"
          value={draft.requestId}
          onChange={e => set('requestId')(e.target.value)}
        />
        <TextField
          size="small"
          label="From"
          type="date"
          InputLabelProps={{ shrink: true }}
          value={draft.dateFrom}
          onChange={e => set('dateFrom')(e.target.value)}
        />
        <TextField
          size="small"
          label="To"
          type="date"
          InputLabelProps={{ shrink: true }}
          value={draft.dateTo}
          onChange={e => set('dateTo')(e.target.value)}
        />
        <Button variant="contained" onClick={apply}>Apply</Button>
        <Button variant="outlined" onClick={clear}>Clear</Button>
        <Box sx={{ ml: 'auto' }}>
          <RefreshButton label="Refresh messages" onRefresh={() => refetch()} refreshing={isFetching} />
        </Box>
      </Paper>
      <DataGrid
        rows={rows}
        columns={columns}
        getRowId={row => row.messageUuid}
        loading={isLoading}
        onRowClick={params => setOpenUuid(params.row.messageUuid)}
        sx={{ '& .MuiDataGrid-row': { cursor: 'pointer' } }}
        pageSizeOptions={[10, 25, 50]}
        initialState={{ pagination: { paginationModel: { pageSize: 10 } } }}
      />
      {openUuid && (
        <Lightbox
          uuid={openUuid}
          rows={rows}
          onClose={() => setOpenUuid(null)}
          onNavigate={setOpenUuid}
        />
      )}
    </Box>
  )
}
