import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { CrewScopeApiError } from '../api/client'
import { AUTH_PRINCIPAL, permissions, type AuthenticatedPrincipal } from '../app/auth'
import { createCrewScopeRouter } from '../app/router'
import { fixtureAuthStore } from '../test/authFixtures'
import type { SettingsScope } from '../domains/settings/types'
import type { KnowledgeIndexGateway } from '../domains/knowledge/index-store'
import { createKnowledgeIndexStore, KNOWLEDGE_INDEX_STORE } from '../domains/knowledge/index-store'
import type {
  KnowledgeIndexJob,
  KnowledgeIndexJobFilter,
  KnowledgeIndexJobPage,
  RebuildAccepted,
  RepositoryBuildAccepted,
  RepositoryBuildInput,
} from '../domains/knowledge/types'
import { CODING_STORE, type CodingStore } from '../domains/coding/store'
import type { CodingResource } from '../domains/coding/store'
import type { RepositoryBinding } from '../domains/coding/types'
import { createScopeStore, SCOPE_STORE } from '../domains/scope/store'
import { FixtureScopeGateway, fixtureIds } from '../test/scopeFixtures'
import { reactive } from 'vue'
import KnowledgeIndexPage from './KnowledgeIndexPage.vue'

const jobAId = '00000000-0000-0000-0000-000000007401'

const manager: AuthenticatedPrincipal = {
  id: fixtureIds.principal, accountId: '00000000-0000-0000-0000-000000000201', displayName: 'Knowledge Manager', role: 'Team Owner',
  organizationId: fixtureIds.organization, organization: 'Test Organization',
  permissions: new Set(Object.values(permissions)),
}

describe('KnowledgeIndexPage', () => {
  it('renders the job listing with the URL filters applied as gateway filters', async () => {
    const { wrapper, gateway } = await mountPage(manager, 'source=REPOSITORY&status=EMBEDDING')

    expect(wrapper.get('section[aria-label="索引作业列表"]').text()).toContain('绑定 00000000')
    expect(gateway.listCalls.at(-1)).toMatchObject({ source: 'REPOSITORY', status: 'EMBEDDING' })
    wrapper.unmount()
  })

  it('re-reads with the new filter once the URL query changes', async () => {
    const { wrapper, gateway, router } = await mountPage(manager, '')

    await wrapper.findAll('select')[1]!.setValue('FAILED')
    await flushPromises()

    expect(router.currentRoute.value.query.status).toBe('FAILED')
    expect(gateway.listCalls.at(-1)).toMatchObject({ status: 'FAILED' })
    wrapper.unmount()
  })

  it('cancels a queued job from its row and swaps in the cancelled snapshot', async () => {
    const { wrapper, gateway } = await mountPage(manager, '')
    const listsBefore = gateway.listCalls.length

    await wrapper.findAll('button').find(button => button.text() === '取消作业')!.trigger('click')
    await flushPromises()

    expect(gateway.cancelCalls).toEqual([jobAId])
    expect(wrapper.text()).toContain('已取消排队中的作业')
    expect(wrapper.text()).toContain('已取消')
    // 取消 200 就地替换行，不再整页重读。
    expect(gateway.listCalls.length).toBe(listsBefore)
    wrapper.unmount()
  })

  it('words a 409 cancel with the live status and keeps the row', async () => {
    const { wrapper, gateway } = await mountPage(manager, '', { cancelStatus: 'EMBEDDING' })

    await wrapper.findAll('button').find(button => button.text() === '取消作业')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Only a QUEUED job can be cancelled（当前状态：嵌入中）')
    expect(wrapper.text()).toContain('嵌入中')
    wrapper.unmount()
  })

  it('words both rebuild receipts and refreshes the listing on success', async () => {
    const { wrapper, gateway } = await mountPage(manager, '', { rebuildEnqueued: 2 })
    const listsBefore = gateway.listCalls.length

    await wrapper.findAll('button').find(button => button.text().includes('重建知识索引'))!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('已入队 2 个知识重建作业')
    expect(gateway.listCalls.length).toBeGreaterThan(listsBefore)

    gateway.rebuildEnqueued = 0
    await wrapper.get('button[aria-label="关闭提示"]').trigger('click')
    await wrapper.findAll('button').find(button => button.text().includes('重建知识索引'))!.trigger('click')
    await flushPromises()

    // 闸关 enqueued:0 是跳过，不是错误（契约 §7）。
    expect(wrapper.text()).toContain('未新建作业：索引开关未开启，或没有可重建的生效版本')
    wrapper.unmount()
  })

  it('builds a repository index through the cascading dialog', async () => {
    const { wrapper, gateway } = await mountPage(manager, '')

    await wrapper.findAll('button').find(button => button.text().includes('构建仓库索引'))!.trigger('click')
    const dialog = wrapper.get('form.build-dialog')
    await wrapper.findAll('select').at(-2)!.setValue(fixtureIds.projectCrewScope)
    await flushPromises()
    await wrapper.findAll('select').at(-1)!.setValue('binding-1')
    await dialog.get('input').setValue('a'.repeat(40))
    await dialog.trigger('submit')
    await flushPromises()

    expect(gateway.buildInputs).toEqual([{ projectId: fixtureIds.projectCrewScope, bindingId: 'binding-1', commit: 'a'.repeat(40) }])
    expect(wrapper.find('form.build-dialog').exists()).toBe(false)
    expect(wrapper.text()).toContain('已入队仓库索引作业（00000000，状态：排队中）')
    wrapper.unmount()
  })

  it('keeps the read surface complete while hiding commands and technical facts from members', async () => {
    const member = { ...manager, role: 'Member', permissions: new Set([permissions.scopeRead]) }
    const { wrapper } = await mountPage(member, '')

    expect(wrapper.get('section[aria-label="索引作业列表"]').text()).toContain('绑定 00000000')
    expect(wrapper.text()).not.toContain('重建知识索引')
    expect(wrapper.text()).not.toContain('构建仓库索引')
    expect(wrapper.text()).not.toContain('取消作业')
    wrapper.unmount()
  })

  it('polls on the 15-second interval and stops when the checkbox is cleared', async () => {
    vi.useFakeTimers()
    try {
      const { wrapper, gateway } = await mountPage(manager, '')
      const listsBefore = gateway.listCalls.length

      await vi.advanceTimersByTimeAsync(15_000)
      expect(gateway.listCalls.length).toBe(listsBefore + 1)

      await wrapper.get('input[type="checkbox"]').setValue(false)
      await vi.advanceTimersByTimeAsync(45_000)
      expect(gateway.listCalls.length).toBe(listsBefore + 1)
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })
})

interface FixtureOverrides {
  cancelStatus?: 'EMBEDDING'
  rebuildEnqueued?: number
}

async function mountPage(principal: AuthenticatedPrincipal, query: string, overrides: FixtureOverrides = {}) {
  const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(principal))
  const scopeStore = createScopeStore(new FixtureScopeGateway(), principal)
  await scopeStore.synchronize(fixtureIds.teamPlatform, fixtureIds.projectCrewScope)
  const gateway = new FixtureIndexPageGateway(overrides)
  const store = createKnowledgeIndexStore(gateway)
  const codingStore = fixtureCodingStore()
  await router.push(`/knowledge/index?team=${fixtureIds.teamPlatform}${query ? `&${query}` : ''}`)
  await router.isReady()
  const wrapper = mount(KnowledgeIndexPage, {
    global: {
      plugins: [router],
      provide: {
        [AUTH_PRINCIPAL as symbol]: principal,
        [SCOPE_STORE as symbol]: scopeStore,
        [KNOWLEDGE_INDEX_STORE as symbol]: store,
        [CODING_STORE as symbol]: codingStore,
      },
      stubs: { AppShell: { template: '<main><slot name="actions"/><slot/></main>' } },
    },
  })
  await flushPromises()
  await flushPromises()
  return { wrapper, gateway, store, router, scopeStore }
}

