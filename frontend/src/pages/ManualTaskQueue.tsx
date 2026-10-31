import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { Box, Card, Chip, FormControl, InputLabel, MenuItem, Select, Stack, Typography } from '@mui/material'
import AssignmentIndOutlinedIcon from '@mui/icons-material/AssignmentIndOutlined'
import NoteAddOutlinedIcon from '@mui/icons-material/NoteAddOutlined'
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutline'
import { DataGrid, GridColDef } from '@mui/x-data-grid'
import { manualTaskApi } from '../api/client'
import { ManualTask } from '../api/types'
import PageHeader from '../components/PageHeader'
import ReasonDialog from '../components/ReasonDialog'
import RowAction from '../components/RowAction'
import { useAuth } from '../auth/AuthContext'

const SEVERITY_COLOR: Record<string, 'error' | 'warning' | 'default'> = { HIGH: 'error', MEDIUM: 'warning', LOW: 'default' }

type Pending = { task: ManualTask; kind: 'note' | 'resolve' } | null

/** The NOC's work queue: everything automation handed over (timeouts, rejections, unknown deliveries...). */
export default function ManualTaskQueue() {
  const { hasRole, user } = useAuth()
  const canOperate = hasRole('ADMIN', 'MANAGER')
  const queryClient = useQueryClient()
  const [status, setStatus] = useState('OPEN')
  const [pending, setPending] = useState<Pending>(null)
  const { data, isLoading } = useQuery({ queryKey: ['manualTasks', status], queryFn: () => manualTaskApi.list(status) })
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['manualTasks'] })

  const columns: GridColDef<ManualTask>[] = [
    { field: 'createdAt', headerName: 'Opened', width: 160, valueFormatter: (v: string) => v?.replace('T', ' ').slice(0, 19) },
    {
      field: 'severity', headerName: 'Severity', width: 100,
      renderCell: p => <Chip size="small" label={p.row.severity} color={SEVERITY_COLOR[p.row.severity] ?? 'default'} />
    },
    { field: 'taskType', headerName: 'Type', width: 200 },
    {
      field: 'description', headerName: 'What happened', flex: 1, minWidth: 320,
      renderCell: p => <Typography variant="body2" sx={{ whiteSpace: 'normal', lineHeight: 1.4, py: 1 }}>{p.row.description}</Typography>
    },
    {
      field: 'workflowUuid', headerName: 'Workflow', width: 120,
      renderCell: p => p.row.workflowUuid ? <Link to={`/workflows/${p.row.workflowUuid}`}>{p.row.phoneNumber ?? 'open'}</Link> : '—'
    },
    { field: 'assignedTo', headerName: 'Assignee', width: 110 },
    {
      field: 'actions', headerName: '', width: 130, sortable: false,
      renderCell: p => canOperate && p.row.status !== 'CLOSED' && (
        <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center', height: '100%' }}>
          {p.row.assignedTo !== user?.username &&
            <RowAction label="Take this task" icon={<AssignmentIndOutlinedIcon fontSize="small" />}
              onClick={() => manualTaskApi.assign(p.row.taskUuid).then(refresh)} />}
          <RowAction label="Add a note" icon={<NoteAddOutlinedIcon fontSize="small" />}
            onClick={() => setPending({ task: p.row, kind: 'note' })} />
          <RowAction label="Resolve" icon={<CheckCircleOutlineIcon fontSize="small" />} color="success"
            onClick={() => setPending({ task: p.row, kind: 'resolve' })} />
        </Stack>
      )
    }
  ]

  return (
    <Box>
      <PageHeader title="Manual Tasks" subtitle="Situations the gateway handed to the NOC" />
      <Card sx={{ p: 2 }}>
        <FormControl size="small" sx={{ minWidth: 180, mb: 2 }}>
          <InputLabel>Status</InputLabel>
          <Select label="Status" value={status} onChange={e => setStatus(e.target.value)}>
            {['OPEN', 'IN_PROGRESS', 'CLOSED'].map(s => <MenuItem key={s} value={s}>{s}</MenuItem>)}
          </Select>
        </FormControl>
        <Box sx={{ height: 600 }}>
          <DataGrid rows={data ?? []} columns={columns} getRowId={row => row.taskUuid} loading={isLoading}
            getRowHeight={() => 'auto'} pageSizeOptions={[25, 50, 100]}
            initialState={{ pagination: { paginationModel: { pageSize: 25 } } }} />
        </Box>
      </Card>
      <ReasonDialog
        open={pending?.kind === 'note'} title="Add note" reasonLabel="Note" confirmLabel="Add note"
        description={pending?.task.description}
        onClose={() => setPending(null)} onConfirm={note => manualTaskApi.addNote(pending!.task.taskUuid, note).then(refresh)}
      />
      <ReasonDialog
        open={pending?.kind === 'resolve'} title="Resolve task" reasonLabel="Resolution (audited)" confirmLabel="Resolve"
        description={pending?.task.description}
        onClose={() => setPending(null)} onConfirm={resolution => manualTaskApi.resolve(pending!.task.taskUuid, resolution).then(refresh)}
      />
    </Box>
  )
}
