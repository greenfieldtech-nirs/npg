import { ReactNode, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import {
  Alert, Box, Button, Card, CardActions, CardContent, Divider, FormControl, Grid, InputLabel, MenuItem, Select, TextField, Typography
} from '@mui/material'
import ManageSearchIcon from '@mui/icons-material/ManageSearch'
import AssignmentReturnIcon from '@mui/icons-material/AssignmentReturn'
import SyncIcon from '@mui/icons-material/Sync'
import ConstructionIcon from '@mui/icons-material/Construction'
import { apiErrorMessage, operatorApi, processApi } from '../api/client'
import PageHeader from '../components/PageHeader'
import { useAuth } from '../auth/AuthContext'

// what the NOC picks, and the ISO-8601 duration Down_system carries on the wire
const MAINTENANCE_DURATIONS: { label: string; iso: string }[] = [
  { label: '15 minutes', iso: 'PT15M' },
  { label: '30 minutes', iso: 'PT30M' },
  { label: '1 hour', iso: 'PT1H' },
  { label: '2 hours', iso: 'PT2H' },
  { label: '4 hours', iso: 'PT4H' },
  { label: '8 hours', iso: 'PT8H' },
  { label: '12 hours', iso: 'PT12H' },
  { label: '1 day', iso: 'P1D' },
  { label: '2 days', iso: 'P2D' }
]

function ProcessCard({ icon, tone = 'primary', title, wire, help, actions, children }: {
  icon: ReactNode
  tone?: 'primary' | 'warning'
  title: string
  wire: string
  help: string
  actions: ReactNode
  children: ReactNode
}) {
  return (
    <Grid item xs={12} md={6}>
      <Card sx={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
        <CardContent sx={{ display: 'flex', flexDirection: 'column', gap: 2, flex: 1, p: 2.5, '&:last-child': { pb: 2.5 } }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
            <Box aria-hidden sx={{
              width: 40, height: 40, flexShrink: 0, borderRadius: 2, display: 'grid', placeItems: 'center',
              bgcolor: 'action.hover', color: `${tone}.main`
            }}>{icon}</Box>
            <Box sx={{ minWidth: 0 }}>
              <Typography variant="subtitle1" sx={{ lineHeight: 1.3 }}>{title}</Typography>
              <Typography variant="overline" color="text.secondary" sx={{ display: 'block', letterSpacing: '0.5px', textTransform: 'none', overflowWrap: 'anywhere' }}>{wire}</Typography>
            </Box>
          </Box>
          <Typography variant="body2" color="text.secondary" sx={{ minHeight: { md: 40 } }}>{help}</Typography>
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, flex: 1 }}>{children}</Box>
        </CardContent>
        <Divider />
        <CardActions sx={{ px: 2.5, py: 1.5, justifyContent: 'flex-end', flexWrap: 'wrap', gap: 1, '& > :not(style)': { m: 0 } }}>
          {actions}
        </CardActions>
      </Card>
    </Grid>
  )
}

/** NOC-initiated protocol processes other than porting. */
export default function ProcessesPage() {
  const navigate = useNavigate()
  const { hasRole } = useAuth()
  const operators = useQuery({ queryKey: ['operators'], queryFn: operatorApi.list })
  const peers = (operators.data ?? []).filter(o => !o.isLocal && !o.requiresConfiguration)
  const [error, setError] = useState<string | null>(null)
  const [inquiry, setInquiry] = useState({ phone: '', operator: '' })
  const [returnPhone, setReturnPhone] = useState('')
  const [sync, setSync] = useState({ operator: '', type: 'FULL' })
  const [maintenance, setMaintenance] = useState({ reason: '', duration: 'PT2H' })

  const start = async (call: () => Promise<{ workflowUuid: string }>) => {
    setError(null)
    try {
      const { workflowUuid } = await call()
      navigate(`/workflows/${workflowUuid}`)
    } catch (err) {
      setError(apiErrorMessage(err))
    }
  }

  const operatorSelect = (id: string, value: string, onChange: (v: string) => void) => (
    <FormControl fullWidth size="small">
      <InputLabel id={`${id}-operator`}>Operator</InputLabel>
      <Select labelId={`${id}-operator`} label="Operator" value={value} onChange={e => onChange(e.target.value)}>
        {peers.map(o => <MenuItem key={o.operatorCode} value={o.operatorCode}>{o.operatorCode} — {o.operatorName}</MenuItem>)}
      </Select>
    </FormControl>
  )

  return (
    <Box>
      <PageHeader title="Processes" subtitle="Inquiries, number returns, maintenance announcements and synchronization" />
      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
      <Grid container spacing={2} alignItems="stretch">
        <ProcessCard icon={<ManageSearchIcon />} title="Number inquiry" wire="Inquire_number" help="Ask an operator which operator serves a number."
          actions={
            <Button variant="contained" disabled={!inquiry.phone || !inquiry.operator}
              onClick={() => start(() => processApi.inquire(inquiry.phone, inquiry.operator))}>Send inquiry</Button>
          }>
          <TextField size="small" fullWidth label="Phone number" value={inquiry.phone} onChange={e => setInquiry({ ...inquiry, phone: e.target.value })} />
          {operatorSelect('inquiry', inquiry.operator, v => setInquiry({ ...inquiry, operator: v }))}
        </ProcessCard>

        <ProcessCard icon={<AssignmentReturnIcon />} tone="warning" title="Return a number" wire="Return"
          help="A ported-in number was disconnected: after the cooling period it goes back to its original operator."
          actions={
            <Button variant="contained" color="warning" disabled={!returnPhone}
              onClick={() => start(() => processApi.startReturn(returnPhone))}>Start return</Button>
          }>
          <TextField size="small" fullWidth label="Phone number" value={returnPhone} onChange={e => setReturnPhone(e.target.value)} />
        </ProcessCard>

        <ProcessCard icon={<SyncIcon />} title="Database synchronization" wire="DB_synch_request" help="Request an operator's snapshot of its porting records."
          actions={
            <Button variant="contained" disabled={!sync.operator}
              onClick={() => start(() => processApi.requestSync(sync.operator, sync.type))}>Request sync</Button>
          }>
          {operatorSelect('sync', sync.operator, v => setSync({ ...sync, operator: v }))}
        </ProcessCard>

        {hasRole('ADMIN') && (
          <ProcessCard icon={<ConstructionIcon />} title="Our maintenance" wire="Down_system → Up_system"
            help="Tell every operator that this gateway goes down for maintenance, then that it is back."
            actions={
              <>
                <Button variant="contained" color="error" disabled={!maintenance.reason}
                  onClick={() => start(() => processApi.announce(true, maintenance.reason, maintenance.duration))}>1 · Announce down</Button>
                <Button variant="outlined" disabled={!maintenance.reason}
                  onClick={() => start(() => processApi.announce(false, maintenance.reason))}>2 · Announce back up</Button>
              </>
            }>
            <TextField size="small" fullWidth label="Reason" value={maintenance.reason} onChange={e => setMaintenance({ ...maintenance, reason: e.target.value })} />
            <FormControl size="small" fullWidth>
              <InputLabel id="maintenance-duration">Expected duration</InputLabel>
              <Select labelId="maintenance-duration" label="Expected duration" value={maintenance.duration}
                onChange={e => setMaintenance({ ...maintenance, duration: e.target.value })}>
                {MAINTENANCE_DURATIONS.map(d => <MenuItem key={d.iso} value={d.iso}>{d.label}</MenuItem>)}
              </Select>
            </FormControl>
          </ProcessCard>
        )}
      </Grid>
    </Box>
  )
}
