import axios from 'axios'
import {
  DashboardSummary, Workflow, WorkflowEvent, Message, MessagePair, Operator, OperatorEndpoint, ManualTask, NumberRecord,
  OwnershipHistoryEntry, SystemUser, OutboxItem, InboxItem, TimerItem, WorkflowMessage, PublicationRecipient, NumberRange,
  NumberingPlanRange, NumberingPlanSummary, NumberingPlanImportResult, NumberingPlanLookup, PlatformHealth
} from './types'

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
  getSummary: () => api.get<DashboardSummary>('/dashboard').then(r => r.data),
  platformHealth: () => api.get<PlatformHealth>('/platform-health').then(r => r.data)
}

/** Human-readable message from an API error ({"error": "..."} bodies), for alerts. */
export function apiErrorMessage(err: unknown, fallback = 'Request failed'): string {
  if (axios.isAxiosError(err)) return err.response?.data?.error ?? err.message
  return err instanceof Error ? err.message : fallback
}

export const workflowApi = {
  search: (params?: {
    phoneNumber?: string; state?: string; requestId?: string; workflowType?: string; localRole?: string
    operatorCode?: string; page?: number; size?: number
  }) =>
    api.get<Workflow[]>('/workflows', { params }).then(r => r.data),
  get: (uuid: string) => api.get<Workflow>(`/workflows/${uuid}`).then(r => r.data),
  getEvents: (uuid: string) => api.get<WorkflowEvent[]>(`/workflows/${uuid}/events`).then(r => r.data),
  manualTransition: (uuid: string, toState: string, reason: string) =>
    api.post(`/workflows/${uuid}/manual-transition`, { toState, reason }),
  timers: (uuid: string) => api.get<TimerItem[]>(`/workflows/${uuid}/timers`).then(r => r.data),
  messages: (uuid: string) => api.get<WorkflowMessage[]>(`/workflows/${uuid}/messages`).then(r => r.data),
  publication: (uuid: string) => api.get<PublicationRecipient[]>(`/workflows/${uuid}/publication`).then(r => r.data),
  updatePortTime: (uuid: string, portTime: string) => api.post(`/workflows/${uuid}/port-time`, { portTime }),
  cancel: (uuid: string, reason: string) => api.post(`/workflows/${uuid}/cancel`, { reason }),
  cancelPublication: (uuid: string, reason: string) => api.post(`/workflows/${uuid}/cancel-publication`, { reason }),
  decide: (uuid: string, approve: boolean, reasonCode?: string, reasonText?: string) =>
    api.post(`/workflows/${uuid}/decision`, { approve, reasonCode, reasonText })
}

export interface PortInRequest {
  phoneNumber?: string; phoneNumbers?: string[]; numberType: string; donorOperatorCode: string
  identificationValue?: string; requestedPortTime?: string
}

export const portingApi = {
  start: (request: PortInRequest) => api.post<Record<string, unknown>>('/porting', request).then(r => r.data)
}

export const processApi = {
  startReturn: (phoneNumber: string) => api.post<{ workflowUuid: string }>(`/numbers/${phoneNumber}/return`).then(r => r.data),
  inquire: (phoneNumber: string, operatorCode: string) =>
    api.post<{ workflowUuid: string }>('/inquiries', { phoneNumber, operatorCode }).then(r => r.data),
  announce: (down: boolean, reason: string, expectedDuration?: string) =>
    api.post<{ workflowUuid: string }>(`/maintenance/${down ? 'down' : 'up'}`, { reason, expectedDuration }).then(r => r.data),
  requestSync: (operatorCode: string, syncType = 'FULL') =>
    api.post<{ workflowUuid: string }>('/sync', { operatorCode, syncType }).then(r => r.data)
}

export const queueApi = {
  outbox: (status?: string) => api.get<OutboxItem[]>('/outbox', { params: { status, size: 200 } }).then(r => r.data),
  inbox: (status?: string) => api.get<InboxItem[]>('/inbox', { params: { status, size: 200 } }).then(r => r.data),
  timers: (status?: string) => api.get<TimerItem[]>('/timers', { params: { status, size: 200 } }).then(r => r.data),
  retryOutbox: (uuid: string, reason: string) => api.post(`/outbox/${uuid}/retry`, { reason }),
  markDelivered: (uuid: string, reason: string) => api.post(`/outbox/${uuid}/mark-delivered`, { reason }),
  cancelOutbox: (uuid: string, reason: string) => api.post(`/outbox/${uuid}/cancel`, { reason }),
  retryInbox: (uuid: string, reason: string) => api.post(`/inbox/${uuid}/retry`, { reason }),
  cancelTimer: (uuid: string, reason: string) => api.post(`/timers/${uuid}/cancel`, { reason }),
  fireTimer: (uuid: string, reason: string) => api.post(`/timers/${uuid}/fire-now`, { reason })
}

export const numberRangeApi = {
  list: () => api.get<NumberRange[]>('/number-ranges').then(r => r.data),
  create: (operatorCode: string, prefix: string, description?: string) =>
    api.post('/number-ranges', { operatorCode, prefix, description }),
  remove: (id: number) => api.delete(`/number-ranges/${id}`)
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
  markUp: (code: string, reason: string) =>
    api.post(`/operators/${code}/mark-up`, { reason }),
  create: (data: { operatorCode: string; operatorName: string; licenseType?: string; isLocal?: boolean; reason: string }) =>
    api.post<Operator>('/operators', data).then(r => r.data),
  update: (code: string, data: { operatorName?: string; licenseType?: string; contactName?: string; contactPhone?: string; contactEmail?: string; isActive?: boolean; isLocal?: boolean; numberingPlanName?: string; reason: string }) =>
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
  get: (uuid: string) => api.get<ManualTask>(`/manual-tasks/${uuid}`).then(r => r.data),
  assign: (uuid: string, assignee?: string) => api.post(`/manual-tasks/${uuid}/assign`, { assignee }),
  addNote: (uuid: string, note: string) => api.post(`/manual-tasks/${uuid}/notes`, { note }),
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

export const numberingPlanApi = {
  ranges: (params?: { operator?: string; ndc?: string; numberType?: string; q?: string }) =>
    api.get<NumberingPlanRange[]>('/numbering-plan', { params }).then(r => r.data),
  summary: () => api.get<NumberingPlanSummary>('/numbering-plan/summary').then(r => r.data),
  lookup: (number: string) => api.get<NumberingPlanLookup>('/numbering-plan/lookup', { params: { number } }).then(r => r.data),
  /** Fetches the current plan from data.gov.il on the server. */
  importOfficial: () => api.post<NumberingPlanImportResult>('/numbering-plan/import').then(r => r.data),
  /** Imports a copy of the CSV the admin downloaded themselves. */
  upload: (csv: string) =>
    api.post<NumberingPlanImportResult>('/numbering-plan/import', csv, { headers: { 'Content-Type': 'text/csv' } }).then(r => r.data)
}
