import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Box, Button, Card, FormControl, InputLabel, MenuItem, Select, Stack, TextField } from '@mui/material'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline'
import RowAction from '../components/RowAction'
import { DataGrid, GridColDef } from '@mui/x-data-grid'
import { apiErrorMessage, numberRangeApi, operatorApi } from '../api/client'
import { NumberRange } from '../api/types'
import PageHeader from '../components/PageHeader'
import ActionCard from '../components/ActionCard'
import ViewListIcon from '@mui/icons-material/ViewList'

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
        <RowAction label="Delete range" icon={<DeleteOutlineIcon fontSize="small" />} color="error"
          onClick={() => numberRangeApi.remove(p.row.id).then(refresh).catch(err => setError(apiErrorMessage(err)))} />
      )
    }
  ]

  return (
    <Box>
      <PageHeader title="Number Ranges" subtitle="Number blocks allocated to each operator (national format prefixes)" />
      <Box sx={{ mb: 2 }}>
        <ActionCard
          icon={<ViewListIcon />}
          title="Add a number range"
          help="A national-format prefix and the operator it is allocated to. The longest matching prefix decides who natively serves a number, ahead of the numbering plan."
          actions={<Button variant="contained" disabled={!form.operator || !form.prefix} onClick={add}>Add range</Button>}
        >
          {error && <Alert severity="error">{error}</Alert>}
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <FormControl sx={{ minWidth: 240 }}>
              <InputLabel id="range-operator">Operator</InputLabel>
              <Select labelId="range-operator" label="Operator" value={form.operator} onChange={e => setForm({ ...form, operator: e.target.value })}>
                {(operators.data ?? []).map(o => <MenuItem key={o.operatorCode} value={o.operatorCode}>{o.operatorCode} — {o.operatorName}</MenuItem>)}
              </Select>
            </FormControl>
            <TextField label="Prefix (e.g. 0812)" value={form.prefix} onChange={e => setForm({ ...form, prefix: e.target.value })} />
            <TextField label="Description" value={form.description} onChange={e => setForm({ ...form, description: e.target.value })} sx={{ flex: 1 }} />
          </Stack>
        </ActionCard>
      </Box>
      <Card sx={{ height: 520, p: 1 }}>
        <DataGrid rows={ranges.data ?? []} columns={columns} loading={ranges.isLoading} />
      </Card>
    </Box>
  )
}
