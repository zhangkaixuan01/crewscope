import { CrewScopeApiError } from '../../api/client'
import { fixtureIds } from '../../test/scopeFixtures'
import { nextTick, watch } from 'vue'
import type { SettingsScope } from '../settings/types'
import { createAgentMemoryStore, type AgentMemoryGateway } from './memory-store'
import { agentMemoryStateOf } from './types'
import type { AgentMemoryClearance, AgentMemoryView } from './types'

const platformScope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }
const securityScope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamSecurity }
const profileId = '00000000-0000-0000-0000-000000006101'
const otherProfileId = '00000000-0000-0000-0000-000000006102'

describe('AgentMemoryStore', () => {
  it('publishes view phases per profile and serves ready views from cache', async () => {
    const gateway = new FixtureMemoryGateway()
    const calls: string[] = []
    vi.spyOn(gateway, 'getMemory').mockImplementation(async (_scope, profileId) => {
      calls.push(profileId)
      return view({})
    })
    const store = createAgentMemoryStore(gateway)
    store.activateScope(platformScope)
    const phases: Array<string | undefined> = []
    const stop = watch(() => store.state.views[profileId]?.phase, phase => phases.push(phase))

    await store.load(profileId)
    await store.load(profileId)
    await store.load(otherProfileId)

    expect(phases).toContain('loading')
    expect(calls).toEqual([profileId, otherProfileId])
    expect(store.state.views[profileId]?.phase).toBe('ready')
    expect(store.state.views[otherProfileId]?.phase).toBe('ready')
    stop()
  })

  it('passes the three view states through unchanged — degraded never reads as unconfigured', async () => {
    const gateway = new FixtureMemoryGateway()
    const views = new Map([
      [profileId, view({ policy: null, degraded: 'POLICY_UNAVAILABLE', entries: [] })],
      [otherProfileId, view({ policyReference: null, policy: null, entries: [], entryCount: 0 })],
    ])
    vi.spyOn(gateway, 'getMemory').mockImplementation(async (_scope, id) => views.get(id)!)
    const store = createAgentMemoryStore(gateway)
    store.activateScope(platformScope)

    await store.load(profileId)
    await store.load(otherProfileId)

    expect(agentMemoryStateOf(store.state.views[profileId]!.value!)).toEqual({ kind: 'degraded', view: views.get(profileId) })
    expect(agentMemoryStateOf(store.state.views[otherProfileId]!.value!)).toEqual({ kind: 'unconfigured' })
  })

  it('isolates a late response after the selected Team Scope changes', async () => {
    const gateway = new FixtureMemoryGateway()
    const first = deferred<AgentMemoryView>()
    vi.spyOn(gateway, 'getMemory')
      .mockImplementationOnce(async () => first.promise)
      .mockImplementationOnce(async () => view({ clearanceGeneration: 7 }))
    const store = createAgentMemoryStore(gateway)

    store.activateScope(platformScope)
    const slow = store.load(profileId)
    store.activateScope(securityScope)
    await store.load(profileId)
    first.resolve(view({ clearanceGeneration: 9 }))
    await slow
    await nextTick()

    expect(store.state.views[profileId]?.value?.clearanceGeneration).toBe(7)
  })

  it('clears, words the receipt banner and re-reads the view', async () => {
    const gateway = new FixtureMemoryGateway()
    let cleared = false
    vi.spyOn(gateway, 'getMemory').mockImplementation(async () => view(cleared ? { clearanceGeneration: 4, entries: [], entryCount: 0 } : {}))
    vi.spyOn(gateway, 'clearMemory').mockImplementation(async (): Promise<AgentMemoryClearance> => {
      cleared = true
      return { clearedCount: 2, clearanceGeneration: 4 }
    })
    const store = createAgentMemoryStore(gateway)
    store.activateScope(platformScope)
    await store.load(profileId)

    expect(await store.clearMemory(profileId)).toBe(true)

    expect(store.state.clear.phase).toBe('success')
    expect(store.state.clear.message).toBe('已清除 2 条记忆 · 清空代际 4')
    // The receipt is the fact, and the entry table re-reads to the cleared state.
    expect(store.state.views[profileId]?.phase).toBe('ready')
    expect(store.state.views[profileId]?.value?.entries).toEqual([])
    expect(store.state.views[profileId]?.value?.clearanceGeneration).toBe(4)
  })

  it('words a repeated clear as the zero-entry structural replay it is', async () => {
    const gateway = new FixtureMemoryGateway()
    let generation = 4
    vi.spyOn(gateway, 'getMemory').mockImplementation(async () => view({ clearanceGeneration: generation, entries: [], entryCount: 0 }))
    vi.spyOn(gateway, 'clearMemory').mockImplementation(async (): Promise<AgentMemoryClearance> => {
      generation += 1
      return { clearedCount: 0, clearanceGeneration: generation }
    })
    const store = createAgentMemoryStore(gateway)
    store.activateScope(platformScope)
    await store.load(profileId)

    expect(await store.clearMemory(profileId)).toBe(true)
    expect(store.state.clear.message).toBe('已清除 0 条记忆 · 清空代际 5')
    store.clearCommand()
    expect(await store.clearMemory(profileId)).toBe(true)

    expect(store.state.clear.message).toBe('已清除 0 条记忆 · 清空代际 6')
  })

  it('surfaces clear errors with their code and rejects a second clear while pending', async () => {
    const gateway = new FixtureMemoryGateway()
    vi.spyOn(gateway, 'getMemory').mockImplementation(async () => view({}))
    const first = deferred<AgentMemoryClearance>()
    vi.spyOn(gateway, 'clearMemory')
      .mockImplementationOnce(async () => first.promise)
      .mockImplementationOnce(async () => { throw new CrewScopeApiError(403, {
        code: 'policy_denied',
        message: 'membership revoked',
        correlationId: '00000000-0000-0000-0000-000000000901',
        retryable: false,
        currentVersion: null,
        details: {},
      }) })
    const store = createAgentMemoryStore(gateway)
    store.activateScope(platformScope)
    await store.load(profileId)

    const slow = store.clearMemory(profileId)
    expect(await store.clearMemory(profileId)).toBe(false)
    first.resolve({ clearedCount: 1, clearanceGeneration: 5 })
    expect(await slow).toBe(true)

    store.clearCommand()
    expect(await store.clearMemory(profileId)).toBe(false)
    expect(store.state.clear.phase).toBe('error')
    expect(store.state.clear.errorCode).toBe('policy_denied')
    expect(store.state.clear.errorStatus).toBe(403)

    store.reset()
    expect(store.state.views[profileId]).toBeUndefined()
    expect(() => store.load(profileId)).rejects.toThrow('Scope is not active')
  })
})

