import { flushPromises, mount, type MountingOptions } from '@vue/test-utils'
import { defineComponent, onMounted, reactive } from 'vue'
import { AUTH_PRINCIPAL } from '../../app/auth'
import { activateF05Identity, clearF05UserData } from '../../app/f05Storage'
import { AGENT_STORE, type AgentStore } from '../../domains/agent/store'
import type { AgentSummary } from '../../domains/agent/types'
import { delegationPreflightKey, TASK_STORE, type TaskStore } from '../../domains/task/store'
import type {
  DelegationAgentCandidate,
  DelegationContext,
  DelegationDefaults,
  DelegationResponsibilityLine,
  TaskDelegationPreflight,
  TaskDelegationSelection,
} from '../../domains/task/types'
import { writeTaskDelegationDraft } from '../../domains/task/delegationDraft'
import { fixtureWorkItemDetails } from '../../test/workItemFixtures'
import { fixtureIds } from '../../test/scopeFixtures'
import DelegateToAgentDialog from './DelegateToAgentDialog.vue'

const fixtureAccount = '00000000-0000-0000-0000-000000000901'
const fixturePrincipal = {
  id: fixtureIds.principal,
  accountId: fixtureAccount,
  displayName: '张凯旋',
  role: 'Team Member',
  organizationId: fixtureIds.organization,
  organization: 'CrewScope',
  permissions: new Set<string>(),
}

const personalProfileId = '00000000-0000-0000-0000-000000000301'
const teamProfileId = '00000000-0000-0000-0000-000000000302'
const personalAgentPrincipalId = '00000000-0000-0000-0000-000000000201'
const teamAgentPrincipalId = '00000000-0000-0000-0000-000000000202'

