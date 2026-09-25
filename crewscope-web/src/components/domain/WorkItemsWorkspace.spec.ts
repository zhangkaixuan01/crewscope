import { mount } from '@vue/test-utils'
import WorkItemsWorkspace from './WorkItemsWorkspace.vue'
import type { WorkItemAvailableTransition, WorkItemSummary } from '../../domains/workitem/types'

const requestReview: WorkItemAvailableTransition = {
  actionId: 'request-review', targetStatus: 'IN_REVIEW', label: '提交评审', strength: 'PRIMARY',
  reversible: true, enabled: true, reason: null, reasonMessage: null, remedyLabel: null, remedyRoute: null,
}

const item: WorkItemSummary = {
  id: 'work-1', organizationId: 'org-1', teamId: 'team-1', workspaceId: 'workspace-1', projectId: 'project-1',
  key: 'DEMO-1', title: '拆分工作台', description: null, type: 'TASK', status: 'READY', priority: 'HIGH', labels: [], dueAt: null,
  source: 'MANUAL', sourceReference: null, version: 1, createdAt: '2026-08-24T01:00:00Z', createdByPrincipalId: null,
  updatedAt: '2026-08-24T01:00:00Z', updatedByPrincipalId: null, availableActions: [requestReview], summary: null,
}

function mountWorkspace(overrides: Record<string, unknown> = {}) {
  return mount(WorkItemsWorkspace, {
    props: {
      phase: 'ready', errorMessage: null, filteredItems: [item], view: 'list', boardStatuses: ['READY'], statusLabels: { READY: '就绪' },
      nextCursor: null, loadingMore: false, canCreate: true, draggedWorkItem: null, dragOverStatus: null, boardAnnouncement: '',
      pendingItemId: null, confirmingTarget: null, busyItemId: null,
      allowDrop: () => true, itemsFor: () => [item], selectable: false, isSelected: () => false,
      onCreate: () => undefined, onRetry: () => undefined, onClearFilters: () => undefined,
      onLoadMore: () => undefined, onSelect: () => undefined, onToggleSelect: () => undefined, onAction: () => undefined, onDragStart: () => undefined, onDragEnd: () => undefined,
      onDragOver: () => undefined, onDragLeave: () => undefined, onDrop: () => undefined, onBoardKeydown: () => undefined,
      ...overrides,
    },
  })
}

describe('WorkItemsWorkspace', () => {
  it('renders list state and delegates card selection', async () => {
    const wrapper = mountWorkspace()
    expect(wrapper.find('[aria-label="工作项列表"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('DEMO-1')
  })

  it('passes the action up together with the row it came from', async () => {
    // The card does not know which row it is; the workspace does. An action emitted without its item
    // would be executed against whatever the drawer happened to have open.
    const onAction = vi.fn()
    const wrapper = mountWorkspace({ onAction })

    await wrapper.get('.status-badge--interactive, .transition-menu button').trigger('click')
    await wrapper.findAll('[role="menuitem"]')[0]!.trigger('click')

    expect(onAction).toHaveBeenCalledWith(item, requestReview)
  })

  it('arms only the row the confirmation belongs to', async () => {
    const wrapper = mountWorkspace({ pendingItemId: 'someone-else', confirmingTarget: 'IN_REVIEW' })
    expect(wrapper.text()).not.toContain('再次点击确认提交评审')

    await wrapper.setProps({ pendingItemId: 'work-1' })
    await wrapper.get('.transition-menu button').trigger('click')
    expect(wrapper.text()).toContain('再次点击确认提交评审')
  })

  it('offers a selectable row only where selection is enabled, and names what it selects', async () => {
    // 看板卡片没有多选列，成员权限不足时列表也没有；复选框出现而没有权限比没有复选框更糟。
    expect(mountWorkspace().find('input[type="checkbox"]').exists()).toBe(false)
    expect(mountWorkspace({ view: 'board' }).find('input[type="checkbox"]').exists()).toBe(false)

    const onToggleSelect = vi.fn()
    const wrapper = mountWorkspace({ selectable: true, isSelected: () => false, onToggleSelect })
    const checkbox = wrapper.get('input[type="checkbox"]')
    expect(checkbox.attributes('aria-label')).toBe('选择 DEMO-1 拆分工作台')

    await checkbox.setValue(true)
    expect(onToggleSelect).toHaveBeenCalledWith(item)
  })
})
