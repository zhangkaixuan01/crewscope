import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createMemoryHistory } from 'vue-router'
import { CrewScopeApiError } from '../api/client'
import { AUTH_PRINCIPAL, permissions, type AuthenticatedPrincipal } from '../app/auth'
import { createCrewScopeRouter } from '../app/router'
import { fixtureAuthStore } from '../test/authFixtures'
import type { Etagged, SettingsScope } from '../domains/settings/types'
import type { SkillGateway } from '../domains/skill/gateway'
import { createSkillStore, SKILL_STORE } from '../domains/skill/store'
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
} from '../domains/skill/types'
import { createScopeStore, SCOPE_STORE } from '../domains/scope/store'
import { FixtureScopeGateway, fixtureIds } from '../test/scopeFixtures'
import SkillsPage from './SkillsPage.vue'

const skillId = '00000000-0000-1000-8000-000000007101'
const otherSkillId = '00000000-0000-1000-8000-000000007111'
const executionId = '00000000-0000-1000-8000-000000007201'

const manager: AuthenticatedPrincipal = {
  id: fixtureIds.principal, accountId: '00000000-0000-0000-0000-000000000201', displayName: 'Skill Manager', role: 'Team Owner',
  organizationId: fixtureIds.organization, organization: 'Test Organization',
  permissions: new Set(Object.values(permissions)),
}