describe('DelegateToAgentDialog', () => {
  beforeEach(() => { sessionStorage.clear(); clearF05UserData(); localStorage.clear(); activateF05Identity(fixtureAccount) })

  it('previews responsibility and submits assign-and-start with the executorAssignment payload', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined)
    const conversationSource = { conversationId: crypto.randomUUID(), messageId: crypto.randomUUID() }
    const wrapper = mountDialog({ onSubmit, conversationSource })
    await flushPromises()

    expect(wrapper.text()).toContain('Owner · 张凯旋')
    expect(wrapper.text()).toContain('Executor · 张凯旋的 Personal Agent')
    expect(wrapper.text()).toContain('来源保留为当前 Conversation 消息')
    expect(wrapper.text()).toContain('PolicySnapshot Preflight 通过')
    expect(wrapper.text()).toContain('个人范围')
    expect(wrapper.text()).toContain('deepseek-v4-flash')
    expect(wrapper.text()).toContain('影响确认')
    expect(wrapper.text()).toContain('责任：把 张凯旋的 Personal Agent 记为 EXECUTOR')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
      objective: fixtureWorkItemDetails.workItem.title,
      executorAgentProfileId: personalProfileId,
      // “分配并启动”在同一条命令里声明责任意图；服务端沿用/冲突规则兜底。
      executorAssignment: { agentProfileId: personalProfileId },
      agentConfigurationRevision: 2,
      providerBindingIds: [],
      conversationSource,
      codingTarget: {
        repositoryBindingId: '00000000-0000-4000-8000-00000000f302',
        baselineRef: 'main',
        allowedPaths: ['.'],
        buildProfile: { key: 'maven-java-17', version: 1, profileHash: 'a'.repeat(64) },
      },
    }))
  })

  it('selects a Team Agent and exposes the USER-Key safety boundary', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    await wrapper.get('select').setValue(teamProfileId)
    await flushPromises()

    expect(wrapper.text()).toContain('TEAM')
    expect(wrapper.text()).toContain('TEAM 执行只允许 TEAM / ORGANIZATION Connection')
    expect(wrapper.text()).toContain('USER Key 已在服务端禁用')
  })

  it('preselects the project-default Agent when no executor is assigned', async () => {
    const wrapper = mountDialog({
      context: context({
        defaults: defaults({ agentProfileId: teamProfileId, agentProfileRevision: 1 }),
      }),
    })
    await flushPromises()

    expect((wrapper.get('select').element as HTMLSelectElement).value).toBe(teamProfileId)
  })

  it('keeps an assigned executor in start mode without reassigning', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined)
    const wrapper = mountDialog({
      context: context({
        responsibilities: [
          ownerLine(),
          { ...personalLine(), role: 'EXECUTOR', actorPrincipalId: personalAgentPrincipalId, actorAgentProfileId: personalProfileId },
        ],
        candidates: [candidate(personalProfileId, 'ASSIGNED'), candidate(teamProfileId, 'EXECUTOR_CONFLICT')],
      }),
      onSubmit,
    })
    await flushPromises()

    // An existing ACTIVE EXECUTOR pins the form to plain start: no intent switch, no assignment.
    expect(wrapper.text()).not.toContain('仅分配')
    expect(wrapper.text()).toContain('当前执行者')
    expect(wrapper.text()).toContain('责任：沿用当前责任链，不新增分配')
    expect(wrapper.text()).toContain('启动执行')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
      executorAgentProfileId: personalProfileId,
      executorAssignment: null,
    }))
  })

  it('marks conflicting and disabled candidates with their server reason', async () => {
    const wrapper = mountDialog({
      context: context({
        candidates: [
          candidate(personalProfileId, 'AVAILABLE'),
          candidate(teamProfileId, 'EXECUTOR_CONFLICT', '已有其他执行者责任——需先显式释放再分配'),
          candidate('00000000-0000-0000-0000-000000000303', 'AGENT_DISABLED', 'Agent 已停用'),
        ],
      }),
    })
    await flushPromises()

    expect(wrapper.text()).toContain('责任冲突')
    expect(wrapper.text()).toContain('已有其他执行者责任——需先显式释放再分配')
    expect(wrapper.text()).toContain('已停用')
    expect(wrapper.text()).toContain('Agent 已停用')
    const options = wrapper.get('select').findAll('option')
    expect(options).toHaveLength(1)
  })

  it('shows the in-flight execution note while an attempt is running', async () => {
    const wrapper = mountDialog({
      context: context({
        activeExecution: true,
        responsibilities: [
          ownerLine(),
          { ...personalLine(), role: 'EXECUTOR', actorPrincipalId: personalAgentPrincipalId, actorAgentProfileId: personalProfileId },
        ],
        candidates: [candidate(personalProfileId, 'ASSIGNED'), candidate(teamProfileId, 'EXECUTOR_CONFLICT')],
      }),
    })
    await flushPromises()

    expect(wrapper.text()).toContain('当前执行仍按原说明继续')
    expect(wrapper.text()).toContain('补充要求请发布评论，或等本轮完成后开启新一轮')
  })

  it('submits assign-only through the responsibility command instead of task creation', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined)
    const onAssign = vi.fn().mockResolvedValue(undefined)
    const wrapper = mountDialog({ onSubmit, onAssign })
    await flushPromises()

    await wrapper.get('input[type="radio"][value="assign-only"]').setValue(true)
    await flushPromises()

    // “仅分配”隐藏启动专用的预检、编码目标与 Task brief。
    expect(wrapper.text()).not.toContain('执行目标')
    expect(wrapper.text()).not.toContain('PolicySnapshot Preflight 通过')
    expect(wrapper.text()).toContain('启动：本次不启动任何执行')
    expect(wrapper.text()).toContain('仅分配')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(onAssign).toHaveBeenCalledWith({
      agentProfileId: personalProfileId,
      agentPrincipalId: personalAgentPrincipalId,
      displayName: '张凯旋的 Personal Agent',
    })
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('restores a Scope-partitioned draft before preflighting the responsible Agent', async () => {
    writeTaskDelegationDraft(
      { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform },
      fixtureIds.projectCrewScope,
      fixtureWorkItemDetails.workItem.id,
      {
        objective: '恢复后的执行目标',
        acceptanceCriteria: '恢复验收一\n恢复验收二',
        executorAgentProfileId: personalProfileId,
        agentConfigurationRevision: null,
      },
      fixturePrincipal,
    )

    const wrapper = mountDialog()
    await flushPromises()

    expect(wrapper.get('input[maxlength="2000"]').element).toHaveProperty('value', '恢复后的执行目标')
    expect(wrapper.get('textarea').element).toHaveProperty('value', '恢复验收一\n恢复验收二')
    expect(wrapper.text()).toContain('PolicySnapshot Preflight 通过')
  })

  it('lets the “让 Agent 处理” prefill override an older draft and states it visibly', async () => {
    writeTaskDelegationDraft(
      { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform },
      fixtureIds.projectCrewScope,
      fixtureWorkItemDetails.workItem.id,
      {
        objective: '旧的草稿目标',
        acceptanceCriteria: '旧验收',
        executorAgentProfileId: personalProfileId,
        agentConfigurationRevision: null,
      },
      fixturePrincipal,
    )
    const onSubmit = vi.fn().mockResolvedValue(undefined)

    const wrapper = mountDialog({ prefillNote: '按新接口契约补齐回调', onSubmit })
    await flushPromises()

    // The explicit latest intent wins over the stored form draft, and the surface says so
    // instead of silently rewriting the member's earlier text (R42).
    expect(wrapper.get('input[maxlength="2000"]').element).toHaveProperty('value', '按新接口契约补齐回调')
    expect(wrapper.text()).toContain('执行目标已预填自你的评论草稿')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ objective: '按新接口契约补齐回调' }))
  })

  it('fails closed without assignable candidates and offers exact-request retry after failure', async () => {
    const unavailable = mountDialog({ context: context({ candidates: [] }) })
    await flushPromises()
    expect(unavailable.text()).toContain('当前没有可分配的 Agent 候选')
    expect(unavailable.get('button[type="submit"]').attributes('disabled')).toBeDefined()

    const onRetry = vi.fn().mockResolvedValue(undefined)
    const retry = mountDialog({ retryable: true, errorMessage: '网络中断', onRetry })
    await flushPromises()
    const retryButton = retry.findAll('button').find(button => button.text().includes('使用原请求重试'))!
    await retryButton.trigger('click')
    expect(retry.text()).toContain('网络中断')
    expect(onRetry).toHaveBeenCalled()
  })

  it('keeps keyboard focus inside the topmost delegation Modal and closes only that layer', async () => {
    const wrapper = mountDialog({}, { attachTo: document.body })
    await flushPromises()

    expect(document.activeElement).toBe(wrapper.get('[role="dialog"]').element)
    const controls = wrapper.get('form').findAll('button:not(:disabled), input:not(:disabled), textarea:not(:disabled), select:not(:disabled)')
    const first = controls[0]!.element as HTMLElement
    const last = controls.at(-1)!.element as HTMLElement
    last.focus()
    await wrapper.get('form').trigger('keydown', { key: 'Tab' })
    expect(document.activeElement).toBe(first)
    await wrapper.get('form').trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(last)

    await wrapper.get('form').trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })

  it('focuses the Modal itself when delegation has no eligible candidate', async () => {
    const wrapper = mountDialog({ context: context({ candidates: [] }) }, { attachTo: document.body })
    await flushPromises()

    expect(document.activeElement).toBe(wrapper.get('[role="dialog"]').element)
    wrapper.unmount()
  })
})

