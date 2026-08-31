import { flushPromises, mount } from '@vue/test-utils'
import { fixtureResponsibilities } from '../../test/workItemFixtures'
import WorkItemResponsibilityPanel from './WorkItemResponsibilityPanel.vue'

const candidates = [
  { principalId: '00000000-0000-0000-0000-000000000101', displayName: '张凯旋' },
  { principalId: '00000000-0000-0000-0000-000000000102', displayName: '林晨' },
  { principalId: '00000000-0000-0000-0000-000000000103', displayName: '周宁' },
]
const agentCandidates = [
  { principalId: '00000000-0000-0000-0000-000000000201', displayName: 'Coding Agent', ownershipType: 'USER' as const, runtimeRole: 'SPECIALIST' },
  { principalId: '00000000-0000-0000-0000-000000000202', displayName: 'Team Coordinator', ownershipType: 'TEAM' as const, runtimeRole: 'TEAM_COORDINATOR' },
]

describe('WorkItemResponsibilityPanel', () => {
  it('renders the real role semantics and releases only non-Owner assignments', async () => {
    const onRelease = vi.fn().mockResolvedValue(undefined)
    const wrapper = mount(WorkItemResponsibilityPanel, { props: props({ onRelease }) })

    expect(wrapper.text()).toContain('Gate Reviewer')
    expect(wrapper.text()).toContain('Advisory Reviewer')
    expect(wrapper.text()).toContain('不具有 Gate 效力')
    expect(wrapper.find('[aria-label*="张凯旋"][aria-label*="释放"]').exists()).toBe(false)
    await wrapper.get('[aria-label*="Architecture Reviewer"][aria-label*="释放"]').trigger('click')
    await flushPromises()
    expect(onRelease).toHaveBeenCalledWith(expect.objectContaining({ role: 'REVIEWER', actorType: 'SPECIALIST_AGENT' }))
  })

  it('submits human candidates and ACTIVE Agents from the server directory', async () => {
    const onReplaceOwner = vi.fn().mockResolvedValue(undefined)
    const onAssignExecutor = vi.fn().mockResolvedValue(undefined)
    const onAssignAdvisoryReviewer = vi.fn().mockResolvedValue(undefined)
    const wrapper = mount(WorkItemResponsibilityPanel, { props: props({ onReplaceOwner, onAssignExecutor, onAssignAdvisoryReviewer }) })

    await wrapper.get('[aria-label="新 Owner"]').setValue(candidates[2]!.principalId)
    await wrapper.findAll('.responsibility-actions > form')[0]!.trigger('submit')
    await wrapper.get('[aria-label="Agent Executor"]').setValue(agentCandidates[1]!.principalId)
    await wrapper.findAll('.agent-directory form')[0]!.trigger('submit')
    await wrapper.get('[aria-label="Advisory Reviewer Agent"]').setValue(agentCandidates[0]!.principalId)
    await wrapper.findAll('.agent-directory form')[1]!.trigger('submit')
    await wrapper.get('details summary').trigger('click')
    await wrapper.get('[aria-label="Executor Agent Principal ID"]').setValue('00000000-0000-0000-0000-000000000201')
    await wrapper.findAll('.agent-assignment form')[0]!.trigger('submit')
    await wrapper.get('[aria-label="Advisory Agent Principal ID"]').setValue('00000000-0000-0000-0000-000000000202')
    await wrapper.findAll('.agent-assignment form')[1]!.trigger('submit')
    await flushPromises()

    expect(onReplaceOwner).toHaveBeenCalledWith(candidates[2]!.principalId)
    expect(onAssignExecutor).toHaveBeenCalledWith(agentCandidates[1]!.principalId)
    expect(onAssignAdvisoryReviewer).toHaveBeenCalledWith(agentCandidates[0]!.principalId)
    expect(onAssignExecutor).toHaveBeenCalledWith('00000000-0000-0000-0000-000000000201')
    expect(onAssignAdvisoryReviewer).toHaveBeenCalledWith('00000000-0000-0000-0000-000000000202')
    expect(wrapper.text()).toContain('来自当前 Team 与 Workspace 的 ACTIVE Agent')
    expect(wrapper.text()).toContain('手动使用 Agent Principal ID')
  })

  it('fails closed when the Agent directory is unavailable and exposes an explicit retry', async () => {
    const onRetryAgents = vi.fn()
    const wrapper = mount(WorkItemResponsibilityPanel, { props: props({
      agentCandidates: [], agentPhase: 'error', agentErrorMessage: 'Agent 目录读取失败', onRetryAgents,
    }) })

    expect(wrapper.text()).toContain('Agent 目录读取失败')
    await wrapper.find('.agent-directory-state.error button').trigger('click')
    expect(onRetryAgents).toHaveBeenCalledOnce()
  })

  it('can continue Agent directory pagination when the current page has no eligible candidate', async () => {
    const onLoadMoreAgents = vi.fn()
    const wrapper = mount(WorkItemResponsibilityPanel, { props: props({
      agentCandidates: [], agentHasMore: true, onLoadMoreAgents,
    }) })

    expect(wrapper.text()).toContain('可继续加载下一页')
    await wrapper.find('.agent-directory-more').trigger('click')
    expect(onLoadMoreAgents).toHaveBeenCalledOnce()
  })

  it('explains role-specific empty states instead of rendering disabled empty selects', () => {
    const wrapper = mount(WorkItemResponsibilityPanel, { props: props({
      members: [
        ...structuredClone(fixtureResponsibilities),
        {
          ...structuredClone(fixtureResponsibilities[1]!),
          id: '00000000-0000-0000-0000-000000000904',
          actorPrincipalId: agentCandidates[1]!.principalId,
          actorType: 'TEAM_AGENT',
          actorDisplayName: 'Team Coordinator',
        },
      ],
      agentCandidates: [agentCandidates[1]!],
      agentHasMore: true,
    }) })

    expect(wrapper.text()).toContain('均已承担 Executor')
    expect(wrapper.text()).toContain('没有可担任 Advisory Reviewer 的 Specialist Agent')
    expect(wrapper.find('[aria-label="Agent Executor"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="Advisory Reviewer Agent"]').exists()).toBe(false)
  })

  it('warns about obvious Gate Reviewer separation conflicts while leaving policy to the server', async () => {
    const onAssignGateReviewer = vi.fn().mockResolvedValue(undefined)
    const wrapper = mount(WorkItemResponsibilityPanel, { props: props({ onAssignGateReviewer }) })

    await wrapper.get('[aria-label="Gate Reviewer"]').setValue(candidates[1]!.principalId)
    expect(wrapper.text()).toContain('默认职责分离策略会拒绝')
    await wrapper.findAll('.responsibility-actions > form')[2]!.trigger('submit')
    await flushPromises()

    expect(onAssignGateReviewer).toHaveBeenCalledWith(candidates[1]!.principalId)
  })

  it('exposes read-only and sanitized error states', () => {
    const readonly = mount(WorkItemResponsibilityPanel, { props: props({ canManage: false }) })
    expect(readonly.text()).toContain('需要 Responsibility Manage 权限')
    expect(readonly.find('.responsibility-actions').exists()).toBe(false)

    const error = mount(WorkItemResponsibilityPanel, { props: props({ phase: 'error', members: [], errorMessage: '责任链读取失败', commandErrorMessage: '责任链已刷新' }) })
    expect(error.text()).toContain('责任链读取失败')
    expect(error.text()).toContain('责任链已刷新')
  })
})

function props(overrides: Record<string, unknown> = {}) {
  return {
    phase: 'ready' as const,
    members: structuredClone(fixtureResponsibilities),
    candidates,
    agentCandidates,
    agentPhase: 'ready' as const,
    agentErrorMessage: null,
    agentLoadingMore: false,
    agentHasMore: false,
    errorMessage: null,
    commandPending: null,
    commandErrorMessage: null,
    canManage: true,
    onRetry: vi.fn(),
    onReplaceOwner: vi.fn().mockResolvedValue(undefined),
    onAssignExecutor: vi.fn().mockResolvedValue(undefined),
    onAssignGateReviewer: vi.fn().mockResolvedValue(undefined),
    onAssignAdvisoryReviewer: vi.fn().mockResolvedValue(undefined),
    onRelease: vi.fn().mockResolvedValue(undefined),
    onRetryAgents: vi.fn(),
    onLoadMoreAgents: vi.fn(),
    ...overrides,
  }
}
