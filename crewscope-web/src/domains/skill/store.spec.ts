import { CrewScopeApiError } from '../../api/client'
import { fixtureIds } from '../../test/scopeFixtures'
import { nextTick, watch } from 'vue'
import type { Etagged, SettingsScope } from '../settings/types'
import type { SkillGateway } from './gateway'
import { createSkillStore } from './store'
import type {
  CreateSkillInput,
  DistillSkillInput,
  SkillCommandReceipt,
  SkillDistillationReceipt,
  SkillFilter,
  SkillPage,
  SkillSummary,
  SkillVersion,
  SkillVersionPage,
  UpdateSkillDraftInput,
} from './types'

const platformScope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }
const securityScope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamSecurity }
const skillId = '00000000-0000-0000-0000-000000008101'
const executionId = '00000000-0000-0000-0000-000000008201'

describe('SkillStore', () => {
  it('publishes listing phases through the reactive proxy and resolves empty listings', async () => {
    const gateway = new FixtureSkillGateway()
    vi.spyOn(gateway, 'listSkills').mockImplementation(async (): Promise<SkillPage> => ({ items: [], nextAfter: null }))
    const store = createSkillStore(gateway)
    store.activateScope(platformScope)
    const phases: Array<string | undefined> = []
    const stop = watch(() => store.state.skills.phase, phase => phases.push(phase))

    await store.loadSkills({})

    expect(phases).toContain('loading')
    expect(store.state.skills.phase).toBe('empty')
    expect(store.state.skills.value).toEqual([])
    stop()
  })

  it('continues skill-key keyset pages and merges without duplicating a skillKey', async () => {
    const gateway = new FixtureSkillGateway()
    const cursors: Array<string | null | undefined> = []
    vi.spyOn(gateway, 'listSkills').mockImplementation(async (_scope, _filter, after) => {
      cursors.push(after)
      return after === null
        ? { items: [skill('api-guard'), skill('deploy-runbook-v2')], nextAfter: 'deploy-runbook-v2' }
        : { items: [skill('deploy-runbook-v2'), skill('release-notes')], nextAfter: null }
    })
    const store = createSkillStore(gateway)
    store.activateScope(platformScope)

    await store.loadSkills({})
    await store.loadSkills({}, true)

    expect(cursors).toEqual([null, 'deploy-runbook-v2'])
    expect(store.state.skills.value?.map(item => item.skillKey))
      .toEqual(['api-guard', 'deploy-runbook-v2', 'release-notes'])
    expect(store.state.skills.nextAfter).toBeNull()
    expect(store.loadSkills({}, true)).resolves.toBeUndefined()
  })

  it('serves a ready listing from cache and re-reads only when the caller forces a refresh', async () => {
    const gateway = new FixtureSkillGateway()
    const calls: Array<SkillFilter | undefined> = []
    vi.spyOn(gateway, 'listSkills').mockImplementation(async (_scope, filter): Promise<SkillPage> => {
      calls.push(filter)
      return { items: [skill('deploy-runbook-v2')], nextAfter: null }
    })
    const store = createSkillStore(gateway)
    store.activateScope(platformScope)

    await store.loadSkills({})
    // A filter change without force stays cached — the page watcher owns the force decision.
    await store.loadSkills({ status: 'PUBLISHED' })
    await store.loadSkills({ status: 'PUBLISHED' }, false, true)

    expect(calls.map(filter => filter?.status ?? null)).toEqual([null, 'PUBLISHED'])
  })

  it('isolates a late response after the selected Team Scope changes', async () => {
    const gateway = new FixtureSkillGateway()
    const first = deferred<SkillPage>()
    vi.spyOn(gateway, 'listSkills')
      .mockImplementationOnce(async () => first.promise)
      .mockImplementationOnce(async () => ({ items: [skill('security-only')], nextAfter: null }))
    const store = createSkillStore(gateway)

    store.activateScope(platformScope)
    const slow = store.loadSkills({})
    store.activateScope(securityScope)
    await store.loadSkills({})
    first.resolve({ items: [skill('platform-only')], nextAfter: null })
    await slow
    await nextTick()

    expect(store.state.skills.value?.map(item => item.skillKey)).toEqual(['security-only'])
    expect(store.state.skillDetails).toEqual({})
  })

  it('loads the head with its strong ETag and the version history with a revision cursor', async () => {
    const gateway = new FixtureSkillGateway()
    const cursors: Array<number | null | undefined> = []
    vi.spyOn(gateway, 'listVersions').mockImplementation(async (_scope, _skillId, after): Promise<SkillVersionPage> => {
      cursors.push(after)
      return after == null
        ? { items: [version(1)], nextAfter: 1 }
        : { items: [version(1), version(2)], nextAfter: null }
    })
    const store = createSkillStore(gateway)
    store.activateScope(platformScope)

    await store.loadSkill(skillId)
    await store.loadVersions(skillId)
    await store.loadVersions(skillId, true)

    expect(store.state.skillDetails[skillId]?.value?.etag).toBe('"3"')
    expect(cursors).toEqual([null, 1])
    expect(store.state.versionHistory[skillId]?.value?.map(item => item.revision)).toEqual([1, 2])
  })

  it('resolves an absent effective version as the empty phase, not an error', async () => {
    const gateway = new FixtureSkillGateway()
    vi.spyOn(gateway, 'getEffectiveVersion').mockImplementation(async () => null)
    const store = createSkillStore(gateway)
    store.activateScope(platformScope)

    await store.loadEffectiveVersion(skillId)

    expect(store.state.effectiveVersions[skillId]?.phase).toBe('empty')
    expect(store.state.effectiveVersions[skillId]?.value).toBeNull()
    expect(store.state.effectiveVersions[skillId]?.errorMessage).toBeNull()
  })

  it('records a 409 as the conflict phase with the server head version', async () => {
    const gateway = new FixtureSkillGateway()
    vi.spyOn(gateway, 'saveDraft').mockImplementation(async () => {
      throw new CrewScopeApiError(409, {
        code: 'optimistic_lock_conflict', message: '其他成员已更新此 Skill', correlationId: 'c-1',
        retryable: false, currentVersion: 7, details: {},
      })
    })
    const store = createSkillStore(gateway)
    store.activateScope(platformScope)
    await store.loadSkill(skillId)

    expect(await store.saveDraft(skillId, { content: document('next') }, 'draft-key')).toBe(false)

    expect(store.state.command.phase).toBe('conflict')
    expect(store.state.command.operation).toBe('save-draft')
    expect(store.state.command.currentVersion).toBe(7)
    expect(store.state.command.errorMessage).toBe('其他成员已更新此 Skill')
  })

  it('uses the loaded head ETag for saveDraft and invalidates head and listing on success', async () => {
    const gateway = new FixtureSkillGateway()
    const store = createSkillStore(gateway)
    store.activateScope(platformScope)
    await store.loadSkills({})
    await store.loadSkill(skillId)

    expect(await store.saveDraft(skillId, { content: document('next') }, 'draft-key')).toBe(true)

    expect(gateway.seenEtag).toBe('"3"')
    expect(store.state.skillDetails[skillId]).toBeUndefined()
    expect(store.state.skills.phase).toBe('idle')
  })

  it('invalidates pointer facts and the published-key fold on publish, disable and rollback', async () => {
    const gateway = new FixtureSkillGateway()
    const store = createSkillStore(gateway)
    store.activateScope(platformScope)
    await store.loadSkill(skillId)
    await store.loadVersions(skillId)
    await store.loadEffectiveVersion(skillId)
    await store.loadPublishedKeys()

    expect(store.state.publishedKeys.phase).toBe('ready')

    expect(await store.publishSkill(skillId, 'publish-key')).toBe(true)
    expect(store.state.skillDetails[skillId]).toBeUndefined()
    expect(store.state.versionHistory[skillId]).toBeUndefined()
    expect(store.state.effectiveVersions[skillId]).toBeUndefined()
    // Publish moved the PUBLISHED key set — the picker fold must re-run.
    expect(store.state.publishedKeys.phase).toBe('idle')

    await store.loadSkill(skillId)
    expect(await store.disableSkill(skillId, '弃用', 'disable-key')).toBe(true)
    expect(store.state.publishedKeys.phase).toBe('idle')

    await store.loadSkill(skillId)
    expect(await store.rollbackSkill(skillId, 1, 'rollback-key')).toBe(true)
    expect(store.state.versionHistory[skillId]).toBeUndefined()
  })

  it('folds PUBLISHED keyset pages into the picker catalog and stops at the caps', async () => {
    const gateway = new FixtureSkillGateway()
    const calls: Array<{ after: string | null | undefined, limit: number | undefined }> = []
    let page = 0
    vi.spyOn(gateway, 'listSkills').mockImplementation(async (_scope, _filter, after, limit): Promise<SkillPage> => {
      calls.push({ after: after ?? null, limit })
      const index = page
      page += 1
      const items = Array.from({ length: 50 }, (_unused, offset) => skill(`key-${index * 50 + offset}`))
      // Page 9 pushes past the 500-key cap; the fold must stop without requesting page 10.
      return { items, nextAfter: index < 9 ? `key-${index * 50 + 49}` : null }
    })
    const store = createSkillStore(gateway)
    store.activateScope(platformScope)

    await store.loadPublishedKeys()

    expect(calls.length).toBeLessThanOrEqual(10)
    expect(store.state.publishedKeys.phase).toBe('ready')
    expect(store.state.publishedKeys.keys.length).toBe(500)
    expect(store.state.publishedKeys.keys[0]).toBe('key-0')
    // A ready catalog is served from cache until forced.
    await store.loadPublishedKeys()
    expect(calls.length).toBeLessThanOrEqual(10)
  })

  it('degrades the picker catalog to empty keys on failure instead of throwing', async () => {
    const gateway = new FixtureSkillGateway()
    vi.spyOn(gateway, 'listSkills').mockImplementation(async () => {
      throw new CrewScopeApiError(503, {
        code: 'unavailable', message: '目录暂时不可用', correlationId: 'c-2',
        retryable: true, currentVersion: null, details: {},
      })
    })
    const store = createSkillStore(gateway)
    store.activateScope(platformScope)

    await expect(store.loadPublishedKeys()).resolves.toBeUndefined()

    expect(store.state.publishedKeys.phase).toBe('error')
    expect(store.state.publishedKeys.keys).toEqual([])
    expect(store.state.publishedKeys.errorMessage).toBe('目录暂时不可用')
  })

  it('does not dispatch a head command after its continuation went stale', async () => {
    const gateway = new FixtureSkillGateway()
    const detail = deferred<Etagged<SkillSummary>>()
    vi.spyOn(gateway, 'getSkill').mockImplementation(() => detail.promise)
    const write = vi.spyOn(gateway, 'publishSkill')
    const store = createSkillStore(gateway)
    store.activateScope(platformScope)

    const old = store.publishSkill(skillId, 'publish-key')
    store.clearCommand()
    detail.resolve({ value: skillWithId(skillId), etag: '"3"' })
    expect(await old).toBe(false)
    expect(write).not.toHaveBeenCalled()
  })

  it('ignores a completed command from the previous Scope', async () => {
    const gateway = new FixtureSkillGateway()
    const command = deferred<SkillCommandReceipt>()
    vi.spyOn(gateway, 'createSkill').mockImplementation(async () => command.promise)
    const store = createSkillStore(gateway)
    store.activateScope(platformScope)

    const old = store.createSkill(createInput(), 'old-key')
    store.activateScope(securityScope)
    command.resolve(receipt())

    expect(await old).toBe(false)
    expect(store.state.command.phase).toBe('idle')
  })

  it('exposes the distillation receipt through the shared command slot', async () => {
    const gateway = new FixtureSkillGateway()
    const store = createSkillStore(gateway)
    store.activateScope(platformScope)

    expect(await store.distill({ taskExecutionId: executionId, skillKey: 'postmortem-cache' }, 'distill-key')).toBe(true)

    const exposed = store.distillationReceipt()
    expect(exposed?.skillKey).toBe('postmortem-cache')
    expect(exposed?.replayed).toBe(false)
    expect(exposed?.status).toBe('DRAFT')
    expect(store.state.skills.phase).toBe('idle')
    expect(store.distillationReceipt()).toBe(store.distillationReceipt())
  })
})

