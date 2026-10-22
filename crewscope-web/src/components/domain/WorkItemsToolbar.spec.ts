import { mount } from '@vue/test-utils'
import WorkItemsToolbar from './WorkItemsToolbar.vue'

describe('WorkItemsToolbar', () => {
  const props = {
    resultCount: 3, view: 'list' as const,
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

  it('names the set the sort menu orders and the field it cannot order by', async () => {
    const wrapper = mount(WorkItemsToolbar, { props: { ...props, hasMore: true, loadedCount: 20 } })
    const group = wrapper.get('[aria-label="排序"]')

    // 排序与筛选随查询发给服务端：作用域是整个项目，计数只说明分页还剩多少——
    // 单行预算下这句声明降级为排序控件的 aria 描述（M13b-F09），屏幕阅读器照读。
    expect(group.text()).toContain('排序与筛选作用于整个项目，已加载 20 项，还有更多')
    // 每个排序键自带服务端合同的固定方向，方向写进选项文本，不再有可切换的方向。
    const options = group.findAll('option')
    expect(options.map(option => option.text())).toContain('更新时间（新→旧）')
    expect(options.map(option => option.text())).toContain('截止时间（近→远）')
    // 负责人不在列表响应里，禁用而不是留一个点了没反应的排序键；禁用原因必须可读出。
    const unsortable = options.find(option => option.text().includes('负责人'))!
    expect((unsortable.element as HTMLOptionElement).disabled).toBe(true)
    expect(group.text()).toContain('工作项列表响应不携带责任链')

    await group.get('select').setValue('priority')
    expect(wrapper.emitted('sort')).toEqual([['priority']])
  })

  it('keeps the filters directly visible at every width instead of folding them', () => {
    const wrapper = mount(WorkItemsToolbar, { props: { ...props, status: 'OPEN', priority: 'HIGH' } })

    // M13b-F09：手机折叠+badge（M9b-R16）由窄屏自然换行替代——筛选控件永远在 DOM 里直接可读，
    // 不再有「知道列表被缩小了但看不见被什么缩小」的折叠态。
    expect(wrapper.findAll('select').length).toBe(4)
    expect(wrapper.find('.filters-toggle').exists()).toBe(false)
    expect(wrapper.text()).toContain('3 项当前结果')
  })
})