class FixtureMemoryGateway implements AgentMemoryGateway {
  async getMemory(_scope: SettingsScope, _profileId: string): Promise<AgentMemoryView> { throw new Error('not stubbed') }
  async clearMemory(_scope: SettingsScope, _profileId: string): Promise<AgentMemoryClearance> { throw new Error('not stubbed') }
}

function view(extra: Partial<AgentMemoryView> = {}): AgentMemoryView {
  return {
    policyReference: { id: '7f2c9d64-5b1a-4f0e-9a3d-2c8b1e6f4a20', version: 1 },
    policy: {
      policyId: '7f2c9d64-5b1a-4f0e-9a3d-2c8b1e6f4a20', version: 1,
      ttlDays: 90, maxEntriesPerOwner: 100, valueMaxBytes: 1024,
    },
    degraded: null,
    clearanceGeneration: 3,
    entries: [{
      memoryKey: 'reply-language', value: '简体中文', version: 2,
      expiresAt: '2026-12-01T00:00:00Z', createdAt: '2026-08-04T09:00:00Z',
      updatedAt: '2026-09-04T09:00:00Z', createdBy: fixtureIds.principal, updatedBy: fixtureIds.principal,
    }],
    entryCount: 1,
    ...extra,
  }
}

function deferred<T>(): { promise: Promise<T>, resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(next => { resolve = next })
  return { promise, resolve }
}
