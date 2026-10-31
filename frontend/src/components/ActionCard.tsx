import { ReactNode } from 'react'
import { Box, Card, CardActions, CardContent, Divider, Typography } from '@mui/material'

/**
 * A form that performs an action: icon, title, optional mono subtitle (e.g. the protocol message it
 * sends), a short description, the fields, and the actions pinned to a footer. Fills its grid cell,
 * so cards in a row are equal height and their buttons line up.
 */
export default function ActionCard({ icon, tone = 'primary', title, subtitle, help, actions, children }: {
  icon: ReactNode
  tone?: 'primary' | 'warning' | 'error' | 'success' | 'info'
  title: string
  subtitle?: string
  help?: ReactNode
  actions?: ReactNode
  children?: ReactNode
}) {
  return (
    <Card sx={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      <CardContent sx={{ display: 'flex', flexDirection: 'column', gap: 2, flex: 1, p: 2.5, '&:last-child': { pb: 2.5 } }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
          <Box aria-hidden sx={{
            width: 40, height: 40, flexShrink: 0, borderRadius: 2, display: 'grid', placeItems: 'center',
            bgcolor: 'action.hover', color: `${tone}.main`
          }}>{icon}</Box>
          <Box sx={{ minWidth: 0 }}>
            <Typography variant="subtitle1" component="h2" sx={{ lineHeight: 1.3 }}>{title}</Typography>
            {subtitle && (
              // protocol names are case-sensitive: never uppercased
              <Typography variant="overline" color="text.secondary"
                sx={{ display: 'block', letterSpacing: '0.5px', textTransform: 'none', overflowWrap: 'anywhere' }}>
                {subtitle}
              </Typography>
            )}
          </Box>
        </Box>
        {help && <Typography variant="body2" color="text.secondary" sx={{ minHeight: { md: 40 } }}>{help}</Typography>}
        {children && <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, flex: 1 }}>{children}</Box>}
      </CardContent>
      {actions && (
        <>
          <Divider />
          <CardActions sx={{ px: 2.5, py: 1.5, justifyContent: 'flex-end', flexWrap: 'wrap', gap: 1, '& > :not(style)': { m: 0 } }}>
            {actions}
          </CardActions>
        </>
      )}
    </Card>
  )
}
