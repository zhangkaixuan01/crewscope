/** Organization and Team jointly form every collaboration cache boundary. */
export interface TeamOpsScope {
  organizationId: string
  teamId: string
}

export interface CursorPage<T> {
  items: T[]
  nextCursor: string | null
}

export interface CommandReceipt {
  commandId: string
  domainEventId: string
  committedVersion: number
  correlationId: string
}

export interface Etagged<T> {
  value: T
  etag: string
}

export interface ActivityFilter {
  workItemId?: string | null
  categories?: string[]
  eventTypes?: string[]
  actorPrincipalIds?: string[]
}

export interface ActivityItem {
  eventId: string
  domainEventId: string
  teamSequence: number
  eventType: string
  category: string
  visibility: string
  subject: { type: string, id: string }
  actor: { type: string, principalId: string | null }
  references: Array<{ type: string, id: string }>
  occurredAt: string
  payload: { schemaName: string, schemaVersion: number, values: Record<string, string> }
}

export interface ActivityPage extends CursorPage<ActivityItem> {
  hasMore: boolean
}

export interface ActivitySnapshot extends ActivityPage {
  snapshotCursor: string | null
}

export interface WorkItemActivityRoute {
  projectId: string
  workItemId: string
}

export interface InboxFilter {
  itemTypes?: InboxItemType[]
  sourceStatuses?: InboxSourceStatus[]
  dispositionStatuses?: InboxDispositionStatus[]
}

export const inboxItemTypes = ['OWNERSHIP', 'EXECUTION', 'REVIEW', 'CONFIRMATION', 'EXCEPTION'] as const
export type InboxItemType = typeof inboxItemTypes[number]
export const inboxPriorities = ['LOW', 'NORMAL', 'HIGH', 'URGENT'] as const
export type InboxPriority = typeof inboxPriorities[number]
export const inboxSourceStatuses = ['OPEN', 'CLOSED'] as const
export type InboxSourceStatus = typeof inboxSourceStatuses[number]
export const inboxDispositionStatuses = ['UNREAD', 'READ', 'ACTED', 'ARCHIVED'] as const
export type InboxDispositionStatus = typeof inboxDispositionStatuses[number]
export const inboxSourceTypes = ['RESPONSIBILITY_ASSIGNMENT', 'REVIEW_REQUEST', 'ACTION_CONFIRMATION', 'TASK_EXECUTION', 'ACTION_DELIVERY', 'NOTIFICATION_DELIVERY'] as const
export type InboxSourceType = typeof inboxSourceTypes[number]
export const inboxTargetKinds = ['WORK_ITEM', 'REVIEW', 'ACTION', 'TASK', 'NOTIFICATION'] as const
export type InboxTargetKind = typeof inboxTargetKinds[number]

export interface InboxItem {
  inboxItemId: string
  itemType: InboxItemType
  priority: InboxPriority
  deadline: string | null
  openedAt: string
  sourceStatus: InboxSourceStatus
  closeReason: string | null
  closedAt: string | null
  dispositionStatus: InboxDispositionStatus
  dispositionVersion: number
  etag: string
  source: { type: InboxSourceType, id: string, revision: number }
}

export interface InboxCounts {
  total: number
  unread: number
  byType: Record<string, { total: number, unread: number }>
}

export interface InboxTarget {
  kind: InboxTargetKind
  href: string
}

export interface AuditFilter {
  from?: string | null
  to?: string | null
  categories?: AuditEventCategory[]
  outcomes?: AuditOutcome[]
  initiatorIds?: string[]
  actorIds?: string[]
  agentPrincipalIds?: string[]
  subjectTypes?: string[]
  subjectIds?: string[]
  providerBindingIds?: string[]
  correlationIds?: string[]
}

