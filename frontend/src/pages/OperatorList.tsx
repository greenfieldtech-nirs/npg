import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { DataGrid, GridColDef } from '@mui/x-data-grid'
import { operatorApi, endpointApi } from '../api/client'
import { Operator, OperatorEndpoint } from '../api/types'
import PageHeader from '../components/PageHeader'
import { useAuth } from '../auth/AuthContext'
import ReasonDialog from '../components/ReasonDialog'
import {
  Alert, Box, Button, Card, CardContent, Chip, Dialog, DialogActions, DialogContent,
  DialogTitle, Divider, FormControl, FormControlLabel, IconButton, InputLabel, MenuItem,
  Select, Switch, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography
} from '@mui/material'
import AddIcon from '@mui/icons-material/Add'
import DeleteIcon from '@mui/icons-material/Delete'
import EditIcon from '@mui/icons-material/Edit'
import axios from 'axios'

interface FormState {
  operatorCode: string
  operatorName: string
  licenseType: string
  contactName: string
  contactPhone: string
  contactEmail: string
  isActive: boolean
  reason: string
}

const emptyForm: FormState = {
  operatorCode: '', operatorName: '', licenseType: '',
  contactName: '', contactPhone: '', contactEmail: '',
  isActive: true, reason: ''
}

const errMsg = (err: unknown, fallback: string) =>
  axios.isAxiosError(err) ? (err.response?.data?.error ?? err.message) : fallback

// ponytail: fixed set until the protocol spec enumerates license categories
const LICENSE_TYPES = ['MOBILE', 'FIXED', 'MVNO', 'OTHER']

const AUTH_MODES = ['MTLS', 'TLS', 'NONE']

