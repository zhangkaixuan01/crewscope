import { CrewScopeApiError } from '../../api/client'
import type { OrganizationGateway } from './gateway'
import type { OrganizationRoleGrant } from './types'
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
})

function grant(id: string, status: 'ACTIVE' | 'REVOKED'): OrganizationRoleGrant {
  return {
    id, organizationId, principalId: 'principal-9', role: 'ORG_ADMIN', status,
    grantedByPrincipalId: 'principal-1', grantedAt: '2026-10-13T08:00:00Z',
    revokedAt: null, revokedByPrincipalId: null, reason: null, version: 2,
  }
}

function fakeGateway(): OrganizationGateway & {
  profile: ReturnType<typeof vi.fn>
  settings: ReturnType<typeof vi.fn>
  updateSettings: ReturnType<typeof vi.fn>
  roleGrants: ReturnType<typeof vi.fn>
  grantRole: ReturnType<typeof vi.fn>
  revokeRoleGrant: ReturnType<typeof vi.fn>
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
  }
}

function apiError(status: number, code: string): CrewScopeApiError {
  return new CrewScopeApiError(status, {
    code, message: '暂时不可用', correlationId: 'c-1', retryable: false, currentVersion: null, details: {},
  })
}
