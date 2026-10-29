import { Box, CircularProgress } from '@mui/material'

export default function Loader() {
  return (
    <Box sx={{ display: 'flex', justifyContent: 'center', py: 6 }}>
      <CircularProgress size={32} aria-label="Loading" />
    </Box>
  )
}
