import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import BaseListShell from './BaseListShell.vue'

/**
 * Shell contract tests (the domain triplets keep their own specs for filters, rows and
 * empty-state actions): the five-level state ladder, the compact strips over cached
 * content, and the load-more footer's offline describedby pattern.
 */
interface Row {
  id: string
}

describe('BaseListShell', () => {
  it('shows the loading panel before the first items arrive', () => {
    const wrapper = mounted({ phase: 'loading', items: [] })
    expect(wrapper.text()).toContain('正在读取条目')
    expect(wrapper.text()).toContain('读取说明')
  })

  it('answers a 403 with the permission explainer action', () => {
    const wrapper = mounted({ phase: 'error', items: [], errorStatus: 403, errorMessage: '无权' })
    expect(wrapper.text()).toContain('无权读取列表')
    expect(wrapper.get('a[href*="access-denied"]').text()).toBe('查看权限说明')
  })

  it('keeps the empty state free of the ladder but injects the domain action', () => {
    const wrapper = mounted({ phase: 'ready', items: [], slots: { 'empty-action': '<button data-create>创建条目</button>' } })
    expect(wrapper.text()).toContain('暂无条目')
    expect(wrapper.find('[data-create]').exists()).toBe(true)
    // The empty state replaces the list and the footer entirely.
    expect(wrapper.find('.base-list-shell__items').exists()).toBe(false)
    expect(wrapper.find('.base-list-shell__more').exists()).toBe(false)
  })

  it('renders cached content under the compact offline strip', () => {
    const wrapper = mounted({
      online: false,
      slots: { items: itemsSlot },
    })
    expect(wrapper.text()).toContain('正在展示最近读取的条目')
    expect(wrapper.findAll('.base-list-shell__items li')).toHaveLength(2)
  })

  it('forwards retry from the empty hard-error state', async () => {
    const wrapper = mounted({ phase: 'error', items: [], errorMessage: '服务暂时无法完成请求' })
    expect(wrapper.text()).toContain('服务暂时无法完成请求')
    await wrapper.findAll('button').find(button => button.text() === '刷新事实')!.trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('disables the load-more button offline and describes why', () => {
    const online = mounted({ online: true, slots: itemsSlot })
    const more = online.get('.base-list-shell__more button')
    expect(more.attributes('disabled')).toBeUndefined()
    expect(more.attributes('aria-describedby')).toBeUndefined()

    const offline = mounted({ online: false, slots: itemsSlot })
    const offlineMore = offline.get('.base-list-shell__more button')
    expect(offlineMore.attributes('disabled')).toBeDefined()
    const describedBy = offlineMore.attributes('aria-describedby')
    expect(describedBy).toBeDefined()
    expect(offline.get(`#${describedBy}`).text()).toBe('离线时续页读取保持关闭。')
  })

  it('forwards load-more and hides the footer without a cursor', async () => {
    const wrapper = mounted({ slots: itemsSlot })
    await wrapper.get('.base-list-shell__more button').trigger('click')
    expect(wrapper.emitted('loadMore')).toHaveLength(1)

    const done = mounted({ nextAfter: null, slots: itemsSlot })
    expect(done.find('.base-list-shell__more').exists()).toBe(false)
  })

  it('renders the filter header and lead note before the ladder', () => {
    const wrapper = mounted({
      slots: {
        filters: '<header class="probe-filters"><select /></header>',
        lead: '<p class="probe-lead" role="note">目录说明</p>',
      },
    })
    expect(wrapper.find('header.probe-filters').exists()).toBe(true)
    expect(wrapper.find('section[aria-label="条目列表"] .probe-lead').exists()).toBe(true)
  })
})

const itemsSlot = '<li>a</li><li>b</li>'
const rows: Row[] = [{ id: 'a' }, { id: 'b' }]

function mounted(overrides: Record<string, unknown> = {}) {
  const { slots, ...rest } = overrides
  // The forbidden ladder links to the access explanation page, so the router table needs
  // that named route.
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/access-denied', name: 'access-denied', component: { template: '<div />' } },
    ],
  })
  void router.push('/')
  void router.isReady()
  return mount(BaseListShell, {
    props: {
      phase: 'ready',
      items: rows,
      nextAfter: 'cursor',
      loadingMore: false,
      errorMessage: null,
      errorStatus: null,
      online: true,
      label: '条目列表',
      loadingTitle: '正在读取条目',
      loadingDescription: '读取说明',
      forbiddenTitle: '无权读取列表',
      forbiddenDescription: '服务端没有为当前身份解析出这个 Team 的活动成员。',
      offlineEmptyTitle: '离线时没有可用条目',
      offlineEmptyDescription: '联网后会回读。',
      emptyTitle: '暂无条目',
      emptyDescription: '条目会沉淀在这里。',
      compactOfflineTitle: '正在展示最近读取的条目',
      compactOfflineDescription: '离线期间管理命令保持关闭。',
      loadMoreLabel: '读取更多条目',
      ...rest,
    },
    slots: slots as Record<string, string> | undefined,
    global: { plugins: [router] },
  })
}
