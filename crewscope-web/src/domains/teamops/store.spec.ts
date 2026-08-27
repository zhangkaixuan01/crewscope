import { CrewScopeApiError } from '../../api/client'
import type { CreateLarkConnectionInput, TeamOpsGateway } from './gateway'
import { createTeamOpsStore } from './store'
import type { ActivitySnapshot, CommandReceipt, CorrelationGraph, InboxItem, TeamOpsScope } from './types'

const platform = { organizationId: 'org-1', teamId: 'team-platform' }
const security = { organizationId: 'org-1', teamId: 'team-security' }

describe('TeamOpsStore', () => {
  it('ignores a previous Team response even when its Gateway ignores AbortSignal', async () => {
    const old = deferred<ActivitySnapshot>()
    const gateway = fixtureGateway({
      teamActivitySnapshot: vi.fn(async (scope: TeamOpsScope) => scope.teamId === platform.teamId
        ? old.promise
        : activitySnapshot('security-event')),
    })
    const store = createTeamOpsStore(gateway)

    store.activateScope(platform)
    const slow = store.loadTeamActivity()
    store.activateScope(security)
    await store.loadTeamActivity()
    old.resolve(activitySnapshot('platform-event'))
    await slow

    expect(store.state.teamActivity.value?.map(item => item.eventId)).toEqual(['security-event'])
  })

  it('continues Cursor pages, removes overlap, and clears an expired continuation', async () => {
    const gateway = fixtureGateway({
      inbox: vi.fn(async (_scope, _filter, after) => {
        if (after === 'cursor-2') throw apiError(410, 'cursor_expired')
        return { items: [inbox('inbox-1')], nextCursor: 'cursor-2' }
      }),
    })
    const store = createTeamOpsStore(gateway)
    store.activateScope(platform)

    await store.loadInbox()
    await store.loadInbox({}, true)

    expect(store.state.inbox.value?.map(item => item.inboxItemId)).toEqual(['inbox-1'])
    expect(store.state.inbox.nextCursor).toBeNull()
    expect(store.state.inbox.error?.kind).toBe('cursor-expired')
  })

  it('never retains App Secret, open_id, or Idempotency-Key in reactive state', async () => {
    let secret = ''
    let openId = ''
    const gateway = fixtureGateway({
      createLarkConnection: vi.fn(async (_scope, _version, input: CreateLarkConnectionInput) => {
        secret = input.appSecret
        return receipt()
      }),
      verifyLarkMember: vi.fn(async (_scope, _binding, _etag, value) => {
        openId = value
        return receipt()
      }),
    })
    const store = createTeamOpsStore(gateway)
    store.activateScope(platform)

    expect(await store.createLarkConnection(0, {
      tenantKey: 'tenant', appId: 'app', appSecret: 'one-way-secret', expiresAt: null,
    }, 'create-idempotency')).toBe(true)
    expect(await store.verifyLarkMember('binding-1', 2, 'ou_private', 'verify-idempotency')).toBe(true)

    expect(secret).toBe('one-way-secret')
    expect(openId).toBe('ou_private')
    expect(JSON.stringify(store.state)).not.toMatch(/one-way-secret|ou_private|idempotency/)
  })

  it('uses the loaded strong ETag and exposes a stable conflict with current version', async () => {
    const gateway = fixtureGateway({
      inboxDetail: vi.fn(async () => ({ value: inbox('inbox-1'), etag: '"4"' })),
      changeInboxDisposition: vi.fn(async (_scope, _item, _status, etag) => {
        expect(etag).toBe('"4"')
        throw apiError(409, 'optimistic_lock_conflict', 5)
      }),
    })
    const store = createTeamOpsStore(gateway)
    store.activateScope(platform)

    expect(await store.changeInboxDisposition('inbox-1', 'READ', 'command-key')).toBe(false)
    expect(store.state.command.phase).toBe('conflict')
    expect(store.state.command.error?.currentVersion).toBe(5)
    expect(JSON.stringify(store.state.command)).not.toContain('command-key')
  })

  it('continues a Correlation graph with event/object de-duplication and merged evidence', async () => {
    const gateway = fixtureGateway({
      correlation: vi.fn(async (_scope, id, after) => correlationPage(id, after ? 2 : 1)),
    })
    const store = createTeamOpsStore(gateway)
    store.activateScope(platform)

    await store.loadCorrelation('correlation-1')
    await store.loadCorrelation('correlation-1', true)

    const graph = store.state.correlations['correlation-1']?.value
    expect(graph?.events.map(item => item.eventId)).toEqual(['event-1', 'event-2'])
    expect(graph?.objects).toHaveLength(1)
    expect(graph?.objects[0]?.relatedEventIds).toEqual(['event-1', 'event-2'])
  })

  it('preserves loaded Correlation nodes and clears continuation when its Cursor expires', async () => {
    const gateway = fixtureGateway({
      correlation: vi.fn(async (_scope, id, after) => {
        if (after) throw apiError(410, 'cursor_expired')
        return correlationPage(id, 1)
      }),
    })
    const store = createTeamOpsStore(gateway)
    store.activateScope(platform)

    await store.loadCorrelation('correlation-1')
    await store.loadCorrelation('correlation-1', true)

    const resource = store.state.correlations['correlation-1']
    expect(resource?.value?.events).toHaveLength(1)
    expect(resource?.nextCursor).toBeNull()
    expect(resource?.error?.kind).toBe('cursor-expired')
  })

  it('preserves the last authorized value when an ordinary resource refresh fails', async () => {
    const counts = { total: 2, unread: 1, byType: {} }
    const gateway = fixtureGateway({
      inboxCounts: vi.fn()
        .mockResolvedValueOnce(counts)
        .mockRejectedValueOnce(new Error('offline')),
    })
    const store = createTeamOpsStore(gateway)
    store.activateScope(platform)

    await store.loadInboxCounts()
    await store.loadInboxCounts(true)

    expect(store.state.inboxCounts.phase).toBe('error')
    expect(store.state.inboxCounts.value).toEqual(counts)
    expect(store.state.inboxCounts.error).not.toBeNull()
  })

  it('rejects a second command while the shared command slot is pending', async () => {
    const pending = deferred<CommandReceipt>()
    const gateway = fixtureGateway({
      createLarkConnection: vi.fn(async () => pending.promise),
      verifyLarkMember: vi.fn(async () => receipt()),
    })
    const store = createTeamOpsStore(gateway)
    store.activateScope(platform)

    const first = store.createLarkConnection(0, {
      tenantKey: 'tenant', appId: 'app', appSecret: 'one-way-secret', expiresAt: null,
    }, 'first-command')
    expect(await store.verifyLarkMember('binding-1', 1, 'ou_private', 'second-command')).toBe(false)
    expect(gateway.verifyLarkMember).not.toHaveBeenCalled()

    pending.resolve(receipt())
    expect(await first).toBe(true)
    expect(store.state.command.phase).toBe('success')
  })
})

