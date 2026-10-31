import { ReactElement } from 'react'
import { IconButton, IconButtonProps, Tooltip } from '@mui/material'

/**
 * An action in a table row: always an icon button, named by its tooltip and aria-label, never a
 * text link. Keeps action columns narrow and consistent across the console.
 */
export default function RowAction({ label, icon, onClick, color = 'default', disabled }: {
  label: string
  icon: ReactElement
  onClick: () => void
  color?: IconButtonProps['color']
  disabled?: boolean
}) {
  return (
    <Tooltip title={label}>
      {/* the span keeps the tooltip working when the button is disabled */}
      <span>
        <IconButton size="small" color={color} aria-label={label} onClick={onClick} disabled={disabled}>
          {icon}
        </IconButton>
      </span>
    </Tooltip>
  )
}
