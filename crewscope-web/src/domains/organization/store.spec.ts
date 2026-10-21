// @vitest-environment node
import { CrewScopeApiError } from '../../api/client'
import type { OrganizationGateway } from './gateway'
import type { CreationResult, DeletionJob, DeletionJobStatus, ExportJob, ExportJobStatus, OrganizationRoleGrant } from './types'
import { createOrganizationStore } from './store'

const organizationId = '00000000-0000-0000-0000-000000000001'

describe('OrganizationStore', () => {
  it('loads the profile for every member and keeps the value for repeat loads', async () => {
    const gateway = fakeGateway()
    const store = createOrganizationStore(gateway)

    await store.loadProfile(organizationId)
    await store.loadProfile(organizationId)

    expect(store.state.profile).toMatchObject({
      phase: 'ready',
      value: { organizationId, name: 'CrewScope Organization', status: 'ACTIVE', version: 1 },
    })
    expect(gateway.profile).toHaveBeenCalledOnce()
  })

  it('maps a 403 on settings to the forbidden phase instead of a retryable error', async () => {
    const gateway = fakeGateway()
    gateway.settings.mockRejectedValueOnce(apiError(403, 'capability_missing'))
    const store = createOrganizationStore(gateway)

    await store.loadSettings(organizationId)

    expect(store.state.settings.phase).toBe('forbidden')
    expect(store.state.settings.value).toBeNull()
  })

  it('maps other failures to the error phase with the server message', async () => {
    const gateway = fakeGateway()
    gateway.profile.mockRejectedValueOnce(apiError(503, 'unavailable'))
    const store = createOrganizationStore(gateway)

    await store.loadProfile(organizationId)

    expect(store.state.profile).toMatchObject({ phase: 'error', errorMessage: '暂时不可用' })
  })

  it('treats the 202 receipt as acceptance and immediately re-reads settings', async () => {
    const gateway = fakeGateway()
    gateway.settings
      .mockResolvedValueOnce({ auditEventRetentionDays: null, version: 0 })
      .mockResolvedValueOnce({ auditEventRetentionDays: 90, version: 1 })
    const store = createOrganizationStore(gateway)
    await store.loadSettings(organizationId)

    const ok = await store.updateSettings(organizationId, { auditEventRetentionDays: 90 }, 0, 'attempt-1')

    expect(ok).toBe(true)
    expect(store.state.settingsCommand.phase).toBe('success')
    expect(store.state.settingsCommand.receipt).toMatchObject({ commandId: 'command-1', replayed: false })
    expect(store.state.settings).toMatchObject({ phase: 'ready', value: { auditEventRetentionDays: 90, version: 1 } })
    expect(gateway.settings).toHaveBeenCalledTimes(2)
  })

  it('records the 409 conflict coordinates and re-reads so the next edit starts from persisted state', async () => {
    const gateway = fakeGateway()
    gateway.settings
      .mockResolvedValueOnce({ auditEventRetentionDays: null, version: 0 })
      .mockResolvedValueOnce({ auditEventRetentionDays: 90, version: 3 })
    gateway.updateSettings.mockRejectedValueOnce(apiError(409, 'optimistic_lock_conflict'))
    const store = createOrganizationStore(gateway)
    await store.loadSettings(organizationId)

    const ok = await store.updateSettings(organizationId, { auditEventRetentionDays: 90 }, 0, 'attempt-1')

    expect(ok).toBe(false)
    expect(store.state.settingsCommand).toMatchObject({ phase: 'error', errorStatus: 409, errorCode: 'optimistic_lock_conflict' })
    expect(store.state.settings.value).toMatchObject({ version: 3 })
    expect(gateway.settings).toHaveBeenCalledTimes(2)
  })

  it('deduplicates concurrent identical writes behind the command gateway', async () => {
    const gateway = fakeGateway()
    let release!: (value: Awaited<ReturnType<OrganizationGateway['updateSettings']>>) => void
    gateway.updateSettings.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
    const store = createOrganizationStore(gateway)

    const first = store.updateSettings(organizationId, { auditEventRetentionDays: 90 }, 0, 'attempt-1')
    const second = store.updateSettings(organizationId, { auditEventRetentionDays: 90 }, 0, 'attempt-2')
    release({ commandId: 'command-1', domainEventId: 'event-1', committedVersion: 1, correlationId: 'c-1', replayed: false })
    const [firstOk, secondOk] = await Promise.all([first, second])

    expect(firstOk).toBe(true)
    expect(secondOk).toBe(true)
    expect(gateway.updateSettings).toHaveBeenCalledOnce()
  })

  it('reset aborts in-flight loads and returns every resource to idle', async () => {
    const gateway = fakeGateway()
    let resolveProfile!: (value: Awaited<ReturnType<OrganizationGateway['profile']>>) => void
    gateway.profile.mockImplementationOnce(() => new Promise(resolve => { resolveProfile = resolve }))
    const store = createOrganizationStore(gateway)
    const loading = store.loadProfile(organizationId)
    expect(store.state.profile.phase).toBe('loading')

    store.reset()
    resolveProfile({ organizationId, name: 'CrewScope Organization', status: 'ACTIVE', version: 1 })
    await loading

    expect(store.state.profile.phase).toBe('idle')
    expect(store.state.profile.value).toBeNull()
    expect(store.state.settingsCommand.phase).toBe('idle')
    expect(store.state.roleCommand.phase).toBe('idle')
  })

  it('re-reads the grant list after a successful grant command', async () => {
    const gateway = fakeGateway()
    gateway.roleGrants
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([grant('g-1', 'ACTIVE')])
    const store = createOrganizationStore(gateway)
    await store.loadRoleGrants(organizationId)

    const ok = await store.grantRole(organizationId, { principalId: 'principal-9', role: 'ORG_AUDITOR' }, 'grant-1')

    expect(ok).toBe(true)
    expect(store.state.roleCommand).toMatchObject({ phase: 'success', operation: 'grant-role' })
    expect(store.state.roleGrants.value).toHaveLength(1)
    expect(gateway.roleGrants).toHaveBeenCalledTimes(2)
  })

  it('records the last-owner protection as command error coordinates and re-reads', async () => {
    const gateway = fakeGateway()
    gateway.roleGrants.mockResolvedValue([grant('g-1', 'ACTIVE')])
    gateway.revokeRoleGrant.mockRejectedValueOnce(apiError(409, 'last_owner_protection'))
    const store = createOrganizationStore(gateway)
    await store.loadRoleGrants(organizationId)

    const ok = await store.revokeRoleGrant(organizationId, 'g-1', {}, 2, 'revoke-1')

    expect(ok).toBe(false)
    expect(store.state.roleCommand).toMatchObject({
      phase: 'error', operation: 'revoke-role', errorStatus: 409, errorCode: 'last_owner_protection',
    })
    expect(gateway.roleGrants).toHaveBeenCalledTimes(2)
  })

  it('keeps the export job of the first create and folds a replay into a notice', async () => {
    const gateway = fakeGateway()
    gateway.createExport
      .mockResolvedValueOnce(creation(exportJobFixture('RUNNING')))
      .mockResolvedValueOnce({ job: null, receipt: commandReceipt(), replayed: true })
    const store = createOrganizationStore(gateway)

    expect(await store.createExport(organizationId, { includeVectors: false }, 'export-1')).toBe(true)
    expect(store.state.exportFlow).toMatchObject({ job: { id: 'job-e1' }, busy: null, replayed: false })

    expect(await store.createExport(organizationId, { includeVectors: false }, 'export-2')).toBe(true)
    // The replay carries the original receipt and no job body: the panel keeps what it had.
    expect(store.state.exportFlow.job).toMatchObject({ id: 'job-e1' })
    expect(store.state.exportFlow.replayed).toBe(true)
    expect(store.state.exportFlow.notice).toContain('幂等重放')
  })

  it('returns the export panel its buttons after a successful download', async () => {
    const gateway = fakeGateway()
    gateway.createExport.mockResolvedValueOnce(creation(exportJobFixture('COMPLETED')))
    gateway.openExportArtifacts.mockResolvedValueOnce({ manifest: null, artifacts: [] })
    const store = createOrganizationStore(gateway)
    await store.createExport(organizationId, { includeVectors: false }, 'export-1')

    expect(await store.fetchExportArtifacts(organizationId)).toEqual({ manifest: null, artifacts: [] })
    // busy is the panel's only disable signal: a completed read must not strand it.
    expect(store.state.exportFlow.busy).toBeNull()

    gateway.openExportArtifacts.mockRejectedValueOnce(apiError(409, 'export_not_ready'))
    expect(await store.fetchExportArtifacts(organizationId)).toBeNull()
    expect(store.state.exportFlow).toMatchObject({ busy: null, errorStatus: 409, errorCode: 'export_not_ready' })
  })

  it('replaces the export job with each step answer and re-reads after a 409', async () => {
    const gateway = fakeGateway()
    gateway.createExport.mockResolvedValueOnce(creation(exportJobFixture('PENDING')))
    gateway.processExport.mockResolvedValueOnce(exportJobFixture('COMPLETED'))
    gateway.cancelExport.mockRejectedValueOnce(apiError(409, 'job_already_terminal'))
    gateway.exportJob.mockResolvedValueOnce(exportJobFixture('CANCELLED'))
    const store = createOrganizationStore(gateway)
    await store.createExport(organizationId, { includeVectors: true }, 'export-1')

    expect(await store.processExport(organizationId)).toBe(true)
    expect(store.state.exportFlow.job).toMatchObject({ status: 'COMPLETED' })

    expect(await store.cancelExport(organizationId)).toBe(false)
    expect(store.state.exportFlow).toMatchObject({
      busy: null, errorStatus: 409, errorCode: 'job_already_terminal',
    })
    expect(gateway.exportJob).toHaveBeenCalledWith(organizationId, 'job-e1')
    expect(store.state.exportFlow.job).toMatchObject({ status: 'CANCELLED' })
  })

  it('caches the precheck per account and forces a re-read for a different account', async () => {
    const gateway = fakeGateway()
    const store = createOrganizationStore(gateway)

    await store.precheckDeletion(organizationId, 'account-1')
    await store.precheckDeletion(organizationId, 'account-1')
    expect(gateway.deletionPrecheck).toHaveBeenCalledOnce()

    await store.precheckDeletion(organizationId, 'account-2')
    expect(gateway.deletionPrecheck).toHaveBeenCalledTimes(2)
    expect(store.state.deletionPrecheck).toMatchObject({
      phase: 'ready', value: { accountId: 'account-2', inFlightTaskExecutions: 0 },
    })
  })

  it('runs the deletion workflow through confirm and process, re-reading after a confirm 409', async () => {
    const gateway = fakeGateway()
    gateway.createDeletion.mockResolvedValueOnce(creation(deletionJobFixture('PENDING_CONFIRMATION')))
    gateway.confirmDeletion
      .mockRejectedValueOnce(apiError(409, 'optimistic_lock_conflict'))
      .mockResolvedValueOnce(deletionJobFixture('CONFIRMED'))
    gateway.deletionJob.mockResolvedValueOnce(deletionJobFixture('CONFIRMED'))
    gateway.processDeletion.mockResolvedValueOnce(deletionJobFixture('COMPLETED'))
    const store = createOrganizationStore(gateway)
    await store.createDeletion(organizationId, { accountId: 'account-1', reason: 'x'.repeat(20) }, 'deletion-1')
    expect(store.state.deletionFlow.job).toMatchObject({ status: 'PENDING_CONFIRMATION' })

    expect(await store.confirmDeletion(organizationId, 'e2e-member')).toBe(false)
    expect(gateway.deletionJob).toHaveBeenCalledWith(organizationId, 'job-d1')
    expect(store.state.deletionFlow).toMatchObject({ busy: null, errorStatus: 409 })

    expect(await store.confirmDeletion(organizationId, 'e2e-member')).toBe(true)
    expect(gateway.confirmDeletion).toHaveBeenCalledWith(organizationId, 'job-d1', 'e2e-member', 1)
    expect(await store.processDeletion(organizationId)).toBe(true)
    expect(store.state.deletionFlow.job).toMatchObject({ status: 'COMPLETED' })
  })
})

