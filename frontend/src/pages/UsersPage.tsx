import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { DataGrid, GridColDef } from '@mui/x-data-grid'
import { userApi } from '../api/client'
import { SystemUser } from '../api/types'
import PageHeader from '../components/PageHeader'
import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle,
  FormControl, FormControlLabel, InputLabel, MenuItem, Select, Switch,
  TextField, Typography, Checkbox, ListItemText, OutlinedInput, SelectChangeEvent
} from '@mui/material'
import AddIcon from '@mui/icons-material/Add'
import EditIcon from '@mui/icons-material/Edit'
import RowAction from '../components/RowAction'
import axios from 'axios'

const ALL_ROLES = ['ADMIN', 'MANAGER', 'USER']

interface FormState {
  username: string
  displayName: string
  email: string
  password: string
  roles: string[]
  isActive: boolean
}

const emptyForm: FormState = {
  username: '', displayName: '', email: '', password: '', roles: [], isActive: true
}

const errMsg = (err: unknown, fallback: string) =>
  axios.isAxiosError(err) ? (err.response?.data?.error ?? err.message) : fallback

export default function UsersPage() {
  const queryClient = useQueryClient()
  const { data, isLoading } = useQuery({ queryKey: ['users'], queryFn: userApi.list })
  const [dialog, setDialog] = useState<{ mode: 'create' | 'edit'; form: FormState } | null>(null)
  const [error, setError] = useState<string | null>(null)

  const save = useMutation({
    mutationFn: (d: { mode: 'create' | 'edit'; form: FormState }) => {
      const { mode, form } = d
      return mode === 'create'
        ? userApi.create({
            username: form.username,
            password: form.password,
            roles: form.roles,
            displayName: form.displayName || undefined,
            email: form.email || undefined
          })
        : userApi.update(form.username, {
            displayName: form.displayName || undefined,
            email: form.email || undefined,
            isActive: form.isActive,
            roles: form.roles,
            ...(form.password ? { password: form.password } : {})
          })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users'] })
      setDialog(null)
      setError(null)
    },
    onError: err => setError(errMsg(err, 'Save failed'))
  })

  const openCreate = () => {
    setError(null)
    setDialog({ mode: 'create', form: emptyForm })
  }

  const openEdit = (u: SystemUser) => {
    setError(null)
    setDialog({
      mode: 'edit',
      form: {
        username: u.username,
        displayName: u.displayName ?? '',
        email: u.email ?? '',
        password: '',
        roles: u.roles,
        isActive: u.isActive
      }
    })
  }

  const setField = (k: keyof FormState, v: string | boolean | string[]) =>
    setDialog(d => d ? { ...d, form: { ...d.form, [k]: v } } : d)

  const onRolesChange = (e: SelectChangeEvent<string[]>) =>
    setField('roles', typeof e.target.value === 'string' ? e.target.value.split(',') : e.target.value)

  const columns: GridColDef<SystemUser>[] = [
    { field: 'username', headerName: 'Username', width: 160 },
    { field: 'displayName', headerName: 'Display Name', width: 180, valueGetter: (_v, row) => row.displayName ?? '—' },
    {
      field: 'roles', headerName: 'Roles', width: 240, sortable: false,
      renderCell: p => (
        <Box sx={{ display: 'flex', gap: 0.5 }}>
          {p.row.roles.map(r => <Chip key={r} label={r} size="small" variant="outlined" />)}
        </Box>
      )
    },
    {
      field: 'isActive', headerName: 'Status', width: 120,
      renderCell: p => (
        <Chip
          label={p.row.isActive ? 'Active' : 'Inactive'}
          color={p.row.isActive ? 'success' : 'error'}
          size="small"
        />
      )
    },
    {
      field: 'createdAt', headerName: 'Created', width: 180,
      valueGetter: (_v, row) => new Date(row.createdAt).toLocaleString()
    },
    {
      field: 'actions', headerName: '', width: 70, sortable: false, filterable: false,
      renderCell: (p: { row: SystemUser }) => (
        <RowAction label="Edit user" icon={<EditIcon fontSize="small" />} onClick={() => openEdit(p.row)} />
      )
    }
  ]

  const form = dialog?.form

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between' }}>
        <PageHeader title="User Management" subtitle="Console users and their roles" />
        <Button variant="contained" startIcon={<AddIcon />} onClick={openCreate}>
          New User
        </Button>
      </Box>
      <DataGrid
        rows={data ?? []}
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
          <Typography variant="h6">{dialog?.mode === 'create' ? 'New User' : 'Edit User'}</Typography>
        </DialogTitle>
        <DialogContent sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: 2 }}>
          {error && <Alert severity="error">{error}</Alert>}
          {dialog?.mode === 'edit' ? (
            <TextField label="Username" value={form?.username ?? ''} InputProps={{ readOnly: true }} fullWidth />
          ) : (
            <TextField
              label="Username" required fullWidth
              helperText="3-64 chars: a-z 0-9 . _ -"
              value={form?.username ?? ''}
              onChange={e => setField('username', e.target.value)}
            />
          )}
          <TextField
            label="Display Name" fullWidth
            value={form?.displayName ?? ''}
            onChange={e => setField('displayName', e.target.value)}
          />
          <TextField
            label="Email" type="email" fullWidth
            value={form?.email ?? ''}
            onChange={e => setField('email', e.target.value)}
          />
          <TextField
            label={dialog?.mode === 'create' ? 'Password' : 'Password (leave blank to keep current)'}
            type="password" fullWidth
            required={dialog?.mode === 'create'}
            helperText="Minimum 8 characters"
            value={form?.password ?? ''}
            onChange={e => setField('password', e.target.value)}
          />
          <FormControl fullWidth required>
            <InputLabel>Roles</InputLabel>
            <Select
              multiple
              label="Roles"
              input={<OutlinedInput label="Roles" />}
              value={form?.roles ?? []}
              onChange={onRolesChange}
              renderValue={selected => (
                <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap' }}>
                  {selected.map(r => <Chip key={r} label={r} size="small" />)}
                </Box>
              )}
            >
              {ALL_ROLES.map(r => (
                <MenuItem key={r} value={r}>
                  <Checkbox checked={(form?.roles ?? []).includes(r)} />
                  <ListItemText primary={r} />
                </MenuItem>
              ))}
            </Select>
          </FormControl>
          <FormControlLabel
            control={
              <Switch
                checked={form?.isActive ?? true}
                onChange={e => setField('isActive', e.target.checked)}
              />
            }
            label="Active"
          />
        </DialogContent>
        <DialogActions sx={{ justifyContent: 'flex-end', px: 3, pb: 2 }}>
          <Button onClick={() => setDialog(null)}>Cancel</Button>
          <Button
            variant="contained"
            disabled={
              save.isPending ||
              !form?.roles.length ||
              (dialog?.mode === 'create' && (!form?.username || form.password.length < 8))
            }
            onClick={() => dialog && save.mutate(dialog)}
          >
            Save
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}
