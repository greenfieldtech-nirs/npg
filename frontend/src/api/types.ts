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
  /** Its "allocated to" name in the national numbering plan, used to verify number ownership */
  numberingPlanName?: string | null;
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

/** One block of the Ministry of Communications numbering plan (the original, non-ported allocation). */
export interface NumberingPlanRange {
  id: number; ndc: string; e164Pattern: string; nationalPattern: string; nsnPrefix: string; nsnLength: number;
  rangeSize: number; allocatedTo: string; notes: string | null; numberType: string; area: string | null;
  exampleInternational: string | null; exampleNational: string | null; libphonenumberValid: boolean;
}

export interface NumberingPlanOperatorSummary {
  operator: string; ranges: number; numbers: number; numberTypes: string | null; ndcs: string | null;
}

export interface NumberingPlanSummary {
  totals: { ranges: number; numbers: number; operators: number; ndcs: number; notValidInLibphonenumber: number };
  byOperator: NumberingPlanOperatorSummary[];
  byNdc: { ndc: string; ranges: number; numbers: number; operators: number; numberTypes: string | null; area: string | null }[];
  byType: { numberType: string; ranges: number; numbers: number }[];
  lastImport: {
    source: string; sha256: string; rangeCount: number; operatorCount: number; warningsJson: string | null;
    importedBy: string | null; importedAt: string;
  } | null;
  source: string;
}

export interface NumberingPlanImportResult {
  ranges: number; operators: number; sha256: string; source: string; warnings: string[]; rangesNotValidInLibphonenumber: number;
}

export interface NumberingPlanLookup {
  input: string; e164: string; international: string; national: string; valid: boolean; numberType: string; area: string | null;
  range: NumberingPlanRange | null;
  porting: { status: string; lastPortedAt: string | null; currentOperator: string | null; currentOperatorName: string | null } | null;
}

export type HealthStatus = 'UP' | 'DOWN' | 'DEGRADED' | 'STALE' | 'MISSING' | 'DISABLED'

/** GET /api/platform-health */
export interface PlatformHealth {
  status: 'UP' | 'DEGRADED' | 'DOWN'
  stores: { name: string; critical: boolean; status: HealthStatus; latencyMs?: number; error?: string }[]
  roles: {
    role: string
    status: HealthStatus
    instances: { hostname: string | null; role: string; lastSeenSecondsAgo: number; startedAt: string }[]
  }[]
  queues: {
    inbox: { due: number; oldestDueSeconds: number }
    outbox: { due: number; oldestDueSeconds: number }
    timersOverdue: number
    oldestOverdueSeconds: number
    outboxNeedingReview: number
  } | null
  peers: { active: number; down: number } | null
  checkedAt: string
}
