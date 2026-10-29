import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Box, Button, Card, CardContent, FormControl, IconButton, InputLabel, MenuItem, Select, Stack, TextField } from '@mui/material'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline'
import { DataGrid, GridColDef } from '@mui/x-data-grid'
import { apiErrorMessage, numberRangeApi, operatorApi } from '../api/client'
import { NumberRange } from '../api/types'
import PageHeader from '../components/PageHeader'

/**
 * Allocated number blocks per operator. They decide who natively serves a number nobody ported —
 * the gateway uses them to refuse publications and port-outs that contradict them.
 */
export default function NumberRangesPage() {
  const queryClient = useQueryClient()
  const ranges = useQuery({ queryKey: ['numberRanges'], queryFn: numberRangeApi.list })
  const operators = useQuery({ queryKey: ['operators'], queryFn: operatorApi.list })
  const [form, setForm] = useState({ operator: '', prefix: '', description: '' })
  const [error, setError] = useState<string | null>(null)

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['numberRanges'] })
  const add = async () => {
    setError(null)
    try {
      await numberRangeApi.create(form.operator, form.prefix, form.description || undefined)
      setForm({ ...form, prefix: '', description: '' })
      refresh()
    } catch (err) {
      setError(apiErrorMessage(err))
    }
  }

  const columns: GridColDef<NumberRange>[] = [
    { field: 'prefix', headerName: 'Prefix', width: 140 },
    { field: 'operatorCode', headerName: 'Operator', width: 120 },
    { field: 'description', headerName: 'Description', flex: 1 },
    {
      field: 'actions', headerName: '', width: 70, sortable: false,
      renderCell: p => (
        <IconButton size="small" aria-label="delete range"
          onClick={() => numberRangeApi.remove(p.row.id).then(refresh).catch(err => setError(apiErrorMessage(err)))}>
          <DeleteOutlineIcon fontSize="small" />
        </IconButton>
      )
    }
  ]

  return (
    <Box>
      <PageHeader title="Number Ranges" subtitle="Number blocks allocated to each operator (national format prefixes)" />
      <Card sx={{ mb: 2 }}>
        <CardContent>
          {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <FormControl size="small" sx={{ minWidth: 200 }}>
              <InputLabel>Operator</InputLabel>
              <Select label="Operator" value={form.operator} onChange={e => setForm({ ...form, operator: e.target.value })}>
                {(operators.data ?? []).map(o => <MenuItem key={o.operatorCode} value={o.operatorCode}>{o.operatorCode} — {o.operatorName}</MenuItem>)}
              </Select>
            </FormControl>
            <TextField size="small" label="Prefix (e.g. 0812)" value={form.prefix} onChange={e => setForm({ ...form, prefix: e.target.value })} />
            <TextField size="small" label="Description" value={form.description} onChange={e => setForm({ ...form, description: e.target.value })} sx={{ flex: 1 }} />
            <Button variant="contained" disabled={!form.operator || !form.prefix} onClick={add}>Add range</Button>
          </Stack>
        </CardContent>
      </Card>
      <Card sx={{ height: 520, p: 1 }}>
        <DataGrid rows={ranges.data ?? []} columns={columns} loading={ranges.isLoading} />
      </Card>
    </Box>
  )
}