function fixtureGateway(overrides: Partial<TeamOpsGateway>): TeamOpsGateway {
  return {
    teamActivitySnapshot: async () => activitySnapshot('event-1'),
    inbox: async () => ({ items: [], nextCursor: null }),
    inboxDetail: async () => ({ value: inbox('inbox-1'), etag: '"4"' }),
    createLarkConnection: async () => receipt(),
    verifyLarkMember: async () => receipt(),
    changeInboxDisposition: async () => receipt(),
    ...overrides,
  } as unknown as TeamOpsGateway
}

function activitySnapshot(eventId: string): ActivitySnapshot {
  return {
    items: [{
      eventId, domainEventId: `${eventId}-domain`, teamSequence: 1, eventType: 'TASK_STARTED',
      category: 'EXECUTION', visibility: 'TEAM', subject: { type: 'TASK', id: 'task-1' },
      actor: { type: 'MEMBER', principalId: 'principal-1' }, references: [],
      occurredAt: '2026-08-27T01:00:00Z', payload: { schemaName: 'task', schemaVersion: 1, values: {} },
    }],
    hasMore: false, nextCursor: null, snapshotCursor: `${eventId}-cursor`,
  }
}

function inbox(id: string): InboxItem {
  return {
    inboxItemId: id, itemType: 'REVIEW', priority: 'HIGH', deadline: null,
    openedAt: '2026-08-27T01:00:00Z', sourceStatus: 'OPEN', closeReason: null, closedAt: null,
    dispositionStatus: 'UNREAD', dispositionVersion: 4, etag: '"4"',
    source: { type: 'REVIEW_REQUEST', id: 'review-1', revision: 1 },
  }
}

function receipt(): CommandReceipt {
  return { commandId: 'command-1', domainEventId: 'event-1', committedVersion: 1, correlationId: 'correlation-1' }
}

function correlationPage(correlationId: string, page: 1 | 2): CorrelationGraph {
  const eventId = `event-${page}`
  return {
    correlationId,
    events: page === 1 ? [correlationEvent('event-1')] : [correlationEvent('event-1'), correlationEvent(eventId)],
    objects: [{
      type: 'WORK_ITEM', id: 'work-item-1', href: '/activity?objectType=WORK_ITEM&objectId=work-item-1',
      relatedEventIds: [eventId],
    }],
    hasMore: page === 1, nextCursor: page === 1 ? 'cursor-2' : null,
  }
}

function correlationEvent(eventId: string) {
  return {
    eventId, source: 'AUDIT' as const, eventType: 'WORK_UPDATED', actorType: 'USER', actorId: 'actor-1',
    outcome: 'SUCCEEDED', occurredAt: '2026-08-27T01:00:00Z', references: [],
  }
}

function apiError(status: number, code: string, currentVersion: number | null = null): CrewScopeApiError {
  return new CrewScopeApiError(status, {
    code, message: code, correlationId: 'correlation-1', retryable: true, currentVersion, details: {},
  })
}

function deferred<T>(): { promise: Promise<T>, resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(complete => { resolve = complete })
  return { promise, resolve }
}
