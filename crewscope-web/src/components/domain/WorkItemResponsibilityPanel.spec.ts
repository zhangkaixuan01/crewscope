import { flushPromises, mount } from '@vue/test-utils'
import { fixtureResponsibilities } from '../../test/workItemFixtures'
import WorkItemResponsibilityPanel from './WorkItemResponsibilityPanel.vue'

const candidates = [
  { principalId: '00000000-0000-0000-0000-000000000101', displayName: '张凯旋' },
  { principalId: '00000000-0000-0000-0000-000000000102', displayName: '林晨' },
  { principalId: '00000000-0000-0000-0000-000000000103', displayName: '周宁' },
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

  it('submits human and explicit Agent Principal candidates without inventing an Agent directory', async () => {
    const onReplaceOwner = vi.fn().mockResolvedValue(undefined)
    const onAssignExecutor = vi.fn().mockResolvedValue(undefined)
    const onAssignAdvisoryReviewer = vi.fn().mockResolvedValue(undefined)
    const wrapper = mount(WorkItemResponsibilityPanel, { props: props({ onReplaceOwner, onAssignExecutor, onAssignAdvisoryReviewer }) })

    await wrapper.get('[aria-label="新 Owner"]').setValue(candidates[2]!.principalId)
    await wrapper.findAll('.responsibility-actions > form')[0]!.trigger('submit')
    await wrapper.get('details summary').trigger('click')
    await wrapper.get('[aria-label="Executor Agent Principal ID"]').setValue('00000000-0000-0000-0000-000000000201')
    await wrapper.findAll('.agent-assignment form')[0]!.trigger('submit')
    await wrapper.get('[aria-label="Advisory Agent Principal ID"]').setValue('00000000-0000-0000-0000-000000000202')
    await wrapper.findAll('.agent-assignment form')[1]!.trigger('submit')
    await flushPromises()

    expect(onReplaceOwner).toHaveBeenCalledWith(candidates[2]!.principalId)
    expect(onAssignExecutor).toHaveBeenCalledWith('00000000-0000-0000-0000-000000000201')
    expect(onAssignAdvisoryReviewer).toHaveBeenCalledWith('00000000-0000-0000-0000-000000000202')
    expect(wrapper.text()).toContain('不生成模拟 Agent')
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
    ...overrides,
  }
}
