import { ReactNode } from 'react'
import { Routes, Route, Navigate, useLocation } from 'react-router-dom'
import Layout from './components/Layout'
import Loader from './components/Loader'
import { useAuth } from './auth/AuthContext'
import Dashboard from './pages/Dashboard'
import WorkflowSearch from './pages/WorkflowSearch'
import WorkflowDetail from './pages/WorkflowDetail'
import LogViewer from './pages/LogViewer'
import XmlViewer from './pages/XmlViewer'
import OperatorList from './pages/OperatorList'
import ManualTaskQueue from './pages/ManualTaskQueue'
import NumberLookup from './pages/NumberLookup'
import UsersPage from './pages/UsersPage'
import LocalSetupPage from './pages/LocalSetupPage'
import Login from './pages/Login'
import LandingPage from './pages/LandingPage'
import { guestPage } from './config/appConfig'

function RequireAuth({ children }: { children: ReactNode }) {
  const { token, initializing } = useAuth()
  const location = useLocation()
  if (initializing) return <Loader />
  if (!token) {
    const destination = guestPage === 'landing' ? '/landing' : '/login'
    return <Navigate to={destination} state={{ from: location.pathname }} replace />
  }
  return <>{children}</>
}

function RequireAdmin({ children }: { children: ReactNode }) {
  const { hasRole } = useAuth()
  if (!hasRole('ADMIN')) return <Navigate to="/" replace />
  return <>{children}</>
}

function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/landing" element={<LandingPage />} />
      <Route
        path="*"
        element={
          <RequireAuth>
            <Layout>
              <Routes>
                <Route path="/" element={<Dashboard />} />
                <Route path="/workflows" element={<WorkflowSearch />} />
                <Route path="/workflows/:uuid" element={<WorkflowDetail />} />
                <Route path="/messages" element={<LogViewer />} />
                <Route path="/messages/:uuid" element={<LogViewer />} />
                <Route path="/messages/:uuid/xml" element={<XmlViewer />} />
                <Route path="/operators" element={<OperatorList />} />
                <Route path="/users" element={<RequireAdmin><UsersPage /></RequireAdmin>} />
                <Route path="/settings/local" element={<RequireAdmin><LocalSetupPage /></RequireAdmin>} />
                <Route path="/manual-tasks" element={<ManualTaskQueue />} />
                <Route path="/numbers" element={<NumberLookup />} />
              </Routes>
            </Layout>
          </RequireAuth>
        }
      />
    </Routes>
  )
}

export default App
