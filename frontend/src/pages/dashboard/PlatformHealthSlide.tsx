import { ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Alert, Box, Card, Grid, Stack, Typography, useTheme } from '@mui/material'
import StorageIcon from '@mui/icons-material/Storage'
import DnsIcon from '@mui/icons-material/Dns'
import BoltIcon from '@mui/icons-material/Bolt'
import { apiErrorMessage, dashboardApi } from '../../api/client'
import { HealthStatus } from '../../api/types'
import Loader from '../../components/Loader'
import { MONO_FONT } from '../../theme/brand'

type Tone = 'success' | 'warning' | 'error' | 'default'

// brand rule: a status color always comes with its glyph and label
const STATUS: Record<HealthStatus, { label: string; glyph: string; tone: Tone }> = {
  UP: { label: 'Up', glyph: '●', tone: 'success' },
  DEGRADED: { label: 'Degraded', glyph: '◐', tone: 'warning' },
  STALE: { label: 'Not reporting', glyph: '◐', tone: 'warning' },
  DOWN: { label: 'Down', glyph: '✕', tone: 'error' },
  MISSING: { label: 'Not running', glyph: '✕', tone: 'error' },
  DISABLED: { label: 'Disabled', glyph: '○', tone: 'default' }
}

const OVERALL: Record<'UP' | 'DEGRADED' | 'DOWN', string> = {
  UP: 'All systems operational',
  DEGRADED: 'Operational, with degraded components',
  DOWN: 'Some components are down'
}

const STORE_LABEL: Record<string, string> = { mariadb: 'MariaDB', mongodb: 'MongoDB', redis: 'Redis' }
const STORE_PURPOSE: Record<string, string> = {
  mariadb: 'Workflows, queues, timers, audit',
  mongodb: 'Raw XML audit store',
  redis: 'Cache only (not critical)'
}
const ROLE_PURPOSE: Record<string, string> = {
  api: 'Management API and console',
  inbound: 'Receives peers’ XML, ACK/NACK',
  worker: 'Processes the inbox',
  timer: 'Fires durable timers',
  dispatcher: 'Delivers the outbox'
}

function useToneColor() {
  const theme = useTheme()
  return (tone: Tone) => (tone === 'default' ? theme.palette.text.secondary : theme.palette[tone].main)
}

function StatusLabel({ status }: { status: HealthStatus }) {
  const s = STATUS[status]
  const color = useToneColor()(s.tone)
  return (
    <Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.75, color, fontWeight: 600, fontSize: 13, whiteSpace: 'nowrap' }}>
      <span aria-hidden>{s.glyph}</span>{s.label}
    </Box>
  )
}

const ago = (seconds: number) => (seconds < 90 ? `${seconds}s ago` : seconds < 5400 ? `${Math.round(seconds / 60)} min ago` : `${Math.round(seconds / 3600)} h ago`)
const age = (seconds: number) => (seconds === 0 ? '—' : seconds < 90 ? `${seconds}s` : seconds < 5400 ? `${Math.round(seconds / 60)} min` : `${Math.round(seconds / 3600)} h`)

function Tile({ title, status, detail, children }: { title: string; status?: HealthStatus; detail: string; children?: ReactNode }) {
  return (
    <Card sx={{ p: 2, height: '100%', '&:hover': { boxShadow: 'none' } }}>
      <Stack direction="row" justifyContent="space-between" alignItems="baseline" spacing={1} sx={{ mb: 0.5 }}>
        <Typography variant="subtitle1" sx={{ fontFamily: MONO_FONT, fontWeight: 600 }}>{title}</Typography>
        {status && <StatusLabel status={status} />}
      </Stack>
      <Typography variant="caption" color="text.secondary" component="div">{detail}</Typography>
      {children}
    </Card>
  )
}

function Section({ icon, title, children }: { icon: ReactNode; title: string; children: ReactNode }) {
  return (
    <Box>
      <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 1.5, color: 'text.secondary' }}>
        <Box aria-hidden sx={{ display: 'grid' }}>{icon}</Box>
        <Typography variant="overline">{title}</Typography>
      </Stack>
      {children}
    </Box>
  )
}

