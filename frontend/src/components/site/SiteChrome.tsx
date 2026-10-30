import { ReactNode } from 'react'
import { Link as RouterLink } from 'react-router-dom'
import { Box, Button, Container, IconButton, Tooltip, Typography } from '@mui/material'
import GitHubIcon from '@mui/icons-material/GitHub'
import { NpgLockup, NpgMark, NpgWordmark } from '../brand/NpgMark'
import { brand, MONO_FONT } from '../../theme/brand'
import { REPO_URL, signalButton } from './site'
import ColorModeToggle from '../ColorModeToggle'

const navLink = { color: brand.mist, '&:hover': { color: brand.paper, bgcolor: 'transparent' } }

/** The public site's top bar, on Ink. Pages put their hero inside it as children. */
export function SiteHeader({ children }: { children?: ReactNode }) {
  return (
    <Box component="header" sx={{ bgcolor: brand.ink, color: brand.paper, borderBottom: 1, borderColor: theme => theme.palette.mode === 'dark' ? 'divider' : 'transparent' }}>
      <Container maxWidth="lg" sx={{ display: 'flex', alignItems: 'center', gap: 1, py: 2, flexWrap: 'wrap' }}>
        <Box component={RouterLink} to="/landing" aria-label="NPG home" sx={{ flexGrow: 1, textDecoration: 'none' }}>
          <NpgLockup markSize={40} wordSize={24} surface="dark" />
        </Box>
        <Box component="nav" sx={{ display: { xs: 'none', sm: 'flex' }, gap: 0.5 }}>
          <Button component={RouterLink} to="/features" sx={navLink}>Features</Button>
          <Button component={RouterLink} to="/compatibility" sx={navLink}>Compatibility</Button>
          <Button href="/landing#protocol" sx={navLink}>Protocol</Button>
          <Button href="/landing#faq" sx={navLink}>FAQ</Button>
        </Box>
        <Tooltip title="NPG on GitHub">
          <IconButton component="a" href={REPO_URL} target="_blank" rel="noopener noreferrer" aria-label="NPG on GitHub"
            size="small" sx={{ color: brand.mist, '&:hover': { color: brand.paper } }}>
            <GitHubIcon fontSize="small" />
          </IconButton>
        </Tooltip>
        <ColorModeToggle sx={{ color: brand.mist, '&:hover': { color: brand.paper } }} />
        <Button component={RouterLink} to="/login" variant="contained" sx={{ ml: 1, ...signalButton }}>
          Sign in
        </Button>
      </Container>
      {children}
    </Box>
  )
}

export function SiteFooter() {
  return (
    <Box component="footer" sx={{ bgcolor: brand.ink, color: brand.mist, borderTop: 1, borderColor: theme => theme.palette.mode === 'dark' ? 'divider' : 'transparent' }}>
      <Container maxWidth="lg" sx={{ py: 4, display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 2 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
          <NpgMark size={32} surface="dark" />
          <NpgWordmark size={18} surface="dark" />
          <Typography variant="caption" sx={{ ml: 1 }}>
            Number Portability Gateway · GPL-3.0 (≤ 50k numbers) / Commercial
          </Typography>
        </Box>
        <Box sx={{ display: 'flex', gap: 2.5, fontFamily: MONO_FONT, fontSize: 13 }}>
          <Box component={RouterLink} to="/features" sx={{ color: brand.signal }}>features</Box>
          <Box component={RouterLink} to="/compatibility" sx={{ color: brand.signal }}>compatibility</Box>
          <Box component="a" href={REPO_URL} target="_blank" rel="noopener noreferrer" sx={{ color: brand.signal }}>github</Box>
          <Box component={RouterLink} to="/login" sx={{ color: brand.signal }}>sign in</Box>
        </Box>
      </Container>
    </Box>
  )
}

/** The brand "label": Mono caps, Deep Signal on light, Signal on dark. */
export function SectionLabel({ children, dark = false }: { children: ReactNode; dark?: boolean }) {
  return (
    <Typography variant="overline" component="div" sx={{ color: dark ? brand.signal : 'primary.main', mb: 1 }}>
      {children}
    </Typography>
  )
}
