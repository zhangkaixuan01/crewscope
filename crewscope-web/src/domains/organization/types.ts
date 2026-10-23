/**
 * M12-F01 organization domain types, mirrored field-by-field from the A01 contract
 * (docs/api/M12-Organization管理API契约.md) and the server response records. The enums
 * are closed unions on purpose: a backend that adds a value fails mapping loudly here
 * instead of rendering a raw enum key.
 */

export const organizationRoles = ['ORG_OWNER', 'ORG_ADMIN', 'ORG_AUDITOR'] as const
export type OrganizationRole = typeof organizationRoles[number]

export const organizationStatuses = ['ACTIVE'] as const
export type OrganizationStatus = typeof organizationStatuses[number]

export const roleGrantStatuses = ['ACTIVE', 'REVOKED'] as const
export type RoleGrantStatus = typeof roleGrantStatuses[number]

export const exportJobStatuses = ['PENDING', 'RUNNING', 'COMPLETED', 'CANCELLED'] as const
export type ExportJobStatus = typeof exportJobStatuses[number]

export const exportItemStates = ['PENDING', 'DONE', 'SKIPPED'] as const
export type ExportItemState = typeof exportItemStates[number]

export const deletionJobStatuses = ['PENDING_CONFIRMATION', 'CONFIRMED', 'RUNNING', 'COMPLETED', 'CANCELLED'] as const
export type DeletionJobStatus = typeof deletionJobStatuses[number]

export const deletionItemStates = ['PENDING', 'DONE', 'CONFLICT', 'DENIED'] as const
export type DeletionItemState = typeof deletionItemStates[number]

/**
 * The six-value step vocabulary of the deletion workflow (server DeletionStepKey): the
 * thirteen coordination rows are these keys with DELETE_DERIVED_ROWS pinned once per
 * derived table (server DERIVED_ROW_TARGETS, shown as the item targetRef).
 */
export const deletionStepKeys = [
  'REVOKE_TASK_CREDENTIAL_GRANTS',
  'REVOKE_SUBJECT_CREDENTIALS',
  'TOMBSTONE_ACCOUNT_ARTIFACTS',
  'DELETE_DERIVED_ROWS',
  'TOMBSTONE_IDENTITY',
  'RECORD_BACKUP_RETENTION',
] as const
export type DeletionStepKey = typeof deletionStepKeys[number]

/** v1 frozen 24 resource families of the organization data export (server ExportResourceFamily). */
export const exportResourceFamilies = [
  'ORGANIZATION',
  'TEAM',
  'TEAM_MEMBER',
  'TEAM_ROLE',
  'TEAM_MEMBER_ROLE',
  'WORKSPACE',
  'WORK_PROJECT',
  'WORK_ITEM',
  'WORK_ITEM_RELATION',
  'TASK',
  'TASK_EXECUTION',
  'RESPONSIBILITY_ASSIGNMENT',
  'KNOWLEDGE_ENTRY',
  'KNOWLEDGE_ENTRY_VERSION',
  'KNOWLEDGE_ENTRY_EMBEDDING',
  'REPOSITORY_INDEX_GENERATION',
  'REPOSITORY_CHUNK_EMBEDDING',
  'ARTIFACT_REFERENCE',
  'TASK_CREDENTIAL_GRANT',
  'CREDENTIAL_SECRET',
  'LOCAL_CREDENTIAL',
  'USER_ACCOUNT',
  'ACCOUNT_ORGANIZATION_BINDING',
  'AUDIT_EVENT',
] as const
export type ExportResourceFamily = typeof exportResourceFamilies[number]

export interface OrganizationProfile {
  organizationId: string
  name: string
  status: OrganizationStatus
  version: number
}

/** `auditEventRetentionDays` null keeps audit events forever (A01 §2.1). */
export interface OrganizationSettings {
  auditEventRetentionDays: number | null
  version: number
}

export interface OrganizationCommandReceipt {
  commandId: string
  domainEventId: string
  committedVersion: number
  correlationId: string
  replayed: boolean
}

export interface OrganizationRoleGrant {
  id: string
  organizationId: string
  principalId: string
  role: OrganizationRole
  status: RoleGrantStatus
  grantedByPrincipalId: string
  grantedAt: string
  revokedAt: string | null
  revokedByPrincipalId: string | null
  reason: string | null
  version: number
}

export interface ExportItem {
  id: string
  resourceFamily: ExportResourceFamily
  batchSequence: number
  rowCount: number
  state: ExportItemState
  checksum: string | null
  errorCode: string | null
  processedAt: string | null
  version: number
}

/** manifest stays opaque JSON here; the COMPLETED summary reads it via the manifest mapper. */
export interface ExportJob {
  id: string
  organizationId: string
  requestedByPrincipalId: string
  status: ExportJobStatus
  includeVectors: boolean
  manifest: ExportManifest | null
  version: number
  createdAt: string
  updatedAt: string
  items: ExportItem[]
}

export interface ExportManifestFamily {
  family: string
  itemCount: number
  pinnedRowCount: number
  checksum: string
  skipped: string[]
}

export interface ExportManifest {
  generatedAt: string
  families: ExportManifestFamily[]
  vectors: Record<string, unknown>
}

export interface DeletionPrecheck {
  accountId: string
  /** M13b-A01: the target's echo facts — the confirm token IS the username, so it must be visible. */
  username: string
  displayName: string
  inFlightTaskExecutions: number
  activeResponsibilities: number
  openReservations: number | null
}

export interface DeletionItem {
  id: string
  stepKey: DeletionStepKey
  targetRef: string
  expectedCount: number
  state: DeletionItemState
  resultCount: number | null
  errorCode: string | null
  processedAt: string | null
  version: number
}

/**
 * The browser disclosure deliberately omits the server's confirmationTokenHash: the token is
 * the target account username and its hash is a server-side verification fact, not UI data.
 */
export interface DeletionJob {
  id: string
  organizationId: string
  accountId: string
  targetPrincipalId: string
  reason: string
  status: DeletionJobStatus
  reservationCheck: string
  backupRetentionStatement: string
  version: number
  createdAt: string
  updatedAt: string
  items: DeletionItem[]
}

/**
 * Create commands answer 202 with the job on first execution but replay with the original
 * CommandReceipt (no jobId) — the gateway folds both into one result.
 */
export interface CreationResult<T> {
  job: T | null
  receipt: OrganizationCommandReceipt | null
  replayed: boolean
}

export interface ExportArtifacts {
  manifest: ExportManifest | null
  artifacts: Array<{
    id: string
    itemId: string
    artifactSequence: number
    byteSize: number
    checksum: string
    rows: unknown[]
  }>
}
