import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ActivityStream from './ActivityStream.vue'
import type { ActivityItem } from '../../domains/teamops/types'

describe('ActivityStream', () => {
  it('renders Actor, Subject, Outcome and keyboard-reachable evidence links', async () => {
    const wrapper = await mounted({ items: [activity()] })

    expect(wrapper.text()).toContain('成员 · principa')
    expect(wrapper.text()).toContain('Task · task-1')
    expect(wrapper.text()).toContain('COMPLETED')
    const evidence = wrapper.get('a')
    expect(evidence.attributes('href')).toContain('/work?')
    expect(evidence.attributes('href')).toContain('workItem=work-item-1')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('select')?.[0]?.[0]).toMatchObject({ eventId: 'event-1' })
  })

  it.each([
    ['loading', { phase: 'loading', items: [] }, '正在同步 Activity'],
    ['empty', { phase: 'empty', items: [] }, '还没有团队活动'],
    ['forbidden', { phase: 'error', items: [], error: error('forbidden') }, '无权查看团队活动'],
    ['offline', { phase: 'error', items: [], error: error('offline'), online: false }, '离线时没有可用 Activity'],
    ['cursor-expired', { phase: 'error', items: [], error: error('cursor-expired') }, 'Activity Cursor 已过期'],
    ['error', { phase: 'error', items: [], error: error('unknown') }, 'unknown'],
  ] as const)('renders the %s state', async (_name, props, expected) => {
    const wrapper = await mounted(props)
    expect(wrapper.text()).toContain(expected)
  })

  it('keeps cached events visible while reconnecting — without a panel pushing the list down', async () => {
    const wrapper = await mounted({ items: [activity()], realtimePhase: 'reconnecting' })
    // M13b-F10（L05）：连接态由页面 strip 收敛表达，组件不再在列表前插恢复提示。
    // M13b-F12（二轮 A18）：事件类型经 activityEventTypeLabels 词表中文化。
    expect(wrapper.text()).not.toContain('正在恢复实时活动')
    expect(wrapper.text()).toContain('任务已完成')
  })

  it('renders every outcome family, system actors and approved reference destinations', async () => {
    const items = [
      activity({ eventId: 'event-success', actor: { type: 'SERVICE', principalId: null }, payload: { schemaName: 'x', schemaVersion: 1, values: { status: 'ACTIVE' } }, references: [{ type: 'CONVERSATION', id: 'conversation-1' }] }),
      activity({ eventId: 'event-danger', payload: { schemaName: 'x', schemaVersion: 1, values: { result: 'FAILED' } }, references: [{ type: 'TASK', id: 'task-2' }] }),
      activity({ eventId: 'event-warning', payload: { schemaName: 'x', schemaVersion: 1, values: { decision: 'PENDING' } }, references: [{ type: 'REVIEW', id: 'review-1' }] }),
      activity({ eventId: 'event-info', payload: { schemaName: 'x', schemaVersion: 1, values: { outcome: 'RUNNING' } }, references: [{ type: 'ACTION', id: 'action-1' }] }),
      activity({ eventId: 'event-neutral', eventType: 'CUSTOM_FACT', payload: { schemaName: 'x', schemaVersion: 1, values: {} }, references: [{ type: 'ARTIFACT', id: 'artifact-1' }] }),
    ]
    const wrapper = await mounted({ items, nextCursor: 'older' })
    expect(wrapper.text()).toContain('服务账号')
    expect(wrapper.text()).toContain('会话 · conversa')
    expect(wrapper.text()).toContain('Task · task-2')
    expect(wrapper.text()).toContain('评审 · review-1')
    expect(wrapper.text()).toContain('外部动作 · action-1')
    expect(wrapper.text()).toContain('产物 · artifact')
    expect(wrapper.findAll('a')[0]!.attributes('href')).toContain('/conversation?')
    expect(wrapper.findAll('a')[1]!.attributes('href')).toContain('/activity?')
    await wrapper.findAll('button').at(-1)!.trigger('click')
    expect(wrapper.emitted('loadMore')).toHaveLength(1)
  })

  it('keeps cached facts readable across offline and hard-error recovery states', async () => {
    const offline = await mounted({ items: [activity()], online: false, realtimePhase: 'offline', nextCursor: 'older' })
    // 离线时缓存事实仍在列表里（R25 的「最近同步于」由 ActivityPage 的 strip note 表达）。
    expect(offline.text()).not.toContain('正在展示最近同步的 Activity')
    expect(offline.text()).toContain('任务已完成')
    const more = offline.findAll('button').at(-1)!
    expect(more.attributes('disabled')).toBeDefined()

    const failed = await mounted({ phase: 'error', items: [activity()], error: error('unknown') })
    // 硬错误时缓存事实保持可读；错误细节由 ActivityPage 的 strip note 表达（L05 收敛）。
    expect(failed.text()).toContain('任务已完成')
  })

  it('M13b-F10 L05: drops the in-stream page header at page level but keeps a one-line section head when embedded', async () => {
    // 页面级页头合一：AppShell context header + summary-strip 已是两层，组件内层的
    // eyebrow/h2/description 与实时徽标不再渲染——徽标由 ActivityPage 的 strip 唯一持有。
    const page = await mounted({ items: [activity()] })
    expect(page.find('.activity-stream__header').exists()).toBe(false)
    expect(page.get('section.activity-stream').attributes('aria-label')).toBe('团队活动')

    // 抽屉 compact 场景没有外部标题：保留单行区块头并自报实时相位。
    const embedded = await mounted({ items: [activity()], compact: true, heading: 'WorkItem Activity', realtimePhase: 'idle' })
    expect(embedded.get('.activity-stream__header h2').text()).toBe('WorkItem Activity')
    expect(embedded.get('.activity-stream__header').text()).toContain('历史模式')
  })
})

async function mounted(overrides: Record<string, unknown>) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/activity', name: 'activity', component: { template: '<div />' } },
      { path: '/work', name: 'work', component: { template: '<div />' } },
      { path: '/conversation', name: 'conversation', component: { template: '<div />' } },
      // 权限不足状态的动作是跳转到权限说明页，因此路由表里要有这个具名路由。
      { path: '/access-denied', name: 'access-denied', component: { template: '<div />' } },
    ],
  })
  await router.push('/activity?team=team-1&project=project-1')
  await router.isReady()
  return mount(ActivityStream, {
    props: {
      phase: 'ready', items: [], nextCursor: null, loadingMore: false, error: null,
      realtimePhase: 'live', online: true, ...overrides,
    },
    global: { plugins: [router] },
  })
}

function activity(overrides: Partial<ActivityItem> = {}): ActivityItem {
  return {
    eventId: 'event-1', domainEventId: 'domain-1', teamSequence: 4, eventType: 'TASK_COMPLETED',
    category: 'EXECUTION', visibility: 'TEAM', subject: { type: 'TASK', id: 'task-1' },
    actor: { type: 'USER', principalId: 'principal-12345678' },
    references: [{ type: 'WORK_ITEM', id: 'work-item-1' }], occurredAt: '2026-08-27T08:00:00Z',
    payload: { schemaName: 'task-summary', schemaVersion: 1, values: { outcome: 'COMPLETED' } },
    ...overrides,
  }
}

function error(kind: 'forbidden' | 'offline' | 'cursor-expired' | 'unknown') {
  return { kind, message: kind, status: null, retryable: false, currentVersion: null }
}
