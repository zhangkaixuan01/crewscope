import { mount } from '@vue/test-utils'
import WorkItemsToolbar from './WorkItemsToolbar.vue'

describe('WorkItemsToolbar', () => {
  const props = {
    teamName: 'Platform', projectKey: 'CORE', resultCount: 3, view: 'list' as const,
    status: 'all', type: 'all', priority: 'all', statuses: ['OPEN'], types: ['TASK'], priorities: ['HIGH'],
    statusLabels: { OPEN: '开放' }, typeLabels: { TASK: '任务' }, priorityLabels: { HIGH: '高' }, canCreate: true,
    sortKey: 'updatedAt' as const, loadedCount: 3, hasMore: false,
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

    // 排序与筛选已经随查询发给服务端：作用域是整个项目，计数只说明分页还剩多少。
    expect(group.text()).toContain('排序与筛选作用于整个项目，已加载 20 项，还有更多')
    expect(group.get('button[aria-label^="按更新时间排序"]').attributes('aria-pressed')).toBe('true')
    // 每个排序键自带服务端合同的固定方向，不再有可切换的方向。
    expect(group.find('button[aria-label="按更新时间排序（当前新→旧）"]').exists()).toBe(true)
    expect(group.find('button[aria-label="按截止时间排序（近→远）"]').exists()).toBe(true)
    // 负责人不在列表响应里，禁用而不是留一个点了没反应的排序键；禁用原因必须可读出。
    const unsortable = group.findAll('button').find(button => button.text() === '负责人')!
    expect(unsortable.attributes('disabled')).toBeDefined()

    await group.get('button[aria-label^="按优先级排序"]').trigger('click')
    expect(wrapper.emitted('sort')).toEqual([['priority']])
  })

  it('keeps the applied filter count readable while the phone filter row is folded', async () => {
    const wrapper = mount(WorkItemsToolbar, { props: { ...props, status: 'OPEN', priority: 'HIGH' } })
    const toggle = wrapper.get('.filters-toggle')

    // 折叠态不显示筛选控件，但已应用的筛选数量必须一直在（R16）。
    expect(toggle.text()).toContain('已应用 2 项')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('.filters--open').exists()).toBe(false)

    await toggle.trigger('click')
    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(wrapper.find('.filters--open').exists()).toBe(true)
  })
})