function ownerLine(): DelegationResponsibilityLine {
  return {
    assignmentId: crypto.randomUUID(),
    role: 'OWNER',
    actorPrincipalId: fixtureIds.principal,
    actorType: 'USER',
    actorDisplayName: '张凯旋',
    actorAgentProfileId: null,
    version: 0,
  }
}

function personalLine(): DelegationResponsibilityLine {
  return {
    ...ownerLine(),
    actorType: 'PERSONAL_AGENT',
    actorDisplayName: '张凯旋的 Personal Agent',
    actorAgentProfileId: personalProfileId,
  }
}

function candidate(agentProfileId: string, state: DelegationAgentCandidate['state'], reason: string | null = null): DelegationAgentCandidate {
  const personal = agentProfileId === personalProfileId
  return {
    agentProfileId,
    agentProfileVersion: 2,
    agentPrincipalId: personal ? personalAgentPrincipalId : teamAgentPrincipalId,
    displayName: personal ? '张凯旋的 Personal Agent' : 'Team Delivery Agent',
    ownershipType: personal ? 'USER' : 'TEAM',
    runtimeRole: personal ? 'SPECIALIST' : 'TEAM_COORDINATOR',
    state,
    reason,
  }
}

function emptyDefaults(): DelegationDefaults {
  const missing = (reason: string) => ({
    value: null, source: 'PROJECT_DEFAULT', availability: 'MISSING', reason,
  })
  const inherited = { value: null, source: 'PROJECT_DEFAULT', availability: 'INHERITED', reason: '使用任务/团队解析结果' }
  return {
    version: 1,
    repositoryBindingId: missing('尚未设置项目仓库'),
    repositoryBindingVersion: missing('尚未设置项目仓库'),
    branch: missing('仓库绑定默认分支将被使用'),
    buildProfile: missing('尚未设置构建方案'),
    agentProfileId: inherited,
    agentProfileRevision: inherited,
  }
}