class FixtureSkillGateway implements SkillGateway {
  seenEtag: string | null = null

  async listSkills(_scope: SettingsScope, _filter: SkillFilter, _after?: string | null, _limit?: number, _signal?: AbortSignal): Promise<SkillPage> {
    return { items: [skill('deploy-runbook-v2')], nextAfter: null }
  }

  async getSkill(_scope: SettingsScope, _skillId: string, _signal?: AbortSignal): Promise<Etagged<SkillSummary>> {
    return { value: skillWithId(skillId), etag: '"3"' }
  }

  async listVersions(_scope: SettingsScope, _skillId: string, _after?: number | null, _limit?: number, _signal?: AbortSignal): Promise<SkillVersionPage> {
    return { items: [version(1)], nextAfter: null }
  }

  async getVersion(): Promise<Etagged<SkillVersion>> {
    return { value: version(1), etag: `"${'a'.repeat(64)}"` }
  }

  async getEffectiveVersion(_scope: SettingsScope, _skillId: string, _signal?: AbortSignal): Promise<Etagged<SkillVersion> | null> {
    return { value: version(1), etag: `"${'a'.repeat(64)}"` }
  }

  async createSkill(_scope: SettingsScope, _input: CreateSkillInput): Promise<SkillCommandReceipt> {
    return receipt()
  }

