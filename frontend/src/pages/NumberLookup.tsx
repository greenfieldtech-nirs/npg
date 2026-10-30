import { useState } from 'react'
import Loader from '../components/Loader'
import { useQuery } from '@tanstack/react-query'
import {
  Alert, Card, CardContent, Chip, Divider, Grid, Table, TableBody, TableCell,
  TableHead, TableRow, TextField, Button, Typography
} from '@mui/material'
import axios from 'axios'
import { numberApi } from '../api/client'
import { NumberRecord } from '../api/types'
import PageHeader from '../components/PageHeader'
import { Box } from '@mui/material'

function statusColor(status: string): 'success' | 'info' | 'default' {
  if (status === 'PORTED') return 'success'
  if (status === 'RETURNED') return 'info'
  return 'default'
}

function formatDate(value: string | null): string {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 19)
}

function DetailItem({ label, value }: { label: string; value: string }) {
  return (
    <Grid item xs={12} sm={6} md={4}>
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography variant="body2">{value}</Typography>
    </Grid>
  )
}

export default function NumberLookup() {
  const [phone, setPhone] = useState('')
  const [query, setQuery] = useState('')
  const { data, isLoading, error, isError } = useQuery({
    queryKey: ['number', query],
    queryFn: () => numberApi.get(query),
    enabled: !!query,
    retry: false
  })

  const history = useQuery({
    queryKey: ['numberHistory', query],
    queryFn: () => numberApi.getHistory(query),
    enabled: !!data
  })

  const record: NumberRecord | undefined = data
  const notFound = isError && axios.isAxiosError(error) && error.response?.status === 404

  const prefixSearch = useQuery({
    queryKey: ['numberPrefix', query],
    queryFn: () => numberApi.searchPrefix(query),
    enabled: notFound && !!query
  })
  const prefixResults = prefixSearch.data ?? []

  const submit = () => setQuery(phone)
  const pickNumber = (number: string) => {
    setPhone(number)
    setQuery(number)
  }

  return (
    <Box>
      <PageHeader title="Number Lookup" subtitle="Query routing and portability for a number" />
      <Card>
        <CardContent>
          <Box sx={{ display: 'flex', gap: 2, mb: 2 }}>
            <TextField
              label="Phone Number or prefix"
              value={phone}
              onChange={e => setPhone(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') submit() }}
            />
            <Button variant="contained" onClick={submit}>Lookup</Button>
          </Box>

          {isLoading && <Loader />}

          {notFound && prefixSearch.isLoading && <Loader />}
          {notFound && !prefixSearch.isLoading && prefixResults.length === 0 && (
            <Alert severity="info">No record found for {query}.</Alert>
          )}
          {notFound && prefixResults.length > 0 && (
            <>
              <Alert severity="info" sx={{ mb: 1 }}>
                No exact match for {query}. {prefixResults.length} number(s) match this prefix — click one to view details.
              </Alert>
              <Table size="small" sx={{ mb: 2 }}>
                <TableHead>
                  <TableRow>
                    <TableCell>Phone Number</TableCell>
                    <TableCell>Type</TableCell>
                    <TableCell>Status</TableCell>
                    <TableCell>Current Operator</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {prefixResults.map(row => (
                    <TableRow
                      key={row.id}
                      hover
                      sx={{ cursor: 'pointer' }}
                      onClick={() => pickNumber(row.phoneNumber)}
                    >
                      <TableCell>{row.phoneNumber}</TableCell>
                      <TableCell>{row.numberType}</TableCell>
                      <TableCell>
                        <Chip label={row.status} color={statusColor(row.status)} size="small" />
                      </TableCell>
                      <TableCell>{row.currentOperatorCode ?? '—'}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </>
          )}
          {isError && !notFound && (
            <Alert severity="error">
              {axios.isAxiosError(error) ? (error.response?.data?.error ?? error.message) : 'Lookup failed'}
            </Alert>
          )}

          {record && (
            <>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 2 }}>
                <Typography variant="h6">
                  {record.phoneNumber}
                </Typography>
                <Chip label={record.status} color={statusColor(record.status)} size="small" />
              </Box>
              <Grid container spacing={2}>
                <DetailItem label="Number Type" value={record.numberType} />
                <DetailItem label="Current Operator" value={record.currentOperatorCode ?? '—'} />
                <DetailItem label="Last Ported" value={formatDate(record.lastPortedAt)} />
                <DetailItem label="Last Returned" value={formatDate(record.lastReturnedAt)} />
                <DetailItem label="Last Published" value={formatDate(record.lastPublishedAt)} />
                <DetailItem label="Last Updated" value={formatDate(record.updatedAt)} />
              </Grid>

              {history.data && history.data.length > 0 && (
                <>
                  <Divider sx={{ my: 2 }} />
                  <Typography variant="subtitle2" sx={{ mb: 1 }}>Ownership History</Typography>
                  <Table size="small">
                    <TableHead>
                      <TableRow>
                        <TableCell>Date</TableCell>
                        <TableCell>Change</TableCell>
                        <TableCell>From</TableCell>
                        <TableCell>To</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {history.data.map(entry => (
                        <TableRow key={entry.id}>
                          <TableCell>{formatDate(entry.effectiveAt)}</TableCell>
                          <TableCell>{entry.changeReason}</TableCell>
                          <TableCell>{entry.previousOperatorCode ?? '—'}</TableCell>
                          <TableCell>{entry.newOperatorCode ?? '—'}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </>
              )}
            </>
          )}
        </CardContent>
      </Card>
    </Box>
  )
}
