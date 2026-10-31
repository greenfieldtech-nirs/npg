import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { operatorApi, settingsApi } from '../api/client'
import PageHeader from '../components/PageHeader'
import Loader from '../components/Loader'
import {
  Alert, Box, Button, FormControl, FormControlLabel, Grid,
  InputLabel, MenuItem, Select, Switch, TextField, Typography
} from '@mui/material'
import HomeWorkIcon from '@mui/icons-material/HomeWork'
import LinkIcon from '@mui/icons-material/Link'
import ActionCard from '../components/ActionCard'
import axios from 'axios'

// ponytail: same fixed set as OperatorList until the protocol spec enumerates license categories
const LICENSE_TYPES = ['MOBILE', 'FIXED', 'MVNO', 'OTHER']

const errMsg = (err: unknown, fallback: string) =>
  axios.isAxiosError(err) ? (err.response?.data?.error ?? err.message) : fallback

const SET_LOCAL_REASON = 'Marked as local operator via Local Setup'

export default function LocalSetupPage() {
  const queryClient = useQueryClient()
  const operators = useQuery({ queryKey: ['operators'], queryFn: operatorApi.list })
  const settings = useQuery({ queryKey: ['settings', 'local'], queryFn: settingsApi.local })

  const [selectedCode, setSelectedCode] = useState('')
  const [newOp, setNewOp] = useState({ code: '', name: '' })
  const [form, setForm] = useState({
    operatorName: '', licenseType: '', contactName: '',
    contactPhone: '', contactEmail: '', isActive: true
  })
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const localOp = (operators.data ?? []).find(o => o.isLocal)
  const candidates = (operators.data ?? []).filter(o => !o.isLocal)

  useEffect(() => {
    if (localOp) {
      setForm({
        operatorName: localOp.operatorName,
        licenseType: localOp.licenseType ?? '',
        contactName: localOp.contactName ?? '',
        contactPhone: localOp.contactPhone ?? '',
        contactEmail: localOp.contactEmail ?? '',
        isActive: localOp.isActive ?? true
      })
    }
  }, [localOp?.id]) // eslint-disable-line react-hooks/exhaustive-deps

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['operators'] })

  const setLocal = useMutation({
    mutationFn: (code: string) => operatorApi.update(code, { isLocal: true, reason: SET_LOCAL_REASON }),
    onSuccess: () => { invalidate(); setError(null); setNotice('Local operator updated') },
    onError: err => { setNotice(null); setError(errMsg(err, 'Set local operator failed')) }
  })

  const createLocal = useMutation({
    mutationFn: () => operatorApi.create({
      operatorCode: newOp.code,
      operatorName: newOp.name,
      isLocal: true,
      reason: SET_LOCAL_REASON
    }),
    onSuccess: () => {
      invalidate(); setError(null); setNotice('Local operator created')
      setNewOp({ code: '', name: '' })
    },
    onError: err => { setNotice(null); setError(errMsg(err, 'Create operator failed')) }
  })

  const saveLocal = useMutation({
    mutationFn: () => operatorApi.update(localOp!.operatorCode, {
      operatorName: form.operatorName,
      licenseType: form.licenseType || undefined,
      contactName: form.contactName || undefined,
      contactPhone: form.contactPhone || undefined,
      contactEmail: form.contactEmail || undefined,
      isActive: form.isActive,
      isLocal: true,
      reason: 'Updated local operator details via Local Setup'
    }),
    onSuccess: () => { invalidate(); setError(null); setNotice('Local operator saved') },
    onError: err => { setNotice(null); setError(errMsg(err, 'Save failed')) }
  })

  const setField = (k: keyof typeof form, v: string | boolean) =>
    setForm(f => ({ ...f, [k]: v }))

  if (operators.isLoading || settings.isLoading) return <Loader />

  const publicBaseUrl = settings.data?.publicBaseUrl ?? ''

  return (
    <Box>
      <PageHeader title="Local Operator Setup" subtitle="Configure this gateway's local NPG identity" />
      <Grid container spacing={2.5}>
        <Grid item xs={12} md={7}>
          {localOp ? (
            <ActionCard
              icon={<HomeWorkIcon />}
              title="Identity"
              subtitle={localOp.operatorCode}
              help="The operator this gateway acts for. Its code is the X-NP-FROM of everything NPG sends."
              actions={
                <Button variant="contained" disabled={saveLocal.isPending || !form.operatorName} onClick={() => saveLocal.mutate()}>
                  Save
                </Button>
              }
            >
              {notice && <Alert severity="success">{notice}</Alert>}
              {error && <Alert severity="error">{error}</Alert>}
              <Grid container spacing={2}>
                <Grid item xs={12} sm={4}>
                  <TextField label="Operator Code" value={localOp.operatorCode} InputProps={{ readOnly: true }} fullWidth />
                </Grid>
                <Grid item xs={12} sm={8}>
                  <TextField label="Operator Name" required fullWidth value={form.operatorName}
                    onChange={e => setField('operatorName', e.target.value)} />
                </Grid>
                <Grid item xs={12} sm={6}>
                  <FormControl fullWidth>
                    <InputLabel id="local-license">License Type</InputLabel>
                    <Select labelId="local-license" label="License Type" value={form.licenseType}
                      onChange={e => setField('licenseType', e.target.value)}>
                      {LICENSE_TYPES.map(t => <MenuItem key={t} value={t}>{t}</MenuItem>)}
                    </Select>
                  </FormControl>
                </Grid>
                <Grid item xs={12} sm={6}>
                  <TextField label="Contact Name" fullWidth value={form.contactName}
                    onChange={e => setField('contactName', e.target.value)} />
                </Grid>
                <Grid item xs={12} sm={6}>
                  <TextField label="Contact Phone" fullWidth value={form.contactPhone}
                    onChange={e => setField('contactPhone', e.target.value)} />
                </Grid>
                <Grid item xs={12} sm={6}>
                  <TextField label="Contact Email" type="email" fullWidth value={form.contactEmail}
                    onChange={e => setField('contactEmail', e.target.value)} />
                </Grid>
              </Grid>
              <FormControlLabel
                control={<Switch checked={form.isActive} onChange={e => setField('isActive', e.target.checked)} />}
                label="Active"
              />
            </ActionCard>
          ) : (
            <ActionCard
              icon={<HomeWorkIcon />}
              tone="warning"
              title="Identity"
              help="No local operator is configured yet. Pick an existing operator, or create a new one, as this gateway's identity."
            >
              {error && <Alert severity="error">{error}</Alert>}
              <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
                <FormControl size="small" sx={{ minWidth: 240 }}>
                  <InputLabel id="local-existing">Existing operator</InputLabel>
                  <Select labelId="local-existing" label="Existing operator" value={selectedCode}
                    onChange={e => setSelectedCode(e.target.value)}>
                    {candidates.map(o => (
                      <MenuItem key={o.operatorCode} value={o.operatorCode}>
                        {o.operatorName} ({o.operatorCode})
                      </MenuItem>
                    ))}
                  </Select>
                </FormControl>
                <Button variant="contained" disabled={setLocal.isPending || !selectedCode}
                  onClick={() => setLocal.mutate(selectedCode)}>
                  Set as local operator
                </Button>
              </Box>
              <Typography variant="caption" color="text.secondary">or create new</Typography>
              <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
                <TextField label="Code" size="small" sx={{ width: 160 }} value={newOp.code}
                  onChange={e => setNewOp(n => ({ ...n, code: e.target.value }))} />
                <TextField label="Name" size="small" sx={{ flexGrow: 1, minWidth: 200 }} value={newOp.name}
                  onChange={e => setNewOp(n => ({ ...n, name: e.target.value }))} />
                <Button variant="outlined" disabled={createLocal.isPending || !newOp.code || !newOp.name}
                  onClick={() => createLocal.mutate()}>
                  Create as local
                </Button>
              </Box>
            </ActionCard>
          )}
        </Grid>
        <Grid item xs={12} md={5}>
          <ActionCard
            icon={<LinkIcon />}
            title="Gateway endpoint"
            subtitle="/np/inbound"
            help="Share this base URL with peer operators: they send their messages to its /np/inbound."
          >
            <TextField label="Public Base URL" value={publicBaseUrl} InputProps={{ readOnly: true }} fullWidth
              helperText={`${publicBaseUrl}/np/inbound`} />
            <Alert severity="info">
              TLS ends at the load balancer in front of this gateway. Peers are authenticated by the IP allowlist on
              their endpoint (Operators page).
            </Alert>
          </ActionCard>
        </Grid>
      </Grid>
    </Box>
  )
}
