import axios from 'axios'
import { DashboardSummary, Workflow, WorkflowEvent, Message, MessagePair, Operator, OperatorEndpoint, ManualTask, NumberRecord, OwnershipHistoryEntry, SystemUser } from './types'

const api = axios.create({
  baseURL: '/api',
  headers: {
    'Content-Type': 'application/json'
  }
})

api.interceptors.request.use(config => {
  const token = localStorage.getItem('npg.token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

api.interceptors.response.use(
  res => res,
  err => {
    if (axios.isAxiosError(err) && err.response?.status === 401) {
      localStorage.removeItem('npg.token')
      localStorage.removeItem('npg.user')
      if (window.location.pathname !== '/login') window.location.assign('/login')
    }
    return Promise.reject(err)
  }
)

export interface LoginResponse {
  token: string
  user: { username: string; displayName: string; roles: string[] }
}

export const authApi = {
  // ponytail: bare axios so a 401 on bad credentials doesn't hit the redirect interceptor
  login: (username: string, password: string) =>
    axios.post<LoginResponse>('/api/auth/login', { username, password }).then(r => r.data),
  me: () => api.get<LoginResponse['user']>('/auth/me').then(r => r.data)
}

export const dashboardApi = {
  getSummary: () => api.get<DashboardSummary>('/dashboard').then(r => r.data)
}

export const workflowApi = {
  search: (params?: { phoneNumber?: string; state?: string; page?: number; size?: number }) =>
    api.get<Workflow[]>('/workflows', { params }).then(r => r.data),
  get: (uuid: string) => api.get<Workflow>(`/workflows/${uuid}`).then(r => r.data),
  getEvents: (uuid: string) => api.get<WorkflowEvent[]>(`/workflows/${uuid}/events`).then(r => r.data),
  manualTransition: (uuid: string, toState: string, reason: string) =>
    api.post(`/workflows/${uuid}/manual-transition`, { toState, reason })
}

export const messageApi = {
  search: (params?: {
    requestId?: string
    messageType?: string
    direction?: string
    technicalStatus?: string
    dateFrom?: string
    dateTo?: string
    page?: number
    size?: number
  }) => api.get<Message[]>('/messages', { params }).then(r => r.data),
  get: (uuid: string) => api.get<Message>(`/messages/${uuid}`).then(r => r.data),
  getPair: (uuid: string) => api.get<MessagePair>(`/messages/${uuid}/pair`).then(r => r.data),
  getXml: (uuid: string) => api.get<string>(`/messages/${uuid}/xml`, { responseType: 'text' }).then(r => r.data)
}

export const operatorApi = {
  list: () => api.get<Operator[]>('/operators').then(r => r.data),
  get: (code: string) => api.get<Operator>(`/operators/${code}`).then(r => r.data),
  markDown: (code: string, reason: string) =>
    api.post(`/operators/${code}/mark-down`, { reason }),
  create: (data: { operatorCode: string; operatorName: string; licenseType?: string; isLocal?: boolean; reason: string }) =>
    api.post<Operator>('/operators', data).then(r => r.data),
  update: (code: string, data: { operatorName?: string; licenseType?: string; contactName?: string; contactPhone?: string; contactEmail?: string; isActive?: boolean; isLocal?: boolean; reason: string }) =>
    api.put<Operator>(`/operators/${code}`, data).then(r => r.data)
}

export const endpointApi = {
  list: (code: string) => api.get<OperatorEndpoint[]>(`/operators/${code}/endpoints`).then(r => r.data),
  create: (code: string, data: Partial<OperatorEndpoint> & { endpointType: string; endpointUrl: string }) =>
    api.post<OperatorEndpoint>(`/operators/${code}/endpoints`, data).then(r => r.data),
  remove: (code: string, id: number) =>
    api.delete(`/operators/${code}/endpoints/${id}`)
}

export const manualTaskApi = {
  list: (status?: string) => api.get<ManualTask[]>('/manual-tasks', { params: { status } }).then(r => r.data),
  resolve: (uuid: string, resolution: string) =>
    api.post(`/manual-tasks/${uuid}/resolve`, { resolution })
}

export const userApi = {
  list: () => api.get<SystemUser[]>('/users').then(r => r.data),
  create: (data: { username: string; password: string; roles: string[]; displayName?: string; email?: string }) =>
    api.post<SystemUser>('/users', data).then(r => r.data),
  update: (username: string, data: Partial<{ displayName: string; email: string; isActive: boolean; password: string; roles: string[] }>) =>
    api.put<SystemUser>(`/users/${username}`, data).then(r => r.data)
}

export const settingsApi = {
  local: () => api.get<{ publicBaseUrl: string }>('/settings/local').then(r => r.data)
}

export const numberApi = {
  get: (phoneNumber: string) => api.get<NumberRecord>(`/numbers/${phoneNumber}`).then(r => r.data),
  getHistory: (phoneNumber: string) => api.get<OwnershipHistoryEntry[]>(`/numbers/${phoneNumber}/history`).then(r => r.data),
  searchPrefix: (prefix: string) => api.get<NumberRecord[]>('/numbers', { params: { prefix } }).then(r => r.data)
}
