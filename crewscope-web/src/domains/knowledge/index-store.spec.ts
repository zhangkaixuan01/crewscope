import { CrewScopeApiError } from '../../api/client'
import { fixtureIds } from '../../test/scopeFixtures'
import { nextTick, watch } from 'vue'
import type { SettingsScope } from '../settings/types'
import { createKnowledgeIndexStore, type KnowledgeIndexGateway } from './index-store'
import type {
  KnowledgeIndexJob,
  KnowledgeIndexJobFilter,
  KnowledgeIndexJobPage,
  RebuildAccepted,
  RepositoryBuildAccepted,
  RepositoryBuildInput,
} from './types'

const platformScope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform }
const securityScope = { organizationId: fixtureIds.organization, teamId: fixtureIds.teamSecurity }

describe('KnowledgeIndexStore', () => {
  it('publishes listing phases through the reactive proxy and resolves empty listings', async () => {
    const gateway = new FixtureIndexGateway()
    vi.spyOn(gateway, 'listJobs').mockImplementation(async (): Promise<KnowledgeIndexJobPage> => ({ items: [], nextAfter: null }))
    const store = createKnowledgeIndexStore(gateway)
    store.activateScope(platformScope)
    const phases: Array<string | undefined> = []
    const stop = watch(() => store.state.jobs.phase, phase => phases.push(phase))

    await store.loadJobs({})

    expect(phases).toContain('loading')
    expect(store.state.jobs.phase).toBe('empty')
    expect(store.state.jobs.value).toEqual([])
    stop()
  })

  it('continues job-id keyset pages and merges without duplicating a job', async () => {
    const gateway = new FixtureIndexGateway()
    const cursors: Array<string | null | undefined> = []
    vi.spyOn(gateway, 'listJobs').mockImplementation(async (_scope, _filter, after) => {
      cursors.push(after)
      return after == null
        ? { items: [job('a'), job('b')], nextAfter: jobBId }
        : { items: [job('b'), job('c')], nextAfter: null }
    })
    const store = createKnowledgeIndexStore(gateway)
    store.activateScope(platformScope)

    await store.loadJobs({})
    await store.loadJobs({}, true)

    expect(cursors).toEqual([null, jobBId])
    expect(store.state.jobs.value?.map(item => item.id)).toEqual([jobAId, jobBId, jobCId])
    expect(store.state.jobs.nextAfter).toBeNull()
    expect(store.loadJobs({}, true)).resolves.toBeUndefined()
  })

  it('serves a ready listing from cache and re-reads only when the caller forces a refresh', async () => {
    const gateway = new FixtureIndexGateway()
    const calls: Array<KnowledgeIndexJobFilter | undefined> = []
    vi.spyOn(gateway, 'listJobs').mockImplementation(async (_scope, filter) => {
      calls.push(filter)
      return { items: [job('a')], nextAfter: null }
    })
    const store = createKnowledgeIndexStore(gateway)
    store.activateScope(platformScope)

    await store.loadJobs({})
    await store.loadJobs({ status: 'QUEUED' })
    await store.loadJobs({ status: 'QUEUED' }, false, true)

    expect(calls.map(filter => filter?.status ?? null)).toEqual([null, 'QUEUED'])
  })

  it('isolates a late response after the selected Team Scope changes', async () => {
    const gateway = new FixtureIndexGateway()
    const first = deferred<KnowledgeIndexJobPage>()
    vi.spyOn(gateway, 'listJobs')
      .mockImplementationOnce(async () => first.promise)
      .mockImplementationOnce(async () => ({ items: [job('security-only')], nextAfter: null }))
    const store = createKnowledgeIndexStore(gateway)

    store.activateScope(platformScope)
    const slow = store.loadJobs({})
    store.activateScope(securityScope)
    await store.loadJobs({})
    first.resolve({ items: [job('platform-only')], nextAfter: null })
    await slow
    await nextTick()

    expect(store.state.jobs.value?.map(item => item.id)).toEqual(['00000000-0000-0000-0000-000000007404'])
  })

  it('words a rebuild receipt for both the enqueued and the gate-closed zero shape, invalidating the listing', async () => {
    const gateway = new FixtureIndexGateway()
    let enqueued = 2
    vi.spyOn(gateway, 'rebuild').mockImplementation(async () => ({ enqueued }))
    const store = createKnowledgeIndexStore(gateway)
    store.activateScope(platformScope)
    await store.loadJobs({})

    expect(await store.rebuild()).toBe(true)
    expect(store.state.command.message).toBe('已入队 2 个知识重建作业')
    expect(store.state.jobs.phase).toBe('idle')

    store.clearCommand()
    enqueued = 0
    expect(await store.rebuild()).toBe(true)
    // enqueued: 0 is a skip, never an error (contract §7).
    expect(store.state.command.phase).toBe('success')
    expect(store.state.command.message).toBe('未新建作业：索引开关未开启，或没有可重建的生效版本')
  })

  it('words a repository-build receipt for the accepted job and the gate-closed zero shape', async () => {
    const gateway = new FixtureIndexGateway()
    let accepted = true
    vi.spyOn(gateway, 'enqueueRepositoryBuild').mockImplementation(async (): Promise<RepositoryBuildAccepted> =>
      accepted ? { enqueued: 1, job: job('a', 'QUEUED') } : { enqueued: 0, job: null })
    const store = createKnowledgeIndexStore(gateway)
    store.activateScope(platformScope)

    expect(await store.enqueueRepositoryBuild(buildInput())).toBe(true)
    expect(store.state.command.message).toBe('已入队仓库索引作业（00000000，状态：排队中）')

    store.clearCommand()
    accepted = false
    expect(await store.enqueueRepositoryBuild(buildInput())).toBe(true)
    expect(store.state.command.message).toBe('未创建作业：索引开关未开启（跳过，不是错误）')
  })

  it('replaces the cancelled row with the cancel response snapshot', async () => {
    const gateway = new FixtureIndexGateway()
    vi.spyOn(gateway, 'listJobs').mockImplementation(async () => ({ items: [job('a', 'QUEUED'), job('b', 'READY')], nextAfter: null }))
    vi.spyOn(gateway, 'cancelJob').mockImplementation(async () => job('a', 'CANCELLED'))
    const store = createKnowledgeIndexStore(gateway)
    store.activateScope(platformScope)
    await store.loadJobs({})

    expect(await store.cancelJob(jobAId)).toBe(true)

    expect(store.state.command.message).toBe('已取消排队中的作业')
    expect(store.state.jobs.value?.map(item => item.status)).toEqual(['CANCELLED', 'READY'])
  })

  it('surfaces the 409 live status on the cancel banner and keeps the row untouched', async () => {
    const gateway = new FixtureIndexGateway()
    vi.spyOn(gateway, 'listJobs').mockImplementation(async () => ({ items: [job('a', 'EMBEDDING')], nextAfter: null }))
    vi.spyOn(gateway, 'cancelJob').mockImplementation(async () => {
      throw new CrewScopeApiError(409, {
        code: 'knowledge_index_job_not_cancellable',
        message: 'Only a QUEUED job can be cancelled',
        correlationId: '00000000-0000-0000-0000-000000000901',
        retryable: false,
        currentVersion: null,
        details: { jobId: jobAId, status: 'EMBEDDING' },
      })
    })
    const store = createKnowledgeIndexStore(gateway)
    store.activateScope(platformScope)
    await store.loadJobs({})

    expect(await store.cancelJob(jobAId)).toBe(false)

    expect(store.state.command.phase).toBe('error')
    expect(store.state.command.errorMessage).toBe('Only a QUEUED job can be cancelled（当前状态：嵌入中）')
    expect(store.state.jobs.value?.[0]?.status).toBe('EMBEDDING')
  })

  it('rejects a second command while one is pending and resets on demand', async () => {
    const gateway = new FixtureIndexGateway()
    const first = deferred<{ enqueued: number }>()
    vi.spyOn(gateway, 'rebuild').mockImplementationOnce(async () => first.promise)
    const store = createKnowledgeIndexStore(gateway)
    store.activateScope(platformScope)

    const slow = store.rebuild()
    expect(await store.cancelJob(jobAId)).toBe(false)
    first.resolve({ enqueued: 1 })
    expect(await slow).toBe(true)

    store.reset()
    expect(store.state.jobs.phase).toBe('idle')
    expect(store.state.command.phase).toBe('idle')
    expect(() => store.loadJobs({})).rejects.toThrow('Scope is not active')
  })
})

