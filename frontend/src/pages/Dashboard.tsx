// Design language adapted from SaaSable UI (https://github.com/phoenixcoded/saasable-ui), MIT License © Phoenixcoded
import Loader from '../components/Loader'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { Grid, Card, Box, Typography, keyframes, useTheme } from '@mui/material'
import AccountTreeIcon from '@mui/icons-material/AccountTree'
import MoveToInboxIcon from '@mui/icons-material/MoveToInbox'
import OutboxIcon from '@mui/icons-material/Outbox'
import TaskAltIcon from '@mui/icons-material/TaskAlt'
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutline'
import CellTowerIcon from '@mui/icons-material/CellTower'
import ScheduleIcon from '@mui/icons-material/Schedule'
import { dashboardApi } from '../api/client'
import PageHeader from '../components/PageHeader'

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
    </Box>
  )
}
