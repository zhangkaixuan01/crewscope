import type { TeamObserverConnection, TeamObserverGateway } from './gateway'
import { createTeamObserverStore } from './store'
import type { TeamObserverCancelResponse, TeamObserverEvidence, TeamObserverEvent, TeamObserverScope, TeamSummary } from './types'

const platform = { organizationId: 'org-1', teamId: 'platform' }
const security = { organizationId: 'org-1', teamId: 'security' }

describe('TeamObserverStore', () => {
  it('creates a read-only session and completes one invocation', async () => {
    const gateway = fixtureGateway()
    const store = createTeamObserverStore(gateway)
    store.activateScope(platform)

    expect(await store.invoke('  总结团队  ')).toBe(true)
    expect(store.state.phase).toBe('completed')
    expect(store.state.summary?.progress[0]?.summary).toBe('完成 M6-A05')
    expect(gateway.invoke).toHaveBeenCalledWith(platform, 'session-platform', '总结团队', 10, expect.any(AbortSignal))
  })

  it('resumes the same invocation after transport closure and de-duplicates sequence', async () => {
    const gateway = fixtureGateway({
      invoke: vi.fn(async () => connection('inv-1', [started('inv-1')])),
      resume: vi.fn(async () => connection('inv-1', [started('inv-1'), completed('inv-1')])),
    })
    const store = createTeamObserverStore(gateway)
    store.activateScope(platform)

    expect(await store.invoke('总结')).toBe(true)
    expect(gateway.resume).toHaveBeenCalledTimes(1)
    expect(store.state.invocationId).toBe('inv-1')
    expect(store.state.lastSequence).toBe(1)
  })

  it('aborts and clears Team-bound identities on Scope change', async () => {
    const pending = deferred<TeamObserverConnection>()
    const gateway = fixtureGateway({ invoke: vi.fn(async () => pending.promise) })
    const store = createTeamObserverStore(gateway)
    store.activateScope(platform)
    const invocation = store.invoke('总结')
    await Promise.resolve()
    await Promise.resolve()

    store.activateScope(security)
    pending.resolve(connection('old-invocation', [completed('old-invocation')]))
    await invocation

    expect(store.state.session).toBeNull()
    expect(store.state.invocationId).toBeNull()
    expect(store.state.summary).toBeNull()
  })

  it('starts each new question with a fresh Sequence boundary while reusing the read-only session', async () => {
    const gateway = fixtureGateway()
    const store = createTeamObserverStore(gateway)
    store.activateScope(platform)

    expect(await store.invoke('第一次')).toBe(true)
    expect(await store.invoke('第二次')).toBe(true)

    expect(gateway.createSession).toHaveBeenCalledTimes(1)
    expect(gateway.invoke).toHaveBeenCalledTimes(2)
    expect(store.state.lastSequence).toBe(1)
    expect(store.state.summary?.progress[0]?.summary).toBe('完成 M6-A05')
  })

  it('discards a late Summary response after switching Team Scope', async () => {
    const pending = deferred<TeamSummary>()
    let signal: AbortSignal | undefined
    const gateway = fixtureGateway({
      summary: vi.fn(async (_scope, _session, _invocation, requestSignal) => {
        signal = requestSignal
        return pending.promise
      }),
    })
    const store = createTeamObserverStore(gateway)
    store.activateScope(platform)
    await store.invoke('总结')

    const refresh = store.refreshSummary()
    await vi.waitFor(() => expect(gateway.summary).toHaveBeenCalledTimes(1))
    store.activateScope(security)
    pending.resolve(completed('inv-1').summary!)

    expect(await refresh).toBe(false)
    expect(signal?.aborted).toBe(true)
    expect(store.state.phase).toBe('idle')
    expect(store.state.summary).toBeNull()
  })

  it('discards a late Evidence failure after switching Team Scope', async () => {
    const pending = deferred<TeamObserverEvidence>()
    const gateway = fixtureGateway({ evidence: vi.fn(async () => pending.promise) })
    const store = createTeamObserverStore(gateway)
    store.activateScope(platform)
    await store.invoke('总结')

    const resolution = store.resolveEvidence(0)
    await vi.waitFor(() => expect(gateway.evidence).toHaveBeenCalledTimes(1))
    store.activateScope(security)
    pending.reject(new Error('late provider failure'))

    expect(await resolution).toBeNull()
    expect(store.state.phase).toBe('idle')
    expect(store.state.errorMessage).toBeNull()
  })

  it('discards a late Cancel response after switching Team Scope', async () => {
    const pending = deferred<TeamObserverCancelResponse>()
    const gateway = fixtureGateway({
      invoke: vi.fn(async (_scope, _session, _instruction, _maximum, signal) => ({
        invocationId: 'inv-1', resumed: false, events: runningEvents('inv-1', signal!),
      })),
      cancel: vi.fn(async () => pending.promise),
    })
    const store = createTeamObserverStore(gateway)
    store.activateScope(platform)
    const invocation = store.invoke('总结')
    await vi.waitFor(() => expect(store.state.phase).toBe('running'))

    const cancellation = store.cancel()
    await vi.waitFor(() => expect(store.state.phase).toBe('cancelling'))
    store.activateScope(security)
    pending.resolve({ invocationId: 'inv-1', cancelled: true })

    expect(await cancellation).toBe(false)
    expect(await invocation).toBe(false)
    expect(store.state.phase).toBe('idle')
    expect(store.state.invocationId).toBeNull()
  })
})

