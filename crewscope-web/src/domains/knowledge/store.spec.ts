import { CrewScopeApiError } from '../../api/client'
import { fixtureIds } from '../../test/scopeFixtures'
import { nextTick, watch } from 'vue'
import type { Etagged, SettingsScope } from '../settings/types'
import type { KnowledgeGateway } from './gateway'
import { createKnowledgeStore } from './store'
import type {
  CreateKnowledgeEntryInput,
  DistillKnowledgeInput,
  KnowledgeCommandReceipt,
  KnowledgeDistillationReceipt,
  KnowledgeEntryFilter,
  KnowledgeEntryPage,
  KnowledgeEntrySummary,
  KnowledgeVersion,
  KnowledgeVersionPage,
  UpdateKnowledgeDraftInput,
} from './types'

const platformScope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }
const securityScope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamSecurity }
const entryId = '00000000-0000-0000-0000-000000007101'
const executionId = '00000000-0000-0000-0000-000000007201'

describe('KnowledgeStore', () => {
  it('publishes listing phases through the reactive proxy and resolves empty listings', async () => {
    const gateway = new FixtureKnowledgeGateway()
    vi.spyOn(gateway, 'listEntries').mockImplementation(async (): Promise<KnowledgeEntryPage> => ({ items: [], nextAfter: null }))
    const store = createKnowledgeStore(gateway)
    store.activateScope(platformScope)
    const phases: Array<string | undefined> = []
    const stop = watch(() => store.state.entries.phase, phase => phases.push(phase))

    await store.loadEntries({})

    expect(phases).toContain('loading')
    expect(store.state.entries.phase).toBe('empty')
    expect(store.state.entries.value).toEqual([])
    stop()
  })

  it('continues entry-key keyset pages and merges without duplicating an entryKey', async () => {
    const gateway = new FixtureKnowledgeGateway()
    const cursors: Array<string | null | undefined> = []
    vi.spyOn(gateway, 'listEntries').mockImplementation(async (_scope, _filter, after) => {
      cursors.push(after)
      return after === null
        ? { items: [entry('api-guard'), entry('deploy-runbook')], nextAfter: 'deploy-runbook' }
        : { items: [entry('deploy-runbook'), entry('release-notes')], nextAfter: null }
    })
    const store = createKnowledgeStore(gateway)
    store.activateScope(platformScope)

    await store.loadEntries({})
    await store.loadEntries({}, true)

    expect(cursors).toEqual([null, 'deploy-runbook'])
    expect(store.state.entries.value?.map(item => item.entryKey))
      .toEqual(['api-guard', 'deploy-runbook', 'release-notes'])
    expect(store.state.entries.nextAfter).toBeNull()
    expect(store.loadEntries({}, true)).resolves.toBeUndefined()
  })

  it('serves a ready listing from cache and re-reads only when the caller forces a refresh', async () => {
    const gateway = new FixtureKnowledgeGateway()
    const calls: Array<KnowledgeEntryFilter | undefined> = []
    vi.spyOn(gateway, 'listEntries').mockImplementation(async (_scope, filter): Promise<KnowledgeEntryPage> => {
      calls.push(filter)
      return { items: [entry('deploy-runbook')], nextAfter: null }
    })
    const store = createKnowledgeStore(gateway)
    store.activateScope(platformScope)

    await store.loadEntries({})
    // A filter change without force stays cached — the page watcher owns the force decision.
    await store.loadEntries({ status: 'PUBLISHED' })
    await store.loadEntries({ status: 'PUBLISHED' }, false, true)

    expect(calls.map(filter => filter?.status ?? null)).toEqual([null, 'PUBLISHED'])
  })

  it('isolates a late response after the selected Team Scope changes', async () => {
    const gateway = new FixtureKnowledgeGateway()
    const first = deferred<KnowledgeEntryPage>()
    vi.spyOn(gateway, 'listEntries')
      .mockImplementationOnce(async () => first.promise)
      .mockImplementationOnce(async () => ({ items: [entry('security-only')], nextAfter: null }))
    const store = createKnowledgeStore(gateway)

    store.activateScope(platformScope)
    const slow = store.loadEntries({})
    store.activateScope(securityScope)
    await store.loadEntries({})
    first.resolve({ items: [entry('platform-only')], nextAfter: null })
    await slow
    await nextTick()

    expect(store.state.entries.value?.map(item => item.entryKey)).toEqual(['security-only'])
    expect(store.state.entryDetails).toEqual({})
  })

  it('loads the head with its strong ETag and the version history with a revision cursor', async () => {
    const gateway = new FixtureKnowledgeGateway()
    const cursors: Array<number | null | undefined> = []
    vi.spyOn(gateway, 'listVersions').mockImplementation(async (_scope, _entryId, after): Promise<KnowledgeVersionPage> => {
      cursors.push(after)
      return after == null
        ? { items: [version(1)], nextAfter: 1 }
        : { items: [version(1), version(2)], nextAfter: null }
    })
    const store = createKnowledgeStore(gateway)
    store.activateScope(platformScope)

    await store.loadEntry(entryId)
    await store.loadVersions(entryId)
    await store.loadVersions(entryId, true)

    expect(store.state.entryDetails[entryId]?.value?.etag).toBe('"3"')
    expect(cursors).toEqual([null, 1])
    expect(store.state.versionHistory[entryId]?.value?.map(item => item.revision)).toEqual([1, 2])
  })

  it('resolves an absent effective version as the empty phase, not an error', async () => {
    const gateway = new FixtureKnowledgeGateway()
    vi.spyOn(gateway, 'getEffectiveVersion').mockImplementation(async () => null)
    const store = createKnowledgeStore(gateway)
    store.activateScope(platformScope)

    await store.loadEffectiveVersion(entryId)

    expect(store.state.effectiveVersions[entryId]?.phase).toBe('empty')
    expect(store.state.effectiveVersions[entryId]?.value).toBeNull()
    expect(store.state.effectiveVersions[entryId]?.errorMessage).toBeNull()
  })

  it('records a 409 as the conflict phase with the server head version', async () => {
    const gateway = new FixtureKnowledgeGateway()
    vi.spyOn(gateway, 'saveDraft').mockImplementation(async () => {
      throw new CrewScopeApiError(409, {
        code: 'optimistic_lock_conflict', message: '其他成员已更新此条目', correlationId: 'c-1',
        retryable: false, currentVersion: 7, details: {},
      })
    })
    const store = createKnowledgeStore(gateway)
    store.activateScope(platformScope)
    await store.loadEntry(entryId)

    expect(await store.saveDraft(entryId, { title: 't', content: 'c' }, 'draft-key')).toBe(false)

    expect(store.state.command.phase).toBe('conflict')
    expect(store.state.command.operation).toBe('save-draft')
    expect(store.state.command.currentVersion).toBe(7)
    expect(store.state.command.errorMessage).toBe('其他成员已更新此条目')
  })

  it('uses the loaded head ETag for saveDraft and invalidates head and listing on success', async () => {
    const gateway = new FixtureKnowledgeGateway()
    const store = createKnowledgeStore(gateway)
    store.activateScope(platformScope)
    await store.loadEntries({})
    await store.loadEntry(entryId)

    expect(await store.saveDraft(entryId, { title: 't', content: 'c' }, 'draft-key')).toBe(true)

    expect(gateway.seenEtag).toBe('"3"')
    expect(store.state.entryDetails[entryId]).toBeUndefined()
    expect(store.state.entries.phase).toBe('idle')
  })

  it('invalidates pointer facts on publish and version details on delete', async () => {
    const gateway = new FixtureKnowledgeGateway()
    const store = createKnowledgeStore(gateway)
    store.activateScope(platformScope)
    await store.loadEntry(entryId)
    await store.loadVersions(entryId)
    await store.loadEffectiveVersion(entryId)
    await store.loadVersion(entryId, 1)

    expect(await store.publishEntry(entryId, 'publish-key')).toBe(true)
    expect(store.state.versionHistory[entryId]).toBeUndefined()
    expect(store.state.effectiveVersions[entryId]).toBeUndefined()

    await store.loadVersion(entryId, 1)
    expect(await store.deleteEntry(entryId, 'delete-key')).toBe(true)
    expect(store.state.versionDetails[`${entryId}:1`]).toBeUndefined()
    expect(store.state.versionHistory[entryId]).toBeUndefined()
  })

  it('does not dispatch a head command after its continuation went stale', async () => {
    const gateway = new FixtureKnowledgeGateway()
    const detail = deferred<Etagged<KnowledgeEntrySummary>>()
    vi.spyOn(gateway, 'getEntry').mockImplementation(() => detail.promise)
    const write = vi.spyOn(gateway, 'publishEntry')
    const store = createKnowledgeStore(gateway)
    store.activateScope(platformScope)

    const old = store.publishEntry(entryId, 'publish-key')
    store.clearCommand()
    detail.resolve({ value: entryWithId(entryId), etag: '"3"' })
    expect(await old).toBe(false)
    expect(write).not.toHaveBeenCalled()
  })

  it('ignores a completed command from the previous Scope', async () => {
    const gateway = new FixtureKnowledgeGateway()
    const command = deferred<KnowledgeCommandReceipt>()
    vi.spyOn(gateway, 'createEntry').mockImplementation(async () => command.promise)
    const store = createKnowledgeStore(gateway)
    store.activateScope(platformScope)

    const old = store.createEntry(createInput(), 'old-key')
    store.activateScope(securityScope)
    command.resolve(receipt())

    expect(await old).toBe(false)
    expect(store.state.command.phase).toBe('idle')
  })

  it('exposes the distillation receipt through the shared command slot', async () => {
    const gateway = new FixtureKnowledgeGateway()
    const store = createKnowledgeStore(gateway)
    store.activateScope(platformScope)

    expect(await store.distill({ taskExecutionId: executionId, entryKey: 'postmortem-cache' }, 'distill-key')).toBe(true)

    const exposed = store.distillationReceipt()
    expect(exposed?.entryKey).toBe('postmortem-cache')
    expect(exposed?.replayed).toBe(false)
    expect(exposed?.indexStatus).toBe('PENDING')
    expect(store.state.entries.phase).toBe('idle')
    expect(store.distillationReceipt()).toBe(store.distillationReceipt())
  })
})

