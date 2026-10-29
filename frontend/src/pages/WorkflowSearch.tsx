import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { DataGrid, GridColDef } from '@mui/x-data-grid'
import { TextField, Button, Box } from '@mui/material'
import { Link } from 'react-router-dom'
import { workflowApi } from '../api/client'
import { Workflow } from '../api/types'
import PageHeader from '../components/PageHeader'

const columns: GridColDef<Workflow>[] = [
  { field: 'workflowUuid', headerName: 'UUID', width: 280, renderCell: params =>
    <Link to={`/workflows/${params.value}`}>{params.value}</Link> },
  { field: 'phoneNumber', headerName: 'Phone', width: 150 },
  { field: 'workflowType', headerName: 'Type', width: 120 },
  { field: 'currentState', headerName: 'State', width: 150 },
  { field: 'requestId', headerName: 'Request ID', width: 180 },
  { field: 'updatedAt', headerName: 'Updated', width: 180 }
]

export default function WorkflowSearch() {
  const [phone, setPhone] = useState('')
  const [state, setState] = useState('')
  const { data, refetch, isLoading } = useQuery({
    queryKey: ['workflows', phone, state],
    queryFn: () => workflowApi.search({ phoneNumber: phone || undefined, state: state || undefined })
  })

  return (
    <Box>
      <PageHeader title="Workflows" subtitle="Search porting and lifecycle workflows" />
      <Box sx={{ display: 'flex', gap: 2, mb: 2 }}>
        <TextField label="Phone Number" value={phone} onChange={e => setPhone(e.target.value)} />
        <TextField label="State" value={state} onChange={e => setState(e.target.value)} />
        <Button variant="contained" onClick={() => refetch()}>Search</Button>
      </Box>
      <DataGrid
        rows={data || []}
        columns={columns}
        getRowId={row => row.workflowUuid}
        loading={isLoading}
        pageSizeOptions={[10, 25, 50]}
        initialState={{ pagination: { paginationModel: { pageSize: 10 } } }}
      />
    </Box>
  )
}
