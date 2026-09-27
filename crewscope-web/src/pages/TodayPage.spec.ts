import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { AUTH_PRINCIPAL } from '../app/auth'
import { SCOPE_STORE, createScopeStore } from '../domains/scope/store'
import { WORK_ITEM_STORE, createWorkItemStore } from '../domains/workitem/store'
import { WORKDESK_STORE, createWorkDeskStore } from '../domains/workdesk/store'
import type { WorkDeskGateway } from '../domains/workdesk/gateway'
import type { WorkDeskFilter, WorkDeskItem, WorkDeskScope, WorkDeskSection, WorkDeskSummary } from '../domains/workdesk/types'
import { FixtureWorkItemGateway, workItemIds } from '../test/workItemFixtures'
import { bootstrapPrincipal } from '../test/authFixtures'
import { FixtureScopeGateway, fixtureIds } from '../test/scopeFixtures'
import TodayPage from './TodayPage.vue'

const workDeskSections = ['HUMAN_GATE', 'REVIEW', 'BLOCKED', 'WORK_ITEM', 'TASK_EXECUTION', 'INBOX'] as const

function deskItem(overrides: Partial<WorkDeskItem> = {}): WorkDeskItem {
  return {
    objectType: 'WORK_ITEM',
    objectId: workItemIds.first,
    projectId: fixtureIds.projectCrewScope,
    title: '建立团队看板',
    status: 'IN_PROGRESS',
    updatedAt: '2026-09-17T09:00:00Z',
    responsibilityRole: 'OWNER',
    needsAction: false,
    urgency: 'NORMAL',
    progress: 40,
    availableActions: [{
      actionId: 'submit-review', targetStatus: 'IN_REVIEW', label: '提交评审', strength: 'PRIMARY',
      reversible: true, enabled: true, reason: null, reasonMessage: null, remedyLabel: null, remedyRoute: null,
    }],
    route: `/work?team=${fixtureIds.teamPlatform}&project=${fixtureIds.projectCrewScope}&workItem=${workItemIds.first}`,
    workItemId: null, workItemTitle: null, rowSummary: null, waitingOn: null,
    ...overrides,
  }
}

class FixtureWorkDeskGateway implements WorkDeskGateway {
  readonly queries: WorkDeskFilter[] = []
  items: Record<string, WorkDeskItem[]> = { WORK_ITEM: [deskItem()] }
  /** Rows the server holds past each section's first page; a section with rows here reports a cursor. */
  more: Record<string, WorkDeskItem[]> = {}
  failNext = false
  getSection = vi.fn(async (_scope: WorkDeskScope, sectionKey: string, _filter?: WorkDeskFilter, _after?: string): Promise<WorkDeskSection> => {
    const more = this.more[sectionKey] ?? []
    return {
      key: sectionKey, title: sectionKey,
      priority: workDeskSections.indexOf(sectionKey as typeof workDeskSections[number]) + 1,
      total: (this.items[sectionKey] ?? []).length + more.length,
      truncated: false, items: structuredClone(more), nextCursor: null,
    }
  })

  async get(scope: WorkDeskScope, filter: WorkDeskFilter = {}): Promise<WorkDeskSummary> {
    this.queries.push(structuredClone(filter))
    if (this.failNext) {
      this.failNext = false
      throw new Error('work desk unavailable')
    }
    return {
      organizationId: scope.organizationId,
      teamId: scope.teamId,
      projectId: null,
      generatedAt: '2026-09-17T10:00:00Z',
      sections: workDeskSections.map((key, index) => {
        const items = this.items[key] ?? []
        const more = this.more[key] ?? []
        return { key, title: key, priority: index + 1, total: items.length + more.length, truncated: false, items: structuredClone(items), nextCursor: more.length ? `${key}-cursor` : null }
      }),
    }
  }
}

interface Harness {
  wrapper: VueWrapper
  router: Router
  desk: FixtureWorkDeskGateway
  workItems: FixtureWorkItemGateway
}

async function harness(options: { projects?: 'empty' | 'ready'; items?: Record<string, WorkDeskItem[]>; more?: Record<string, WorkDeskItem[]> } = {}): Promise<Harness> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/today', name: 'today', component: TodayPage },
      { path: '/work', name: 'work', component: { template: '<div />' } },
      { path: '/conversation', name: 'conversation', component: { template: '<div />' } },
      { path: '/inbox', name: 'inbox', component: { template: '<div />' } },
      { path: '/team/members', name: 'team-members', component: { template: '<div />' } },
      { path: '/setup', name: 'setup', component: { template: '<div />' } },
    ],
  })
  await router.push(`/today?team=${fixtureIds.teamPlatform}`)
  await router.isReady()
  const scopeGateway = new FixtureScopeGateway()
  if (options.projects === 'empty') scopeGateway.projects[fixtureIds.teamPlatform] = []
  const scopeStore = createScopeStore(scopeGateway, bootstrapPrincipal)
  await scopeStore.synchronize(fixtureIds.teamPlatform)
  const desk = new FixtureWorkDeskGateway()
  if (options.items) desk.items = options.items
  if (options.more) desk.more = options.more
  const workItems = new FixtureWorkItemGateway()
  const wrapper = mount(TodayPage, {
    attachTo: document.body,
    global: {
      plugins: [router],
      provide: {
        [SCOPE_STORE as symbol]: scopeStore,
        [AUTH_PRINCIPAL as symbol]: bootstrapPrincipal,
        [WORKDESK_STORE as symbol]: createWorkDeskStore(desk),
        [WORK_ITEM_STORE as symbol]: createWorkItemStore(workItems),
      },
      stubs: {
        AppShell: { template: '<main><slot name="actions"/><slot/></main>' },
      },
    },
  })
  await flushPromises()
  return { wrapper, router, desk, workItems }
}

