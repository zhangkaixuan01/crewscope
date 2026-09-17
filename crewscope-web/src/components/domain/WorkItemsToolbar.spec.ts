import { mount } from '@vue/test-utils'
import WorkItemsToolbar from './WorkItemsToolbar.vue'

describe('WorkItemsToolbar', () => {
  const props = {
    teamName: 'Platform', projectKey: 'CORE', resultCount: 3, view: 'list' as const,
    status: 'all', type: 'all', priority: 'all', statuses: ['OPEN'], types: ['TASK'], priorities: ['HIGH'],
    statusLabels: { OPEN: '开放' }, typeLabels: { TASK: '任务' }, priorityLabels: { HIGH: '高' }, canCreate: true,
    sortKey: 'updatedAt' as const, sortDirection: 'desc' as const, loadedCount: 3, hasMore: false,
  }

  it('emits filter and view changes', async () => {
    const wrapper = mount(WorkItemsToolbar, { props })
    await wrapper.findAll('select')[0]!.setValue('OPEN')
    await wrapper.get('button[aria-label="看板视图"]').trigger('click')
    expect(wrapper.emitted('updateQuery')).toEqual([['status', 'OPEN'], ['view', 'board']])
  })

  it('emits the create action only through the explicit button', async () => {
    const wrapper = mount(WorkItemsToolbar, { props })
    await wrapper.get('.create-work-item').trigger('click')
    expect(wrapper.emitted('create')).toHaveLength(1)
  })

  it('names the set the sort control orders and the field it cannot order by', async () => {
    const wrapper = mount(WorkItemsToolbar, { props: { ...props, hasMore: true, loadedCount: 20 } })
    const group = wrapper.get('[aria-label="排序"]')

    expect(group.text()).toContain('排序作用于已加载的 20 项，还有更多未加载')
    expect(group.get('button[aria-label^="按更新时间排序"]').attributes('aria-pressed')).toBe('true')
    // 负责人不在列表响应里，禁用而不是留一个点了没反应的排序键；禁用原因必须可读出。
    const unsortable = group.findAll('button').find(button => button.text() === '负责人')!
    expect(unsortable.attributes('disabled')).toBeDefined()

    await group.get('button[aria-label^="按优先级排序"]').trigger('click')
    expect(wrapper.emitted('sort')).toEqual([['priority']])
  })
})