class FixtureKnowledgeGateway implements KnowledgeGateway {
  seenEtag: string | null = null

  async listEntries(_scope: SettingsScope, _filter: KnowledgeEntryFilter, _after?: string | null, _limit?: number, _signal?: AbortSignal): Promise<KnowledgeEntryPage> {
    return { items: [entry('deploy-runbook')], nextAfter: null }
  }

  async getEntry(_scope: SettingsScope, _entryId: string, _signal?: AbortSignal): Promise<Etagged<KnowledgeEntrySummary>> {
    return { value: entryWithId(entryId), etag: '"3"' }
  }

  async listVersions(_scope: SettingsScope, _entryId: string, _after?: number | null, _limit?: number, _signal?: AbortSignal): Promise<KnowledgeVersionPage> {
    return { items: [version(1)], nextAfter: null }
  }

  async getVersion(): Promise<Etagged<KnowledgeVersion>> {
    return { value: version(1), etag: `"${'a'.repeat(64)}"` }
  }

  async getEffectiveVersion(_scope: SettingsScope, _entryId: string, _signal?: AbortSignal): Promise<Etagged<KnowledgeVersion> | null> {
    return { value: version(1), etag: `"${'a'.repeat(64)}"` }
  }

  async createEntry(_scope: SettingsScope, _input: CreateKnowledgeEntryInput): Promise<KnowledgeCommandReceipt> {
    return receipt()
  }

