import { FormEvent, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Alert, Box, Button, Card, CardContent, TextField, Typography } from '@mui/material'
import axios from 'axios'
import { useAuth } from '../auth/AuthContext'

export default function Login() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const from = (location.state as { from?: string } | null)?.from ?? '/'

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      await login(username, password)
      navigate(from, { replace: true })
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
    <Box
      sx={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        bgcolor: 'grey.50'
      }}
    >
      <Card sx={{ width: 380, borderRadius: '12px' }}>
        <CardContent sx={{ p: 4, display: 'flex', flexDirection: 'column', gap: 2 }}>
          <Box sx={{ mb: 1 }}>
            <Typography
              sx={{ fontFamily: "'Archivo', sans-serif", fontWeight: 700, fontSize: 32, color: 'primary.main', lineHeight: 1 }}
            >
              NPG
            </Typography>
            <Typography variant="caption" color="text.secondary">
              Number Portability Gateway
            </Typography>
          </Box>
          {error && <Alert severity="error">{error}</Alert>}
          <Box component="form" onSubmit={onSubmit} sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <TextField
              label="Username"
              value={username}
              onChange={e => setUsername(e.target.value)}
              autoComplete="username"
              autoFocus
              required
              fullWidth
            />
            <TextField
              label="Password"
              type="password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              autoComplete="current-password"
              required
              fullWidth
            />
            <Button
              type="submit"
              variant="contained"
              size="large"
              disabled={submitting || !username || !password}
            >
              {submitting ? 'Signing in…' : 'Sign in'}
            </Button>
          </Box>
        </CardContent>
      </Card>
    </Box>
  )
}