function defaults(agent: { agentProfileId: string, agentProfileRevision: number }): DelegationDefaults {
  return {
    ...emptyDefaults(),
    agentProfileId: {
      value: agent.agentProfileId, source: 'PROJECT_DEFAULT', availability: 'AVAILABLE', reason: '项目已选择 Agent',
    },
    agentProfileRevision: {
      value: agent.agentProfileRevision, source: 'PROJECT_DEFAULT', availability: 'AVAILABLE', reason: '项目已选择 Agent',
    },
  }
}

function context(overrides: Partial<DelegationContext> = {}): DelegationContext {
  return {
    workItem: {
      id: fixtureWorkItemDetails.workItem.id,
      projectId: fixtureIds.projectCrewScope,
      version: 3,
      title: fixtureWorkItemDetails.workItem.title,
      status: 'IN_PROGRESS',
    },
    responsibilities: [ownerLine()],
    candidates: [candidate(personalProfileId, 'AVAILABLE'), candidate(teamProfileId, 'AVAILABLE')],
    defaults: emptyDefaults(),
    activeExecution: false,
    permissions: { canAssignResponsibility: true, canDelegate: true },
    ...overrides,
  }
}

function props(overrides: Record<string, unknown> = {}) {
  return {
    workItem: structuredClone(fixtureWorkItemDetails.workItem),
    codingScope: {
      organizationId: fixtureIds.organization,
      teamId: fixtureIds.teamPlatform,
      projectId: fixtureIds.projectCrewScope,
    },
    submitting: false,
    retryable: false,
    errorMessage: null,
    onSubmit: vi.fn().mockResolvedValue(undefined),
    onRetry: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  }
}

function mountDialog(
  overrides: Record<string, unknown> = {},
  options: Pick<MountingOptions<typeof DelegateToAgentDialog>, 'attachTo'> = {},
) {
  const delegationContext = (overrides.context as DelegationContext | undefined) ?? context()
  const agentStore = fakeAgentStore([personalProfileId, teamProfileId])
  const taskStore = fakeTaskStore(delegationContext)
  return mount(DelegateToAgentDialog, {
    ...options,
    props: props(overrides),
    global: {
      provide: {
        [AGENT_STORE as symbol]: agentStore,
        [TASK_STORE as symbol]: taskStore,
        [AUTH_PRINCIPAL as symbol]: fixturePrincipal,
      },
      stubs: {
        CodingTargetFormSection: defineComponent({
          emits: ['change'],
          setup(_, { emit }) {
            onMounted(() => emit('change', {
              repositoryBindingId: '00000000-0000-4000-8000-00000000f302',
              baselineRef: 'main',
              allowedPaths: ['.'],
              buildProfile: { key: 'maven-java-17', version: 1, profileHash: 'a'.repeat(64) },
            }, true))
            return {}
          },
          template: '<div data-test="coding-target-stub" />',
        }),
      },
    },
  })
}