export const auditEventCategories = [
  'IDENTITY', 'TEAM', 'WORK', 'COLLABORATION', 'EXECUTION', 'AGENT', 'MODEL',
  'REVIEW', 'ACTION', 'PROVIDER', 'NOTIFICATION', 'PROJECTION', 'SECURITY', 'SYSTEM',
] as const
export type AuditEventCategory = typeof auditEventCategories[number]
export const auditOutcomes = ['SUCCEEDED', 'DENIED', 'FAILED'] as const
export type AuditOutcome = typeof auditOutcomes[number]
export const auditRetentionLevels = ['STANDARD', 'EXTENDED', 'LEGAL_HOLD'] as const
export type AuditRetentionLevel = typeof auditRetentionLevels[number]
export const auditActorTypes = ['USER', 'PERSONAL_AGENT', 'TEAM_AGENT', 'SPECIALIST_AGENT', 'SERVICE'] as const
export type AuditActorType = typeof auditActorTypes[number]

export interface AuditEvent {
  eventId: string
  eventType: string
  sourceSchemaVersion: number
  category: AuditEventCategory
  outcome: AuditOutcome
  retentionLevel: AuditRetentionLevel
  occurredAt: string
  identity: {
    initiatorId: string | null
    actorType: AuditActorType
    actorId: string | null
    agentPrincipalId: string | null
  }
  subject: { type: string, id: string }
  provider: {
    providerBindingId: string
    connectionId: string
    externalOperationHash: string | null
  } | null
  correlation: {
    correlationId: string
    causationId: string | null
    domainEventId: string | null
  }
  summary: Record<string, string>
}

export interface AuditExport {
  generatedAt: string
  rowCount: number
  maximumRows: number
  events: AuditEvent[]
}

export const correlationEventSources = ['DOMAIN_EVENT', 'AUDIT'] as const
export type CorrelationEventSource = typeof correlationEventSources[number]
export const correlationObjectTypes = [
  'CONVERSATION', 'WORK_ITEM', 'TASK', 'REVIEW', 'ACTION', 'PULL_REQUEST',
  'ACTIVITY', 'INBOX', 'NOTIFICATION', 'AUDIT',
] as const
export type CorrelationObjectType = typeof correlationObjectTypes[number]

export interface CorrelationReference {
  type: CorrelationObjectType
  id: string
  href: string
}

export interface CorrelationEvent {
  eventId: string
  source: CorrelationEventSource
  eventType: string
  actorType: string
  actorId: string | null
  outcome: string | null
  occurredAt: string
  references: CorrelationReference[]
}

export interface CorrelationObject extends CorrelationReference {
  relatedEventIds: string[]
}

export interface CorrelationGraph {
  correlationId: string
  events: CorrelationEvent[]
  objects: CorrelationObject[]
  hasMore: boolean
  nextCursor: string | null
}

export const larkConnectionStatuses = ['ACTIVE', 'SUSPENDED', 'REVOKED', 'EXPIRED'] as const
export type LarkConnectionStatus = typeof larkConnectionStatuses[number]
export const larkCredentialStatuses = ['ACTIVE', 'ROTATING', 'REVOKED'] as const
export type LarkCredentialStatus = typeof larkCredentialStatuses[number]
export const larkMappingStatuses = ['ACTIVE', 'REVOKED', 'INVALIDATED'] as const
export type LarkMappingStatus = typeof larkMappingStatuses[number]
export const larkMappingTerminalReasons = ['ADMIN_REVOKED', 'MEMBER_LEFT', 'AUTHORIZATION_DRIFT', 'IDENTITY_REPLACED'] as const
export type LarkMappingTerminalReason = typeof larkMappingTerminalReasons[number]
export const larkHealthStatuses = [
  'HEALTHY', 'AUTHORIZATION_UNAVAILABLE', 'AUTHENTICATION_REQUIRED', 'PERMISSION_DENIED',
  'RESOURCE_UNAVAILABLE', 'RATE_LIMITED', 'PROVIDER_UNAVAILABLE', 'INVALID_RESPONSE',
  'IDENTITY_MISMATCH', 'CONNECTION_UNAVAILABLE', 'CREDENTIAL_UNAVAILABLE', 'CANCELLED',
] as const
export type LarkHealthStatus = typeof larkHealthStatuses[number]

