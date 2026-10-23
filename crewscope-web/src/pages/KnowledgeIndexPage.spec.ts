import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory } from 'vue-router'
import { CrewScopeApiError } from '../api/client'
import { AUTH_PRINCIPAL, permissions, type AuthenticatedPrincipal } from '../app/auth'
import { createCrewScopeRouter } from '../app/router'
import { fixtureAuthStore } from '../test/authFixtures'
import type { SettingsScope } from '../domains/settings/types'
import type { KnowledgeIndexGateway } from '../domains/knowledge/index-store'
import { createKnowledgeIndexStore, KNOWLEDGE_INDEX_STORE } from '../domains/knowledge/index-store'
import type { KnowledgeGateway } from '../domains/knowledge/gateway'
import { createKnowledgeStore, KNOWLEDGE_STORE } from '../domains/knowledge/store'
import type {
  KnowledgeCommandReceipt,
  KnowledgeEntryFilter,
  KnowledgeEntryPage,
  KnowledgeIndexJob,
  KnowledgeIndexJobFilter,
  KnowledgeIndexJobPage,
  KnowledgeSwitchConfig,
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
import type { VueWrapper } from '@vue/test-utils'
import KnowledgeIndexPage from './KnowledgeIndexPage.vue'

const jobAId = '00000000-0000-0000-0000-000000007401'
// uuidQuery 要求 RFC 9562 version/variant 位——与知识页条目 fixture 同一 id 形态。
const entryJobId = '00000000-0000-1000-8000-000000007101'

/** L16：重建/自动刷新收进 overflow 菜单（弹层 v-if，收起时不在 DOM），先开菜单再取内容。
 * 幂等：banner 的关闭按钮不派发 pointerdown，jsdom 里菜单不会被外部点击关掉，重复 toggle 反而收起。 */
async function openMoreActions(wrapper: VueWrapper): Promise<void> {
  const trigger = wrapper.get('button[aria-label="更多索引动作"]')
  if (trigger.attributes('aria-expanded') !== 'true') await trigger.trigger('click')
  await flushPromises()
}

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

  it('narrows the listing to one entry through the deep-link filter and clears it (R16)', async () => {
    const { wrapper, gateway, router } = await mountPage(manager, `entryId=${entryJobId}`)

    // 过滤事实常显在筛选区，可一键清除。
    expect(wrapper.get('.job-list__entry-filter').text()).toContain(entryJobId.slice(0, 8))
    expect(gateway.listCalls.at(-1)).toMatchObject({ entryId: entryJobId })

    await wrapper.get('button[aria-label="清除条目过滤"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.entryId).toBeUndefined()
    expect(gateway.listCalls.at(-1)).not.toHaveProperty('entryId')
    wrapper.unmount()
  })

  it('retries a failed knowledge-entry job per entry through the A04 command (R17)', async () => {
    const { wrapper, gateway } = await mountPage(manager, '', { failedEntryJob: true })

    await wrapper.findAll('button').find(button => button.text() === '重试索引')!.trigger('click')
    await flushPromises()

    // 每次点击是一条独立命令（新幂等键），成功后条目过滤队列立即重读。
    expect(gateway.refreshCalls).toEqual([entryJobId])
    expect(gateway.listCalls.length).toBeGreaterThan(1)
    expect(wrapper.text()).toContain('已提交该条目的索引刷新命令')
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

    // L16：重建收进 overflow 菜单，先开菜单才能拿到按钮；M13b-F03（R31）命令前先过确认对话框。
    await openMoreActions(wrapper)
    await wrapper.findAll('button').find(button => button.text().includes('重建知识索引'))!.trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '确认重建')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('已入队 2 个知识重建作业')
    expect(gateway.listCalls.length).toBeGreaterThan(listsBefore)

    gateway.rebuildEnqueued = 0
    await wrapper.get('button[aria-label="关闭提示"]').trigger('click')
    await openMoreActions(wrapper)
    await wrapper.findAll('button').find(button => button.text().includes('重建知识索引'))!.trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '确认重建')!.trigger('click')
    await flushPromises()

    // 闸关 enqueued:0 是跳过，不是错误（契约 §7）。
    expect(wrapper.text()).toContain('未新建作业：索引开关未开启，或没有可重建的生效版本')
    wrapper.unmount()
  })

  it('keeps the rebuild command unissued when the confirm dialog is cancelled (R31)', async () => {
    const { wrapper, gateway } = await mountPage(manager, '')
    await openMoreActions(wrapper)
    await wrapper.findAll('button').find(button => button.text().includes('重建知识索引'))!.trigger('click')

    // 确认对话框先呈现影响说明；取消即关闭且命令未发。
    expect(wrapper.text()).toContain('现有队列被替换为新的重建任务')
    await wrapper.findAll('button').find(button => button.text() === '取消')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('现有队列被替换为新的重建任务')
    expect(gateway.rebuildCalls).toBe(0)
    wrapper.unmount()
  })

  it('builds a repository index through the cascading dialog', async () => {
    const { wrapper, gateway } = await mountPage(manager, '')

    await wrapper.findAll('button').find(button => button.text().includes('构建仓库索引'))!.trigger('click')
    const dialog = wrapper.get('form.build-dialog__content')
    await wrapper.findAll('select').at(-2)!.setValue(fixtureIds.projectCrewScope)
    await flushPromises()
    await wrapper.findAll('select').at(-1)!.setValue('binding-1')
    await dialog.get('input').setValue('a'.repeat(40))
    await dialog.trigger('submit')
    await flushPromises()

    expect(gateway.buildInputs).toEqual([{ projectId: fixtureIds.projectCrewScope, bindingId: 'binding-1', commit: 'a'.repeat(40) }])
    expect(wrapper.find('form.build-dialog__content').exists()).toBe(false)
    expect(wrapper.text()).toContain('已入队仓库索引作业（00000000，状态：排队中）')
    wrapper.unmount()
  })

  it('keeps the read surface complete while hiding commands and technical facts from members', async () => {
    const member = { ...manager, role: 'Member', permissions: new Set([permissions.scopeRead]) }
    const { wrapper } = await mountPage(member, '')

    expect(wrapper.get('section[aria-label="索引作业列表"]').text()).toContain('绑定 00000000')
    // 菜单开着也没有重建入口（菜单收起时弹层不在 DOM，断言会空转）。
    await openMoreActions(wrapper)
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

      await openMoreActions(wrapper)
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
  /** R17: serves a failed knowledge-entry job instead of the default repository row. */
  failedEntryJob?: boolean
}

async function mountPage(principal: AuthenticatedPrincipal, query: string, overrides: FixtureOverrides = {}) {
  const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(principal))
  const scopeStore = createScopeStore(new FixtureScopeGateway(), principal)
  await scopeStore.synchronize(fixtureIds.teamPlatform, fixtureIds.projectCrewScope)
  const gateway = new FixtureIndexPageGateway(overrides)
  const store = createKnowledgeIndexStore(gateway)
  // R16：作业行的条目标题映射来自条目 store 的第一页——页面消费它，spec 只喂两个读面。
  const knowledgeStore = createKnowledgeStore(new FixtureEntryTitleGateway())
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
        [KNOWLEDGE_STORE as symbol]: knowledgeStore,
        [CODING_STORE as symbol]: codingStore,
      },
      stubs: { teleport: true, AppShell: { template: '<main><slot name="actions"/><slot/></main>' } },
    },
  })
  await flushPromises()
  await flushPromises()
  return { wrapper, gateway, store, router, scopeStore }
}

