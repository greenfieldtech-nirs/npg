// Design language adapted from SaaSable UI (https://github.com/phoenixcoded/saasable-ui), MIT License © Phoenixcoded
import { Box, Typography } from '@mui/material'

export default function PageHeader({ title, subtitle }: { title: string; subtitle: string }) {
  return (
    <Box sx={{ mb: 3 }}>
      <Typography variant="h5">{title}</Typography>
      <Typography variant="caption" color="text.secondary">{subtitle}</Typography>
    </Box>
  )
}
