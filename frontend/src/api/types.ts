export interface Workflow {
  id: number;
  workflowUuid: string;
  workflowType: string;
  requestId: string;
  phoneNumber: string;
  customerBatchId: string | null;
  recipientOperatorId: number | null;
  donorOperatorId: number | null;
  originalOperatorId: number | null;
  currentOperatorId: number | null;
  currentState: string;
  requestedPortTime: string | null;
  approvedPortTime: string | null;
  executedAt: string | null;
  completedAt: string | null;
  cancelledAt: string | null;
  failedAt: string | null;
  failureCode: string | null;
  failureReason: string | null;
  createdBy: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface WorkflowEvent {
  id: number;
  eventType: string;
  fromState: string | null;
  toState: string | null;
  messageId: number | null;
  detailsJson: string | null;
  createdBy: string | null;
  createdAt: string;
}

export interface Message {
  id: number;
  messageUuid: string;
  messageType: string;
  direction: string;
  requestId: string | null;
  transactionId: string | null;
  correlationId: string | null;
  technicalStatus: string;
  createdAt: string;
  phoneNumber?: string | null;
  donorOperatorCode?: string | null;
  donorOperatorName?: string | null;
}

export interface MessagePair {
  request: Message | null
  response: Message | null
}

export interface Operator {
  id: number;
  operatorCode: string;
  operatorName: string;
  availabilityStatus: string;
  licenseType?: string;
  contactName?: string;
  contactPhone?: string;
  contactEmail?: string;
  isActive?: boolean;
  isLocal?: boolean;
  requiresConfiguration?: boolean;
}

export interface OperatorEndpoint {
  id: number
  operatorId: number
  endpointType: string
  endpointUrl: string
  protocolVersion: string
  authMode: string
  tlsSubject: string | null
  ipAllowlist: string | null
  isActive: boolean
}

export interface NumberRecord {
  id: number
  phoneNumber: string
  numberType: string
  status: string
  currentOperatorId: number | null
  previousOperatorId: number | null
  currentOperatorCode: string | null
  lastPortedAt: string | null
  lastReturnedAt: string | null
  lastPublishedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface OwnershipHistoryEntry {
  id: number
  phoneNumber: string
  changeReason: string
  previousOperatorCode: string | null
  newOperatorCode: string | null
  effectiveAt: string
}

export interface ManualTask {
  id: number;
  taskUuid: string;
  taskType: string;
  severity: string;
  status: string;
  description: string;
  createdAt: string;
}

export interface SystemUser {
  id: number
  username: string
  displayName: string | null
  email: string | null
  isActive: boolean
  roles: string[]
  createdAt: string
  updatedAt: string
}

export interface DashboardSummary {
  activeWorkflows: number;
  inboxPending: number;
  outboxPending: number;
  openManualTasks: number;
  failedWorkflows: number;
  operatorsDown: number;
  timersDue: number;
}