function grant(id: string, status: 'ACTIVE' | 'REVOKED'): OrganizationRoleGrant {
  return {
    id, organizationId, principalId: 'principal-9', role: 'ORG_ADMIN', status,
    grantedByPrincipalId: 'principal-1', grantedAt: '2026-10-13T08:00:00Z',
    revokedAt: null, revokedByPrincipalId: null, reason: null, version: 2,
  }
}

function exportJobFixture(status: ExportJobStatus): ExportJob {
  return {
    id: 'job-e1', organizationId, requestedByPrincipalId: 'principal-1', status,
    includeVectors: false, manifest: null, version: 1,
    createdAt: '2026-10-13T08:00:00Z', updatedAt: '2026-10-13T08:00:00Z', items: [],
  }
}

function deletionJobFixture(status: DeletionJobStatus): DeletionJob {
  return {
    id: 'job-d1', organizationId, accountId: 'account-1', targetPrincipalId: 'principal-2',
    reason: '离职账号清理，含满足最短长度要求的完整说明。',
    status,
    reservationCheck: '{"inFlightTaskExecutions":0,"activeResponsibilities":0,"openReservations":null,"checkedAt":"2026-10-13T08:00:00Z"}',
    backupRetentionStatement: '备份保留声明：每日快照 7 天、每周快照 4 周。',
    version: 1, createdAt: '2026-10-13T08:00:00Z', updatedAt: '2026-10-13T08:00:00Z', items: [],
  }
}

