import { mount } from '@vue/test-utils'
import { fixtureIds } from '../../test/scopeFixtures'
import { fixtureTasks, taskIds } from '../../test/taskFixtures'
import TaskListPanel from './TaskListPanel.vue'

describe('TaskListPanel', () => {
  it('shows Owner, current attempt and waiting reason and drives server filters', async () => {
    const task = structuredClone(fixtureTasks[fixtureIds.teamPlatform]![0]!)
    task.status = 'WAITING'
    task.currentExecutionStatus = 'WAITING'
    task.currentWaitingReason = 'CAPACITY'
    const onStatusChange = vi.fn()
    const onOwnerChange = vi.fn()
    const onSelect = vi.fn()
    const wrapper = mount(TaskListPanel, { props: props({ items: [task], onStatusChange, onOwnerChange, onSelect }) })

    expect(wrapper.text()).toContain('Attempt 1 · WAITING')
    expect(wrapper.text()).toContain('等待原因 · CAPACITY')
    expect(wrapper.text()).toContain('张凯旋')
    expect(wrapper.get(`[aria-label="查看 Task：${task.objective}"]`).attributes('aria-pressed')).toBe('true')
    await wrapper.findAll('select')[0]!.setValue('WAITING')
    await wrapper.findAll('select')[1]!.setValue(fixtureIds.principal)
    await wrapper.get(`[aria-label="查看 Task：${task.objective}"]`).trigger('click')

    expect(onStatusChange).toHaveBeenCalledWith('WAITING')
    expect(onOwnerChange).toHaveBeenCalledWith(fixtureIds.principal)
    expect(onSelect).toHaveBeenCalledWith(task)
  })

  it('renders loading, empty and error states without optimistic Task facts', () => {
    expect(mount(TaskListPanel, { props: props({ phase: 'loading', items: [] }) }).text()).toContain('正在加载 Agent Tasks')
    expect(mount(TaskListPanel, { props: props({ phase: 'empty', items: [] }) }).text()).toContain('当前筛选下没有 Task')
    expect(mount(TaskListPanel, { props: props({ phase: 'error', items: [], errorMessage: '读取失败' }) }).text()).toContain('读取失败')
  })
})

function props(overrides: Record<string, unknown> = {}) {
  return {
    phase: 'ready' as const,
    items: structuredClone(fixtureTasks[fixtureIds.teamPlatform]!),
    status: 'all' as const,
    ownerPrincipalId: 'all',
    owners: [{ principalId: fixtureIds.principal, displayName: '张凯旋' }],
    selectedTaskId: taskIds.first,
    nextCursor: null,
    loadingMore: false,
    errorMessage: null,
    onStatusChange: vi.fn(), onOwnerChange: vi.fn(), onSelect: vi.fn(),
    onOpenWorkItem: vi.fn(), onRetry: vi.fn(), onLoadMore: vi.fn(),
    ...overrides,
  }
}
