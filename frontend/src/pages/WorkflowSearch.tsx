import { useQuery } from '@tanstack/react-query'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { Box, Button, Card, FormControl, InputLabel, MenuItem, Select, Stack, TextField } from '@mui/material'
import { DataGrid, GridColDef } from '@mui/x-data-grid'
import { workflowApi } from '../api/client'
import { Workflow } from '../api/types'
import PageHeader from '../components/PageHeader'

const columns: GridColDef<Workflow>[] = [
  { field: 'updatedAt', headerName: 'Updated', width: 160, valueFormatter: (v: string) => v?.replace('T', ' ').slice(0, 19) },
  { field: 'workflowType', headerName: 'Type', width: 170 },
  { field: 'localRole', headerName: 'Our role', width: 150 },
  { field: 'phoneNumber', headerName: 'Number', width: 130 },
  { field: 'counterpartyCode', headerName: 'Peer', width: 80 },
  { field: 'currentState', headerName: 'State', width: 190 },
  { field: 'requestId', headerName: 'Request ID', flex: 1, minWidth: 220 }
]

const FILTERS = ['phoneNumber', 'requestId', 'state', 'workflowType', 'localRole'] as const

/** Workflow search; filters live in the URL so dashboard cards and links can deep-link into it. */
export default function WorkflowSearch() {
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const filters = Object.fromEntries(FILTERS.map(f => [f, params.get(f) ?? ''])) as Record<(typeof FILTERS)[number], string>
  const query = Object.fromEntries(Object.entries(filters).filter(([, v]) => v)) as Record<string, string>
  const { data, isLoading } = useQuery({
    queryKey: ['workflows', query],
    queryFn: () => workflowApi.search({ ...query, size: 200 })
  })

  const set = (name: string, value: string) => {
    const next = new URLSearchParams(params)
    if (value) next.set(name, value)
    else next.delete(name)
    setParams(next, { replace: true })
  }

  const select = (name: 'workflowType' | 'localRole', label: string, options: string[]) => (
    <FormControl size="small" sx={{ minWidth: 170 }}>
      <InputLabel>{label}</InputLabel>
      <Select label={label} value={filters[name]} onChange={e => set(name, e.target.value)}>
        <MenuItem value="">Any</MenuItem>
        {options.map(o => <MenuItem key={o} value={o}>{o}</MenuItem>)}
      </Select>
    </FormControl>
  )

  return (
    <Box>
      <PageHeader title="Workflows" subtitle="Every porting, publication, return, inquiry, maintenance and sync process" />
      <Card sx={{ p: 2 }}>
        <Stack direction="row" spacing={2} sx={{ mb: 2, flexWrap: 'wrap', rowGap: 2 }}>
          <TextField size="small" label="Phone number" value={filters.phoneNumber} onChange={e => set('phoneNumber', e.target.value.trim())} />
          <TextField size="small" label="Request ID" value={filters.requestId} onChange={e => set('requestId', e.target.value.trim())} />
          <TextField size="small" label="State" value={filters.state} onChange={e => set('state', e.target.value.trim().toUpperCase())} />
          {select('workflowType', 'Type', ['PORTING', 'PUBLICATION', 'NUMBER_RETURN', 'NUMBER_INQUIRY', 'MAINTENANCE', 'DATA_SYNCHRONIZATION'])}
          {select('localRole', 'Our role', ['RECIPIENT', 'DONOR', 'OBSERVER', 'CURRENT_OPERATOR', 'ORIGINAL_OPERATOR', 'REQUESTER', 'RESPONDER', 'ANNOUNCER'])}
          <Button onClick={() => setParams(new URLSearchParams(), { replace: true })}>Clear</Button>
        </Stack>
        <Box sx={{ height: 600 }}>
          <DataGrid
            rows={data ?? []} columns={columns} getRowId={r => r.workflowUuid} loading={isLoading}
            onRowClick={p => navigate(`/workflows/${p.row.workflowUuid}`)} sx={{ cursor: 'pointer' }}
            pageSizeOptions={[25, 50, 100]} initialState={{ pagination: { paginationModel: { pageSize: 25 } } }}
          />
        </Box>
      </Card>
    </Box>
  )
}