function creation<T extends ExportJob | DeletionJob>(job: T): CreationResult<T> {
  return { job, receipt: null, replayed: false }
}

function commandReceipt() {
  return {
    commandId: 'command-9', domainEventId: 'event-9', committedVersion: 1,
    correlationId: 'c-9', replayed: true,
  }
}

function fakeGateway(): OrganizationGateway & {
  profile: ReturnType<typeof vi.fn>
  settings: ReturnType<typeof vi.fn>
  updateSettings: ReturnType<typeof vi.fn>
  roleGrants: ReturnType<typeof vi.fn>
  grantRole: ReturnType<typeof vi.fn>
  revokeRoleGrant: ReturnType<typeof vi.fn>
  exportJob: ReturnType<typeof vi.fn>
  createExport: ReturnType<typeof vi.fn>
  processExport: ReturnType<typeof vi.fn>
  cancelExport: ReturnType<typeof vi.fn>
  openExportArtifacts: ReturnType<typeof vi.fn>
  deletionPrecheck: ReturnType<typeof vi.fn>
  createDeletion: ReturnType<typeof vi.fn>
  deletionJob: ReturnType<typeof vi.fn>
  confirmDeletion: ReturnType<typeof vi.fn>
  processDeletion: ReturnType<typeof vi.fn>
  cancelDeletion: ReturnType<typeof vi.fn>
} {
  return {
    profile: vi.fn(async () => ({ organizationId, name: 'CrewScope Organization', status: 'ACTIVE' as const, version: 1 })),
    settings: vi.fn(async () => ({ auditEventRetentionDays: null, version: 0 })),
    updateSettings: vi.fn(async () => ({
      commandId: 'command-1', domainEventId: 'event-1', committedVersion: 1,
      correlationId: 'c-1', replayed: false,
    })),
    roleGrants: vi.fn(async () => []),
    grantRole: vi.fn(async () => ({
      commandId: 'command-2', domainEventId: 'event-2', committedVersion: 1,
      correlationId: 'c-2', replayed: false,
    })),
    revokeRoleGrant: vi.fn(async () => ({
      commandId: 'command-3', domainEventId: 'event-3', committedVersion: 2,
      correlationId: 'c-3', replayed: false,
    })),
    exportJob: vi.fn(async () => exportJobFixture('PENDING')),
    createExport: vi.fn(async () => creation(exportJobFixture('PENDING'))),
    processExport: vi.fn(async () => exportJobFixture('COMPLETED')),
    cancelExport: vi.fn(async () => exportJobFixture('CANCELLED')),
    openExportArtifacts: vi.fn(async () => ({ manifest: null, artifacts: [] })),
    deletionPrecheck: vi.fn(async (_organizationId: string, accountId: string) => ({
      accountId, inFlightTaskExecutions: 0, activeResponsibilities: 0, openReservations: null,
    })),
    createDeletion: vi.fn(async () => creation(deletionJobFixture('PENDING_CONFIRMATION'))),
    deletionJob: vi.fn(async () => deletionJobFixture('PENDING_CONFIRMATION')),
    confirmDeletion: vi.fn(async () => deletionJobFixture('CONFIRMED')),
    processDeletion: vi.fn(async () => deletionJobFixture('COMPLETED')),
    cancelDeletion: vi.fn(async () => deletionJobFixture('CANCELLED')),
  }
}

function apiError(status: number, code: string): CrewScopeApiError {
  return new CrewScopeApiError(status, {
    code, message: '暂时不可用', correlationId: 'c-1', retryable: false, currentVersion: null, details: {},
  })
}