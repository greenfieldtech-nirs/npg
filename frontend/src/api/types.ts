export interface Workflow {
  id: number;
  workflowUuid: string;
  workflowType: string;
  localRole: string;
  counterpartyCode?: string | null;
  contextJson?: string | Record<string, unknown> | null;
  machineStates?: string[];
  finished?: boolean;
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
  assignedTo?: string | null;
  resolution?: string | null;
  workflowUuid?: string | null;
  requestId?: string | null;
  phoneNumber?: string | null;
  notes?: { note: string; createdBy: string | null; createdAt: string }[];
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
  timersDueSoon: number;
  workflowsInManualReview: number;
  outboxNeedsAttention: number;
  inboxFailed: number;
  publicationBacklog: number;
  operatorsNeedingConfiguration: number;
}

export interface OutboxItem {
  outboxUuid: string; status: string; itemType: string; attempts: number; maxAttempts: number; dueAt: string;
  lastErrorCode: string | null; lastErrorMessage: string | null; updatedAt: string; messageUuid: string;
  requestId: string | null; transactionId: string | null; destination: string | null; workflowUuid: string | null;
}

export interface InboxItem {
  inboxUuid: string; status: string; itemType: string; attempts: number; maxAttempts: number; dueAt: string;
  lastErrorCode: string | null; lastErrorMessage: string | null; updatedAt: string; messageUuid: string;
  requestId: string | null; transactionId: string | null; sender: string | null;
}

export interface TimerItem {
  timerUuid: string; timerName: string; timerType: string; status: string; dueAt: string; firedAt: string | null;
  cancelledAt: string | null; attempts: number; workflowUuid?: string | null; requestId?: string | null;
  phoneNumber?: string | null; currentState?: string | null;
}

export interface WorkflowMessage {
  messageUuid: string; messageType: string; direction: string; requestId: string | null; transactionId: string | null;
  correlationId: string | null; technicalStatus: string; ackStatus: string | null; nackCode: string | null;
  createdAt: string; sender: string | null; receiver: string | null; outboxStatus: string | null; outboxUuid: string | null;
}

export interface PublicationRecipient {
  purpose: string; status: string; responseStatus: string | null; sentAt: string | null; responseAt: string | null;
  attempts: number; operatorCode: string;
}

export interface NumberRange {
  id: number; prefix: string; description: string | null; operatorCode: string; createdAt: string;
}
