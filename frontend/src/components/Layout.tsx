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
import { NpgMark, NpgWordmark } from './brand/NpgMark'
import ColorModeToggle from './ColorModeToggle'
import { brand, MONO_FONT } from '../theme/brand'
import DashboardIcon from '@mui/icons-material/Dashboard'
import AccountTreeIcon from '@mui/icons-material/AccountTree'
import MailOutlineIcon from '@mui/icons-material/MailOutline'
import CellTowerIcon from '@mui/icons-material/CellTower'
import TaskAltIcon from '@mui/icons-material/TaskAlt'
import PinIcon from '@mui/icons-material/Pin'
import ManageAccountsIcon from '@mui/icons-material/ManageAccounts'
import HomeWorkIcon from '@mui/icons-material/HomeWork'
import SwapHorizIcon from '@mui/icons-material/SwapHoriz'
import HubIcon from '@mui/icons-material/Hub'
import QueueIcon from '@mui/icons-material/Queue'
import ViewListIcon from '@mui/icons-material/ViewList'
import MapIcon from '@mui/icons-material/Map'

const DRAWER_WIDTH = 256

const navItems: { label: string; path: string; icon: JSX.Element; adminOnly?: boolean; operatorOnly?: boolean }[] = [
  { label: 'Dashboard', path: '/', icon: <DashboardIcon fontSize="small" /> },
  { label: 'Workflows', path: '/workflows', icon: <AccountTreeIcon fontSize="small" /> },
  { label: 'Start Port-In', path: '/porting/new', icon: <SwapHorizIcon fontSize="small" />, operatorOnly: true },
  { label: 'Processes', path: '/processes', icon: <HubIcon fontSize="small" />, operatorOnly: true },
  { label: 'Queues & Timers', path: '/queues', icon: <QueueIcon fontSize="small" /> },
  { label: 'Log Viewer', path: '/messages', icon: <MailOutlineIcon fontSize="small" /> },
  { label: 'Operators', path: '/operators', icon: <CellTowerIcon fontSize="small" /> },
  { label: 'Users', path: '/users', icon: <ManageAccountsIcon fontSize="small" />, adminOnly: true },
  { label: 'Local Setup', path: '/settings/local', icon: <HomeWorkIcon fontSize="small" />, adminOnly: true },
  { label: 'Manual Tasks', path: '/manual-tasks', icon: <TaskAltIcon fontSize="small" /> },
  { label: 'Numbers', path: '/numbers', icon: <PinIcon fontSize="small" /> },
  { label: 'Number Ranges', path: '/number-ranges', icon: <ViewListIcon fontSize="small" />, adminOnly: true },
  { label: 'Israel Numbering Plan', path: '/numbering-plan', icon: <MapIcon fontSize="small" /> }
]

export default function Layout({ children }: { children: ReactNode }) {
  const location = useLocation()
  const navigate = useNavigate()
  const busy = useIsFetching() + useIsMutating() > 0
  const { user, logout, hasRole } = useAuth()
  const isAdmin = hasRole('ADMIN')
  const isOperator = hasRole('ADMIN', 'MANAGER')

  const onLogout = () => {
    logout()
    navigate('/login', { replace: true })
  }

  return (
    <Box sx={{ display: 'flex', bgcolor: 'background.default', minHeight: '100vh' }}>
      <AppBar position="fixed" sx={{ zIndex: theme => theme.zIndex.drawer + 1 }}>
        <Toolbar>
          <Box component={RouterLink} to="/" aria-label="NPG dashboard"
            sx={{ display: 'flex', alignItems: 'center', gap: 1.25, textDecoration: 'none', mr: 'auto' }}>
            <NpgMark size={40} surface="dark" />
            <NpgWordmark size={22} surface="dark" />
          </Box>
          <Box sx={{ display: { xs: 'none', md: 'flex' }, fontFamily: MONO_FONT, fontSize: 12, borderRadius: '4px', overflow: 'hidden' }}>
            <Box sx={{ px: 1, py: 0.5, bgcolor: brand.ink2, color: brand.paper }}>npg</Box>
            <Box sx={{ px: 1, py: 0.5, bgcolor: brand.deepSignal, color: '#FFFFFF' }}>v0.1.0</Box>
          </Box>
          <ColorModeToggle sx={{ ml: 2, color: brand.mist, '&:hover': { color: brand.paper } }} />
          {user && (
            <>
              <Typography variant="subtitle2" noWrap sx={{ ml: 1.5, color: brand.paper }}>
                {user.displayName}
              </Typography>
              <Chip
                label={user.roles[0]}
                size="small"
                variant="outlined"
                sx={{ ml: 1, fontFamily: MONO_FONT, fontSize: 11, letterSpacing: '1px', color: brand.signal, borderColor: brand.slate }}
              />
              <IconButton size="small" onClick={onLogout} aria-label="logout" sx={{ ml: 1, color: brand.mist, '&:hover': { color: brand.paper } }}>
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
        <List sx={{ px: 1.5, pt: 2 }}>
          {navItems.filter(item => (!item.adminOnly || isAdmin) && (!item.operatorOnly || isOperator)).map(item => {
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
                  '&:hover': { bgcolor: selected ? 'primary.lighter' : 'action.hover' },
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
      <Box component="main" sx={{ flexGrow: 1, minWidth: 0, py: 4 }}>
        <Toolbar />
        <Container maxWidth="lg">{children}</Container>
      </Box>
    </Box>
  )
}
