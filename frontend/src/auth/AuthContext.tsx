import { createContext, useCallback, useContext, useEffect, useState, ReactNode } from 'react'
import { authApi } from '../api/client'

export interface AuthUser {
  username: string
  displayName: string
  roles: string[]
}

interface AuthContextValue {
  user: AuthUser | null
  token: string | null
  initializing: boolean
  login: (username: string, password: string) => Promise<void>
  logout: () => void
  hasRole: (...roles: string[]) => boolean
}

const AuthContext = createContext<AuthContextValue | null>(null)

const TOKEN_KEY = 'npg.token'
const USER_KEY = 'npg.user'

function readStored(): { token: string | null; user: AuthUser | null } {
  const token = localStorage.getItem(TOKEN_KEY)
  const raw = localStorage.getItem(USER_KEY)
  let user: AuthUser | null = null
  try {
    user = raw ? (JSON.parse(raw) as AuthUser) : null
  } catch {
    user = null
  }
  return { token, user }
}

// auth helpers live with their provider; fast refresh simply reloads this module
// eslint-disable-next-line react-refresh/only-export-components
export function clearStoredAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState(() => readStored())
  const [initializing, setInitializing] = useState(!!state.token)

  useEffect(() => {
    if (!state.token) return
    authApi.me()
      .then(user => {
        // ponytail: /auth/me omits displayName; keep the stored copy
        setState(s => {
          const merged = { ...s.user, ...user } as AuthUser
          localStorage.setItem(USER_KEY, JSON.stringify(merged))
          return { ...s, user: merged }
        })
      })
      .catch(() => {
        clearStoredAuth()
        setState({ token: null, user: null })
      })
      .finally(() => setInitializing(false))
    // ponytail: run once on mount; login() sets state directly
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const login = useCallback(async (username: string, password: string) => {
    const res = await authApi.login(username, password)
    localStorage.setItem(TOKEN_KEY, res.token)
    localStorage.setItem(USER_KEY, JSON.stringify(res.user))
    setState({ token: res.token, user: res.user })
  }, [])

  const logout = useCallback(() => {
    clearStoredAuth()
    setState({ token: null, user: null })
  }, [])

  const hasRole = useCallback(
    (...roles: string[]) => !!state.user && roles.some(r => state.user!.roles.includes(r)),
    [state.user]
  )

  return (
    <AuthContext.Provider
      value={{ user: state.user, token: state.token, initializing, login, logout, hasRole }}
    >
      {children}
    </AuthContext.Provider>
  )
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
