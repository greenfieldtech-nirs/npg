import { FormEvent, ReactNode, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import {
  Alert, Box, Button, Card, CardContent, Chip, Divider, FormControl, Grid, InputLabel, Link, MenuItem, Select, Stack,
  TextField, Tooltip, Typography
} from '@mui/material'
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutline'
import WarningAmberIcon from '@mui/icons-material/WarningAmber'
import { DataGrid, GridColDef } from '@mui/x-data-grid'
import { apiErrorMessage, numberingPlanApi } from '../api/client'
import { NumberingPlanLookup, NumberingPlanRange } from '../api/types'
import PageHeader from '../components/PageHeader'
import Loader from '../components/Loader'
import OperatorAllocationBars from '../components/numberingPlan/OperatorAllocationBars'
import NumberingPlanImport from '../components/numberingPlan/NumberingPlanImport'
import { compactCount, fullCount, numberTypeLabel } from '../components/numberingPlan/format'

const NOT_IN_LIBPHONENUMBER = 'Google libphonenumber does not recognise numbers in this range (typically M2M / IoT blocks).'

function Stat({ label, value, hint }: { label: string; value: ReactNode; hint?: string }) {
  return (
    <Card sx={{ p: 2, height: '100%' }}>
      <Typography variant="h5" sx={{ fontVariantNumeric: 'tabular-nums' }}>{value}</Typography>
      <Tooltip title={hint ?? ''} disableHoverListener={!hint}>
        <Typography variant="caption" color="text.secondary">{label}</Typography>
      </Tooltip>
    </Card>
  )
}

/**
 * The Ministry of Communications numbering plan: which operator every range was originally
 * allocated to, before any porting. Ported numbers live in this gateway's own records.
 */
export default function NumberingPlanPage() {
  const [params, setParams] = useSearchParams()
  const operator = params.get('operator') ?? ''
  const ndc = params.get('ndc') ?? ''
  const numberType = params.get('type') ?? ''
  const [search, setSearch] = useState(params.get('q') ?? '')
  const [q, setQ] = useState(search)

  useEffect(() => {
    const t = setTimeout(() => setQ(search.trim()), 300)
    return () => clearTimeout(t)
  }, [search])

  const summary = useQuery({ queryKey: ['numberingPlan', 'summary'], queryFn: numberingPlanApi.summary })
  const ranges = useQuery({
    queryKey: ['numberingPlan', 'ranges', operator, ndc, numberType, q],
    queryFn: () => numberingPlanApi.ranges({
      operator: operator || undefined, ndc: ndc || undefined, numberType: numberType || undefined, q: q || undefined
    }),
    enabled: (summary.data?.totals.ranges ?? 0) > 0
  })

  const setFilter = (key: string, value: string) => {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value)
    else next.delete(key)
    setParams(next, { replace: true })
  }

  if (summary.isLoading) return <Loader />
  if (summary.error) return <Alert severity="error">{apiErrorMessage(summary.error)}</Alert>
  const plan = summary.data!
  const imported = plan.totals.ranges > 0
  const operatorNames = plan.byOperator.map(o => o.operator).sort((a, b) => a.localeCompare(b, 'en', { sensitivity: 'base' }))

  const columns: GridColDef<NumberingPlanRange>[] = [
    { field: 'nationalPattern', headerName: 'National', width: 125 },
    { field: 'e164Pattern', headerName: 'E.164', width: 145 },
    { field: 'allocatedTo', headerName: 'Allocated to', flex: 1, minWidth: 200 },
    {
      field: 'numberType', headerName: 'Type', width: 195,
      renderCell: p => p.row.libphonenumberValid
        ? numberTypeLabel(p.row.numberType)
        : (
          <Tooltip title={NOT_IN_LIBPHONENUMBER}>
            <Stack direction="row" spacing={0.5} alignItems="center" sx={{ height: '100%' }}>
              <WarningAmberIcon fontSize="small" color="warning" />
              <span>{numberTypeLabel(p.row.numberType)}</span>
            </Stack>
          </Tooltip>
        )
    },
    { field: 'area', headerName: 'Area', width: 120, valueGetter: (_v, row) => row.area ?? '' },
    {
      field: 'rangeSize', headerName: 'Numbers', width: 110, type: 'number',
      valueFormatter: (v: number) => fullCount(v)
    },
    { field: 'exampleNational', headerName: 'Example', width: 120 },
    { field: 'notes', headerName: 'Notes', flex: 0.6, minWidth: 140 }
  ]

  return (
    <Box>
      <PageHeader
        title="Israel Numbering Plan"
        subtitle="Original (non-ported) allocation of every number range, classified with Google libphonenumber"
      />

      <Card sx={{ mb: 2.5 }}>
        <CardContent>
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} justifyContent="space-between" alignItems={{ md: 'center' }}>
            <Box>
              {plan.lastImport ? (
                <Typography variant="body2">
                  {plan.lastImport.source === 'upload' ? 'Uploaded copy' : 'Official file'} imported{' '}
                  {new Date(plan.lastImport.importedAt).toLocaleString()}
                  {plan.lastImport.importedBy && ` by ${plan.lastImport.importedBy}`}.
                </Typography>
              ) : (
                <Typography variant="body2">The numbering plan has not been imported yet.</Typography>
              )}
              <Typography variant="caption" color="text.secondary">
                Source: <Link href={plan.source} target="_blank" rel="noopener noreferrer">Ministry of Communications, data.gov.il</Link>
              </Typography>
            </Box>
            <NumberingPlanImport />
          </Stack>
        </CardContent>
      </Card>

      {!imported ? (
        <Alert severity="info">No ranges yet. An administrator can import the plan above.</Alert>
      ) : (
        <>
          <Grid container spacing={2} sx={{ mb: 2.5 }}>
            <Grid item xs={6} md={2.4}><Stat label="Number ranges" value={fullCount(plan.totals.ranges)} /></Grid>
            <Grid item xs={6} md={2.4}><Stat label="Operators" value={plan.totals.operators} /></Grid>
            <Grid item xs={6} md={2.4}>
              <Stat label="Allocated numbers" value={compactCount(plan.totals.numbers)} hint={fullCount(plan.totals.numbers)} />
            </Grid>
            <Grid item xs={6} md={2.4}><Stat label="Area codes" value={plan.totals.ndcs} /></Grid>
            <Grid item xs={12} md={2.4}>
              <Stat label="Ranges libphonenumber rejects" value={plan.totals.notValidInLibphonenumber} hint={NOT_IN_LIBPHONENUMBER} />
            </Grid>
          </Grid>

          <Grid container spacing={2.5} sx={{ mb: 2.5 }}>
            <Grid item xs={12} md={5}>
              <NumberLookupCard />
            </Grid>
            <Grid item xs={12} md={7}>
              <Card sx={{ height: '100%' }}>
                <CardContent>
                  <Typography variant="subtitle1">Allocated numbers by operator</Typography>
                  <Typography variant="caption" color="text.secondary" component="div" sx={{ mb: 1 }}>
                    Select an operator to list its ranges.
                  </Typography>
                  <Box sx={{ maxHeight: 360, overflowY: 'auto' }}>
                    <OperatorAllocationBars operators={plan.byOperator} onSelect={o => setFilter('operator', o === operator ? '' : o)} />
                  </Box>
                </CardContent>
              </Card>
            </Grid>
          </Grid>

          <Card sx={{ mb: 1.5 }}>
            <CardContent>
              <Stack direction={{ xs: 'column', md: 'row' }} spacing={2}>
                <FormControl size="small" sx={{ minWidth: 260 }}>
                  <InputLabel>Operator</InputLabel>
                  <Select label="Operator" value={operator} onChange={e => setFilter('operator', e.target.value)}>
                    <MenuItem value="">All operators</MenuItem>
                    {operatorNames.map(name => <MenuItem key={name} value={name}>{name}</MenuItem>)}
                  </Select>
                </FormControl>
                <FormControl size="small" sx={{ minWidth: 160 }}>
                  <InputLabel>Area code</InputLabel>
                  <Select label="Area code" value={ndc} onChange={e => setFilter('ndc', e.target.value)}>
                    <MenuItem value="">All</MenuItem>
                    {plan.byNdc.map(n => (
                      <MenuItem key={n.ndc} value={n.ndc}>
                        {n.ndc.startsWith('1') ? n.ndc : `0${n.ndc}`}{n.area ? ` — ${n.area}` : ''}
                      </MenuItem>
                    ))}
                  </Select>
                </FormControl>
                <FormControl size="small" sx={{ minWidth: 180 }}>
                  <InputLabel>Type</InputLabel>
                  <Select label="Type" value={numberType} onChange={e => setFilter('type', e.target.value)}>
                    <MenuItem value="">All</MenuItem>
                    {plan.byType.map(t => <MenuItem key={t.numberType} value={t.numberType}>{numberTypeLabel(t.numberType)}</MenuItem>)}
                  </Select>
                </FormControl>
                <TextField size="small" label="Prefix, number or operator" value={search} sx={{ flex: 1 }}
                  onChange={e => setSearch(e.target.value)} placeholder="e.g. 03-30, +972 52 234 5678, Bezeq" />
              </Stack>
            </CardContent>
          </Card>
          <Typography variant="caption" color="text.secondary" component="div" sx={{ mb: 1 }}>
            {ranges.data ? `${fullCount(ranges.data.length)} ranges · ${fullCount(ranges.data.reduce((sum, r) => sum + Number(r.rangeSize), 0))} numbers` : ' '}
          </Typography>
          <Card sx={{ height: 620, p: 1 }}>
            <DataGrid rows={ranges.data ?? []} columns={columns} loading={ranges.isFetching} density="compact"
              initialState={{ pagination: { paginationModel: { pageSize: 100 } } }} pageSizeOptions={[25, 100]} />
          </Card>
        </>
      )}
    </Box>
  )
}

