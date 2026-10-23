import { inject, type App, type InjectionKey } from 'vue'
import { apiClient, type CrewScopeApiClient } from '../../api/client'
import {
  deletionItemStates,
  deletionJobStatuses,
  deletionStepKeys,
  exportItemStates,
  exportJobStatuses,
  exportResourceFamilies,
  organizationRoles,
  roleGrantStatuses,
} from './types'
import type {
  CreationResult,
  DeletionItem,
  DeletionJob,
  DeletionPrecheck,
  ExportArtifacts,
  ExportItem,
  ExportJob,
  OrganizationCommandReceipt,
  OrganizationProfile,
  OrganizationRole,
  OrganizationRoleGrant,
  OrganizationSettings,
} from './types'

/** The frozen download media type of a completed export (server GOVERNANCE_EXPORT_MEDIA_TYPE). */
const GOVERNANCE_EXPORT_ACCEPT = 'application/vnd.crewscope.governance-export+json'

/**
 * M12-F01 organization HTTP adapter. Every method maps the response through an explicit
 * whitelist (model gateway precedent): unknown enum values and missing fields fail loudly,
 * and fields the browser must not disclose (confirmationTokenHash) never enter the DTOs.
 */
export interface OrganizationGateway {
  profile(organizationId: string, signal?: AbortSignal): Promise<OrganizationProfile>
  settings(organizationId: string, signal?: AbortSignal): Promise<OrganizationSettings>
  updateSettings(
    organizationId: string,
    input: { auditEventRetentionDays: number | null },
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<OrganizationCommandReceipt>
  /** The role-management audit face: a bare array, active grants first, revoked history behind. */
  roleGrants(organizationId: string, signal?: AbortSignal): Promise<OrganizationRoleGrant[]>
  grantRole(
    organizationId: string,
    input: { principalId: string, role: OrganizationRole, reason?: string },
    idempotencyKey: string,
  ): Promise<OrganizationCommandReceipt>
  revokeRoleGrant(
    organizationId: string,
    grantId: string,
    input: { reason?: string },
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<OrganizationCommandReceipt>
  exportJob(organizationId: string, jobId: string, signal?: AbortSignal): Promise<ExportJob>
  createExport(
    organizationId: string,
    input: { includeVectors: boolean },
    idempotencyKey: string,
  ): Promise<CreationResult<ExportJob>>
  processExport(organizationId: string, jobId: string): Promise<ExportJob>
  cancelExport(organizationId: string, jobId: string): Promise<ExportJob>
  openExportArtifacts(organizationId: string, jobId: string): Promise<ExportArtifacts>
  deletionPrecheck(organizationId: string, accountId: string, signal?: AbortSignal): Promise<DeletionPrecheck>
  createDeletion(
    organizationId: string,
    input: { accountId: string, reason: string },
    idempotencyKey: string,
  ): Promise<CreationResult<DeletionJob>>
  deletionJob(organizationId: string, jobId: string, signal?: AbortSignal): Promise<DeletionJob>
  confirmDeletion(
    organizationId: string,
    jobId: string,
    confirmationToken: string,
    expectedVersion: number,
  ): Promise<DeletionJob>
  processDeletion(organizationId: string, jobId: string): Promise<DeletionJob>
  cancelDeletion(organizationId: string, jobId: string): Promise<DeletionJob>
}

export const ORGANIZATION_GATEWAY: InjectionKey<OrganizationGateway> = Symbol('crewscope-organization-gateway')

export class HttpOrganizationGateway implements OrganizationGateway {
  constructor(private readonly client: CrewScopeApiClient = apiClient) {}

  async profile(organizationId: string, signal?: AbortSignal): Promise<OrganizationProfile> {
    return mapProfile(await this.client.get(`${organizationRoot(organizationId)}`, { signal }))
  }

  async settings(organizationId: string, signal?: AbortSignal): Promise<OrganizationSettings> {
    return mapSettings(await this.client.get(`${organizationRoot(organizationId)}/settings`, { signal }))
  }

  async updateSettings(
    organizationId: string,
    input: { auditEventRetentionDays: number | null },
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<OrganizationCommandReceipt> {
    // The first write materializes the row at version 0; null clears the dimension back to forever.
    const response = await this.client.open(`${organizationRoot(organizationId)}/settings`, {
      method: 'PUT',
      idempotencyKey,
      expectedVersion,
      body: { auditEventRetentionDays: input.auditEventRetentionDays },
    })
    return mapReceipt(await response.json(), replayed(response))
  }

  async roleGrants(organizationId: string, signal?: AbortSignal): Promise<OrganizationRoleGrant[]> {
    const value = await this.client.get(`${organizationRoot(organizationId)}/role-grants`, { signal })
    if (!Array.isArray(value)) throw new TypeError('Organization role grants response is invalid')
    return value.map(mapRoleGrant)
  }

  async grantRole(
    organizationId: string,
    input: { principalId: string, role: OrganizationRole, reason?: string },
    idempotencyKey: string,
  ): Promise<OrganizationCommandReceipt> {
    const response = await this.client.open(`${organizationRoot(organizationId)}/role-grants`, {
      method: 'POST',
      idempotencyKey,
      body: { principalId: input.principalId, role: input.role, reason: input.reason ?? null },
    })
    return mapReceipt(await response.json(), replayed(response))
  }

  async revokeRoleGrant(
    organizationId: string,
    grantId: string,
    input: { reason?: string },
    expectedVersion: number,
    idempotencyKey: string,
  ): Promise<OrganizationCommandReceipt> {
    const response = await this.client.open(
      `${organizationRoot(organizationId)}/role-grants/${encodeURIComponent(grantId)}/revoke`,
      {
        method: 'POST',
        idempotencyKey,
        expectedVersion,
        body: { reason: input.reason ?? null },
      },
    )
    return mapReceipt(await response.json(), replayed(response))
  }

  async exportJob(organizationId: string, jobId: string, signal?: AbortSignal): Promise<ExportJob> {
    return mapExportJob(await this.client.get(exportPath(organizationId, jobId), { signal }))
  }

  async createExport(
    organizationId: string,
    input: { includeVectors: boolean },
    idempotencyKey: string,
  ): Promise<CreationResult<ExportJob>> {
    const response = await this.client.open(`${organizationRoot(organizationId)}/data-exports`, {
      method: 'POST',
      idempotencyKey,
      body: { includeVectors: input.includeVectors },
    })
    return creationResult(response, mapExportJob)
  }

  async processExport(organizationId: string, jobId: string): Promise<ExportJob> {
    return mapExportJob(await this.client.post(exportAction(organizationId, jobId, 'process'), undefined))
  }

  async cancelExport(organizationId: string, jobId: string): Promise<ExportJob> {
    return mapExportJob(await this.client.post(exportAction(organizationId, jobId, 'cancel'), undefined))
  }

  async openExportArtifacts(organizationId: string, jobId: string): Promise<ExportArtifacts> {
    const response = await this.client.open(
      `${exportPath(organizationId, jobId)}/artifacts`,
      { method: 'GET' },
      GOVERNANCE_EXPORT_ACCEPT,
    )
    return mapArtifacts(await response.json())
  }

  async deletionPrecheck(
    organizationId: string,
    accountId: string,
    signal?: AbortSignal,
  ): Promise<DeletionPrecheck> {
    const value = await this.client.get(
      `${organizationRoot(organizationId)}/deletion-precheck?accountId=${encodeURIComponent(accountId)}`,
      { signal },
    )
    return mapDeletionPrecheck(value)
  }

  async createDeletion(
    organizationId: string,
    input: { accountId: string, reason: string },
    idempotencyKey: string,
  ): Promise<CreationResult<DeletionJob>> {
    const response = await this.client.open(`${organizationRoot(organizationId)}/account-deletions`, {
      method: 'POST',
      idempotencyKey,
      body: { accountId: input.accountId, reason: input.reason },
    })
    return creationResult(response, mapDeletionJob)
  }

  async deletionJob(organizationId: string, jobId: string, signal?: AbortSignal): Promise<DeletionJob> {
    return mapDeletionJob(await this.client.get(deletionPath(organizationId, jobId), { signal }))
  }

  async confirmDeletion(
    organizationId: string,
    jobId: string,
    confirmationToken: string,
    expectedVersion: number,
  ): Promise<DeletionJob> {
    return mapDeletionJob(await this.client.post(`${deletionPath(organizationId, jobId)}/confirm`, {
      confirmationToken,
    }, { expectedVersion }))
  }

  async processDeletion(organizationId: string, jobId: string): Promise<DeletionJob> {
    return mapDeletionJob(await this.client.post(deletionAction(organizationId, jobId, 'process'), undefined))
  }

  async cancelDeletion(organizationId: string, jobId: string): Promise<DeletionJob> {
    return mapDeletionJob(await this.client.post(deletionAction(organizationId, jobId, 'cancel'), undefined))
  }
}

export function installOrganizationGateway(app: App, gateway: OrganizationGateway): void {
  app.provide(ORGANIZATION_GATEWAY, gateway)
}

export function useOrganizationGateway(): OrganizationGateway {
  const gateway = inject(ORGANIZATION_GATEWAY)
  if (!gateway) throw new Error('CrewScope Organization Gateway is not installed')
  return gateway
}

function organizationRoot(organizationId: string): string {
  return `/organizations/${encodeURIComponent(organizationId)}`
}

function exportPath(organizationId: string, jobId: string): string {
  return `${organizationRoot(organizationId)}/data-exports/${encodeURIComponent(jobId)}`
}

function deletionPath(organizationId: string, jobId: string): string {
  return `${organizationRoot(organizationId)}/account-deletions/${encodeURIComponent(jobId)}`
}

function exportAction(organizationId: string, jobId: string, action: string): string {
  return `${exportPath(organizationId, jobId)}/${action}`
}

function deletionAction(organizationId: string, jobId: string, action: string): string {
  return `${deletionPath(organizationId, jobId)}/${action}`
}

/**
 * Create commands answer 202 with the job on first execution but with the original
 * CommandReceipt (no jobId) on replay — the Idempotency-Replayed header picks the branch.
 */
async function creationResult<T>(response: Response, mapJob: (input: unknown) => T): Promise<CreationResult<T>> {
  const body = await response.json()
  if (replayed(response)) return { job: null, receipt: mapReceipt(body, true), replayed: true }
  return { job: mapJob(body), receipt: null, replayed: false }
}

function replayed(response: Response): boolean {
  return response.headers.get('Idempotency-Replayed') === 'true'
}

function mapProfile(input: unknown): OrganizationProfile {
  const value = record(input)
  return {
    organizationId: nonEmptyString(value.organizationId),
    name: nonEmptyString(value.name),
    status: oneOf(value.status, ['ACTIVE'] as const),
    version: nonNegativeInteger(value.version),
  }
}

function mapSettings(input: unknown): OrganizationSettings {
  const value = record(input)
  return {
    auditEventRetentionDays: nullableInteger(value.auditEventRetentionDays),
    version: nonNegativeInteger(value.version),
  }
}

function mapRoleGrant(input: unknown): OrganizationRoleGrant {
  const value = record(input)
  return {
    id: nonEmptyString(value.id),
    organizationId: nonEmptyString(value.organizationId),
    principalId: nonEmptyString(value.principalId),
    role: oneOf(value.role, organizationRoles),
    status: oneOf(value.status, roleGrantStatuses),
    grantedByPrincipalId: nonEmptyString(value.grantedByPrincipalId),
    grantedAt: nonEmptyString(value.grantedAt),
    revokedAt: nullableString(value.revokedAt),
    revokedByPrincipalId: nullableString(value.revokedByPrincipalId),
    reason: nullableString(value.reason),
    version: nonNegativeInteger(value.version),
  }
}

function mapExportJob(input: unknown): ExportJob {
  const value = record(input)
  return {
    id: nonEmptyString(value.id),
    organizationId: nonEmptyString(value.organizationId),
    requestedByPrincipalId: nonEmptyString(value.requestedByPrincipalId),
    status: oneOf(value.status, exportJobStatuses),
    includeVectors: booleanOf(value.includeVectors),
    manifest: value.manifest === null ? null : mapExportManifest(value.manifest),
    version: nonNegativeInteger(value.version),
    createdAt: nonEmptyString(value.createdAt),
    updatedAt: nonEmptyString(value.updatedAt),
    items: itemsOf(value.items, mapExportItem),
  }
}

function mapExportItem(input: unknown): ExportItem {
  const value = record(input)
  return {
    id: nonEmptyString(value.id),
    resourceFamily: oneOf(value.resourceFamily, exportResourceFamilies),
    batchSequence: nonNegativeInteger(value.batchSequence),
    rowCount: nonNegativeInteger(value.rowCount),
    state: oneOf(value.state, exportItemStates),
    checksum: nullableString(value.checksum),
    errorCode: nullableString(value.errorCode),
    processedAt: nullableString(value.processedAt),
    version: nonNegativeInteger(value.version),
  }
}

/** The manifest is server-authored reconciliation JSON; only the frozen summary shape is mapped. */
function mapExportManifest(input: unknown): ExportJob['manifest'] {
  const value = record(input)
  return {
    generatedAt: nonEmptyString(value.generatedAt),
    families: familiesOf(value.families),
    vectors: record(value.vectors),
  }
}

function familiesOf(input: unknown): NonNullable<ExportJob['manifest']>['families'] {
  if (!Array.isArray(input)) throw new TypeError('Organization export manifest is invalid')
  return input.map(entry => {
    const value = record(entry)
    return {
      family: nonEmptyString(value.family),
      itemCount: nonNegativeInteger(value.itemCount),
      pinnedRowCount: nonNegativeInteger(value.pinnedRowCount),
      checksum: nonEmptyString(value.checksum),
      skipped: stringsOf(value.skipped),
    }
  })
}

function stringsOf(input: unknown): string[] {
  if (!Array.isArray(input)) throw new TypeError('Organization response string list is invalid')
  return input.map(nonEmptyString)
}

function mapDeletionPrecheck(input: unknown): DeletionPrecheck {
  const value = record(input)
  return {
    accountId: nonEmptyString(value.accountId),
    username: nonEmptyString(value.username),
    displayName: nonEmptyString(value.displayName),
    inFlightTaskExecutions: nonNegativeInteger(value.inFlightTaskExecutions),
    activeResponsibilities: nonNegativeInteger(value.activeResponsibilities),
    openReservations: value.openReservations === null ? null : nonNegativeInteger(value.openReservations),
  }
}

function mapDeletionJob(input: unknown): DeletionJob {
  const value = record(input)
  return {
    id: nonEmptyString(value.id),
    organizationId: nonEmptyString(value.organizationId),
    accountId: nonEmptyString(value.accountId),
    targetPrincipalId: nonEmptyString(value.targetPrincipalId),
    reason: nonEmptyString(value.reason),
    status: oneOf(value.status, deletionJobStatuses),
    reservationCheck: nonEmptyString(value.reservationCheck),
    backupRetentionStatement: nonEmptyString(value.backupRetentionStatement),
    version: nonNegativeInteger(value.version),
    createdAt: nonEmptyString(value.createdAt),
    updatedAt: nonEmptyString(value.updatedAt),
    items: itemsOf(value.items, mapDeletionItem),
  }
}

function mapDeletionItem(input: unknown): DeletionItem {
  const value = record(input)
  return {
    id: nonEmptyString(value.id),
    stepKey: oneOf(value.stepKey, deletionStepKeys),
    targetRef: nonEmptyString(value.targetRef),
    expectedCount: nonNegativeInteger(value.expectedCount),
    state: oneOf(value.state, deletionItemStates),
    resultCount: value.resultCount === null ? null : nonNegativeInteger(value.resultCount),
    errorCode: nullableString(value.errorCode),
    processedAt: nullableString(value.processedAt),
    version: nonNegativeInteger(value.version),
  }
}

function mapArtifacts(input: unknown): ExportArtifacts {
  const value = record(input)
  if (!Array.isArray(value.artifacts)) throw new TypeError('Organization export artifacts are invalid')
  return {
    manifest: value.manifest === null || value.manifest === undefined ? null : mapExportManifest(value.manifest),
    artifacts: value.artifacts.map(artifact => {
      const entry = record(artifact)
      return {
        id: nonEmptyString(entry.id),
        itemId: nonEmptyString(entry.itemId),
        artifactSequence: nonNegativeInteger(entry.artifactSequence),
        byteSize: nonNegativeInteger(entry.byteSize),
        checksum: nonEmptyString(entry.checksum),
        rows: Array.isArray(entry.rows) ? entry.rows : [],
      }
    }),
  }
}

function itemsOf<T>(input: unknown, mapItem: (entry: unknown) => T): T[] {
  if (!Array.isArray(input)) throw new TypeError('Organization response item list is invalid')
  return input.map(mapItem)
}

function booleanOf(input: unknown): boolean {
  if (typeof input !== 'boolean') throw new TypeError('Organization response boolean is invalid')
  return input
}

function nullableString(input: unknown): string | null {
  return input === null ? null : nonEmptyString(input)
}

function mapReceipt(input: unknown, replayed: boolean): OrganizationCommandReceipt {
  const value = record(input)
  return {
    commandId: nonEmptyString(value.commandId),
    domainEventId: nonEmptyString(value.domainEventId),
    committedVersion: nonNegativeInteger(value.committedVersion),
    correlationId: nonEmptyString(value.correlationId),
    replayed,
  }
}

function nullableInteger(input: unknown): number | null {
  return input === null ? null : nonNegativeInteger(input)
}

function record(input: unknown): Record<string, unknown> {
  if (!input || typeof input !== 'object' || Array.isArray(input)) {
    throw new TypeError('Organization response object is invalid')
  }
  return input as Record<string, unknown>
}

function nonEmptyString(input: unknown): string {
  if (typeof input !== 'string' || !input.trim()) throw new TypeError('Organization response string is invalid')
  return input
}

function nonNegativeInteger(input: unknown): number {
  if (typeof input !== 'number' || !Number.isSafeInteger(input) || input < 0) {
    throw new TypeError('Organization response integer is invalid')
  }
  return input
}

function oneOf<const T extends readonly string[]>(input: unknown, values: T): T[number] {
  if (typeof input !== 'string' || !values.includes(input)) throw new TypeError('Organization response enum is invalid')
  return input as T[number]
}
