import { ReactNode, Suspense, lazy } from 'react'
import { Routes, Route, Navigate, useLocation } from 'react-router-dom'
import Layout from './components/Layout'
import Loader from './components/Loader'
import { useAuth } from './auth/AuthContext'
import Login from './pages/Login'
import LandingPage from './pages/LandingPage'
import AnalyticsConsent from './analytics/AnalyticsConsent'

// pages load on demand: the console's first paint only needs the shell
const Dashboard = lazy(() => import('./pages/Dashboard'))
const WorkflowSearch = lazy(() => import('./pages/WorkflowSearch'))
const WorkflowDetail = lazy(() => import('./pages/WorkflowDetail'))
const StartPortIn = lazy(() => import('./pages/StartPortIn'))
const ProcessesPage = lazy(() => import('./pages/ProcessesPage'))
const QueuesPage = lazy(() => import('./pages/QueuesPage'))
const LogViewer = lazy(() => import('./pages/LogViewer'))
const XmlViewer = lazy(() => import('./pages/XmlViewer'))
const OperatorList = lazy(() => import('./pages/OperatorList'))
const ManualTaskQueue = lazy(() => import('./pages/ManualTaskQueue'))
const NumberLookup = lazy(() => import('./pages/NumberLookup'))
const NumberRangesPage = lazy(() => import('./pages/NumberRangesPage'))
const NumberingPlanPage = lazy(() => import('./pages/NumberingPlanPage'))
const UsersPage = lazy(() => import('./pages/UsersPage'))
const LocalSetupPage = lazy(() => import('./pages/LocalSetupPage'))
const FeaturesPage = lazy(() => import('./pages/FeaturesPage'))
const CompatibilityPage = lazy(() => import('./pages/CompatibilityPage'))
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

function RequireOperator({ children }: { children: ReactNode }) {
  const { hasRole } = useAuth()
  if (!hasRole('ADMIN', 'MANAGER')) return <Navigate to="/" replace />
  return <>{children}</>
}

function App() {
  return (
    <>
    <AnalyticsConsent />
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/landing" element={<LandingPage />} />
      <Route path="/features" element={<Suspense fallback={<Loader />}><FeaturesPage /></Suspense>} />
      <Route path="/compatibility" element={<Suspense fallback={<Loader />}><CompatibilityPage /></Suspense>} />
      <Route
        path="*"
        element={
          <RequireAuth>
            <Layout>
              <Suspense fallback={<Loader />}>
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
                <Route path="/porting/new" element={<RequireOperator><StartPortIn /></RequireOperator>} />
                <Route path="/processes" element={<RequireOperator><ProcessesPage /></RequireOperator>} />
                <Route path="/queues" element={<QueuesPage />} />
                <Route path="/number-ranges" element={<RequireAdmin><NumberRangesPage /></RequireAdmin>} />
                <Route path="/numbering-plan" element={<NumberingPlanPage />} />
              </Routes>
              </Suspense>
            </Layout>
          </RequireAuth>
        }
      />
    </Routes>
    </>
  )
}

export default App