  async saveDraft(
    _scope: SettingsScope,
    _entryId: string,
    _input: UpdateKnowledgeDraftInput,
    etag: string,
  ): Promise<KnowledgeCommandReceipt> {
    this.seenEtag = etag
    return receipt()
  }

  async publishEntry(_scope: SettingsScope, _entryId: string, etag: string): Promise<KnowledgeCommandReceipt> {
    this.seenEtag = etag
    return receipt()
  }

  async retireEntry(_scope: SettingsScope, _entryId: string, etag: string): Promise<KnowledgeCommandReceipt> {
    this.seenEtag = etag
    return receipt()
  }

  async deleteEntry(_scope: SettingsScope, _entryId: string, etag: string): Promise<KnowledgeCommandReceipt> {
    this.seenEtag = etag
    return receipt()
  }

  async distill(_scope: SettingsScope, _input: DistillKnowledgeInput): Promise<KnowledgeDistillationReceipt> {
    return {
      ...receipt(),
      entryId,
      entryKey: _input.entryKey,
      origin: { taskExecutionId: executionId, attempt: 2 },
      indexStatus: 'PENDING',
      replayed: false,
    }
  }

  // The I01c job plane belongs to the index store (F01b); this fixture must only satisfy the shape.
  async listJobs(): Promise<never> { throw new Error('not used by the entry store') }
  async getJob(): Promise<never> { throw new Error('not used by the entry store') }
  async cancelJob(): Promise<never> { throw new Error('not used by the entry store') }
  async rebuild(): Promise<never> { throw new Error('not used by the entry store') }
  async enqueueRepositoryBuild(): Promise<never> { throw new Error('not used by the entry store') }
}

function entry(entryKey: string): KnowledgeEntrySummary {
  return {
    id: entryId, entryKey, category: 'RUNBOOK', status: 'PUBLISHED', indexStatus: 'INDEXED',
    effectiveRevision: 1, latestRevision: 1, draft: null, version: 3,
    createdAt: '2026-10-01T01:00:00Z', updatedAt: '2026-10-02T01:00:00Z',
    createdBy: fixtureIds.principal, updatedBy: fixtureIds.principal, origin: null,
  }
}

function entryWithId(id: string): KnowledgeEntrySummary {
  return { ...entry('deploy-runbook'), id }
}

function version(revision: number): KnowledgeVersion {
  return {
    entryId, revision, previousRevision: revision > 1 ? revision - 1 : null,
    title: '部署手册', content: '先排干连接池。', contentHash: 'a'.repeat(64), indexStatus: 'INDEXED',
    createdAt: '2026-10-02T01:00:00Z', createdBy: fixtureIds.principal,
  }
}

function createInput(): CreateKnowledgeEntryInput {
  return { entryKey: 'deploy-runbook', category: 'RUNBOOK', title: '部署手册', content: '先排干连接池。' }
}

function receipt(): KnowledgeCommandReceipt {
  return {
    commandId: '00000000-0000-0000-0000-000000007301',
    domainEventId: '00000000-0000-0000-0000-000000007302',
    committedVersion: 4,
    correlationId: '00000000-0000-0000-0000-000000007303',
  }
}

function deferred<T>(): { promise: Promise<T>, resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(yes => { resolve = yes })
  return { promise, resolve }
}
