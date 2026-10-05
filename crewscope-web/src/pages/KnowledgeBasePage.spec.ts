import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createMemoryHistory } from 'vue-router'
import { CrewScopeApiError } from '../api/client'
import { AUTH_PRINCIPAL, permissions, type AuthenticatedPrincipal } from '../app/auth'
import { createCrewScopeRouter } from '../app/router'
import { fixtureAuthStore } from '../test/authFixtures'
import type { Etagged, SettingsScope } from '../domains/settings/types'
import type { KnowledgeGateway } from '../domains/knowledge/gateway'
import { createKnowledgeStore, KNOWLEDGE_STORE } from '../domains/knowledge/store'
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
} from '../domains/knowledge/types'
import { createScopeStore, SCOPE_STORE } from '../domains/scope/store'
import { FixtureScopeGateway, fixtureIds } from '../test/scopeFixtures'
import KnowledgeBasePage from './KnowledgeBasePage.vue'

const entryId = '00000000-0000-1000-8000-000000007101'
const executionId = '00000000-0000-1000-8000-000000007201'

const manager: AuthenticatedPrincipal = {
  id: fixtureIds.principal, accountId: '00000000-0000-0000-0000-000000000201', displayName: 'Knowledge Manager', role: 'Team Owner',
  organizationId: fixtureIds.organization, organization: 'Test Organization',
  permissions: new Set(Object.values(permissions)),
}

