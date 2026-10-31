import { IconButton, Tooltip, keyframes } from '@mui/material'
import RefreshIcon from '@mui/icons-material/Refresh'

const spin = keyframes`from { transform: rotate(0deg); } to { transform: rotate(360deg); }`

/** Reloads a page's data now; the icon turns while the reload runs. */
export default function RefreshButton({ onRefresh, refreshing, label = 'Refresh' }: {
  onRefresh: () => void
  refreshing: boolean
  label?: string
}) {
  return (
    <Tooltip title={label}>
      <span>
        <IconButton aria-label={label} onClick={onRefresh} disabled={refreshing} color="primary">
          <RefreshIcon sx={refreshing ? { animation: `${spin} 0.9s linear infinite` } : undefined} />
        </IconButton>
      </span>
    </Tooltip>
  )
}