function fakeAgentStore(profileIds: string[]): AgentStore {
  const agents = profileIds.map((id, index) => agent(id, index))
  const history = Object.fromEntries(profileIds.map(id => [id, {
    phase: 'ready', value: [], nextOffset: null, loadingMore: false,
    errorMessage: null, errorStatus: null,
  }]))
  return {
    state: {
      agents: { phase: agents.length ? 'ready' : 'empty', value: agents, nextOffset: null, loadingMore: false, errorMessage: null, errorStatus: null },
      configurationHistory: history,
    },
    activateScope: vi.fn(),
    loadAgents: vi.fn().mockResolvedValue(undefined),
    loadConfigurationHistory: vi.fn().mockResolvedValue(undefined),
  } as unknown as AgentStore
}

function fakeTaskStore(delegationContext: DelegationContext): TaskStore {
  // The dialog reads the context through the reactive store state, so the fake must be reactive
  // too — a plain object would never notify the computed after the load resolves.
  const delegationPreflights: Record<string, { phase: 'ready', value: TaskDelegationPreflight, errorMessage: null, errorStatus: null }> = reactive({})
  const delegationContexts: Record<string, unknown> = reactive({})
  return {
    state: { delegationPreflights, delegationContexts },
    activateScope: vi.fn(),
    clearDelegationPreflight: vi.fn(),
    clearDelegationContext: vi.fn(),
    loadDelegationContext: vi.fn(async (projectId: string, workItemId: string) => {
      delegationContexts[`${projectId}:${workItemId}`] = {
        phase: 'ready', value: delegationContext, errorMessage: null, errorStatus: null,
      }
      return delegationContext
    }),
    preflightDelegation: vi.fn(async (projectId: string, workItemId: string, selection: TaskDelegationSelection) => {
      const value = preflight(selection.executorAgentProfileId, selection.agentConfigurationRevision ?? 2)
      delegationPreflights[delegationPreflightKey(projectId, workItemId, selection)] = {
        phase: 'ready', value, errorMessage: null, errorStatus: null,
      }
      return value
    }),
  } as unknown as TaskStore
}

function agent(id: string, index: number): AgentSummary {
  return {
    id, principalId: crypto.randomUUID(), displayName: index ? 'Team Delivery Agent' : '张凯旋的 Personal Agent',
    principalStatus: 'ACTIVE', organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform,
    workspaceId: fixtureIds.workspacePlatform, ownershipType: index ? 'TEAM' : 'USER', ownerMemberId: index ? null : crypto.randomUUID(),
    runtimeRole: index ? 'ORCHESTRATOR' : 'PERSONAL', templateKey: 'personal-assistant', templateVersion: 1,
    defaultProfile: !index, status: 'ACTIVE', currentConfigurationRevision: 2,
    currentConfigurationHash: 'c'.repeat(64), createdAt: '2026-08-01T00:00:00Z',
    updatedAt: '2026-08-01T00:00:00Z', version: 2,
  }
}

function preflight(agentProfileId: string, revision: number): TaskDelegationPreflight {
  const team = agentProfileId.endsWith('302')
  return {
    agentProfileId, agentProfileVersion: 2, executionScope: team ? 'TEAM' : 'PERSONAL', configurationRevision: revision,
    configurationHash: 'c'.repeat(64), bindingSource: 'DIRECT', templateVersion: 'personal-assistant@1',
    primary: { role: 'PRIMARY', providerKey: 'deepseek', connectionId: crypto.randomUUID(), connectionOwnerType: team ? 'TEAM' : 'USER', modelId: 'deepseek-v4-flash', catalogRevision: 7, modelRevision: '2026-08', priceRevision: 3 },
    fallback: null, policyPackId: crypto.randomUUID(), policyPackVersion: 4, resolutionHash: 'd'.repeat(64),
  }
}