describe('TodayPage', () => {
  afterEach(() => {
    vi.useRealTimers()
    document.body.innerHTML = ''
  })

  it('creates the first WorkProject from the empty state and updates the URL scope', async () => {
    vi.useFakeTimers()
    const { wrapper, router } = await harness({ projects: 'empty', items: {} })

    expect(wrapper.text()).toContain('这个 Team 还没有 WorkProject')
    await wrapper.findAll('button').find(button => button.text().includes('创建 WorkProject'))!.trigger('click')
    const inputs = document.body.querySelectorAll<HTMLInputElement>('.project-create-dialog input')
    inputs[0]!.value = 'CrewScope Platform'
    inputs[0]!.dispatchEvent(new Event('input', { bubbles: true }))
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()
    document.body.querySelector<HTMLFormElement>('.project-create-dialog')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushPromises()

    expect(router.currentRoute.value.query.project).toBeTruthy()
    expect(wrapper.text()).toContain('CrewScope Platform')
    wrapper.unmount()
  })

  it('runs a card action through the Store and re-reads the projection afterwards', async () => {
    const { wrapper, desk, workItems } = await harness()
    const initialReads = desk.queries.length

    await wrapper.get('.desk-card .transition-menu button').trigger('click')
    await wrapper.findAll('[role="menuitem"]').find(item => item.text().includes('提交评审'))!.trigger('click')
    await flushPromises()

    expect(workItems.transitions).toEqual([{ workItemId: workItemIds.first, targetStatus: 'IN_REVIEW', expectedVersion: 0 }])
    // The section a row belongs to after the command is the server's answer, so the page re-reads
    // rather than moving the card itself.
    expect(desk.queries.length).toBe(initialReads + 1)
  })

  it('offers a next step when nothing needs action, instead of an empty box', async () => {
    const { wrapper } = await harness()

    const panel = wrapper.get('.action-required')
    expect(panel.text()).toContain('没有需要你行动的事项')
    expect(panel.get('a').attributes('href')).toContain('/work')
  })

  it('groups by responsibility role from the URL and stops offering drag there', async () => {
    const { wrapper } = await harness()
    await wrapper.findAll('.grouping-switcher button').find(button => button.text().includes('按责任角色'))!.trigger('click')
    await flushPromises()

    expect(wrapper.vm.$route.query.deskGroup).toBe('role')
    const columns = wrapper.findAll('.desk-column')
    expect(columns.map(column => column.get('header span').text())).toEqual(['负责人', '执行人', 'Reviewer', '未关联责任'])
    // A drop onto a role column would mean "reassign this", which is a different command against a
    // different aggregate; the board must not look like it offers it.
    expect(wrapper.get('.desk-board').classes()).toContain('desk-board--locked')
    expect(wrapper.get('.desk-hint').text()).toContain('改派责任')
    expect(wrapper.get('.desk-card').attributes('draggable')).toBe('false')
  })

  it('keeps a status this build does not know in its own column instead of dropping the row', async () => {
    const { wrapper } = await harness({ items: { WORK_ITEM: [deskItem({ status: 'SOMETHING_NEW' })] } })

    const columns = wrapper.findAll('.desk-column')
    expect(columns.at(-1)!.get('header span').text()).toBe('其他状态')
    expect(columns.at(-1)!.text()).toContain('建立团队看板')
  })

  it('keeps the last known rows when a refresh fails and says the refresh failed', async () => {
    const { wrapper, desk } = await harness()
    expect(wrapper.text()).toContain('建立团队看板')

    // The role filter is one of the query dimensions the projection is read with, so changing it is
    // what makes the page go back to the server.
    desk.failNext = true
    await wrapper.findAll('select')[1]!.setValue('OWNER')
    await flushPromises()

    expect(wrapper.get('.desk-stale-notice').text()).toContain('刷新失败')
    expect(wrapper.text()).toContain('建立团队看板')
  })

  it('continues a desk section from its cursor instead of hiding the rest behind the first page', async () => {
    const { wrapper, desk } = await harness({ more: { WORK_ITEM: [deskItem({ objectId: workItemIds.second, title: '第二页工作项' })] } })

    // 有续页的分区给出「加载更多」；按钮以分区为单位，不把没有续页的分区也拉一遍。
    const loadMore = wrapper.findAll('button').find(button => button.text().includes('加载更多工作项'))
    expect(loadMore).toBeTruthy()
    expect(wrapper.findAll('button').filter(button => button.text().includes('加载更多需要处理的事项'))).toHaveLength(0)

    await loadMore!.trigger('click')
    await flushPromises()

    expect(desk.getSection).toHaveBeenCalledTimes(1)
    expect(desk.getSection.mock.calls[0]![1]).toBe('WORK_ITEM')
    // 续页从首页末尾的 cursor 开始，接在已加载行后面而不是替换它们。
    expect(desk.getSection.mock.calls[0]![3]).toBe('WORK_ITEM-cursor')
    expect(wrapper.text()).toContain('建立团队看板')
    expect(wrapper.text()).toContain('第二页工作项')
    expect(wrapper.findAll('button').filter(button => button.text().includes('加载更多工作项'))).toHaveLength(0)
  })
})