/** Health of every platform component: stores, runtime roles (from heartbeats), backlog, peers. */
export default function PlatformHealthSlide() {
  const toneColor = useToneColor()
  const { data, isLoading, error } = useQuery({ queryKey: ['platformHealth'], queryFn: dashboardApi.platformHealth, refetchInterval: 15000 })

  if (isLoading) return <Loader />
  if (error || !data) return <Alert severity="error">{apiErrorMessage(error, 'Platform health is unavailable')}</Alert>

  const overall = STATUS[data.status]
  const q = data.queues

  return (
    <Stack spacing={3}>
      <Card role="status" sx={{ p: 2.5, borderLeft: 0, '&:hover': { boxShadow: 'none' } }}>
        <Stack direction="row" alignItems="center" spacing={2}>
          <Box aria-hidden sx={{ fontSize: 32, lineHeight: 1, color: toneColor(overall.tone) }}>{overall.glyph}</Box>
          <Box>
            <Typography variant="h5">{OVERALL[data.status]}</Typography>
            <Typography variant="caption" color="text.secondary">
              Checked {new Date(data.checkedAt).toLocaleTimeString()} · refreshes every 15 seconds
            </Typography>
          </Box>
        </Stack>
      </Card>

      <Section icon={<StorageIcon fontSize="small" />} title="Data stores">
        <Grid container spacing={2}>
          {data.stores.map(s => (
            <Grid item xs={12} md={4} key={s.name}>
              <Tile title={STORE_LABEL[s.name] ?? s.name} status={s.status} detail={STORE_PURPOSE[s.name] ?? ''}>
                <Typography variant="body2" sx={{ mt: 1.5, fontVariantNumeric: 'tabular-nums' }}>
                  {s.status === 'UP' ? `${s.latencyMs} ms` : s.status === 'DISABLED' ? 'Not configured' : (s.error ?? 'Unreachable')}
                </Typography>
              </Tile>
            </Grid>
          ))}
        </Grid>
      </Section>

      <Section icon={<DnsIcon fontSize="small" />} title="Runtime roles">
        <Grid container spacing={2}>
          {data.roles.map(r => {
            const latest = r.instances[0]
            return (
              <Grid item xs={12} sm={6} md={12 / 5} key={r.role}>
                <Tile title={r.role} status={r.status} detail={ROLE_PURPOSE[r.role] ?? ''}>
                  <Typography variant="body2" sx={{ mt: 1.5 }}>
                    {r.instances.length === 0
                      ? 'No process has reported'
                      : `${r.instances.length} process${r.instances.length === 1 ? '' : 'es'} · last seen ${ago(latest.lastSeenSecondsAgo)}`}
                  </Typography>
                  {latest && (
                    <Typography variant="caption" color="text.secondary" component="div" noWrap title={latest.hostname ?? ''}
                      sx={{ fontFamily: MONO_FONT }}>
                      {latest.hostname}{latest.role === 'all' ? ' (all roles)' : ''}
                    </Typography>
                  )}
                </Tile>
              </Grid>
            )
          })}
        </Grid>
      </Section>

      <Section icon={<BoltIcon fontSize="small" />} title="Processing">
        {q ? (
          <Grid container spacing={2}>
            <Grid item xs={12} sm={6} md={3}>
              <Tile title="Inbox" detail="Due, not yet processed">
                <Typography variant="h5" sx={{ mt: 1, fontVariantNumeric: 'tabular-nums' }}>{q.inbox.due}</Typography>
                <Typography variant="caption" color="text.secondary">oldest waiting {age(q.inbox.oldestDueSeconds)}</Typography>
              </Tile>
            </Grid>
            <Grid item xs={12} sm={6} md={3}>
              <Tile title="Outbox" detail="Due, not yet delivered">
                <Typography variant="h5" sx={{ mt: 1, fontVariantNumeric: 'tabular-nums' }}>{q.outbox.due}</Typography>
                <Typography variant="caption" color="text.secondary">oldest waiting {age(q.outbox.oldestDueSeconds)}</Typography>
              </Tile>
            </Grid>
            <Grid item xs={12} sm={6} md={3}>
              <Tile title="Timers" detail="Overdue by more than a minute" status={q.timersOverdue > 0 ? 'DEGRADED' : 'UP'}>
                <Typography variant="h5" sx={{ mt: 1, fontVariantNumeric: 'tabular-nums' }}>{q.timersOverdue}</Typography>
                <Typography variant="caption" color="text.secondary">oldest overdue {age(q.oldestOverdueSeconds)}</Typography>
              </Tile>
            </Grid>
            <Grid item xs={12} sm={6} md={3}>
              <Tile title="Peers" detail="Active peer operators" status={data.peers && data.peers.down > 0 ? 'DEGRADED' : 'UP'}>
                <Typography variant="h5" sx={{ mt: 1, fontVariantNumeric: 'tabular-nums' }}>
                  {data.peers ? `${data.peers.active - data.peers.down} / ${data.peers.active}` : '—'}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  up · {q.outboxNeedingReview} outbox item{q.outboxNeedingReview === 1 ? '' : 's'} in review
                </Typography>
              </Tile>
            </Grid>
          </Grid>
        ) : <Alert severity="warning">Queue figures are unavailable.</Alert>}
      </Section>
    </Stack>
  )
}