/** R16：条目标题映射的读面——作业页只读条目第一页与开关，其余方法不可达。 */
class FixtureEntryTitleGateway implements KnowledgeGateway {
  async listEntries(_scope: SettingsScope, _filter?: KnowledgeEntryFilter, _after?: string | null, _limit?: number, _signal?: AbortSignal): Promise<KnowledgeEntryPage> {
    return { items: [], nextAfter: null }
  }

  async switchConfig(): Promise<KnowledgeSwitchConfig> {
    return {
      vectorEnabled: true, indexEnabled: true, indexWorkerEnabled: true,
      retrievalEnabled: true, injectionEnabled: true, skillEnabled: true,
    }
  }

  async refreshEntryIndex(): Promise<KnowledgeCommandReceipt> { throw new Error('not used by the index page title map') }
  async listJobs(): Promise<never> { throw new Error('not used by the index page title map') }
  async getEntry(): Promise<never> { throw new Error('not used by the index page title map') }
  async listVersions(): Promise<never> { throw new Error('not used by the index page title map') }
  async getVersion(): Promise<never> { throw new Error('not used by the index page title map') }
  async getEffectiveVersion(): Promise<never> { throw new Error('not used by the index page title map') }
  async createEntry(): Promise<never> { throw new Error('not used by the index page title map') }
  async saveDraft(): Promise<never> { throw new Error('not used by the index page title map') }
  async publishEntry(): Promise<never> { throw new Error('not used by the index page title map') }
  async rollbackEntry(): Promise<never> { throw new Error('not used by the index page title map') }
  async retireEntry(): Promise<never> { throw new Error('not used by the index page title map') }
  async deleteEntry(): Promise<never> { throw new Error('not used by the index page title map') }
  async distill(): Promise<never> { throw new Error('not used by the index page title map') }
  async getJob(): Promise<never> { throw new Error('not used by the index page title map') }
  async cancelJob(): Promise<never> { throw new Error('not used by the index page title map') }
  async rebuild(): Promise<never> { throw new Error('not used by the index page title map') }
  async enqueueRepositoryBuild(): Promise<never> { throw new Error('not used by the index page title map') }
}

class FixtureIndexPageGateway implements KnowledgeIndexGateway {
  listCalls: Array<KnowledgeIndexJobFilter | undefined> = []
  cancelCalls: string[] = []
  buildInputs: RepositoryBuildInput[] = []
  rebuildCalls = 0
  rebuildEnqueued: number
  refreshCalls: string[] = []

  constructor(private readonly overrides: FixtureOverrides) {
    this.rebuildEnqueued = overrides.rebuildEnqueued ?? 1
  }

  async listJobs(_scope: SettingsScope, filter?: KnowledgeIndexJobFilter): Promise<KnowledgeIndexJobPage> {
    this.listCalls.push(filter)
    return { items: [this.overrides.failedEntryJob
      ? job({ source: 'KNOWLEDGE_ENTRY', status: 'FAILED', entryId: entryJobId, indexKey: null, failureCode: 'INTERNAL' })
      : job()], nextAfter: null }
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
    this.rebuildCalls += 1
    return { enqueued: this.rebuildEnqueued }
  }

  async enqueueRepositoryBuild(_scope: SettingsScope, input: RepositoryBuildInput): Promise<RepositoryBuildAccepted> {
    this.buildInputs.push(input)
    return { enqueued: 1, job: job({ status: 'QUEUED' }) }
  }

  async refreshEntryIndex(_scope: SettingsScope, entryId: string, _idempotencyKey: string): Promise<KnowledgeCommandReceipt> {
    this.refreshCalls.push(entryId)
    return {
      commandId: '00000000-0000-0000-0000-000000007701',
      domainEventId: '00000000-0000-0000-0000-000000007702',
      committedVersion: 5,
      correlationId: '00000000-0000-0000-0000-000000007703',
    }
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