function EndpointsSection({ code }: { code: string }) {
  const queryClient = useQueryClient()
  const { data: endpoints } = useQuery({
    queryKey: ['endpoints', code],
    queryFn: () => endpointApi.list(code)
  })
  const [error, setError] = useState<string | null>(null)
  const [draft, setDraft] = useState({
    endpointType: 'NPG_INBOUND', endpointUrl: '', protocolVersion: '4.0',
    authMode: 'MTLS', isActive: true
  })

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['endpoints', code] })

  const add = useMutation({
    mutationFn: () => endpointApi.create(code, {
      endpointType: draft.endpointType,
      endpointUrl: draft.endpointUrl,
      protocolVersion: draft.protocolVersion || undefined,
      authMode: draft.authMode,
      isActive: draft.isActive
    }),
    onSuccess: () => {
      invalidate()
      setError(null)
      setDraft({ endpointType: 'NPG_INBOUND', endpointUrl: '', protocolVersion: '4.0', authMode: 'MTLS', isActive: true })
    },
    onError: err => setError(errMsg(err, 'Add endpoint failed'))
  })

  const remove = useMutation({
    mutationFn: (id: number) => endpointApi.remove(code, id),
    onSuccess: () => { invalidate(); setError(null) },
    onError: err => setError(errMsg(err, 'Delete endpoint failed'))
  })

  return (
    <>
      <Divider />
      <Typography variant="subtitle2">Endpoints</Typography>
      {error && <Alert severity="error">{error}</Alert>}
      {endpoints && endpoints.length > 0 && (
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Type</TableCell>
              <TableCell>URL</TableCell>
              <TableCell>Protocol</TableCell>
              <TableCell>Auth</TableCell>
              <TableCell>Active</TableCell>
              <TableCell />
            </TableRow>
          </TableHead>
          <TableBody>
            {endpoints.map((ep: OperatorEndpoint) => (
              <TableRow key={ep.id}>
                <TableCell>{ep.endpointType}</TableCell>
                <TableCell sx={{ wordBreak: 'break-all' }}>{ep.endpointUrl}</TableCell>
                <TableCell>{ep.protocolVersion}</TableCell>
                <TableCell>{ep.authMode}</TableCell>
                <TableCell>{ep.isActive ? 'Yes' : 'No'}</TableCell>
                <TableCell>
                  <IconButton size="small" onClick={() => remove.mutate(ep.id)} aria-label="delete endpoint">
                    <DeleteIcon fontSize="small" />
                  </IconButton>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', alignItems: 'center' }}>
        <TextField
          label="Type" size="small" sx={{ width: 160 }}
          value={draft.endpointType}
          onChange={e => setDraft(d => ({ ...d, endpointType: e.target.value }))}
        />
        <TextField
          label="URL" size="small" sx={{ flexGrow: 1, minWidth: 220 }}
          placeholder="https://peer.example.com/np/inbound"
          value={draft.endpointUrl}
          onChange={e => setDraft(d => ({ ...d, endpointUrl: e.target.value }))}
        />
        <TextField
          label="Protocol" size="small" sx={{ width: 100 }}
          value={draft.protocolVersion}
          onChange={e => setDraft(d => ({ ...d, protocolVersion: e.target.value }))}
        />
        <FormControl size="small" sx={{ width: 110 }}>
          <InputLabel>Auth</InputLabel>
          <Select
            label="Auth"
            value={draft.authMode}
            onChange={e => setDraft(d => ({ ...d, authMode: e.target.value }))}
          >
            {AUTH_MODES.map(m => <MenuItem key={m} value={m}>{m}</MenuItem>)}
          </Select>
        </FormControl>
        <FormControlLabel
          control={
            <Switch
              checked={draft.isActive}
              onChange={e => setDraft(d => ({ ...d, isActive: e.target.checked }))}
            />
          }
          label="Active"
        />
        <Button
          variant="outlined" size="small"
          disabled={add.isPending || !draft.endpointType || !draft.endpointUrl}
          onClick={() => add.mutate()}
        >
          Add
        </Button>
      </Box>
    </>
  )
}

export default function OperatorList() {
  const queryClient = useQueryClient()
  const { hasRole } = useAuth()
  const isAdmin = hasRole('ADMIN')
  const canManagePeers = hasRole('ADMIN', 'MANAGER')
  const { data, isLoading } = useQuery({ queryKey: ['operators'], queryFn: operatorApi.list })
  const [dialog, setDialog] = useState<{ mode: 'create' | 'edit'; form: FormState } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [availability, setAvailability] = useState<{ operator: Operator; down: boolean } | null>(null)

  const save = useMutation({
    mutationFn: (d: { mode: 'create' | 'edit'; form: FormState }) => {
      const { mode, form } = d
      return mode === 'create'
        ? operatorApi.create({
            operatorCode: form.operatorCode,
            operatorName: form.operatorName,
            licenseType: form.licenseType || undefined,
            reason: form.reason
          })
        : operatorApi.update(form.operatorCode, {
            operatorName: form.operatorName,
            licenseType: form.licenseType || undefined,
            contactName: form.contactName || undefined,
            contactPhone: form.contactPhone || undefined,
            contactEmail: form.contactEmail || undefined,
            isActive: form.isActive,
            reason: form.reason
          })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['operators'] })
      setDialog(null)
      setError(null)
    },
    onError: err => setError(errMsg(err, 'Save failed'))
  })

  const openCreate = () => {
    setError(null)
    setDialog({ mode: 'create', form: emptyForm })
  }

  const openEdit = (op: Operator) => {
    setError(null)
    setDialog({
      mode: 'edit',
      form: {
        operatorCode: op.operatorCode,
        operatorName: op.operatorName,
        licenseType: op.licenseType ?? '',
        contactName: op.contactName ?? '',
        contactPhone: op.contactPhone ?? '',
        contactEmail: op.contactEmail ?? '',
        isActive: op.isActive ?? true,
        reason: ''
      }
    })
  }

  const setField = (k: keyof FormState, v: string | boolean) =>
    setDialog(d => d ? { ...d, form: { ...d.form, [k]: v } } : d)

  const columns: GridColDef<Operator>[] = [
    { field: 'operatorCode', headerName: 'Code', width: 120 },
    { field: 'operatorName', headerName: 'Name', width: 200 },
    { field: 'availabilityStatus', headerName: 'Status', width: 120 },
    {
      field: 'isActive', headerName: 'Active', width: 260,
      renderCell: p => (
        <Box sx={{ display: 'flex', gap: 0.5 }}>
          <Chip
            label={p.row.isActive === false ? 'Inactive' : 'Active'}
            color={p.row.isActive === false ? 'error' : 'success'}
            size="small"
          />
          {p.row.requiresConfiguration && (
            <Chip label="Needs config" color="warning" size="small" variant="outlined" />
          )}
        </Box>
      )
    },
    ...(canManagePeers
      ? [{
          field: 'actions', headerName: '', width: 150, sortable: false, filterable: false,
          renderCell: (p: { row: Operator }) => (
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, height: '100%' }}>
              <IconButton size="small" onClick={() => openEdit(p.row)} aria-label="edit">
                <EditIcon fontSize="small" />
              </IconButton>
              {!p.row.isLocal && (
                <Button size="small" color={p.row.availabilityStatus === 'DOWN' ? 'success' : 'warning'}
                  onClick={() => setAvailability({ operator: p.row, down: p.row.availabilityStatus !== 'DOWN' })}>
                  {p.row.availabilityStatus === 'DOWN' ? 'Mark up' : 'Mark down'}
                </Button>
              )}
            </Box>
          )
        } as GridColDef<Operator>]
      : [])
  ]

  const form = dialog?.form
  const localOp = (data ?? []).find(o => o.isLocal)
  const peers = (data ?? []).filter(o => !o.isLocal)
  const needsConfig = peers.filter(o => o.requiresConfiguration)

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between' }}>
        <PageHeader title="Operators" subtitle="Peer operators and availability" />
        {canManagePeers && (
          <Button variant="contained" startIcon={<AddIcon />} onClick={openCreate}>
            New Operator
          </Button>
        )}
      </Box>
      <Card sx={{ mb: 3 }}>
        <CardContent sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
          <Box sx={{ flexGrow: 1 }}>
            <Typography variant="subtitle2" sx={{ mb: 1 }}>Local Operator</Typography>
            {localOp ? (
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
                <Typography variant="body2">
                  {localOp.operatorName} ({localOp.operatorCode})
                </Typography>
                <Chip label={localOp.availabilityStatus} size="small" />
                <Chip
                  label={localOp.isActive === false ? 'Inactive' : 'Active'}
                  color={localOp.isActive === false ? 'error' : 'success'}
                  size="small"
                />
                {localOp.requiresConfiguration && (
                  <Chip label="Needs config" color="warning" size="small" variant="outlined" />
                )}
              </Box>
            ) : (
              <Alert severity="warning" sx={{ py: 0 }}>
                No local operator configured yet.
              </Alert>
            )}
          </Box>
          {isAdmin && localOp && (
            <Button variant="outlined" size="small" onClick={() => openEdit(localOp)}>
              Configure
            </Button>
          )}
        </CardContent>
      </Card>
      {needsConfig.map(op => (
        <Alert
          key={op.id}
          severity="warning"
          sx={{ mb: 2 }}
          action={
            canManagePeers ? (
              <Button color="inherit" size="small" onClick={() => openEdit(op)}>
                Configure
              </Button>
            ) : undefined
          }
        >
          New operator '{op.operatorCode}' was identified in an inbound message and requires configuration.
        </Alert>
      ))}
      <DataGrid
        rows={peers}
        columns={columns}
        getRowId={row => row.id}
        loading={isLoading}
        pageSizeOptions={[10, 25, 50]}
        initialState={{ pagination: { paginationModel: { pageSize: 10 } } }}
      />
      <Dialog
        open={dialog !== null}
        onClose={() => setDialog(null)}
        maxWidth="sm"
        fullWidth
        PaperProps={{ sx: { borderRadius: '12px' } }}
      >
        <DialogTitle>
          <Typography variant="h6">{dialog?.mode === 'create' ? 'New Operator' : 'Edit Operator'}</Typography>
        </DialogTitle>
        <DialogContent sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: 2 }}>
          {error && <Alert severity="error">{error}</Alert>}
          {dialog?.mode === 'edit' ? (
            <TextField label="Operator Code" value={form?.operatorCode ?? ''} InputProps={{ readOnly: true }} fullWidth />
          ) : (
            <TextField
              label="Operator Code" required fullWidth
              value={form?.operatorCode ?? ''}
              onChange={e => setField('operatorCode', e.target.value)}
            />
          )}
          <TextField
            label="Operator Name" required fullWidth
            value={form?.operatorName ?? ''}
            onChange={e => setField('operatorName', e.target.value)}
          />
          <FormControl fullWidth>
            <InputLabel>License Type</InputLabel>
            <Select
              label="License Type"
              value={form?.licenseType ?? ''}
              onChange={e => setField('licenseType', e.target.value)}
            >
              {LICENSE_TYPES.map(t => <MenuItem key={t} value={t}>{t}</MenuItem>)}
            </Select>
          </FormControl>
          {dialog?.mode === 'edit' && (
            <>
              <TextField
                label="Contact Name" fullWidth
                value={form?.contactName ?? ''}
                onChange={e => setField('contactName', e.target.value)}
              />
              <TextField
                label="Contact Phone" fullWidth
                value={form?.contactPhone ?? ''}
                onChange={e => setField('contactPhone', e.target.value)}
              />
              <TextField
                label="Contact Email" type="email" fullWidth
                value={form?.contactEmail ?? ''}
                onChange={e => setField('contactEmail', e.target.value)}
              />
              <FormControlLabel
                control={
                  <Switch
                    checked={form?.isActive ?? true}
                    onChange={e => setField('isActive', e.target.checked)}
                  />
                }
                label="Active"
              />
              {isAdmin && <EndpointsSection code={form?.operatorCode ?? ''} />}
            </>
          )}
          <TextField
            label="Reason" fullWidth multiline minRows={2}
            value={form?.reason ?? ''}
            onChange={e => setField('reason', e.target.value)}
          />
        </DialogContent>
        <DialogActions sx={{ justifyContent: 'flex-end', px: 3, pb: 2 }}>
          <Button onClick={() => setDialog(null)}>Cancel</Button>
          <Button
            variant="contained"
            disabled={
              save.isPending || !form?.operatorName ||
              (dialog?.mode === 'create' && !form?.operatorCode)
            }
            onClick={() => dialog && save.mutate(dialog)}
          >
            Save
          </Button>
        </DialogActions>
      </Dialog>
      <ReasonDialog
        open={!!availability}
        title={availability?.down ? `Mark ${availability.operator.operatorCode} down` : `Mark ${availability?.operator.operatorCode} up`}
        description={availability?.down
          ? 'Outbound messages to this operator are held until it is marked up again.'
          : 'Held outbound messages are released immediately.'}
        onClose={() => setAvailability(null)}
        onConfirm={reason => (availability!.down
          ? operatorApi.markDown(availability!.operator.operatorCode, reason)
          : operatorApi.markUp(availability!.operator.operatorCode, reason))
          .then(() => queryClient.invalidateQueries({ queryKey: ['operators'] }))}
      />
    </Box>
  )
}
