import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import {
  Alert, Box, Button, Card, CardContent, FormControl, Grid, InputLabel, MenuItem, Select, Table, TableBody,
  TableCell, TableRow, TextField, Typography
} from '@mui/material'
import { apiErrorMessage, operatorApi, portingApi } from '../api/client'
import PageHeader from '../components/PageHeader'
import ActionCard from '../components/ActionCard'
import SwapHorizIcon from '@mui/icons-material/SwapHoriz'

interface BatchResult { phoneNumber: string; workflowUuid?: string; error?: string }

/** Port numbers in from another operator: one workflow per number (a batch is split server-side). */
export default function StartPortIn() {
  const navigate = useNavigate()
  const operators = useQuery({ queryKey: ['operators'], queryFn: operatorApi.list })
  const [numbers, setNumbers] = useState('')
  const [numberType, setNumberType] = useState('MOBILE')
  const [donor, setDonor] = useState('')
  const [identification, setIdentification] = useState('')
  const [portTime, setPortTime] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [results, setResults] = useState<BatchResult[] | null>(null)
  const [busy, setBusy] = useState(false)

  const peers = (operators.data ?? []).filter(o => !o.isLocal && o.isActive !== false && !o.requiresConfiguration)
  const phoneNumbers = numbers.split(/[\s,;]+/).map(n => n.trim()).filter(Boolean)

  const submit = async () => {
    setBusy(true)
    setError(null)
    setResults(null)
    try {
      const response = await portingApi.start({
        ...(phoneNumbers.length === 1 ? { phoneNumber: phoneNumbers[0] } : { phoneNumbers }),
        numberType, donorOperatorCode: donor,
        identificationValue: identification || undefined,
        requestedPortTime: portTime ? new Date(portTime).toISOString() : undefined
      })
      if (phoneNumbers.length === 1) {
        navigate(`/workflows/${response.workflowUuid}`)
      } else {
        setResults(response.results as BatchResult[])
      }
    } catch (err) {
      setError(apiErrorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Box>
      <PageHeader title="Start Port-In" subtitle="Bring numbers over from another operator (this gateway is the recipient)" />
      <ActionCard
        icon={<SwapHorizIcon />}
        title="Port numbers in"
        subtitle="Check → Request → Execute"
        help="The donor is asked first (Check). Once it approves, NPG sends the Request and executes the port at the approved time on its own."
        actions={
          <Button variant="contained" disabled={busy || phoneNumbers.length === 0 || !donor} onClick={submit}>
            Send Check{phoneNumbers.length > 1 ? ` (${phoneNumbers.length} numbers)` : ''}
          </Button>
        }
      >
        {error && <Alert severity="error">{error}</Alert>}
        <TextField label="Phone numbers" fullWidth multiline minRows={2} value={numbers}
          onChange={e => setNumbers(e.target.value)}
          helperText="One or more national numbers (05XXXXXXXX), separated by spaces, commas or new lines" />
        <Grid container spacing={2}>
          <Grid item xs={12} sm={6} md={3}>
            <FormControl fullWidth>
              <InputLabel id="port-in-type">Number type</InputLabel>
              <Select labelId="port-in-type" label="Number type" value={numberType} onChange={e => setNumberType(e.target.value)}>
                <MenuItem value="MOBILE">Mobile</MenuItem>
                <MenuItem value="FIXED">Fixed</MenuItem>
              </Select>
            </FormControl>
          </Grid>
          <Grid item xs={12} sm={6} md={3}>
            <FormControl fullWidth>
              <InputLabel id="port-in-donor">Donor operator</InputLabel>
              <Select labelId="port-in-donor" label="Donor operator" value={donor} onChange={e => setDonor(e.target.value)}>
                {peers.map(o => <MenuItem key={o.operatorCode} value={o.operatorCode}>{o.operatorCode} — {o.operatorName}</MenuItem>)}
              </Select>
            </FormControl>
          </Grid>
          <Grid item xs={12} sm={6} md={3}>
            <TextField label="Customer identification" fullWidth value={identification}
              onChange={e => setIdentification(e.target.value)} helperText="ID number sent with the Check" />
          </Grid>
          <Grid item xs={12} sm={6} md={3}>
            <TextField type="datetime-local" label="Requested port time" fullWidth InputLabelProps={{ shrink: true }}
              value={portTime} onChange={e => setPortTime(e.target.value)}
              helperText="Optional; default: shortly after approval" />
          </Grid>
        </Grid>
      </ActionCard>

      {results && (
        <Card sx={{ mt: 2 }}>
          <CardContent>
            <Typography variant="subtitle2" sx={{ mb: 1 }}>Batch result</Typography>
            <Table size="small">
              <TableBody>
                {results.map(r => (
                  <TableRow key={r.phoneNumber} hover sx={{ cursor: r.workflowUuid ? 'pointer' : 'default' }}
                    onClick={() => r.workflowUuid && navigate(`/workflows/${r.workflowUuid}`)}>
                    <TableCell>{r.phoneNumber}</TableCell>
                    <TableCell>{r.workflowUuid ? 'Started' : <Typography color="error" variant="body2">{r.error}</Typography>}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}
    </Box>
  )
}
