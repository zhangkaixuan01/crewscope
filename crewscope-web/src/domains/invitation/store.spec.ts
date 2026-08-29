import { CrewScopeApiError } from '../../api/client'
import type { InvitationGateway } from './gateway'
import { createInvitationStore } from './store'
import type { InvitationPreview, TeamInvitationSummary } from './types'

const csrf = { headerName: 'X-XSRF-TOKEN' as const, parameterName: '_csrf' as const, token: 'csrf-private' }
const token = 'A'.repeat(43)

describe('InvitationStore', () => {
  it('loads, appends and de-duplicates management pages', async () => {
    const gateway = fixtureGateway()
    gateway.list = vi.fn(async (_organization, _team, after) => after
      ? { items: [invitation(), invitation({ id: 'invitation-2' })], nextCursor: null }
      : { items: [invitation()], nextCursor: 'next' })
    const store = createInvitationStore(gateway)

    await store.loadManagement('organization-1', 'team-1')
    await store.loadManagement('organization-1', 'team-1', true)

    expect(store.state.items.map(item => item.id)).toEqual(['invitation-1', 'invitation-2'])
    expect(store.state.nextCursor).toBeNull()
  })

  it('issues and revokes invitations without retaining proof or command keys in reactive state', async () => {
    const gateway = fixtureGateway()
    const store = createInvitationStore(gateway)
    await store.loadManagement('organization-1', 'team-1')

    const result = await store.createInvitation('organization-1', 'team-1', {
      targetEmail: 'member@example.com', targetRole: 'MEMBER', expiresInMinutes: 10_080,
    }, csrf)
    expect(result?.token).toBe(token)
    expect(JSON.stringify(store.state)).not.toContain(token)
    expect(await store.revokeInvitation('organization-1', 'team-1', 'invitation-1', csrf)).toBe(true)
    expect(store.state.items[0]?.status).toBe('REVOKED')
    expect(JSON.stringify(store.state)).not.toMatch(/idempotency|csrf-private/i)
  })

  it('keeps a valid proof privately across preview and accepts with the current Session only', async () => {
    const gateway = fixtureGateway()
    const store = createInvitationStore(gateway)

    expect(await store.previewProof(`#token=${token}`)).toBe(true)
    expect(store.hasProof()).toBe(true)
    expect(store.registrationProof()).toBe(token)
    expect(JSON.stringify(store.state)).not.toContain(token)
    expect(await store.acceptInvitation(csrf)).toBe(true)
    expect(store.hasProof()).toBe(false)
    expect(gateway.accept).toHaveBeenCalledWith(token, expect.objectContaining({ csrf }), expect.any(AbortSignal))
  })

  it('folds invalid links and account-email mismatches into non-identifying states', async () => {
    const gateway = fixtureGateway()
    const store = createInvitationStore(gateway)
    expect(await store.previewProof('#token=bad')).toBe(false)
    expect(store.state.publicPhase).toBe('unavailable')

    await store.previewProof(`#token=${token}`)
    gateway.accept = vi.fn(async () => { throw apiError('invitation_invalid', 422) })
    expect(await store.acceptInvitation(csrf)).toBe(false)
    expect(store.state.publicProblem).toMatchObject({ code: 'invitation_invalid', title: '无法使用这个邀请' })
    expect(JSON.stringify(store.state)).not.toContain('private invitation detail')
  })

  it('preserves the same idempotency key after an uncertain creation failure', async () => {
    const gateway = fixtureGateway()
    gateway.create = vi.fn()
      .mockRejectedValueOnce(apiError('network_unavailable', 0))
      .mockResolvedValueOnce({ command: receipt(), invitation: invitation(), token, replayed: false })
    const store = createInvitationStore(gateway)
    const input = { targetRole: 'MEMBER' as const, expiresInMinutes: 60 }

    await store.createInvitation('organization-1', 'team-1', input, csrf)
    await store.createInvitation('organization-1', 'team-1', input, csrf)

    expect(gateway.create).toHaveBeenCalledTimes(2)
    expect(vi.mocked(gateway.create).mock.calls[0]?.[3].idempotencyKey)
      .toBe(vi.mocked(gateway.create).mock.calls[1]?.[3].idempotencyKey)
  })
})

function fixtureGateway(): InvitationGateway {
  return {
    list: vi.fn(async () => ({ items: [invitation()], nextCursor: null })),
    create: vi.fn(async () => ({ command: receipt(), invitation: invitation(), token, replayed: false })),
    revoke: vi.fn(async () => receipt()),
    preview: vi.fn(async () => preview()),
    accept: vi.fn(async () => receipt()),
  }
}

function invitation(overrides: Partial<TeamInvitationSummary> = {}): TeamInvitationSummary {
  return {
    id: 'invitation-1', organizationId: 'organization-1', teamId: 'team-1', invitedByPrincipalId: 'principal-1',
    targetEmail: 'member@example.com', targetRole: 'MEMBER', status: 'PENDING', expiresAt: '2026-09-01T00:00:00Z',
    acceptedMemberId: null, resolvedAt: null, version: 0,
    createdAt: '2026-08-29T00:00:00Z', updatedAt: '2026-08-29T00:00:00Z', ...overrides,
  }
}

function preview(overrides: Partial<InvitationPreview> = {}): InvitationPreview {
  return {
    state: 'AVAILABLE', invitationId: 'invitation-1', teamName: 'Platform Engineering', targetRole: 'MEMBER',
    expiresAt: '2026-09-01T00:00:00Z', targetRestricted: true, ...overrides,
  }
}

function receipt() {
  return { commandId: 'command-1', domainEventId: 'event-1', committedVersion: 1, correlationId: 'correlation-1' }
}

function apiError(code: string, status: number): CrewScopeApiError {
  return new CrewScopeApiError(status, {
    code, message: 'private invitation detail', correlationId: 'correlation-private', retryable: true,
    currentVersion: null, details: {},
  })
}