describe('KnowledgeBasePage', () => {
  it('renders the listing with a deep-linked detail and prefilled draft editor', async () => {
    const { wrapper, store } = await mountPage(manager, `entry=${entryId}`)

    expect(wrapper.get('section[aria-label="知识条目列表"]').text()).toContain('deploy-runbook')
    expect(store.state.entryDetails[entryId]?.phase).toBe('ready')
    expect(wrapper.get('aside[aria-label="知识条目详情"]').text()).toContain('deploy-runbook')
    expect((wrapper.get('input[type="text"]').element as HTMLInputElement).value).toBe('服务端草稿标题')
    wrapper.unmount()
  })

  it('re-reads the listing with the URL filter once the status changes', async () => {
    const { wrapper, gateway, router } = await mountPage(manager, '')

    await wrapper.findAll('select')[0]!.setValue('DRAFT')
    await flushPromises()

    expect(router.currentRoute.value.query.status).toBe('DRAFT')
    expect(gateway.listCalls.at(-1)?.status).toBe('DRAFT')
    wrapper.unmount()
  })

  it('reuses the idempotency key across retries of the same save coordinates only', async () => {
    const { wrapper, gateway } = await mountPage(manager, `entry=${entryId}`)
    gateway.draftFailure = new CrewScopeApiError(503, {
      code: 'service_unavailable', message: '服务暂时无法完成请求', correlationId: 'c-1', retryable: true, currentVersion: null, details: {},
    })

    await editAndSave(wrapper, '第一次修订')
    await editAndSave(wrapper, '第一次修订')
    expect(gateway.saveKeys).toHaveLength(2)
    expect(gateway.saveKeys[0]).toBe(gateway.saveKeys[1])

    await editAndSave(wrapper, '第二次修订')
    expect(gateway.saveKeys[2]).not.toBe(gateway.saveKeys[1])
    wrapper.unmount()
  })

  it('keeps the editor content through a 409 reload and shows the server head version', async () => {
    const { wrapper, gateway, store } = await mountPage(manager, `entry=${entryId}`)
    gateway.draftFailure = new CrewScopeApiError(409, {
      code: 'optimistic_lock_conflict', message: '其他成员已更新此条目', correlationId: 'c-1',
      retryable: false, currentVersion: 7, details: {},
    })
    const readsBefore = gateway.entryReads

    await editAndSave(wrapper, '冲突中的本地修订')

    expect(wrapper.text()).toContain('条目已被其他成员更新')
    expect(wrapper.text()).toContain('服务端当前版本 v7')
    expect((wrapper.get('input[type="text"]').element as HTMLInputElement).value).toBe('冲突中的本地修订')
    expect(gateway.entryReads).toBeGreaterThan(readsBefore)
    expect(store.state.command.phase).toBe('conflict')
    wrapper.unmount()
  })

  it('keeps the read surface complete for members without knowledge:manage', async () => {
    const member = { ...manager, role: 'Member', permissions: new Set([permissions.scopeRead]) }
    const { wrapper } = await mountPage(member, `entry=${entryId}`)

    expect(wrapper.get('section[aria-label="知识条目列表"]').text()).toContain('deploy-runbook')
    expect(wrapper.get('aside[aria-label="知识条目详情"]').text()).toContain('操作手册')
    expect(wrapper.text()).not.toContain('审计信息')
    expect(wrapper.text()).not.toContain('创建条目')
    expect(wrapper.text()).not.toContain('发布当前草稿')
    expect(wrapper.text()).not.toContain('删除条目')
    expect(wrapper.text()).not.toContain('从执行蒸馏')
    wrapper.unmount()
  })

  it('walks the delete flow through the confirmed alertdialog into the tombstone', async () => {
    const { wrapper, gateway } = await mountPage(manager, `entry=${entryId}`)

    await wrapper.findAll('button').find(button => button.text() === '删除条目')!.trigger('click')
    expect(wrapper.get('[role="alertdialog"]').text()).toContain('删除墓碑')
    expect(wrapper.find('[role="alertdialog"] button:disabled').exists()).toBe(true)

    await wrapper.get('[role="alertdialog"] input[type="checkbox"]').setValue(true)
    await wrapper.get('[role="alertdialog"]').findAll('button').find(button => button.text() === '确认删除')!.trigger('click')
    await flushPromises()

    expect(gateway.deleteKeys).toHaveLength(1)
    expect(wrapper.text()).toContain('此条目已删除')
    expect(wrapper.find('[role="alertdialog"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('surfaces the authoritative PENDING wording next to the badge', async () => {
    const { wrapper } = await mountPage(manager, `entry=${entryId}`, { indexStatus: 'PENDING' })

    expect(wrapper.text()).toContain('待索引')
    expect(wrapper.text()).toContain('索引状态与保存状态无关：内容已保存，等待后台建立索引')
    expect(wrapper.text()).not.toContain('未保存')
    wrapper.unmount()
  })

  it('creates an entry through the dialog and refreshes the listing', async () => {
    const { wrapper, gateway } = await mountPage(manager, '')
    const listsBefore = gateway.listCalls.length

    await wrapper.findAll('button').find(button => button.text().includes('创建条目'))!.trigger('click')
    const inputs = wrapper.findAll('form.knowledge-dialog input[type="text"]')
    await inputs[0]!.setValue('release-notes')
    await inputs[1]!.setValue('发布说明')
    await wrapper.get('form.knowledge-dialog').trigger('submit')
    await flushPromises()

    expect(gateway.createInputs).toHaveLength(1)
    expect(gateway.createInputs[0]).toMatchObject({ entryKey: 'release-notes', title: '发布说明' })
    expect(wrapper.find('.knowledge-dialog').exists()).toBe(false)
    expect(gateway.listCalls.length).toBeGreaterThan(listsBefore)
    wrapper.unmount()
  })

  it('presents the distillation receipt inside the dialog and deep-links to the entry', async () => {
    const { wrapper, router } = await mountPage(manager, '')

    await wrapper.findAll('button').find(button => button.text().includes('从执行蒸馏'))!.trigger('click')
    const inputs = wrapper.findAll('form.knowledge-dialog input[type="text"]')
    await inputs[0]!.setValue(executionId)
    await inputs[1]!.setValue('postmortem-cache')
    await wrapper.get('form.knowledge-dialog').trigger('submit')
    await flushPromises()

    const receipt = wrapper.get('section[aria-label="蒸馏回执"]')
    expect(receipt.text()).toContain('postmortem-cache')
    expect(receipt.text()).toContain('内容已保存，等待后台建立索引')
    await wrapper.findAll('button').find(button => button.text() === '打开条目')!.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.entry).toBe(entryId)
    expect(wrapper.find('.knowledge-dialog').exists()).toBe(false)
    wrapper.unmount()
  })
})

async function mountPage(principal: AuthenticatedPrincipal, query: string, overrides: { indexStatus?: 'PENDING' } = {}) {
  const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(principal))
  const scopeStore = createScopeStore(new FixtureScopeGateway(), principal)
  await scopeStore.synchronize(fixtureIds.teamPlatform, fixtureIds.projectCrewScope)
  const gateway = new FixtureKnowledgeGateway(overrides)
  const store = createKnowledgeStore(gateway)
  await router.push(`/knowledge?team=${fixtureIds.teamPlatform}${query ? `&${query}` : ''}`)
  await router.isReady()
  const wrapper = mount(KnowledgeBasePage, {
    global: {
      plugins: [router],
      provide: {
        [AUTH_PRINCIPAL as symbol]: principal,
        [SCOPE_STORE as symbol]: scopeStore,
        [KNOWLEDGE_STORE as symbol]: store,
      },
      stubs: { AppShell: { template: '<main><slot name="actions"/><slot/></main>' } },
    },
  })
  await flushPromises()
  await flushPromises()
  if (typeof router.currentRoute.value.query.entry === 'string') {
    await vi.waitFor(() => expect(store.state.entryDetails[router.currentRoute.value.query.entry as string]?.phase).toBe('ready'))
  }
  await nextTick()
  return { wrapper, gateway, store, router, scopeStore }
}

