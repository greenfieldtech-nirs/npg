import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { Box, Button, Card, Grid, Stack, Typography } from '@mui/material'
import ArrowForwardIcon from '@mui/icons-material/ArrowForward'
import Loader from '../../components/Loader'
import { numberingPlanApi } from '../../api/client'
import OperatorAllocationBars from '../../components/numberingPlan/OperatorAllocationBars'
import NumberingPlanImport from '../../components/numberingPlan/NumberingPlanImport'
import { compactCount, fullCount } from '../../components/numberingPlan/format'

/** The original (non-ported) allocation of Israeli numbers, at a glance. */
export default function NumberingPlanSlide() {
  const navigate = useNavigate()
  const { data } = useQuery({ queryKey: ['numberingPlan', 'summary'], queryFn: numberingPlanApi.summary, staleTime: 5 * 60_000 })
  if (!data) return <Loader />
  const { totals } = data

  const tiles = [
    { label: 'Number ranges', value: fullCount(totals.ranges) },
    { label: 'Operators', value: String(totals.operators) },
    { label: 'Allocated numbers', value: compactCount(totals.numbers), title: fullCount(totals.numbers) },
    { label: 'Area codes', value: String(totals.ndcs) }
  ]

  return (
    <Box>
      <Stack direction="row" justifyContent="flex-end" sx={{ mb: 1 }}>
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
