import { mount } from '@vue/test-utils'
import { fixtureTimeline } from '../../test/workItemFixtures'
import WorkItemTimeline from './WorkItemTimeline.vue'

describe('WorkItemTimeline', () => {
  it('renders reviewed event semantics, actor and aggregate version', () => {
    const wrapper = mount(WorkItemTimeline, { props: props() })

    expect(wrapper.get('ol').attributes('aria-label')).toBe('工作项时间线')
    expect(wrapper.text()).toContain('分配责任')
    expect(wrapper.text()).toContain('创建工作项')
    expect(wrapper.text()).toContain('林晨')
    expect(wrapper.text()).toContain('v0')
  })

  it('loads an older Cursor page and preserves partial-page errors', async () => {
    const onLoadMore = vi.fn().mockResolvedValue(undefined)
    const wrapper = mount(WorkItemTimeline, { props: props({ nextCursor: 'opaque-cursor', errorMessage: '更早活动读取失败', onLoadMore }) })

    expect(wrapper.text()).toContain('更早活动读取失败')
    await wrapper.get('button').trigger('click')
    expect(onLoadMore).toHaveBeenCalledOnce()
  })

  it('renders independent loading, empty and error states', () => {
    expect(mount(WorkItemTimeline, { props: props({ phase: 'loading', events: [] }) }).text()).toContain('正在加载活动时间线')
    expect(mount(WorkItemTimeline, { props: props({ phase: 'empty', events: [] }) }).text()).toContain('还没有可展示')
    expect(mount(WorkItemTimeline, { props: props({ phase: 'error', events: [], errorMessage: '时间线失败' }) }).text()).toContain('时间线失败')
  })
})

function props(overrides: Record<string, unknown> = {}) {
  return {
    phase: 'ready' as const,
    events: structuredClone(fixtureTimeline),
    nextCursor: null,
    loadingMore: false,
    errorMessage: null,
    onLoadMore: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  }
}
