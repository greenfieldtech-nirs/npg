import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import {
  Alert, Box, Button, Card, CardContent, FormControl, Grid, InputLabel, MenuItem, Select, Table, TableBody,
  TableCell, TableRow, TextField, Typography
} from '@mui/material'
import { apiErrorMessage, operatorApi, portingApi } from '../api/client'
import PageHeader from '../components/PageHeader'

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
      <Card>
        <CardContent>
          {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
          <Grid container spacing={2}>
            <Grid item xs={12}>
              <TextField label="Phone numbers" fullWidth multiline minRows={2} value={numbers}
                onChange={e => setNumbers(e.target.value)}
                helperText="One or more national numbers (05XXXXXXXX), separated by spaces, commas or new lines" />
            </Grid>
            <Grid item xs={12} sm={4}>
              <FormControl fullWidth>
                <InputLabel>Number type</InputLabel>
                <Select label="Number type" value={numberType} onChange={e => setNumberType(e.target.value)}>
                  <MenuItem value="MOBILE">Mobile</MenuItem>
                  <MenuItem value="FIXED">Fixed</MenuItem>
                </Select>
              </FormControl>
            </Grid>
            <Grid item xs={12} sm={4}>
              <FormControl fullWidth>
                <InputLabel>Donor operator</InputLabel>
                <Select label="Donor operator" value={donor} onChange={e => setDonor(e.target.value)}>
                  {peers.map(o => <MenuItem key={o.operatorCode} value={o.operatorCode}>{o.operatorCode} — {o.operatorName}</MenuItem>)}
                </Select>
              </FormControl>
            </Grid>
            <Grid item xs={12} sm={4}>
              <TextField label="Customer identification" fullWidth value={identification}
                onChange={e => setIdentification(e.target.value)} helperText="ID number sent with the Check" />
            </Grid>
            <Grid item xs={12} sm={6}>
              <TextField type="datetime-local" label="Requested port time (optional)" fullWidth InputLabelProps={{ shrink: true }}
                value={portTime} onChange={e => setPortTime(e.target.value)}
                helperText="Default: shortly after the donor approves" />
            </Grid>
          </Grid>
          <Box sx={{ mt: 2, display: 'flex', justifyContent: 'flex-end' }}>
            <Button variant="contained" disabled={busy || phoneNumbers.length === 0 || !donor} onClick={submit}>
              Send Check{phoneNumbers.length > 1 ? ` (${phoneNumbers.length} numbers)` : ''}
            </Button>
          </Box>
        </CardContent>
      </Card>

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