/** Who a number was allocated to, what libphonenumber makes of it, and whether this gateway saw it ported. */
function NumberLookupCard() {
  const [number, setNumber] = useState('')
  const [result, setResult] = useState<NumberingPlanLookup | null>(null)
  const [error, setError] = useState<string | null>(null)

  const lookup = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    setResult(null)
    try {
      setResult(await numberingPlanApi.lookup(number.trim()))
    } catch (err) {
      setError(apiErrorMessage(err))
    }
  }

  return (
    <Card sx={{ height: '100%' }}>
      <CardContent>
        <Typography variant="subtitle1" sx={{ mb: 1.5 }}>Look up a number</Typography>
        <Stack component="form" direction="row" spacing={1} onSubmit={lookup}>
          <TextField size="small" fullWidth label="Phone number" placeholder="052-234-5678 or +972 3 300 1234"
            value={number} onChange={e => setNumber(e.target.value)} />
          <Button type="submit" variant="contained" disabled={!number.trim()} sx={{ flexShrink: 0 }}>Look up</Button>
        </Stack>
        {!result && !error && (
          <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 1.5 }}>
            Any Israeli format works. Shows the operator the number was originally allocated to, how Google
            libphonenumber classifies it, and whether this gateway has recorded it as ported.
          </Typography>
        )}
        {error && <Alert severity="error" sx={{ mt: 2 }}>{error}</Alert>}
        {result && (
          <Box sx={{ mt: 2 }}>
            <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
              <Typography variant="h6">{result.national}</Typography>
              <Typography variant="body2" color="text.secondary">{result.international}</Typography>
            </Stack>
            <Stack direction="row" spacing={1} sx={{ mb: 1.5, flexWrap: 'wrap', rowGap: 1 }}>
              <Chip size="small" variant="outlined"
                icon={result.valid ? <CheckCircleOutlineIcon /> : <WarningAmberIcon />}
                color={result.valid ? 'success' : 'warning'}
                label={result.valid ? `Valid · ${numberTypeLabel(result.numberType)}` : 'Not valid in libphonenumber'} />
              {result.area && <Chip size="small" variant="outlined" label={result.area} />}
            </Stack>
            <Divider sx={{ mb: 1.5 }} />
            <Typography variant="caption" color="text.secondary">Original allocation</Typography>
            {result.range ? (
              <Typography variant="body2" sx={{ mb: 1 }}>
                <strong>{result.range.allocatedTo}</strong> — range {result.range.nationalPattern}
                {result.range.notes && ` (${result.range.notes})`}
              </Typography>
            ) : (
              <Typography variant="body2" sx={{ mb: 1 }}>Not in any allocated range.</Typography>
            )}
            <Typography variant="caption" color="text.secondary">This gateway&apos;s records</Typography>
            <Typography variant="body2">
              {result.porting
                ? <>Status {result.porting.status}{result.porting.currentOperator && <>, served by <strong>{result.porting.currentOperatorName ?? result.porting.currentOperator}</strong></>}
                  {result.porting.lastPortedAt && `, last ported ${new Date(result.porting.lastPortedAt).toLocaleDateString()}`}</>
                : 'No porting recorded by this gateway.'}
            </Typography>
          </Box>
        )}
      </CardContent>
    </Card>
  )
}