class FixtureIndexGateway implements KnowledgeIndexGateway {
  async listJobs(_scope: SettingsScope, _filter?: KnowledgeIndexJobFilter, _after?: string | null): Promise<KnowledgeIndexJobPage> { throw new Error('not stubbed') }
  async cancelJob(_scope: SettingsScope, _jobId: string): Promise<KnowledgeIndexJob> { throw new Error('not stubbed') }
  async rebuild(_scope: SettingsScope): Promise<RebuildAccepted> { throw new Error('not stubbed') }
  async enqueueRepositoryBuild(_scope: SettingsScope, _input: RepositoryBuildInput): Promise<RepositoryBuildAccepted> { throw new Error('not stubbed') }
}

const jobAId = '00000000-0000-0000-0000-000000007401'
const jobBId = '00000000-0000-0000-0000-000000007402'
const jobCId = '00000000-0000-0000-0000-000000007403'

function job(suffix: 'a' | 'b' | 'c' | 'security-only' | 'platform-only', status: KnowledgeIndexJob['status'] = 'QUEUED'): KnowledgeIndexJob {
  const ids = { a: jobAId, b: jobBId, c: jobCId, 'security-only': '00000000-0000-0000-0000-000000007404', 'platform-only': '00000000-0000-0000-0000-000000007405' }
  return {
    id: ids[suffix],
    source: 'KNOWLEDGE_ENTRY',
    status,
    entryId: '00000000-0000-0000-0000-000000007101',
    projectId: null,
    indexKey: null,
    attempt: 0,
    chunksDone: 0,
    chunksTotal: 1,
    failureCode: null,
    generationBuildSequence: 1,
    claimedBy: null,
    leaseExpiresAt: null,
    createdBy: fixtureIds.principal,
    createdAt: '2026-10-04T09:00:00Z',
    updatedAt: '2026-10-04T09:00:00Z',
  }
}

function buildInput(): RepositoryBuildInput {
  return {
    projectId: fixtureIds.projectCrewScope,
    bindingId: '00000000-0000-0000-0000-000000007501',
    commit: 'a'.repeat(40),
  }
}

function deferred<T>(): { promise: Promise<T>, resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(next => { resolve = next })
  return { promise, resolve }
}
