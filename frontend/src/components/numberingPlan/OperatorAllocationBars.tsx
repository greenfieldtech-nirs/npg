import { Box, ButtonBase, Tooltip, Typography, useTheme } from '@mui/material'
import { NumberingPlanOperatorSummary } from '../../api/types'
import { compactCount, fullCount, numberTypeLabel } from './format'

/**
 * Operators ranked by how many numbers the plan allocates them: one series, so one hue and no
 * legend; values sit in text ink beside the bar and each row explains itself on hover.
 */
export default function OperatorAllocationBars({ operators, limit, onSelect }: {
  operators: NumberingPlanOperatorSummary[]
  limit?: number
  onSelect?: (operator: string) => void
}) {
  const theme = useTheme()
  const shown = limit ? operators.slice(0, limit) : operators
  const max = Math.max(1, ...operators.map(o => Number(o.numbers)))

  return (
    <Box>
      {shown.map(o => {
        const types = (o.numberTypes ?? '').split(',').filter(Boolean).map(numberTypeLabel).join(', ')
        const ndcs = (o.ndcs ?? '').split(',').filter(Boolean).map(n => (n.startsWith('1') ? n : `0${n}`)).join(', ')
        return (
          <Tooltip
            key={o.operator}
            placement="top-start"
            title={
              <Box>
                <Typography variant="subtitle2">{o.operator}</Typography>
                <Typography variant="caption" component="div">{fullCount(o.numbers)} numbers in {o.ranges} range{o.ranges === 1 ? '' : 's'}</Typography>
                {types && <Typography variant="caption" component="div">{types}</Typography>}
                {ndcs && <Typography variant="caption" component="div">Area codes {ndcs}</Typography>}
              </Box>
            }
          >
            <ButtonBase
              disabled={!onSelect}
              onClick={() => onSelect?.(o.operator)}
              aria-label={`${o.operator}: ${fullCount(o.numbers)} numbers`}
              sx={{
                width: '100%', display: 'grid', gridTemplateColumns: 'minmax(0, 2fr) minmax(0, 3fr) 56px', columnGap: 1.5,
                alignItems: 'center', textAlign: 'left', py: 0.75, px: 1, borderRadius: 1,
                '&:hover': { bgcolor: 'action.hover' }
              }}
            >
              <Typography variant="body2" noWrap title={o.operator}>{o.operator}</Typography>
              <Box sx={{ height: 8, bgcolor: 'action.hover', borderRadius: 1 }}>
                <Box sx={{
                  height: '100%', width: `${Math.max(1, (Number(o.numbers) / max) * 100)}%`,
                  bgcolor: theme.palette.primary.main, borderRadius: '0 4px 4px 0'
                }} />
              </Box>
              <Typography variant="body2" color="text.secondary" sx={{ textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>
                {compactCount(o.numbers)}
              </Typography>
            </ButtonBase>
          </Tooltip>
        )
      })}
      {limit && operators.length > limit && (
        <Typography variant="caption" color="text.secondary" sx={{ px: 1 }}>
          +{operators.length - limit} more operators
        </Typography>
      )}
    </Box>
  )
}