async function editAndSave(wrapper: ReturnType<typeof mount>, title: string): Promise<void> {
  await wrapper.get('input[type="text"]').setValue(title)
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

class FixtureKnowledgeGateway implements KnowledgeGateway {
  listCalls: Array<KnowledgeEntryFilter | undefined> = []
  entryReads = 0
  saveKeys: string[] = []
  deleteKeys: string[] = []
  createInputs: CreateKnowledgeEntryInput[] = []
  deleted = false
  draftFailure: CrewScopeApiError | null = null

  constructor(private readonly overrides: { indexStatus?: 'PENDING' } = {}) {}

  async listEntries(_scope: SettingsScope, filter?: KnowledgeEntryFilter, _after?: string | null, _limit?: number, _signal?: AbortSignal): Promise<KnowledgeEntryPage> {
    this.listCalls.push(filter)
    return { items: [entry({ indexStatus: this.overrides.indexStatus ?? 'INDEXED' })], nextAfter: null }
  }

  async getEntry(_scope: SettingsScope, _entryId: string, _signal?: AbortSignal): Promise<Etagged<KnowledgeEntrySummary>> {
    this.entryReads += 1
    return {
      value: entry({
        indexStatus: this.overrides.indexStatus ?? 'INDEXED',
        status: this.deleted ? 'DELETED' : 'PUBLISHED',
      }),
      etag: '"3"',
    }
  }

  async listVersions(_scope: SettingsScope, _entryId: string, _after?: number | null, _limit?: number, _signal?: AbortSignal): Promise<KnowledgeVersionPage> {
    return { items: [version(1)], nextAfter: null }
  }

  async getVersion(_scope: SettingsScope, _entryId: string, _revision: number, _signal?: AbortSignal): Promise<Etagged<KnowledgeVersion>> {
    return { value: version(1), etag: `"${'a'.repeat(64)}"` }
  }

  async getEffectiveVersion(_scope: SettingsScope, _entryId: string, _signal?: AbortSignal): Promise<Etagged<KnowledgeVersion> | null> {
    return { value: version(1), etag: `"${'a'.repeat(64)}"` }
  }

  async createEntry(_scope: SettingsScope, input: CreateKnowledgeEntryInput, _idempotencyKey: string): Promise<KnowledgeCommandReceipt> {
    this.createInputs.push(input)
    return receipt()
  }

  async saveDraft(_scope: SettingsScope, _entryId: string, _input: UpdateKnowledgeDraftInput, _etag: string, idempotencyKey: string): Promise<KnowledgeCommandReceipt> {
    this.saveKeys.push(idempotencyKey)
    if (this.draftFailure) {
      const failure = this.draftFailure
      this.draftFailure = null
      throw failure
    }
    return receipt()
  }

  async publishEntry(_scope: SettingsScope, _entryId: string, _etag: string, _idempotencyKey: string): Promise<KnowledgeCommandReceipt> {
    return receipt()
  }

  async retireEntry(_scope: SettingsScope, _entryId: string, _etag: string, _idempotencyKey: string): Promise<KnowledgeCommandReceipt> {
    return receipt()
  }

  async deleteEntry(_scope: SettingsScope, _entryId: string, _etag: string, idempotencyKey: string): Promise<KnowledgeCommandReceipt> {
    this.deleteKeys.push(idempotencyKey)
    this.deleted = true
    return receipt()
  }

  async distill(_scope: SettingsScope, input: DistillKnowledgeInput, _idempotencyKey: string): Promise<KnowledgeDistillationReceipt> {
    return {
      ...receipt(),
      entryId,
      entryKey: input.entryKey,
      origin: { taskExecutionId: executionId, attempt: 2 },
      indexStatus: 'PENDING',
      replayed: false,
    }
  }

  // The I01c job plane belongs to the index page (F01b); this fixture must only satisfy the shape.
  async listJobs(): Promise<never> { throw new Error('not used by the knowledge base page') }
  async getJob(): Promise<never> { throw new Error('not used by the knowledge base page') }
  async cancelJob(): Promise<never> { throw new Error('not used by the knowledge base page') }
  async rebuild(): Promise<never> { throw new Error('not used by the knowledge base page') }
  async enqueueRepositoryBuild(): Promise<never> { throw new Error('not used by the knowledge base page') }
}

function entry(overrides: Partial<KnowledgeEntrySummary> = {}): KnowledgeEntrySummary {
  return {
    id: entryId, entryKey: 'deploy-runbook', category: 'RUNBOOK', status: 'PUBLISHED', indexStatus: 'INDEXED',
    effectiveRevision: 2, latestRevision: 2, draft: { title: '服务端草稿标题', content: '服务端草稿内容' }, version: 3,
    createdAt: '2026-10-01T01:00:00Z', updatedAt: '2026-10-02T01:00:00Z',
    createdBy: fixtureIds.principal, updatedBy: fixtureIds.principal, origin: null, ...overrides,
  }
}

function version(revision: number): KnowledgeVersion {
  return {
    entryId, revision, previousRevision: null, title: '部署手册', content: '先排干连接池。',
    contentHash: 'a'.repeat(64), indexStatus: 'INDEXED', createdAt: '2026-10-02T01:00:00Z', createdBy: fixtureIds.principal,
  }
}

function receipt(): KnowledgeCommandReceipt {
  return {
    commandId: '00000000-0000-0000-0000-000000007301',
    domainEventId: '00000000-0000-0000-0000-000000007302',
    committedVersion: 4,
    correlationId: '00000000-0000-0000-0000-000000007303',
  }
}
