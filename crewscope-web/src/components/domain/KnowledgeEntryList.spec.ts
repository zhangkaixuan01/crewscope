import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import KnowledgeEntryList from './KnowledgeEntryList.vue'
import type { KnowledgeEntrySummary } from '../../domains/knowledge/types'

describe('KnowledgeEntryList', () => {
  it('renders entry keys as the row identity with category, status, index and revision facts', async () => {
    const wrapper = await mounted({ items: [entry(), entry({ id: 'entry-2', entryKey: 'release-notes', status: 'DRAFT', indexStatus: 'PENDING', effectiveRevision: null, draft: { title: 'x', content: 'y' } })] })

    expect(wrapper.get('section[aria-label="知识条目列表"]')).toBeTruthy()
    expect(wrapper.text()).toContain('deploy-runbook')
    expect(wrapper.text()).toContain('操作手册')
    expect(wrapper.text()).toContain('已发布')
    expect(wrapper.text()).toContain('已索引')
    expect(wrapper.text()).toContain('生效 r2')
    expect(wrapper.text()).toContain('最新 r2')
    expect(wrapper.text()).toContain('待索引')

    await wrapper.findAll('button.knowledge-list__row')[0]!.trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual(['entry-1'])
  })

  it('marks the selected row and hides the index badge on tombstones', async () => {
    const wrapper = await mounted({
      items: [entry(), entry({ id: 'entry-2', entryKey: 'legacy-guard', status: 'DELETED', indexStatus: 'FAILED' })],
      selectedEntryId: 'entry-2',
    })

    const rows = wrapper.findAll('button.knowledge-list__row')
    expect(rows[1]!.attributes('aria-current')).toBe('true')
    expect(rows[0]!.attributes('aria-current')).toBeUndefined()
    expect(wrapper.text()).not.toContain('索引失败')
  })

  it.each([
    ['loading', { phase: 'loading', items: [] }, '正在读取知识条目'],
    ['empty', { phase: 'empty', items: [] }, '暂无知识条目'],
    ['forbidden', { phase: 'error', items: [], errorMessage: 'denied', errorStatus: 403 }, '无权读取知识库'],
    ['offline', { phase: 'ready', items: [], online: false }, '离线时没有可用知识条目'],
    ['error', { phase: 'error', items: [], errorMessage: '服务暂时无法完成请求', errorStatus: 503 }, '服务暂时无法完成请求'],
  ] as const)('renders the %s state', async (_name, props, expected) => {
    const wrapper = await mounted({ ...props })
    expect(wrapper.text()).toContain(expected)
  })

  it('offers creation and distillation actions to managers on an empty listing', async () => {
    const wrapper = await mounted({ phase: 'empty', items: [], canManage: true })
    const buttons = wrapper.findAll('button')
    expect(buttons.map(button => button.text())).toEqual(expect.arrayContaining(['创建条目', '从执行蒸馏']))
    await buttons.find(button => button.text() === '创建条目')!.trigger('click')
    expect(wrapper.emitted('create')).toHaveLength(1)
  })

  it('offers only filter clearing to readers on a filtered empty listing', async () => {
    const wrapper = await mounted({ phase: 'empty', items: [], statusFilter: 'PUBLISHED' })

    expect(wrapper.text()).not.toContain('创建条目')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('changeStatus')?.[0]).toEqual(['ALL'])
    expect(wrapper.emitted('changeCategory')?.[0]).toEqual(['ALL'])
  })

  it('links forbidden readers to the access explanation page', async () => {
    const wrapper = await mounted({ phase: 'error', items: [], errorMessage: 'denied', errorStatus: 403 })

    expect(wrapper.get('a').attributes('href')).toContain('/access-denied')
    expect(wrapper.get('a').attributes('href')).toContain('requiredPermission=scope:read')
  })

  it('emits filter changes and keeps the previous listing while a forced reload runs', async () => {
    const wrapper = await mounted({ items: [entry()], statusFilter: 'PUBLISHED', categoryFilter: 'RUNBOOK' })
    const selects = wrapper.findAll('select')

    await selects[0]!.setValue('DRAFT')
    await selects[1]!.setValue('DECISION')

    expect(wrapper.emitted('changeStatus')?.[0]).toEqual(['DRAFT'])
    expect(wrapper.emitted('changeCategory')?.[0]).toEqual(['DECISION'])
    expect(wrapper.text()).toContain('deploy-runbook')
  })

  it('continues keyset pages and disables continuation while offline', async () => {
    const paging = await mounted({ items: [entry()], nextAfter: 'deploy-runbook' })
    await paging.get('.knowledge-list__more button').trigger('click')
    expect(paging.emitted('loadMore')).toHaveLength(1)

    const offline = await mounted({ items: [entry()], nextAfter: 'deploy-runbook', online: false })
    expect(offline.text()).toContain('正在展示最近读取的知识条目')
    expect(offline.get('.knowledge-list__more button').attributes('disabled')).toBeDefined()
  })
})

async function mounted(overrides: Record<string, unknown>) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/knowledge', name: 'knowledge-base', component: { template: '<div />' } },
      // 权限不足状态的动作是跳转到权限说明页，因此路由表里要有这个具名路由。
      { path: '/access-denied', name: 'access-denied', component: { template: '<div />' } },
    ],
  })
  await router.push('/knowledge?team=team-1')
  await router.isReady()
  return mount(KnowledgeEntryList, {
    props: {
      phase: 'ready', items: [], nextAfter: null, loadingMore: false,
      errorMessage: null, errorStatus: null, online: true,
      statusFilter: 'ALL', categoryFilter: 'ALL', canManage: false, selectedEntryId: null,
      ...overrides,
    },
    global: { plugins: [router] },
  })
}

function entry(overrides: Partial<KnowledgeEntrySummary> = {}): KnowledgeEntrySummary {
  return {
    id: 'entry-1', entryKey: 'deploy-runbook', category: 'RUNBOOK', status: 'PUBLISHED', indexStatus: 'INDEXED',
    effectiveRevision: 2, latestRevision: 2, draft: null, version: 3,
    createdAt: '2026-10-01T01:00:00Z', updatedAt: '2026-10-02T01:00:00Z',
    createdBy: 'principal-1', updatedBy: 'principal-1', origin: null,
    ...overrides,
  }
}