  async saveDraft(
    _scope: SettingsScope,
    _skillId: string,
    _input: UpdateSkillDraftInput,
    etag: string,
  ): Promise<SkillCommandReceipt> {
    this.seenEtag = etag
    return receipt()
  }

  async publishSkill(_scope: SettingsScope, _skillId: string, etag: string): Promise<SkillCommandReceipt> {
    this.seenEtag = etag
    return receipt()
  }

  async disableSkill(_scope: SettingsScope, _skillId: string, _reason: string | null, etag: string): Promise<SkillCommandReceipt> {
    this.seenEtag = etag
    return receipt()
  }

  async rollbackSkill(_scope: SettingsScope, _skillId: string, _toRevision: number, etag: string): Promise<SkillCommandReceipt> {
    this.seenEtag = etag
    return receipt()
  }

  async distill(_scope: SettingsScope, input: DistillSkillInput): Promise<SkillDistillationReceipt> {
    return {
      ...receipt(),
      skillId,
      skillKey: input.skillKey,
      status: 'DRAFT',
      origin: { taskExecutionId: executionId, attempt: 2 },
      replayed: false,
    }
  }
}

function skill(skillKey: string): SkillSummary {
  return {
    id: skillId, skillKey, status: 'PUBLISHED',
    effectiveRevision: 1, latestRevision: 1, draft: null, disableReason: null, version: 3,
    createdAt: '2026-10-01T01:00:00Z', updatedAt: '2026-10-02T01:00:00Z',
    createdBy: fixtureIds.principal, updatedBy: fixtureIds.principal, origin: null,
  }
}

function skillWithId(id: string): SkillSummary {
  return { ...skill('deploy-runbook-v2'), id }
}

function version(revision: number): SkillVersion {
  return {
    skillId, revision, previousRevision: revision > 1 ? revision - 1 : null,
    content: document(), contentHash: 'a'.repeat(64),
    createdAt: '2026-10-02T01:00:00Z', createdBy: fixtureIds.principal,
  }
}

function createInput(): CreateSkillInput {
  return { skillKey: 'deploy-runbook-v2', content: document() }
}

function receipt(): SkillCommandReceipt {
  return {
    commandId: '00000000-0000-0000-0000-000000008301',
    domainEventId: '00000000-0000-0000-0000-000000008302',
    committedVersion: 4,
    correlationId: '00000000-0000-0000-0000-000000008303',
  }
}

function document(marker = 'd1') {
  return `---\nname: deploy-runbook-v2\ndescription: Marker ${marker} of the drill.\n---\n\nBody of the document.`
}

function deferred<T>(): { promise: Promise<T>, resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(yes => { resolve = yes })
  return { promise, resolve }
}
