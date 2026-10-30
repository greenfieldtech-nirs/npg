// Design language adapted from SaaSable UI (https://github.com/phoenixcoded/saasable-ui), MIT License © Phoenixcoded
import Loader from '../components/Loader'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { Grid, Card, Box, Button, Stack, Typography, keyframes, useTheme } from '@mui/material'
import AccountTreeIcon from '@mui/icons-material/AccountTree'
import MoveToInboxIcon from '@mui/icons-material/MoveToInbox'
import OutboxIcon from '@mui/icons-material/Outbox'
import TaskAltIcon from '@mui/icons-material/TaskAlt'
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutline'
import CellTowerIcon from '@mui/icons-material/CellTower'
import ScheduleIcon from '@mui/icons-material/Schedule'
import ArrowForwardIcon from '@mui/icons-material/ArrowForward'
import { dashboardApi, numberingPlanApi } from '../api/client'
import PageHeader from '../components/PageHeader'
import OperatorAllocationBars from '../components/numberingPlan/OperatorAllocationBars'
import NumberingPlanImport from '../components/numberingPlan/NumberingPlanImport'
import { compactCount, fullCount } from '../components/numberingPlan/format'

const rise = keyframes`
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
`

type Tint = 'primary' | 'info' | 'warning' | 'error' | 'success'

export default function Dashboard() {
  const theme = useTheme()
  const navigate = useNavigate()
  const { data, isLoading } = useQuery({ queryKey: ['dashboard'], queryFn: dashboardApi.getSummary, refetchInterval: 15000 })

  if (isLoading) return <Loader />

  const cards: { label: string; value: number; icon: JSX.Element; tint: Tint; to?: string }[] = data ? [
    { label: 'Active Workflows', value: data.activeWorkflows, icon: <AccountTreeIcon />, tint: 'primary' as Tint },
    { label: 'Inbox Pending', value: data.inboxPending, icon: <MoveToInboxIcon />, tint: 'info' as Tint },
    { label: 'Outbox Pending', value: data.outboxPending, icon: <OutboxIcon />, tint: 'info' as Tint },
    { label: 'Open Manual Tasks', value: data.openManualTasks, icon: <TaskAltIcon />, tint: 'warning' as Tint, to: '/manual-tasks' },
    { label: 'Failed Workflows', value: data.failedWorkflows, icon: <ErrorOutlineIcon />, tint: 'error' as Tint },
    { label: 'Operators Down', value: data.operatorsDown, icon: <CellTowerIcon />, tint: 'error' as Tint, to: '/operators' },
    { label: 'Timers Due', value: data.timersDue, icon: <ScheduleIcon />, tint: 'info' as Tint, to: '/queues' },
    { label: 'Timers Due Within 1h', value: data.timersDueSoon, icon: <ScheduleIcon />, tint: 'info' as Tint, to: '/queues' },
    { label: 'Workflows In Manual Review', value: data.workflowsInManualReview, icon: <AccountTreeIcon />, tint: 'warning' as Tint, to: '/workflows?state=MANUAL_REVIEW' },
    { label: 'Outbox Needing Attention', value: data.outboxNeedsAttention, icon: <OutboxIcon />, tint: 'error' as Tint, to: '/queues' },
    { label: 'Failed Inbound Messages', value: data.inboxFailed, icon: <MoveToInboxIcon />, tint: 'error' as Tint, to: '/queues' },
    { label: 'Publication Responses Pending', value: data.publicationBacklog, icon: <OutboxIcon />, tint: 'info' as Tint },
    { label: 'Operators To Configure', value: data.operatorsNeedingConfiguration, icon: <CellTowerIcon />, tint: 'warning' as Tint, to: '/operators' }
  ] : []

  return (
    <Box>
      <PageHeader title="Operations Dashboard" subtitle="Live gateway health at a glance" />
      <Grid container spacing={2.5}>
        {cards.map((card, i) => {
          const tint = theme.palette[card.tint]
          return (
            <Grid item xs={12} sm={6} md={3} key={card.label}>
              <Card
                onClick={() => card.to && navigate(card.to)}
                sx={{ p: 2.5, animation: `${rise} 400ms ease both`, animationDelay: `${i * 60}ms`, cursor: card.to ? 'pointer' : 'default' }}
              >
                <Box
                  sx={{
                    width: 44, height: 44, borderRadius: 2.5, mb: 2,
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    bgcolor: tint.lighter, color: tint.main
                  }}
                >
                  {card.icon}
                </Box>
                <Typography variant="h4" sx={{ mb: 0.5 }}>{card.value}</Typography>
                <Typography variant="caption" color="text.secondary">{card.label}</Typography>
              </Card>
            </Grid>
          )
        })}
      </Grid>
      <NumberingPlanSection />
    </Box>
  )
}

/** The original (non-ported) allocation of Israeli numbers, at a glance. */
function NumberingPlanSection() {
  const navigate = useNavigate()
  const { data } = useQuery({ queryKey: ['numberingPlan', 'summary'], queryFn: numberingPlanApi.summary, staleTime: 5 * 60_000 })
  if (!data) return null
  const { totals } = data

  const tiles = [
    { label: 'Number ranges', value: fullCount(totals.ranges) },
    { label: 'Operators', value: String(totals.operators) },
    { label: 'Allocated numbers', value: compactCount(totals.numbers), title: fullCount(totals.numbers) },
    { label: 'Area codes', value: String(totals.ndcs) }
  ]

  return (
    <Box sx={{ mt: 4 }}>
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 2 }}>
        <Box>
          <Typography variant="h6">Israel Numbering Plan</Typography>
          <Typography variant="caption" color="text.secondary">
            Who each number range was originally allocated to, before porting
          </Typography>
        </Box>
        <Button size="small" endIcon={<ArrowForwardIcon />} onClick={() => navigate('/numbering-plan')}>Open plan</Button>
      </Stack>
      {totals.ranges === 0 ? (
        <Card sx={{ p: 2.5 }}>
          <Typography variant="body2" sx={{ mb: 1.5 }}>
            The numbering plan has not been imported yet.
          </Typography>
          <NumberingPlanImport />
        </Card>
      ) : (
        <Grid container spacing={2.5}>
          <Grid item xs={12} md={4}>
            <Grid container spacing={2.5}>
              {tiles.map(tile => (
                <Grid item xs={6} key={tile.label}>
                  <Card sx={{ p: 2.5 }} title={tile.title}>
                    <Typography variant="h4" sx={{ mb: 0.5, fontVariantNumeric: 'tabular-nums' }}>{tile.value}</Typography>
                    <Typography variant="caption" color="text.secondary">{tile.label}</Typography>
                  </Card>
                </Grid>
              ))}
            </Grid>
          </Grid>
          <Grid item xs={12} md={8}>
            <Card sx={{ p: 2, height: '100%' }}>
              <Typography variant="subtitle2" sx={{ mb: 1, px: 1 }}>Allocated numbers by operator</Typography>
              <OperatorAllocationBars operators={data.byOperator} limit={8}
                onSelect={o => navigate(`/numbering-plan?operator=${encodeURIComponent(o)}`)} />
            </Card>
          </Grid>
        </Grid>
      )}
    </Box>
  )
}
