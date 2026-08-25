import { flushPromises, mount, type MountingOptions } from '@vue/test-utils'
import { defineComponent, onMounted } from 'vue'
import { AGENT_STORE, type AgentStore } from '../../domains/agent/store'
import type { AgentSummary } from '../../domains/agent/types'
import { delegationPreflightKey, TASK_STORE, type TaskStore } from '../../domains/task/store'
import type { TaskDelegationPreflight, TaskDelegationSelection } from '../../domains/task/types'
import { taskDelegationDraftKey } from '../../domains/task/delegationDraft'
import { fixtureResponsibilities, fixtureWorkItemDetails } from '../../test/workItemFixtures'
import { fixtureIds } from '../../test/scopeFixtures'
import DelegateToAgentDialog from './DelegateToAgentDialog.vue'

describe('DelegateToAgentDialog', () => {
  beforeEach(() => sessionStorage.clear())

  it('previews responsibility and submits the selected server-authored AgentProfile identity', async () => {
    const responsibilities = structuredClone(fixtureResponsibilities)
    responsibilities[1] = {
      ...responsibilities[1]!,
      actorPrincipalId: '00000000-0000-0000-0000-000000000201',
      actorType: 'PERSONAL_AGENT',
      actorMemberId: null,
      actorDisplayName: '张凯旋的 Personal Agent',
      actorAgentProfileId: '00000000-0000-0000-0000-000000000301',
    }
    const onSubmit = vi.fn().mockResolvedValue(undefined)
    const conversationSource = { conversationId: crypto.randomUUID(), messageId: crypto.randomUUID() }
    const wrapper = mountDialog({ responsibilities, onSubmit, conversationSource })
    await flushPromises()

    expect(wrapper.text()).toContain('Owner · 张凯旋')
    expect(wrapper.text()).toContain('Executor · 张凯旋的 Personal Agent')
    expect(wrapper.text()).toContain('来源保留为当前 Conversation 消息')
    expect(wrapper.text()).toContain('PolicySnapshot Preflight 通过')
    expect(wrapper.text()).toContain('PERSONAL')
    expect(wrapper.text()).toContain('deepseek-v4-flash')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
      objective: fixtureWorkItemDetails.workItem.title,
      executorAgentProfileId: '00000000-0000-0000-0000-000000000301',
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

  it('selects a Team Agent from the responsibility chain and exposes the USER-Key safety boundary', async () => {
    const responsibilities = structuredClone(fixtureResponsibilities)
    responsibilities.push({
      ...responsibilities[1]!,
      id: crypto.randomUUID(),
      actorPrincipalId: crypto.randomUUID(),
      actorType: 'TEAM_AGENT',
      actorMemberId: null,
      actorDisplayName: 'Team Delivery Agent',
      actorAgentProfileId: '00000000-0000-0000-0000-000000000302',
    })
    const wrapper = mountDialog({ responsibilities })
    await flushPromises()

    await wrapper.get('select').setValue('00000000-0000-0000-0000-000000000302')
    await flushPromises()

    expect(wrapper.text()).toContain('TEAM')
    expect(wrapper.text()).toContain('TEAM 执行只允许 TEAM / ORGANIZATION Connection')
    expect(wrapper.text()).toContain('USER Key 已在服务端禁用')
  })

  it('restores a Scope-partitioned draft before preflighting the responsible Agent', async () => {
    const responsibilities = structuredClone(fixtureResponsibilities)
    responsibilities[1] = {
      ...responsibilities[1]!,
      actorType: 'PERSONAL_AGENT',
      actorMemberId: null,
      actorDisplayName: '张凯旋的 Personal Agent',
      actorAgentProfileId: '00000000-0000-0000-0000-000000000301',
    }
    sessionStorage.setItem(taskDelegationDraftKey(
      { organizationId: fixtureIds.organization, teamId: fixtureIds.teamPlatform },
      fixtureIds.projectCrewScope,
      fixtureWorkItemDetails.workItem.id,
    ), JSON.stringify({
      objective: '恢复后的执行目标',
      acceptanceCriteria: '恢复验收一\n恢复验收二',
      executorAgentProfileId: '00000000-0000-0000-0000-000000000301',
      agentConfigurationRevision: null,
    }))

    const wrapper = mountDialog({ responsibilities })
    await flushPromises()

    expect(wrapper.get('input').element).toHaveProperty('value', '恢复后的执行目标')
    expect(wrapper.get('textarea').element).toHaveProperty('value', '恢复验收一\n恢复验收二')
    expect(wrapper.text()).toContain('PolicySnapshot Preflight 通过')
  })

  it('fails closed without an Agent Executor and offers exact-request retry after failure', async () => {
    const unavailable = mountDialog()
    expect(unavailable.text()).toContain('请先在责任链中分配 Agent')
    expect(unavailable.get('button[type="submit"]').attributes('disabled')).toBeDefined()

    const onRetry = vi.fn().mockResolvedValue(undefined)
    const retry = mountDialog({ retryable: true, errorMessage: '网络中断', onRetry })
    const retryButton = retry.findAll('button').find(button => button.text().includes('使用原请求重试'))!
    await retryButton.trigger('click')
    expect(retry.text()).toContain('网络中断')
    expect(onRetry).toHaveBeenCalled()
  })

  it('keeps keyboard focus inside the topmost delegation Modal and closes only that layer', async () => {
    const responsibilities = structuredClone(fixtureResponsibilities)
    responsibilities[1] = {
      ...responsibilities[1]!,
      actorType: 'PERSONAL_AGENT',
      actorMemberId: null,
      actorAgentProfileId: crypto.randomUUID(),
    }
    const wrapper = mountDialog({ responsibilities }, { attachTo: document.body })
    await flushPromises()

    expect(document.activeElement).toBe(wrapper.get('[role="dialog"]').element)
    const controls = wrapper.get('form').findAll('button:not(:disabled), input:not(:disabled), textarea:not(:disabled)')
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

  it('focuses the Modal itself when delegation has no eligible Agent Executor', async () => {
    const wrapper = mountDialog({}, { attachTo: document.body })
    await flushPromises()

    expect(document.activeElement).toBe(wrapper.get('[role="dialog"]').element)
    wrapper.unmount()
  })
})

function props(overrides: Record<string, unknown> = {}) {
  return {
    workItem: structuredClone(fixtureWorkItemDetails.workItem),
    codingScope: {
      organizationId: fixtureIds.organization,
      teamId: fixtureIds.teamPlatform,
      projectId: fixtureIds.projectCrewScope,
    },
    responsibilities: structuredClone(fixtureResponsibilities),
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
  const responsibilities = props(overrides).responsibilities as Array<{ actorAgentProfileId: string | null }>
  const profileIds = responsibilities
    .map((item: { actorAgentProfileId: string | null }) => item.actorAgentProfileId)
    .filter((value: string | null): value is string => Boolean(value))
  const agentStore = fakeAgentStore(profileIds)
  const taskStore = fakeTaskStore()
  return mount(DelegateToAgentDialog, {
    ...options,
    props: props(overrides),
    global: {
      provide: {
        [AGENT_STORE as symbol]: agentStore,
        [TASK_STORE as symbol]: taskStore,
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

function fakeTaskStore(): TaskStore {
  const delegationPreflights: Record<string, { phase: 'ready', value: TaskDelegationPreflight, errorMessage: null, errorStatus: null }> = {}
  return {
    state: { delegationPreflights },
    activateScope: vi.fn(),
    clearDelegationPreflight: vi.fn(),
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
