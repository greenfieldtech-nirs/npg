import { ReactElement } from 'react'
import { Link as RouterLink } from 'react-router-dom'
import { IconButton, IconButtonProps, Tooltip } from '@mui/material'

/**
 * An action in a table row: always an icon button, named by its tooltip and aria-label, never a
 * text link. Keeps action columns narrow and consistent across the console. With `to` it navigates
 * as a real link (open in a new tab works); otherwise it runs `onClick`.
 */
export default function RowAction({ label, icon, onClick, to, color = 'default', disabled }: {
  label: string
  icon: ReactElement
  onClick?: () => void
  to?: string
  color?: IconButtonProps['color']
  disabled?: boolean
}) {
  return (
    <Tooltip title={label}>
      {/* the span keeps the tooltip working when the button is disabled */}
      <span>
        {to
          ? <IconButton size="small" color={color} aria-label={label} component={RouterLink} to={to} disabled={disabled}>{icon}</IconButton>
          : <IconButton size="small" color={color} aria-label={label} onClick={onClick} disabled={disabled}>{icon}</IconButton>}
      </span>
    </Tooltip>
  )
}