class FixtureIndexPageGateway implements KnowledgeIndexGateway {
  listCalls: Array<KnowledgeIndexJobFilter | undefined> = []
  cancelCalls: string[] = []
  buildInputs: RepositoryBuildInput[] = []
  rebuildEnqueued: number

  constructor(private readonly overrides: FixtureOverrides) {
    this.rebuildEnqueued = overrides.rebuildEnqueued ?? 1
  }

  async listJobs(_scope: SettingsScope, filter?: KnowledgeIndexJobFilter): Promise<KnowledgeIndexJobPage> {
    this.listCalls.push(filter)
    return { items: [job()], nextAfter: null }
  }

  async cancelJob(_scope: SettingsScope, jobId: string): Promise<KnowledgeIndexJob> {
    this.cancelCalls.push(jobId)
    if (this.overrides.cancelStatus) {
      throw new CrewScopeApiError(409, {
        code: 'knowledge_index_job_not_cancellable', message: 'Only a QUEUED job can be cancelled', correlationId: 'c-1',
        retryable: false, currentVersion: null, details: { jobId, status: this.overrides.cancelStatus },
      })
    }
    return job({ status: 'CANCELLED', failureCode: 'CANCELLED' })
  }

  async rebuild(_scope: SettingsScope): Promise<RebuildAccepted> {
    return { enqueued: this.rebuildEnqueued }
  }

  async enqueueRepositoryBuild(_scope: SettingsScope, input: RepositoryBuildInput): Promise<RepositoryBuildAccepted> {
    this.buildInputs.push(input)
    return { enqueued: 1, job: job({ status: 'QUEUED' }) }
  }
}

function fixtureCodingStore(): CodingStore {
  return {
    state: reactive({
      repositories: { phase: 'ready', value: [binding()], errorMessage: null, errorStatus: null } as CodingResource<RepositoryBinding[]>,
    }),
    async loadRepositories() {},
  } as unknown as CodingStore
}

function binding(): RepositoryBinding {
  return {
    id: 'binding-1', organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform, workspaceId: 'workspace-1',
    projectId: fixtureIds.projectCrewScope, kind: 'GITHUB', repositoryKey: 'crewscope/backend', defaultBranch: 'main',
    status: 'ACTIVE', version: 1, createdAt: '2026-10-01T01:00:00Z', createdByPrincipalId: fixtureIds.principal,
    updatedAt: '2026-10-01T01:00:00Z', updatedByPrincipalId: fixtureIds.principal,
  }
}

function job(overrides: Partial<KnowledgeIndexJob> = {}): KnowledgeIndexJob {
  return {
    id: jobAId, source: 'REPOSITORY', status: 'QUEUED', entryId: null,
    projectId: fixtureIds.projectCrewScope,
    indexKey: { bindingId: '00000000-0000-0000-0000-000000007501', commit: 'a'.repeat(40), chunkPolicyHash: 'c'.repeat(64), modelKey: 'text-embedding-v4', modelRevision: 3 },
    attempt: 0, chunksDone: 0, chunksTotal: 0, failureCode: null, generationBuildSequence: 1,
    claimedBy: null, leaseExpiresAt: null, createdBy: fixtureIds.principal,
    createdAt: '2026-10-04T09:00:00Z', updatedAt: '2026-10-04T09:00:00Z',
    ...overrides,
  }
}
