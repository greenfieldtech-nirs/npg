import { ReactNode, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import {
  Alert, Box, Button, Card, CardContent, FormControl, Grid, InputLabel, MenuItem, Select, TextField, Typography
} from '@mui/material'
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

function ProcessCard({ title, help, children }: { title: string; help: string; children: ReactNode }) {
  return (
    <Grid item xs={12} md={6}>
      <Card sx={{ height: '100%' }}>
        <CardContent sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <Box>
            <Typography variant="subtitle1">{title}</Typography>
            <Typography variant="body2" color="text.secondary">{help}</Typography>
          </Box>
          {children}
        </CardContent>
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

  const operatorSelect = (value: string, onChange: (v: string) => void) => (
    <FormControl fullWidth size="small">
      <InputLabel>Operator</InputLabel>
      <Select label="Operator" value={value} onChange={e => onChange(e.target.value)}>
        {peers.map(o => <MenuItem key={o.operatorCode} value={o.operatorCode}>{o.operatorCode} — {o.operatorName}</MenuItem>)}
      </Select>
    </FormControl>
  )

  return (
    <Box>
      <PageHeader title="Processes" subtitle="Inquiries, number returns, maintenance announcements and synchronization" />
      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
      <Grid container spacing={2}>
        <ProcessCard title="Number inquiry" help="Ask an operator which operator serves a number.">
          <TextField size="small" label="Phone number" value={inquiry.phone} onChange={e => setInquiry({ ...inquiry, phone: e.target.value })} />
          {operatorSelect(inquiry.operator, v => setInquiry({ ...inquiry, operator: v }))}
          <Button variant="contained" disabled={!inquiry.phone || !inquiry.operator}
            onClick={() => start(() => processApi.inquire(inquiry.phone, inquiry.operator))}>Send Inquire_number</Button>
        </ProcessCard>

        <ProcessCard title="Return a number" help="A ported-in number was disconnected: after the cooling period it goes back to its original operator.">
          <TextField size="small" label="Phone number" value={returnPhone} onChange={e => setReturnPhone(e.target.value)} />
          <Button variant="contained" color="warning" disabled={!returnPhone}
            onClick={() => start(() => processApi.startReturn(returnPhone))}>Start return</Button>
        </ProcessCard>

        <ProcessCard title="Database synchronization" help="Request an operator's snapshot of its porting records.">
          {operatorSelect(sync.operator, v => setSync({ ...sync, operator: v }))}
          <Button variant="contained" disabled={!sync.operator}
            onClick={() => start(() => processApi.requestSync(sync.operator, sync.type))}>Send DB_synch_request</Button>
        </ProcessCard>

        {hasRole('ADMIN') && (
          <ProcessCard title="Our maintenance" help="Tell every operator that this gateway goes down for maintenance, then that it is back.">
            <TextField size="small" label="Reason" value={maintenance.reason} onChange={e => setMaintenance({ ...maintenance, reason: e.target.value })} />
            <FormControl size="small" fullWidth>
              <InputLabel id="maintenance-duration">Expected duration</InputLabel>
              <Select labelId="maintenance-duration" label="Expected duration" value={maintenance.duration}
                onChange={e => setMaintenance({ ...maintenance, duration: e.target.value })}>
                {MAINTENANCE_DURATIONS.map(d => <MenuItem key={d.iso} value={d.iso}>{d.label}</MenuItem>)}
              </Select>
            </FormControl>
            <Box sx={{ display: 'flex', gap: 1 }}>
              <Button variant="contained" color="error" disabled={!maintenance.reason}
                onClick={() => start(() => processApi.announce(true, maintenance.reason, maintenance.duration))}>Announce Down_system</Button>
              <Button variant="outlined" disabled={!maintenance.reason}
                onClick={() => start(() => processApi.announce(false, maintenance.reason))}>Announce Up_system</Button>
            </Box>
          </ProcessCard>
        )}
      </Grid>
    </Box>
  )
}
