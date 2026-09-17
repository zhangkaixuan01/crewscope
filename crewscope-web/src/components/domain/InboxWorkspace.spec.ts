import { mount } from '@vue/test-utils'
import type { Etagged, InboxCounts, InboxItem } from '../../domains/teamops/types'

// 权限不足状态的动作是跳转到权限说明页；这些用例只断言文案，因此用轻量 stub 代替路由实例。
const routerLinkStub = { RouterLink: { props: ['to'], template: '<a href="#"><slot /></a>' } }
import InboxWorkspace from './InboxWorkspace.vue'

describe('InboxWorkspace', () => {
  /**
   * The batch bar exists only while something is selected.
   *
   * `useSelection` returns refs held on an object, and only top-level template bindings are
   * unwrapped — reading `selection.selectedCount` straight from the template yields the ref object,
   * which is always truthy. The bar would then be on screen with 「已选 0 项」 and a live batch button
   * over an empty selection, which is what this pins down.
   */
  it('hides the batch bar and its count until a row is selected', async () => {
    const wrapper = mount(InboxWorkspace, { props: props(), global: { stubs: routerLinkStub } })
    expect(wrapper.find('[aria-label="批量处置"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('已选')

    await wrapper.get('input[type="checkbox"]').setValue(true)
    expect(wrapper.get('[aria-label="批量处置"]').text()).toContain('已选 1 项（跨页保留）')
  })

  it('renders five member views, server counts, priority, deadline and filter events', async () => {
    const wrapper = mount(InboxWorkspace, { props: props(), global: { stubs: routerLinkStub } })

    expect(wrapper.get('[aria-label="Inbox 五类视图"]').text()).toContain('我的负责')
    expect(wrapper.get('[aria-label="Inbox 五类视图"]').text()).toContain('我的执行')
    expect(wrapper.get('[aria-label="Inbox 五类视图"]').text()).toContain('待 Review')
    expect(wrapper.get('[aria-label="Inbox 五类视图"]').text()).toContain('待确认')
    expect(wrapper.get('[aria-label="Inbox 五类视图"]').text()).toContain('异常')
    expect(wrapper.text()).toContain('2 项待处理事实')
    expect(wrapper.text()).toContain('紧急')
    expect(wrapper.text()).toContain('无截止时间')

    await wrapper.get('button[aria-label="查看 我的负责 详情"]').trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual([item().inboxItemId])
    await wrapper.findAll<HTMLSelectElement>('.inbox-toolbar select')[0]!.setValue('CLOSED')
    expect(wrapper.emitted('changeSourceStatus')?.[0]).toEqual(['CLOSED'])
    await wrapper.findAll<HTMLSelectElement>('.inbox-toolbar select')[1]!.setValue('READ')
    expect(wrapper.emitted('changeDispositionStatus')?.[0]).toEqual(['READ'])
  })

  it('exposes the strong-version member actions and authorized target entry', async () => {
    const selected = item({ dispositionStatus: 'UNREAD', dispositionVersion: 0, etag: '"0"' })
    const wrapper = mount(InboxWorkspace, {
      props: props({ selectedItemId: selected.inboxItemId, detailPhase: 'ready', detail: { value: selected, etag: '"0"' } }),
      global: { stubs: routerLinkStub },
    })

    expect(wrapper.text()).toContain('强 ETag · 单调状态')
    expect(wrapper.text()).toContain('v0')
    const openTarget = wrapper.findAll('button').find(button => button.text().includes('打开来源'))!
    await openTarget.trigger('click')
    expect(wrapper.emitted('openTarget')?.[0]).toEqual([selected.inboxItemId])

    const actionButtons = wrapper.findAll('.inbox-detail__actions button')
    expect(actionButtons.map(button => button.text())).toEqual(['标记已读', '标记已处理', '归档'])
    await actionButtons[1]!.trigger('click')
    expect(wrapper.emitted('changeDisposition')?.[0]).toEqual([selected.inboxItemId, 'ACTED'])
  })

  it('shows conflict, retryable command error and source resolution error without hiding facts', () => {
    const selected = item({ dispositionStatus: 'READ', dispositionVersion: 2, etag: '"2"' })
    const wrapper = mount(InboxWorkspace, {
      props: props({
        selectedItemId: selected.inboxItemId,
        detailPhase: 'ready',
        detail: { value: selected, etag: '"2"' },
        command: { phase: 'conflict', operation: 'inbox-disposition', targetId: selected.inboxItemId, receipt: null, error: error('conflict') },
        targetError: error('unknown'),
      }),
      global: { stubs: routerLinkStub },
    })

    expect(wrapper.text()).toContain('处置版本已更新')
    expect(wrapper.text()).toContain('来源解析失败')
    expect(wrapper.text()).toContain('我的负责')
  })

  it('never presents a failed server count as an authoritative zero', () => {
    const wrapper = mount(InboxWorkspace, {
      props: props({ countsPhase: 'error', counts: null, countsError: error('unknown') }),
      global: { stubs: routerLinkStub },
    })

    expect(wrapper.text()).toContain('计数暂不可用')
    expect(wrapper.text()).toContain('计数同步失败')
    expect(wrapper.text()).not.toContain('0 项待处理事实')
  })

  it('keeps cached facts visible when a continuation Cursor expires', () => {
    const wrapper = mount(InboxWorkspace, {
      props: props({ phase: 'error', error: error('cursor-expired') }),
      global: { stubs: routerLinkStub },
    })

    expect(wrapper.text()).toContain('续页 Cursor 已过期')
    expect(wrapper.text()).toContain('责任分配')
  })

  it.each([
    ['loading', { phase: 'loading', items: [] }, '正在同步我的 Inbox'],
    ['empty', { phase: 'empty', items: [] }, '我的负责暂无项目'],
    ['forbidden', { phase: 'error', items: [], error: error('forbidden') }, '无权读取 Inbox'],
    ['offline', { phase: 'error', items: [], error: error('offline'), online: false }, '离线时没有可用 Inbox'],
    ['cursor-expired', { phase: 'error', items: [], error: error('cursor-expired') }, 'Inbox Cursor 已过期'],
    ['error', { phase: 'error', items: [], error: error('unknown') }, 'unknown'],
  ] as const)('renders the %s list state', (_name, overrides, expected) => {
    const wrapper = mount(InboxWorkspace, { props: props(overrides), global: { stubs: routerLinkStub } })
    expect(wrapper.text()).toContain(expected)
  })
})

function props(overrides: Record<string, unknown> = {}) {
  const value = item()
  return {
    phase: 'ready' as const,
    items: [value],
    countsPhase: 'ready' as const,
    counts: counts(),
    countsError: null,
    nextCursor: 'older-cursor',
    loadingMore: false,
    error: null,
    selectedItemId: null,
    detailPhase: 'idle' as const,
    detail: null as Etagged<InboxItem> | null,
    detailError: null,
    targetPhase: 'idle' as const,
    targetError: null,
    command: { phase: 'idle' as const, operation: null, targetId: null, receipt: null, error: null },
    itemType: 'OWNERSHIP' as const,
    sourceStatus: 'OPEN' as const,
    dispositionStatus: 'ALL' as const,
    online: true,
    ...overrides,
  }
}

function item(overrides: Partial<InboxItem> = {}): InboxItem {
  return {
    inboxItemId: '00000000-0000-4000-8000-000000000901', itemType: 'OWNERSHIP', priority: 'URGENT',
    deadline: null, openedAt: '2026-08-27T08:00:00Z', sourceStatus: 'OPEN', closeReason: null, closedAt: null,
    dispositionStatus: 'UNREAD', dispositionVersion: 0, etag: '"0"',
    source: { type: 'RESPONSIBILITY_ASSIGNMENT', id: '00000000-0000-4000-8000-000000000902', revision: 3 },
    ...overrides,
  }
}

function counts(): InboxCounts {
  return {
    total: 2, unread: 1,
    byType: {
      OWNERSHIP: { total: 1, unread: 1 }, EXECUTION: { total: 1, unread: 0 }, REVIEW: { total: 0, unread: 0 },
      CONFIRMATION: { total: 0, unread: 0 }, EXCEPTION: { total: 0, unread: 0 },
    },
  }
}

function error(kind: 'forbidden' | 'offline' | 'cursor-expired' | 'conflict' | 'unknown') {
  return { kind, message: kind, status: null, retryable: kind === 'conflict', currentVersion: kind === 'conflict' ? 2 : null }
}
