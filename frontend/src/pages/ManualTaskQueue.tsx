import { useQuery } from '@tanstack/react-query'
import { DataGrid, GridColDef } from '@mui/x-data-grid'
import { manualTaskApi } from '../api/client'
import { ManualTask } from '../api/types'
import PageHeader from '../components/PageHeader'
import { Box } from '@mui/material'

const columns: GridColDef<ManualTask>[] = [
  { field: 'taskUuid', headerName: 'UUID', width: 280 },
  { field: 'taskType', headerName: 'Type', width: 150 },
  { field: 'severity', headerName: 'Severity', width: 100 },
  { field: 'status', headerName: 'Status', width: 100 },
  { field: 'description', headerName: 'Description', width: 300 }
]

export default function ManualTaskQueue() {
  const { data, isLoading } = useQuery({ queryKey: ['manualTasks'], queryFn: () => manualTaskApi.list('OPEN') })

  return (
    <Box>
      <PageHeader title="Manual Tasks" subtitle="Open items awaiting operator action" />
      <DataGrid
        rows={data || []}
        columns={columns}
        getRowId={row => row.taskUuid}
        loading={isLoading}
        pageSizeOptions={[10, 25, 50]}
        initialState={{ pagination: { paginationModel: { pageSize: 10 } } }}
      />
    </Box>
  )
}