describe('SkillsPage', () => {
  it('renders the catalog with a deep-linked detail and the server draft in the editor', async () => {
    const { wrapper, store } = await mountPage(manager, `skill=${skillId}`)

    expect(wrapper.get('section[aria-label="Skill 目录列表"]').text()).toContain('deploy-runbook')
    expect(store.state.skillDetails[skillId]?.phase).toBe('ready')
    expect(wrapper.get('aside[aria-label="Skill 详情"]').text()).toContain('deploy-runbook')
    expect((wrapper.get('.draft-editor__form textarea').element as HTMLTextAreaElement).value).toBe('服务端草稿内容')
    // The effective revision feeds the editor baseline on both tabs (SkillsPage owns the resource).
    expect(store.state.effectiveVersions[skillId]?.phase).toBe('ready')
    wrapper.unmount()
  })

  it('resolves a skillKey deep link by paging the unfiltered catalog, then rewrites the id', async () => {
    const { wrapper, gateway, router, store } = await mountPage(manager, 'skillKey=deploy-runbook', {
      pages: [[skill({ id: otherSkillId, skillKey: 'code-review' })], [skill()]],
    })

    await vi.waitFor(() => expect(router.currentRoute.value.query.skill).toBe(skillId))
    expect(router.currentRoute.value.query.skillKey).toBeUndefined()
    expect(gateway.listAfters).toEqual([null, 'code-review'])
    expect(store.state.skillDetails[skillId]?.phase).toBe('ready')
    expect(wrapper.text()).not.toContain('未在目录中找到')
    wrapper.unmount()
  })

  it('surfaces a note instead of faking a hit when the skillKey deep link misses', async () => {
    const { wrapper, router } = await mountPage(manager, 'skillKey=missing-key')

    await vi.waitFor(() => expect(router.currentRoute.value.query.skillKey).toBeUndefined())
    expect(wrapper.text()).toContain('未在目录中找到 Skill missing-key')
    wrapper.unmount()
  })

  it('re-reads the catalog with the URL filter once the status changes', async () => {
    const { wrapper, gateway, router } = await mountPage(manager, '')

    await wrapper.get('select[aria-label="按状态筛选"]').setValue('DRAFT')
    await flushPromises()

    expect(router.currentRoute.value.query.status).toBe('DRAFT')
    expect(gateway.listCalls.at(-1)?.status).toBe('DRAFT')
    wrapper.unmount()
  })

  it('walks publish through the impact alertdialog and refreshes the facts', async () => {
    const { wrapper, gateway } = await mountPage(manager, `skill=${skillId}`)
    const listsBefore = gateway.listCalls.length

    await wrapper.findAll('button').find(button => button.text() === '发布当前草稿')!.trigger('click')
    expect(wrapper.get('[role="alertdialog"]').text()).toContain('无条件披露扫描')

    await wrapper.get('form[role="alertdialog"]').trigger('submit')
    await flushPromises()

    expect(gateway.publishKeys).toHaveLength(1)
    expect(gateway.listCalls.length).toBeGreaterThan(listsBefore)
    expect(wrapper.find('[role="alertdialog"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('keeps the editor content through a 409 reload and shows the server head version', async () => {
    const { wrapper, gateway, store } = await mountPage(manager, `skill=${skillId}`)
    gateway.draftFailure = new CrewScopeApiError(409, {
      code: 'optimistic_lock_conflict', message: '其他成员已更新此 Skill', correlationId: 'c-1',
      retryable: false, currentVersion: 7, details: {},
    })
    const readsBefore = gateway.skillReads

    await editAndSave(wrapper, '冲突中的本地修订')

    expect(wrapper.text()).toContain('Skill 已被其他成员更新')
    expect(wrapper.text()).toContain('服务端当前版本 v7')
    expect((wrapper.get('.draft-editor__form textarea').element as HTMLTextAreaElement).value).toBe('---\nname: deploy-runbook\ndescription: 部署手册\n---\n\n冲突中的本地修订')
    expect(gateway.skillReads).toBeGreaterThan(readsBefore)
    expect(store.state.command.phase).toBe('conflict')
    wrapper.unmount()
  })

  it('keeps the read surface complete for members without skill:manage, distillation included', async () => {
    const member = { ...manager, role: 'Member', permissions: new Set([permissions.scopeRead]) }
    const { wrapper } = await mountPage(member, `skill=${skillId}`)

    expect(wrapper.get('section[aria-label="Skill 目录列表"]').text()).toContain('deploy-runbook')
    expect(wrapper.get('aside[aria-label="Skill 详情"]').text()).toContain('deploy-runbook')
    expect(wrapper.text()).not.toContain('审计信息')
    expect(wrapper.text()).not.toContain('创建 Skill')
    expect(wrapper.text()).not.toContain('发布当前草稿')
    // Distillation belongs to the execution creator, not to skill:manage (contract §8).
    expect(wrapper.findAll('button').find(button => button.text().includes('从执行蒸馏'))).toBeTruthy()
    wrapper.unmount()
  })

  it('creates a skill through the dialog and refreshes the catalog', async () => {
    const { wrapper, gateway } = await mountPage(manager, '')
    const listsBefore = gateway.listCalls.length

    await wrapper.findAll('button').find(button => button.text() === '创建 Skill')!.trigger('click')
    const content = '---\nname: release-notes\ndescription: 发布说明\n---\n\n每次发布后回填。'
    await wrapper.get('form.skill-dialog input[type="text"]').setValue('release-notes')
    await wrapper.get('form.skill-dialog textarea').setValue(content)
    await wrapper.get('form.skill-dialog').trigger('submit')
    await flushPromises()

    expect(gateway.createInputs).toHaveLength(1)
    expect(gateway.createInputs[0]).toMatchObject({ skillKey: 'release-notes', content })
    expect(wrapper.find('form.skill-dialog').exists()).toBe(false)
    expect(gateway.listCalls.length).toBeGreaterThan(listsBefore)
    wrapper.unmount()
  })

  it('presents the distillation receipt inside the dialog and deep-links to the skill', async () => {
    const { wrapper, router } = await mountPage(manager, '')

    await wrapper.findAll('button').find(button => button.text().includes('从执行蒸馏'))!.trigger('click')
    const inputs = wrapper.findAll('form.skill-dialog input[type="text"]')
    await inputs[0]!.setValue(executionId)
    await inputs[1]!.setValue('postmortem-cache')
    await wrapper.get('form.skill-dialog').trigger('submit')
    await flushPromises()

    const receipt = wrapper.get('section[aria-label="蒸馏回执"]')
    expect(receipt.text()).toContain('postmortem-cache')
    await wrapper.findAll('button').find(button => button.text() === '打开 Skill')!.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.skill).toBe(skillId)
    expect(wrapper.find('form.skill-dialog').exists()).toBe(false)
    wrapper.unmount()
  })
})

async function mountPage(
  principal: AuthenticatedPrincipal,
  query: string,
  overrides: { pages?: SkillSummary[][] } = {},
) {
  const router = createCrewScopeRouter(createMemoryHistory(), fixtureAuthStore(principal))
  const scopeStore = createScopeStore(new FixtureScopeGateway(), principal)
  await scopeStore.synchronize(fixtureIds.teamPlatform, fixtureIds.projectCrewScope)
  const gateway = new FixtureSkillGateway(overrides)
  const store = createSkillStore(gateway)
  await router.push(`/skills?team=${fixtureIds.teamPlatform}${query ? `&${query}` : ''}`)
  await router.isReady()
  const wrapper = mount(SkillsPage, {
    global: {
      plugins: [router],
      provide: {
        [AUTH_PRINCIPAL as symbol]: principal,
        [SCOPE_STORE as symbol]: scopeStore,
        [SKILL_STORE as symbol]: store,
      },
      stubs: { AppShell: { template: '<main><slot name="actions"/><slot/></main>' } },
    },
  })
  await flushPromises()
  await flushPromises()
  if (typeof router.currentRoute.value.query.skill === 'string') {
    await vi.waitFor(() => expect(store.state.skillDetails[router.currentRoute.value.query.skill as string]?.phase).toBe('ready'))
  }
  await nextTick()
  return { wrapper, gateway, store, router, scopeStore }
}

async function editAndSave(wrapper: ReturnType<typeof mount>, body: string): Promise<void> {
  await wrapper.get('.draft-editor__form textarea').setValue(`---\nname: deploy-runbook\ndescription: 部署手册\n---\n\n${body}`)
  await wrapper.get('.draft-editor__form').trigger('submit')
  await flushPromises()
}

class FixtureSkillGateway implements SkillGateway {
  listCalls: Array<SkillFilter | undefined> = []
  listAfters: Array<string | null> = []
  skillReads = 0
  saveKeys: string[] = []
  publishKeys: string[] = []
  createInputs: CreateSkillInput[] = []
  draftFailure: CrewScopeApiError | null = null

  constructor(private readonly overrides: { pages?: SkillSummary[][] } = {}) {}

  async listSkills(_scope: SettingsScope, filter: SkillFilter = {}, after?: string | null, _limit?: number, _signal?: AbortSignal): Promise<SkillPage> {
    this.listCalls.push(filter)
    const pages = this.overrides.pages ?? [[skill()]]
    // Keyset pagination: nextAfter is the last key of the served page, and the next call
    // resumes after that key — so the cursor is the last item of each page.
    const index = after === null || after === undefined
      ? 0
      : Math.min(pages.findIndex(page => page[page.length - 1]?.skillKey === after) + 1, pages.length - 1)
    const items = pages[index] ?? []
    const next = index + 1 < pages.length ? items[items.length - 1]!.skillKey : null
    void this.listAfters.push(after ?? null)
    return { items, nextAfter: next }
  }

  async getSkill(_scope: SettingsScope, _skillId: string, _signal?: AbortSignal): Promise<Etagged<SkillSummary>> {
    this.skillReads += 1
    return { value: skill(), etag: '"3"' }
  }

  async listVersions(_scope: SettingsScope, _skillId: string, _after?: number | null, _limit?: number, _signal?: AbortSignal): Promise<SkillVersionPage> {
    return { items: [version(1)], nextAfter: null }
  }

  async getVersion(_scope: SettingsScope, _skillId: string, _revision: number, _signal?: AbortSignal): Promise<Etagged<SkillVersion>> {
    return { value: version(1), etag: `"${'a'.repeat(64)}"` }
  }

  async getEffectiveVersion(_scope: SettingsScope, _skillId: string, _signal?: AbortSignal): Promise<Etagged<SkillVersion> | null> {
    return { value: version(1), etag: `"${'a'.repeat(64)}"` }
  }

  async createSkill(_scope: SettingsScope, input: CreateSkillInput, _idempotencyKey: string): Promise<SkillCommandReceipt> {
    this.createInputs.push(input)
    return receipt()
  }

  async saveDraft(_scope: SettingsScope, _skillId: string, _input: UpdateSkillDraftInput, _etag: string, idempotencyKey: string): Promise<SkillCommandReceipt> {
    this.saveKeys.push(idempotencyKey)
    if (this.draftFailure) {
      const failure = this.draftFailure
      this.draftFailure = null
      throw failure
    }
    return receipt()
  }

  async publishSkill(_scope: SettingsScope, _skillId: string, _etag: string, idempotencyKey: string): Promise<SkillCommandReceipt> {
    this.publishKeys.push(idempotencyKey)
    return receipt()
  }

  async disableSkill(_scope: SettingsScope, _skillId: string, _reason: string | null, _etag: string, _idempotencyKey: string): Promise<SkillCommandReceipt> {
    return receipt()
  }

  async rollbackSkill(_scope: SettingsScope, _skillId: string, _toRevision: number, _etag: string, _idempotencyKey: string): Promise<SkillCommandReceipt> {
    return receipt()
  }

  async distill(_scope: SettingsScope, input: DistillSkillInput, _idempotencyKey: string): Promise<SkillDistillationReceipt> {
    return {
      ...receipt(),
      skillId,
      skillKey: input.skillKey,
      status: 'DRAFT',
      origin: { taskExecutionId: input.taskExecutionId, attempt: 2 },
      replayed: false,
    }
  }
}

function skill(overrides: Partial<SkillSummary> = {}): SkillSummary {
  return {
    id: skillId, skillKey: 'deploy-runbook', status: 'PUBLISHED',
    effectiveRevision: 1, latestRevision: 1, draft: { name: 'deploy-runbook', description: '部署手册', content: '服务端草稿内容' },
    disableReason: null, version: 3,
    createdAt: '2026-10-01T01:00:00Z', updatedAt: '2026-10-02T01:00:00Z',
    createdBy: fixtureIds.principal, updatedBy: fixtureIds.principal, origin: null, ...overrides,
  }
}

function version(revision: number): SkillVersion {
  return {
    skillId, revision, previousRevision: null, content: '先排干连接池。',
    contentHash: 'a'.repeat(64), createdAt: '2026-10-02T01:00:00Z', createdBy: fixtureIds.principal,
  }
}

function receipt(): SkillCommandReceipt {
  return {
    commandId: '00000000-0000-0000-0000-000000007301',
    domainEventId: '00000000-0000-0000-0000-000000007302',
    committedVersion: 4,
    correlationId: '00000000-0000-0000-0000-000000007303',
  }
}
