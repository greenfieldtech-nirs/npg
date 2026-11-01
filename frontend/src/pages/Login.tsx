// Layout adapted from the SaaSable UI sign-in page (https://github.com/phoenixcoded/saasable-ui), MIT License © Phoenixcoded
import { FormEvent, useState } from 'react'
import { Link as RouterLink, useLocation, useNavigate } from 'react-router-dom'
import {
  Alert, Box, Button, IconButton, InputAdornment, InputLabel, Link, OutlinedInput, Stack, Typography, useTheme
} from '@mui/material'
import VisibilityOutlinedIcon from '@mui/icons-material/VisibilityOutlined'
import VisibilityOffOutlinedIcon from '@mui/icons-material/VisibilityOffOutlined'
import axios from 'axios'
import { useAuth } from '../auth/AuthContext'
import { isLoaded as analyticsLoaded } from '../analytics/analytics'
import { NpgLockup } from '../components/brand/NpgMark'
import ColorModeToggle from '../components/ColorModeToggle'
import { REPO_URL } from '../components/site/site'
import { brand, MONO_FONT } from '../theme/brand'
import dashboardShot from '../assets/screens/dashboard.webp'
import dashboardShotDark from '../assets/screens/dark/dashboard.webp'

export default function Login() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const from = (location.state as { from?: string } | null)?.from ?? '/'
  const dark = useTheme().palette.mode === 'dark'

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      await login(username, password)
      // a page that loaded Google Analytics enters the console with a full page load, so GA's
      // automatic page tracking never sees console URLs (phone numbers, ids)
      if (analyticsLoaded() && from.startsWith('/') && !from.startsWith('//')) window.location.replace(from)
      else navigate(from, { replace: true })
    } catch (err) {
      setError(
        axios.isAxiosError(err) && err.response?.status === 401
          ? 'Invalid username or password'
          : 'Sign in failed. Please try again.'
      )
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Box sx={{ minHeight: '100vh', display: 'flex', bgcolor: 'background.paper' }}>
      {/* the form */}
      <Box sx={{ flex: { xs: 1, md: '0 0 58%' }, display: 'flex', flexDirection: 'column', position: 'relative', px: { xs: 3, sm: 6 }, py: { xs: 4, md: 6 } }}>
        <ColorModeToggle sx={{ position: 'absolute', top: 16, right: 16, color: 'text.secondary' }} />
        <Box sx={{ display: { xs: 'flex', md: 'none' }, justifyContent: 'center', mb: 4 }}>
          <NpgLockup markSize={40} wordSize={24} />
        </Box>
        <Box sx={{ flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'center', width: '100%', maxWidth: 460, mx: 'auto' }}>
          <Box sx={{ textAlign: 'center', mb: 5 }}>
            <Typography component="h1" sx={{ fontFamily: MONO_FONT, fontWeight: 700, fontSize: { xs: 36, sm: 44 }, letterSpacing: '-1px', lineHeight: 1.15, mb: 1.5 }}>
              Sign in
            </Typography>
            <Typography variant="body1" color="text.secondary">
              Welcome back. Sign in to the NPG operations console.
            </Typography>
          </Box>

          {error && <Alert severity="error" sx={{ mb: 3 }}>{error}</Alert>}

          <Box component="form" onSubmit={onSubmit}>
            <Stack spacing={2.5}>
              <Box>
                <InputLabel htmlFor="login-username" required sx={{ mb: 0.75, color: 'text.primary', fontWeight: 500 }}>Username</InputLabel>
                <OutlinedInput
                  id="login-username"
                  value={username}
                  onChange={e => setUsername(e.target.value)}
                  autoComplete="username"
                  placeholder="Your username"
                  autoFocus
                  required
                  fullWidth
                />
              </Box>
              <Box>
                <InputLabel htmlFor="login-password" required sx={{ mb: 0.75, color: 'text.primary', fontWeight: 500 }}>Password</InputLabel>
                <OutlinedInput
                  id="login-password"
                  type={showPassword ? 'text' : 'password'}
                  value={password}
                  onChange={e => setPassword(e.target.value)}
                  autoComplete="current-password"
                  placeholder="Your password"
                  required
                  fullWidth
                  endAdornment={
                    <InputAdornment position="end">
                      <IconButton
                        edge="end"
                        onClick={() => setShowPassword(s => !s)}
                        aria-label={showPassword ? 'Hide password' : 'Show password'}
                      >
                        {showPassword ? <VisibilityOffOutlinedIcon fontSize="small" /> : <VisibilityOutlinedIcon fontSize="small" />}
                      </IconButton>
                    </InputAdornment>
                  }
                />
              </Box>
            </Stack>
            <Button
              type="submit"
              variant="contained"
              size="large"
              disabled={submitting || !username || !password}
              sx={{ mt: 4, px: 5, minWidth: 140 }}
            >
              {submitting ? 'Signing in…' : 'Sign in'}
            </Button>
          </Box>
        </Box>

        <Stack direction="row" spacing={2} justifyContent="center" alignItems="center" sx={{ mt: 4, flexWrap: 'wrap', rowGap: 1 }}
          divider={<Box sx={{ width: '1px', height: 14, bgcolor: 'divider' }} />}>
          <Typography variant="body2" color="text.secondary">© {new Date().getFullYear()} NPG</Typography>
          <Link component={RouterLink} to="/landing" variant="body2" color="text.secondary" underline="hover">Home</Link>
          <Link component={RouterLink} to="/features" variant="body2" color="text.secondary" underline="hover">Features</Link>
          <Link href={REPO_URL} target="_blank" rel="noopener noreferrer" variant="body2" color="text.secondary" underline="hover">GitHub</Link>
        </Stack>
      </Box>

      {/* the brand panel: lockup, tagline, and the console peeking in from the corner */}
      <Box aria-hidden sx={{
        display: { xs: 'none', md: 'flex' }, flex: 1, flexDirection: 'column', alignItems: 'center', position: 'relative',
        overflow: 'hidden', bgcolor: brand.ink, color: brand.paper, pt: 7,
        borderLeft: 1, borderColor: theme => theme.palette.mode === 'dark' ? 'divider' : 'transparent'
      }}>
        <NpgLockup markSize={44} wordSize={28} surface="dark" />
        <Typography sx={{ mt: 2.5, px: 6, maxWidth: 520, textAlign: 'center', fontSize: 16, lineHeight: 1.6, color: brand.mist }}>
          The number-portability exchange for licensed operators. Every request, response and publication,
          with a durable audit trail.
        </Typography>
        <Box sx={{
          // bleeds off the bottom-right corner at any panel size; the image is cropped, never letterboxed
          position: 'absolute', left: 56, top: 250, right: -320, bottom: -24, borderRadius: '16px 0 0 0', overflow: 'hidden',
          border: `6px solid ${brand.ink2}`, borderRight: 0, borderBottom: 0,
          boxShadow: '0px 30px 60px -20px rgba(0, 0, 0, 0.6)'
        }}>
          <Box component="img" src={dark ? dashboardShotDark : dashboardShot} alt="" sx={{ display: 'block', width: '100%', height: '100%', objectFit: 'cover', objectPosition: 'left top' }} />
        </Box>
      </Box>
    </Box>
  )
}
