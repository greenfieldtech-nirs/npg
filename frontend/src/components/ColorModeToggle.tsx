import { IconButton, SxProps, Theme, Tooltip } from '@mui/material'
import DarkModeOutlinedIcon from '@mui/icons-material/DarkModeOutlined'
import LightModeOutlinedIcon from '@mui/icons-material/LightModeOutlined'
import { useColorMode } from '../theme/colorMode'

/** Switches between light and dark; the icon shows the mode it switches to. */
export default function ColorModeToggle({ sx }: { sx?: SxProps<Theme> }) {
  const { mode, toggle } = useColorMode()
  const label = mode === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'
  return (
    <Tooltip title={label}>
      <IconButton onClick={toggle} aria-label={label} size="small" sx={sx}>
        {mode === 'dark' ? <LightModeOutlinedIcon fontSize="small" /> : <DarkModeOutlinedIcon fontSize="small" />}
      </IconButton>
    </Tooltip>
  )
}
