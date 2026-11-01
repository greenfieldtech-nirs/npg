import { useEffect, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { Box, Button, Container, Stack, Typography } from '@mui/material'
import { brand } from '../theme/brand'
import {
  Consent, isPublicPath, measurementId, OPEN_CONSENT_EVENT, storeConsent, storedConsent, trackPage, withdraw
} from './analytics'

/**
 * Asks public-site visitors for analytics consent (only when GA is configured) and reports public
 * page views. Mounted once at the root; renders nothing on console pages.
 */
export default function AnalyticsConsent() {
  const { pathname } = useLocation()
  const [consent, setConsent] = useState<Consent>(storedConsent)
  const [open, setOpen] = useState(false)
  const configured = measurementId() !== null

  useEffect(() => { trackPage(pathname) }, [pathname, consent])

  useEffect(() => {
    const reopen = () => setOpen(true)
    window.addEventListener(OPEN_CONSENT_EVENT, reopen)
    return () => window.removeEventListener(OPEN_CONSENT_EVENT, reopen)
  }, [])

  if (!configured || !isPublicPath(pathname) || (consent !== null && !open)) return null

  const decide = (choice: 'granted' | 'denied') => {
    if (choice === 'denied') withdraw()
    storeConsent(choice)
    setConsent(choice)
    setOpen(false)
  }

  return (
    <Box role="dialog" aria-label="Analytics cookies" sx={{
      position: 'fixed', left: 0, right: 0, bottom: 0, zIndex: t => t.zIndex.snackbar,
      bgcolor: brand.ink2, color: brand.paper, borderTop: `1px solid ${brand.slate}`, py: 2
    }}>
      <Container maxWidth="lg">
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} alignItems={{ md: 'center' }} justifyContent="space-between">
          <Typography variant="body2" sx={{ color: brand.mist, maxWidth: 760 }}>
            We would like to use Google Analytics cookies to understand how visitors use this site. Only the public
            pages are measured, never the operations console. You can change this any time from &ldquo;Cookie settings&rdquo;.
          </Typography>
          <Stack direction="row" spacing={1} sx={{ flexShrink: 0 }}>
            <Button variant="outlined" onClick={() => decide('denied')}
              sx={{ color: brand.paper, borderColor: brand.slate, '&:hover': { borderColor: brand.paper, bgcolor: 'transparent' } }}>
              Decline
            </Button>
            <Button variant="contained" onClick={() => decide('granted')}
              sx={{ bgcolor: brand.signal, color: brand.ink, '&:hover': { bgcolor: '#67E8F9' } }}>
              Accept
            </Button>
          </Stack>
        </Stack>
      </Container>
    </Box>
  )
}