function fixtureGateway(overrides: Partial<TeamObserverGateway> = {}): TeamObserverGateway {
  return {
    createSession: vi.fn(async (scope: TeamObserverScope) => ({ sessionId: `session-${scope.teamId}`, observerProfileId: 'team-observer@1', mode: 'READ_ONLY' as const, createdAt: '2026-08-27T08:00:00Z' })),
    invoke: vi.fn(async () => connection('inv-1', [started('inv-1'), completed('inv-1')])),
    resume: vi.fn(async () => connection('inv-1', [completed('inv-1')])),
    cancel: vi.fn(async (_scope, _session, invocationId) => ({ invocationId, cancelled: true })),
    summary: vi.fn(async () => completed('inv-1').summary!),
    evidence: vi.fn(async (_scope, _session, _invocation, evidenceIndex) => ({ evidenceIndex, section: 'PROGRESS', dataScope: 'TEAM', summary: 'evidence', path: '/api/v1/organizations/org-1/teams/team-platform/activity/00000000-0000-4000-8000-000000000001', navigationPath: '/activity?event=1', authorized: true as const })),
    ...overrides,
  }
}

function connection(invocationId: string, values: TeamObserverEvent[]): TeamObserverConnection {
  return { invocationId, resumed: false, events: iterable(values) }
}
async function* iterable(values: TeamObserverEvent[]) { for (const value of values) yield value }
async function* runningEvents(invocationId: string, signal: AbortSignal) {
  yield started(invocationId)
  await new Promise<void>(resolve => {
    if (signal.aborted) resolve()
    else signal.addEventListener('abort', () => resolve(), { once: true })
  })
}
function started(invocationId: string): TeamObserverEvent { return { invocationId, sequence: 0, occurredAt: '2026-08-27T08:00:00Z', type: 'STARTED', summary: null, errorCode: null } }
function completed(invocationId: string): TeamObserverEvent {
  return { invocationId, sequence: 1, occurredAt: '2026-08-27T08:00:01Z', type: 'SUMMARY_COMPLETED', errorCode: null, summary: { observerProfileId: 'team-observer@1', generatedAt: '2026-08-27T08:00:01Z', progress: [{ section: 'PROGRESS', dataScope: 'TEAM_ACTIVITY', summary: '完成 M6-A05', evidenceIndex: 0 }], blockers: [], reviewBacklog: [], pendingConfirmations: [], anomalies: [] } }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}
