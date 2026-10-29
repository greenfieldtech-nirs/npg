// Design language adapted from SaaSable UI (https://github.com/phoenixcoded/saasable-ui), MIT License © Phoenixcoded
import { ReactNode } from 'react'
import { Link as RouterLink, useLocation, useNavigate } from 'react-router-dom'
import { useIsFetching, useIsMutating } from '@tanstack/react-query'
import {
  AppBar, Toolbar, Typography, Drawer, List, ListItemButton, ListItemIcon,
  ListItemText, Box, Container, Chip, LinearProgress, IconButton
} from '@mui/material'
import LogoutIcon from '@mui/icons-material/Logout'
import { useAuth } from '../auth/AuthContext'
import DashboardIcon from '@mui/icons-material/Dashboard'
import AccountTreeIcon from '@mui/icons-material/AccountTree'
import MailOutlineIcon from '@mui/icons-material/MailOutline'
import CellTowerIcon from '@mui/icons-material/CellTower'
import TaskAltIcon from '@mui/icons-material/TaskAlt'
import PinIcon from '@mui/icons-material/Pin'
import ManageAccountsIcon from '@mui/icons-material/ManageAccounts'
import HomeWorkIcon from '@mui/icons-material/HomeWork'

const DRAWER_WIDTH = 240

const navItems = [
  { label: 'Dashboard', path: '/', icon: <DashboardIcon fontSize="small" /> },
  { label: 'Workflows', path: '/workflows', icon: <AccountTreeIcon fontSize="small" /> },
  { label: 'Log Viewer', path: '/messages', icon: <MailOutlineIcon fontSize="small" /> },
  { label: 'Operators', path: '/operators', icon: <CellTowerIcon fontSize="small" /> },
  { label: 'Users', path: '/users', icon: <ManageAccountsIcon fontSize="small" />, adminOnly: true },
  { label: 'Local Setup', path: '/settings/local', icon: <HomeWorkIcon fontSize="small" />, adminOnly: true },
  { label: 'Manual Tasks', path: '/manual-tasks', icon: <TaskAltIcon fontSize="small" /> },
  { label: 'Numbers', path: '/numbers', icon: <PinIcon fontSize="small" /> }
]

function pageTitle(pathname: string): string {
  if (pathname.startsWith('/workflows/')) return 'Workflow Detail'
  if (pathname.startsWith('/messages/') && pathname.endsWith('/xml')) return 'Raw XML'
  if (pathname.startsWith('/messages')) return 'Log Viewer'
  return navItems.find(item => item.path === pathname)?.label ?? 'Dashboard'
}

export default function Layout({ children }: { children: ReactNode }) {
  const location = useLocation()
  const navigate = useNavigate()
  const busy = useIsFetching() + useIsMutating() > 0
  const { user, logout, hasRole } = useAuth()
  const isAdmin = hasRole('ADMIN')

  const onLogout = () => {
    logout()
    navigate('/login', { replace: true })
  }

  return (
    <Box sx={{ display: 'flex', bgcolor: 'grey.50', minHeight: '100vh' }}>
      <AppBar position="fixed" sx={{ zIndex: theme => theme.zIndex.drawer + 1 }}>
        <Toolbar>
          <Typography variant="h6" noWrap component="div" sx={{ flexGrow: 1 }}>
            {pageTitle(location.pathname)}
          </Typography>
          <Typography variant="subtitle2" color="text.secondary" noWrap sx={{ mr: 2 }}>
            Number Portability Gateway
          </Typography>
          <Chip label="v0.1.0" size="small" variant="outlined" sx={{ color: 'text.secondary', borderColor: 'divider' }} />
          {user && (
            <>
              <Typography variant="subtitle2" noWrap sx={{ ml: 2 }}>
                {user.displayName}
              </Typography>
              <Chip
                label={user.roles[0]}
                size="small"
                color="primary"
                variant="outlined"
                sx={{ ml: 1 }}
              />
              <IconButton size="small" onClick={onLogout} aria-label="logout" sx={{ ml: 1 }}>
                <LogoutIcon fontSize="small" />
              </IconButton>
            </>
          )}
        </Toolbar>
        <LinearProgress
          sx={{
            position: 'absolute', left: 0, right: 0, bottom: 0, height: 3,
            visibility: busy ? 'visible' : 'hidden',
            bgcolor: 'transparent'
          }}
        />
      </AppBar>
      <Drawer
        variant="permanent"
        sx={{
          width: DRAWER_WIDTH,
          flexShrink: 0,
          '& .MuiDrawer-paper': { width: DRAWER_WIDTH, boxSizing: 'border-box' }
        }}
      >
        <Toolbar />
        <Box sx={{ px: 2.5, py: 3 }}>
          <Typography
            sx={{ fontFamily: "'Archivo', sans-serif", fontWeight: 700, fontSize: 24, color: 'primary.main', lineHeight: 1 }}
          >
            NPG
          </Typography>
          <Typography variant="caption" color="text.secondary">
            Number Portability Gateway
          </Typography>
        </Box>
        <List sx={{ px: 1.5 }}>
          {navItems.filter(item => !item.adminOnly || isAdmin).map(item => {
            const selected = location.pathname === item.path
            return (
              <ListItemButton
                key={item.path}
                component={RouterLink}
                to={item.path}
                selected={selected}
                sx={{
                  borderRadius: 2,
                  mb: 0.5,
                  color: selected ? 'primary.darker' : 'text.secondary',
                  bgcolor: selected ? 'primary.lighter' : 'transparent',
                  '&:hover': { bgcolor: selected ? 'primary.lighter' : 'grey.100' },
                  '&.Mui-selected': {
                    color: 'primary.darker',
                    bgcolor: 'primary.lighter',
                    '&:hover': { bgcolor: 'primary.lighter' }
                  }
                }}
              >
                <ListItemIcon sx={{ minWidth: 36, color: 'inherit' }}>{item.icon}</ListItemIcon>
                <ListItemText
                  primary={item.label}
                  primaryTypographyProps={{ variant: 'subtitle2', color: 'inherit' }}
                />
              </ListItemButton>
            )
          })}
        </List>
      </Drawer>
      <Box component="main" sx={{ flexGrow: 1, py: 4 }}>
        <Toolbar />
        <Container maxWidth="lg">{children}</Container>
      </Box>
    </Box>
  )
}
