import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import KnowledgeIndexJobList from './KnowledgeIndexJobList.vue'
import type { KnowledgeIndexJob } from '../../domains/knowledge/types'

describe('KnowledgeIndexJobList', () => {
  it('renders job rows with source, status, target and chunk facts', async () => {
    const wrapper = await mounted({ items: [job(), repositoryJob()] })

    expect(wrapper.get('section[aria-label="索引作业列表"]')).toBeTruthy()
    expect(wrapper.text()).toContain('知识条目')
    expect(wrapper.text()).toContain('仓库')
    expect(wrapper.text()).toContain('排队中')
    expect(wrapper.text()).toContain('嵌入中')
    expect(wrapper.text()).toContain('条目 00000000')
    expect(wrapper.text()).toContain('绑定 00000000 @ aaaaaaaa')
    expect(wrapper.text()).toContain('12/40')
    expect(wrapper.findAll('li.job-item')).toHaveLength(2)
  })

  it.each([
    ['loading', { phase: 'loading', items: [] }, '正在读取索引作业'],
    ['empty', { phase: 'empty', items: [] }, '暂无索引作业'],
    ['forbidden', { phase: 'error', items: [], errorMessage: 'denied', errorStatus: 403 }, '无权读取索引作业'],
    ['offline', { phase: 'ready', items: [], online: false }, '离线时没有可用索引作业'],
    ['error', { phase: 'error', items: [], errorMessage: '服务暂时无法完成请求', errorStatus: 503 }, '服务暂时无法完成请求'],
  ] as const)('renders the %s state', async (_name, props, expected) => {
    const wrapper = await mounted({ ...props })
    expect(wrapper.text()).toContain(expected)
  })

  it('offers filter clearing on a filtered empty listing', async () => {
    const wrapper = await mounted({ phase: 'empty', items: [], statusFilter: 'QUEUED' })

    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('changeSource')?.[0]).toEqual(['ALL'])
    expect(wrapper.emitted('changeStatus')?.[0]).toEqual(['ALL'])
  })

  it('emits source and status filter changes', async () => {
    const wrapper = await mounted({ items: [job()], sourceFilter: 'REPOSITORY', statusFilter: 'QUEUED' })
    const selects = wrapper.findAll('select')

    await selects[0]!.setValue('KNOWLEDGE_ENTRY')
    await selects[1]!.setValue('FAILED')

    expect(wrapper.emitted('changeSource')?.[0]).toEqual(['KNOWLEDGE_ENTRY'])
    expect(wrapper.emitted('changeStatus')?.[0]).toEqual(['FAILED'])
  })

  it('continues keyset pages and disables continuation while offline', async () => {
    const paging = await mounted({ items: [job()], nextAfter: '00000000-0000-0000-0000-000000007401' })
    await paging.get('.job-list__more button').trigger('click')
    expect(paging.emitted('loadMore')).toHaveLength(1)

    const offline = await mounted({ items: [job()], nextAfter: '00000000-0000-0000-0000-000000007401', online: false })
    expect(offline.text()).toContain('正在展示最近读取的索引作业')
    expect(offline.get('.job-list__more button').attributes('disabled')).toBeDefined()
  })

  it('marks only the row the store command names while staying clickable elsewhere', async () => {
    const wrapper = await mounted({ items: [job(), repositoryJob({ status: 'QUEUED' })], canManage: true, cancellingJobId: '00000000-0000-0000-0000-000000007402' })
    const cancelButtons = wrapper.findAll('button').filter(button => button.text() === '取消作业')

    expect(cancelButtons).toHaveLength(2)
    // 命令命中的行进入 busy，另一行仍可发起取消。
    expect(cancelButtons[1]!.attributes('aria-busy')).toBe('true')
    await cancelButtons[0]!.trigger('click')
    expect(wrapper.emitted('cancel')?.[0]).toEqual(['00000000-0000-0000-0000-000000007401'])
  })
})

async function mounted(overrides: Record<string, unknown>) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/knowledge/index', name: 'knowledge-index', component: { template: '<div />' } },
      // 权限不足状态的动作是跳转到权限说明页，因此路由表里要有这个具名路由。
      { path: '/access-denied', name: 'access-denied', component: { template: '<div />' } },
    ],
  })
  await router.push('/knowledge/index?team=team-1')
  await router.isReady()
  return mount(KnowledgeIndexJobList, {
    props: {
      phase: 'ready', items: [], nextAfter: null, loadingMore: false,
      errorMessage: null, errorStatus: null, online: true,
      sourceFilter: 'ALL', statusFilter: 'ALL', canManage: false, cancellingJobId: null,
      ...overrides,
    },
    global: { plugins: [router] },
  })
}

function job(overrides: Partial<KnowledgeIndexJob> = {}): KnowledgeIndexJob {
  return {
    id: '00000000-0000-0000-0000-000000007401', source: 'KNOWLEDGE_ENTRY', status: 'QUEUED',
    entryId: '00000000-0000-0000-0000-000000007101', projectId: null, indexKey: null,
    attempt: 0, chunksDone: 0, chunksTotal: 1, failureCode: null, generationBuildSequence: 1,
    claimedBy: null, leaseExpiresAt: null, createdBy: 'principal-1',
    createdAt: '2026-10-04T09:00:00Z', updatedAt: '2026-10-04T09:00:00Z',
    ...overrides,
  }
}

function repositoryJob(overrides: Partial<KnowledgeIndexJob> = {}): KnowledgeIndexJob {
  return job({
    id: '00000000-0000-0000-0000-000000007402', source: 'REPOSITORY', status: 'EMBEDDING',
    entryId: null, projectId: 'project-1',
    indexKey: { bindingId: '00000000-0000-0000-0000-000000007501', commit: 'a'.repeat(40), chunkPolicyHash: 'c'.repeat(64), modelKey: 'text-embedding-v4', modelRevision: 3 },
    attempt: 2, chunksDone: 12, chunksTotal: 40, claimedBy: 'knowledge-index-worker-1',
    leaseExpiresAt: '2026-10-04T09:30:00Z',
    ...overrides,
  })
}