export interface LarkConnection {
  connectionId: string
  teamId: string
  providerBindingId: string | null
  providerBindingVersion: number | null
  maskedAppId: string
  status: LarkConnectionStatus
  credentialStatus: LarkCredentialStatus
  expiresAt: string | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface LarkPreflight {
  providerBindingId: string
  version: number
  checkedAt: string
}

export interface LarkHealth {
  status: LarkHealthStatus
  retryable: boolean
  retryAfterSeconds: number | null
  evidenceCode: string
  checkedAt: string
}

export interface LarkMapping {
  mappingId: string
  memberId: string
  providerBindingId: string
  status: LarkMappingStatus
  terminalReason: LarkMappingTerminalReason | null
  verifiedAt: string
  updatedAt: string
  version: number
}

export interface NotificationPreference {
  memberId: string
  enabled: boolean
  enabledItemTypes: InboxItemType[]
  mutedUntil: string | null
  version: number
}

export const notificationTemplateStatuses = ['PUBLISHED', 'RETIRED'] as const
export type NotificationTemplateStatus = typeof notificationTemplateStatuses[number]
export const notificationVariableTypes = ['TEXT', 'TRUSTED_LINK'] as const
export type NotificationVariableType = typeof notificationVariableTypes[number]
export const notificationDeliveryStatuses = [
  'READY', 'RUNNING', 'RETRY_WAIT', 'UNKNOWN', 'RECONCILING', 'SUCCEEDED',
  'FAILED_FINAL', 'INVALIDATED', 'CANCELLED',
] as const
export type NotificationDeliveryStatus = typeof notificationDeliveryStatuses[number]
export const notificationFailureCodes = [
  'RECIPIENT_UNAVAILABLE', 'AUTHORIZATION_REVOKED', 'PROVIDER_REJECTED',
  'RETRY_EXHAUSTED', 'RECONCILIATION_EXHAUSTED',
] as const
export type NotificationFailureCode = typeof notificationFailureCodes[number]

export interface NotificationTemplate {
  ref: { templateId: string, version: number }
  serverTemplateKey: string
  status: NotificationTemplateStatus
  variables: Array<{ name: string, type: NotificationVariableType, maximumLength: number }>
}

export interface NotificationDelivery {
  organizationId: string
  teamId: string
  deliveryId: string
  recipientMemberId: string
  itemType: InboxItemType
  template: { templateId: string, version: number }
  providerBindingId: string
  status: NotificationDeliveryStatus
  attemptCount: number
  failureCode: NotificationFailureCode | null
  evidenceCode: string | null
  redeliveryOf: string | null
  createdAt: string
  updatedAt: string
  version: number
}

export const operationsHealthComponents = ['PROJECTION', 'OUTBOX', 'DEAD_LETTER', 'CURSOR', 'NOTIFICATION'] as const
export type OperationsHealthComponent = typeof operationsHealthComponents[number]
export const operationsHealthLevels = ['HEALTHY', 'DEGRADED', 'ATTENTION_REQUIRED', 'UNAVAILABLE'] as const
export type OperationsHealthLevel = typeof operationsHealthLevels[number]
export const projectionGenerationStatuses = ['BUILDING', 'VALIDATING', 'ACTIVE', 'RETIRED', 'FAILED', 'CANCELLED'] as const
export type ProjectionGenerationStatus = typeof projectionGenerationStatuses[number]
export const projectionRebuildStatuses = ['BUILDING', 'VALIDATING', 'COMPLETED', 'FAILED', 'CANCELLED'] as const
export type ProjectionRebuildStatus = typeof projectionRebuildStatuses[number]
export const operationsRecoveryActions = ['REPLAY_OUTBOX_DEAD_LETTER', 'REPLAY_PROJECTION_DEAD_LETTER', 'RETRY_NOTIFICATION_DELIVERY'] as const
export type OperationsRecoveryAction = typeof operationsRecoveryActions[number]

export interface ComponentHealth {
  component: OperationsHealthComponent
  health: OperationsHealthLevel
  backlog: number
  inFlight: number
  failures: number
  affected: number
  oldestOutstandingAgeSeconds: number
  stale: boolean
}

export interface OperationsHealthSummary {
  observedAt: string
  health: OperationsHealthLevel
  components: ComponentHealth[]
}

export interface ProjectionDiagnostic {
  projectionName: string
  definitionVersion: number
  activeGeneration: number
  pointerVersion: number
  activeGenerationVersion: number
  shadowGeneration: number | null
  shadowStatus: ProjectionGenerationStatus | null
  shadowGenerationVersion: number | null
  rebuildJobId: string | null
  rebuildJobVersion: number | null
  lagSeconds: number
  gapCount: number
  deadLetterCount: number
  latestFailureCode: string | null
  startConfirmation: string
  validateConfirmation: string | null
  switchConfirmation: string | null
  cancelConfirmation: string | null
  failConfirmation: string | null
}

export type RecoveryCandidate =
  | { type: 'OUTBOX_DEAD_LETTER', action: 'REPLAY_OUTBOX_DEAD_LETTER', outboxEventId: string, domainEventId: string, expectedVersion: number, referenceHash: string, confirmation: string }
  | { type: 'PROJECTION_DEAD_LETTER', action: 'REPLAY_PROJECTION_DEAD_LETTER', projectionName: string, generation: number, deadLetterId: string, domainEventId: string, expectedGenerationVersion: number, referenceHash: string, confirmation: string }
  | { type: 'NOTIFICATION_DELIVERY', action: 'RETRY_NOTIFICATION_DELIVERY', deliveryId: string, expectedVersion: number, referenceHash: string, confirmation: string }

export interface AdministratorDiagnostics {
  summary: OperationsHealthSummary
  projections: ProjectionDiagnostic[]
  recoveryCandidates: RecoveryCandidate[]
}

export interface RecoveryReceipt {
  commandId: string
  action: OperationsRecoveryAction
  targetReferenceHash: string
  status: 'SCHEDULED'
  acceptedAt: string
}

export interface ProjectionCommandReceipt {
  commandId: string
  projectionName: string
  generation: number
  rebuildJobId: string
  generationStatus: ProjectionGenerationStatus
  rebuildStatus: ProjectionRebuildStatus
  generationVersion: number
  rebuildJobVersion: number
  pointerVersion: number | null
}

export type ProjectionCommand =
  | { operation: 'start', projectionName: string, body: { expectedDefinitionVersion: number, expectedPointerVersion: number, confirmation: string } }
  | { operation: 'retry', projectionName: string, rebuildJobId: string, body: { expectedRetryOfJobVersion: number, expectedDefinitionVersion: number, expectedPointerVersion: number, confirmation: string } }
  | { operation: 'validate', projectionName: string, generation: number, body: { expectedDefinitionVersion: number, rebuildJobId: string, expectedGenerationVersion: number, expectedJobVersion: number, confirmation: string } }
  | { operation: 'switch', projectionName: string, generation: number, body: { expectedDefinitionVersion: number, previousActiveGeneration: number, rebuildJobId: string, expectedPointerVersion: number, expectedPreviousGenerationVersion: number, expectedTargetGenerationVersion: number, expectedJobVersion: number, confirmation: string } }
  | { operation: 'cancel', projectionName: string, generation: number, rebuildJobId: string, body: { expectedGenerationVersion: number, expectedJobVersion: number, confirmation: string } }
  | { operation: 'fail', projectionName: string, generation: number, rebuildJobId: string, body: { expectedGenerationVersion: number, expectedJobVersion: number, failureCode: string, confirmation: string } }
