import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page, type Route } from '@playwright/test'
import { authenticatedSession } from './auth-session'
import { permissions } from '../src/app/auth'
import { allowedWorkItemTransitions, type WorkItemStatus } from '../src/domains/workitem/types'

const ids = {
  organization: '00000000-0000-0000-0000-000000000001', team: '00000000-0000-0000-0000-000000000201',
  project: '00000000-0000-0000-0000-000000000401', workspace: '00000000-0000-0000-0000-000000000501', principal: '00000000-0000-0000-0000-000000000101',
  workItem: '00000000-0000-0000-0000-000000000601',
}

const otherProjects = [
  '00000000-0000-0000-0000-000000000402',
  '00000000-0000-0000-0000-000000000403',
  '00000000-0000-0000-0000-000000000404',
  '00000000-0000-0000-0000-000000000405',
]

interface DeskRow {
  objectId: string
  projectId: string
  title: string
  status: WorkItemStatus
  responsibilityRole: 'OWNER' | 'EXECUTOR' | 'REVIEWER' | null
  needsAction: boolean
  urgency: string
  progress: number | null
  updatedAt: string
  actions?: boolean
}

interface World {
  session: unknown
  projects: Array<{ id: string; key: string; name: string }>
  rows: DeskRow[]
  commands: Array<{ workItemId: string; targetStatus: string }>
  deskReads: number
}

function defaultWorld(): World {
  return {
    session: authenticatedSession(ids.organization, ids.principal, ids.team),
    projects: [{ id: ids.project, key: 'CRW', name: 'CrewScope' }],
    rows: [{
      objectId: ids.workItem, projectId: ids.project, title: '修复登录提示', status: 'IN_PROGRESS',
      responsibilityRole: 'OWNER', needsAction: true, urgency: 'HIGH', progress: 40, updatedAt: '2026-09-13T09:00:00Z',
    }],
    commands: [],
    deskReads: 0,
  }
}

test.describe('personal workbench', () => {
  test('shows personal workdesk and keeps filters in the URL', async ({ page }) => {
    const world = defaultWorld()
    await mockWorkDeskApi(page, world)

    await page.goto(`/today?team=${ids.team}`)
    await expect(page.getByRole('heading', { name: '我的工作台', exact: true })).toBeVisible()
    await expect(page.getByText('修复登录提示')).toBeVisible()
    await expect(page.getByRole('heading', { name: '需要我行动' })).toBeVisible()

    await page.locator('select').first().selectOption(ids.project)
    await page.getByRole('checkbox', { name: /仅看需要我行动/ }).check()
    await expect(page).toHaveURL(new RegExp(`deskProject=${ids.project}.*deskAction=true|deskAction=true.*deskProject=${ids.project}`))
    expect(await new AxeBuilder({ page }).analyze()).toEqual(expect.objectContaining({ violations: [] }))
  })

  test('executes a card action through the work item Store and re-reads the projection', async ({ page }) => {
    const world = defaultWorld()
    await mockWorkDeskApi(page, world)

    await page.goto(`/today?team=${ids.team}`)
    const readsBefore = world.deskReads
    await page.locator('.desk-card').getByRole('button', { name: '进行中' }).click()
    await page.getByRole('menuitem', { name: '提交评审' }).click()

    await expect.poll(() => world.commands).toEqual([{ workItemId: ids.workItem, targetStatus: 'IN_REVIEW' }])
    // The section a row belongs to after the command is the server's answer, so the page re-reads
    // rather than moving the card itself.
    await expect.poll(() => world.deskReads).toBeGreaterThan(readsBefore)
    await expect(page.getByRole('region', { name: '评审中' }).getByText('修复登录提示')).toBeVisible()
  })

  test('moves a card between status columns with the keyboard alone', async ({ page }) => {
    const world = defaultWorld()
    await mockWorkDeskApi(page, world)

    await page.goto(`/today?team=${ids.team}`)
    await page.getByRole('button', { name: '打开 修复登录提示' }).focus()
    await page.keyboard.press('Space')
    await expect(page.locator('[aria-live]').filter({ hasText: '已拾起 修复登录提示' })).toHaveCount(1)

    await page.keyboard.press('ArrowRight')
    await page.keyboard.press('Enter')

    await expect.poll(() => world.commands).toEqual([{ workItemId: ids.workItem, targetStatus: 'IN_REVIEW' }])
  })

  test('gives a member with nothing assigned a next step instead of two empty boxes', async ({ page }) => {
    const world = { ...defaultWorld(), rows: [] }
    await mockWorkDeskApi(page, world)

    await page.goto(`/today?team=${ids.team}`)
    const actionRequired = page.locator('.action-required')
    await expect(actionRequired.getByText('没有需要你行动的事项')).toBeVisible()
    await expect(actionRequired.getByRole('link', { name: /进入 Work/ })).toBeVisible()

    const myWork = page.locator('.my-work')
    await expect(myWork.getByText('没有分配到你的工作项')).toBeVisible()
    await expect(myWork.getByRole('link', { name: /进入 Work/ })).toBeVisible()
    expect(await new AxeBuilder({ page }).analyze()).toEqual(expect.objectContaining({ violations: [] }))
  })

  test('aggregates every WorkProject and narrows the board with the project filter', async ({ page }) => {
    const world: World = {
      ...defaultWorld(),
      projects: [
        { id: ids.project, key: 'CRW', name: 'CrewScope' },
        { id: otherProjects[0]!, key: 'WEB', name: 'Web Platform' },
        { id: otherProjects[1]!, key: 'MOB', name: 'Mobile App' },
        { id: otherProjects[2]!, key: 'OPS', name: 'Operations' },
        { id: otherProjects[3]!, key: 'DOC', name: 'Docs' },
      ],
      rows: [
        { objectId: ids.workItem, projectId: ids.project, title: '修复登录提示', status: 'IN_PROGRESS', responsibilityRole: 'OWNER', needsAction: false, urgency: 'NORMAL', progress: 40, updatedAt: '2026-09-13T09:00:00Z' },
        { objectId: '00000000-0000-0000-0000-000000000602', projectId: otherProjects[0]!, title: '接入 Provider 回调', status: 'READY', responsibilityRole: 'OWNER', needsAction: false, urgency: 'NORMAL', progress: null, updatedAt: '2026-09-13T08:00:00Z' },
        { objectId: '00000000-0000-0000-0000-000000000603', projectId: otherProjects[1]!, title: '移动端断线重连', status: 'BLOCKED', responsibilityRole: 'OWNER', needsAction: true, urgency: 'HIGH', progress: 20, updatedAt: '2026-09-13T07:00:00Z' },
        { objectId: '00000000-0000-0000-0000-000000000604', projectId: otherProjects[2]!, title: '值班手册更新', status: 'IN_REVIEW', responsibilityRole: 'OWNER', needsAction: false, urgency: 'LOW', progress: 80, updatedAt: '2026-09-12T09:00:00Z' },
        { objectId: '00000000-0000-0000-0000-000000000605', projectId: otherProjects[3]!, title: '发布说明校对', status: 'BACKLOG', responsibilityRole: 'OWNER', needsAction: false, urgency: 'LOW', progress: null, updatedAt: '2026-09-12T06:00:00Z' },
      ],
    }
    await mockWorkDeskApi(page, world)

    await page.goto(`/today?team=${ids.team}`)
    const board = page.getByLabel('我的工作看板')
    for (const title of ['修复登录提示', '接入 Provider 回调', '移动端断线重连', '值班手册更新', '发布说明校对']) {
      await expect(board.getByText(title)).toBeVisible()
    }

    await page.locator('select').first().selectOption(otherProjects[1]!)
    await expect(board.getByText('移动端断线重连')).toBeVisible()
    await expect(board.getByText('修复登录提示')).toHaveCount(0)
  })

  test('keeps a member without participation permission read-only and says why', async ({ page }) => {
    const session = authenticatedSession(ids.organization, ids.principal, ids.team)
    const limited = [permissions.scopeRead, permissions.workProjectsRead, permissions.workRead]
    session.permissions = limited
    session.teams[0]!.permissions = limited
    const world: World = {
      ...defaultWorld(),
      session,
      // A member who may not participate gets no executable action from the server, rather than an
      // enabled one the command would refuse — the projection and the command share one verdict.
      rows: [{ ...defaultWorld().rows[0]!, actions: false }],
    }
    await mockWorkDeskApi(page, world)

    await page.goto(`/today?team=${ids.team}`)
    await expect(page.locator('.desk-board--locked')).toBeVisible()
    await expect(page.locator('.desk-hint')).toContainText('没有推进工作项的权限')
    await expect(page.locator('.desk-card').first()).toHaveAttribute('draggable', 'false')
    await expect(page.locator('.desk-card').first().getByRole('button', { name: '进行中' })).toHaveCount(0)
  })
})

async function mockWorkDeskApi(page: Page, world: World): Promise<void> {
  await page.route(/\/api\/v1\//, async route => {
    const request = route.request(); const url = new URL(request.url()); const path = url.pathname
    const method = request.method()
    if (method === 'GET' && path === '/api/v1/auth/session') return json(route, world.session)
    if (method === 'GET' && path.endsWith('/teams')) return json(route, [{
      id: ids.team, organizationId: ids.organization, name: 'Platform Engineering', status: 'ACTIVE',
      initializationStatus: 'READY', ownerMemberId: 'member-1', defaultWorkspaceId: ids.workspace, version: 1,
    }])
    if (method === 'GET' && path.endsWith(`/${ids.team}/work-projects`)) {
      return json(route, {
        items: world.projects.map(project => ({
          id: project.id, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace,
          key: project.key, name: project.name, status: 'ACTIVE', version: 1, createdAt: '2026-08-27T07:00:00Z',
          createdByPrincipalId: ids.principal, updatedAt: '2026-08-27T07:00:00Z', updatedByPrincipalId: ids.principal,
        })),
        nextCursor: null,
      })
    }
    if (method === 'GET' && path.endsWith(`/${ids.team}/members`)) return json(route, [])
    if (method === 'GET' && path.endsWith('/work-desk')) {
      world.deskReads += 1
      return json(route, workDesk(world, url.searchParams))
    }
    if (method === 'GET' && path.endsWith('/work-items')) return json(route, { items: world.rows.map(row => workItemSummary(row)), nextCursor: null })
    const row = (path: string, pattern: RegExp) => {
      const match = pattern.exec(path)
      return match ? world.rows.find(candidate => candidate.objectId === match[1]!) ?? null : null
    }
    // The drawer reads the same three collections for a row it opens; the board only needs the
    // availability and the detail, but a 404 on the other two would take the whole action down.
    const availability = row(path, /\/work-items\/([^/]+)\/transitions\/availability$/)
    // The per-object availability endpoint wraps its list; the WorkDesk projection inlines it.
    if (availability) return json(route, { transitions: availabilityOf(availability) })
    if (/\/work-items\/[^/]+\/responsibilities$/.test(path)) return json(route, [])
    if (/\/work-items\/[^/]+\/timeline$/.test(path)) return json(route, { items: [], nextCursor: null })
    const transitions = row(path, /\/work-items\/([^/]+)\/transitions$/)
    if (transitions && method === 'POST') {
      const body = request.postDataJSON() as { targetStatus: WorkItemStatus }
      world.commands.push({ workItemId: transitions.objectId, targetStatus: body.targetStatus })
      transitions.status = body.targetStatus
      return json(route, {
        commandId: `cmd-${world.commands.length}`, domainEventId: `evt-${world.commands.length}`,
        committedVersion: world.commands.length, correlationId: `corr-${world.commands.length}`,
      })
    }
    const detail = row(path, /\/work-items\/([^/]+)$/)
    if (detail && method === 'GET') return json(route, { workItem: workItemSummary(detail), comments: [], resourceLinks: [] })
    return notFound(route)
  })
}

function workDesk(world: World, search: URLSearchParams) {
  const projectId = search.get('projectId')
  const role = search.get('responsibilityRole')
  const onlyNeedsAction = search.get('onlyNeedsAction') === 'true'
  const rows = world.rows.filter(row => (
    (!projectId || row.projectId === projectId)
    && (!role || row.responsibilityRole === role)
    && (!onlyNeedsAction || row.needsAction)
  ))
  const item = (row: DeskRow) => ({
    objectType: 'WORK_ITEM', objectId: row.objectId, projectId: row.projectId, title: row.title, status: row.status,
    updatedAt: row.updatedAt, responsibilityRole: row.responsibilityRole, needsAction: row.needsAction,
    urgency: row.urgency, progress: row.progress, availableActions: availabilityOf(row),
    route: `/work?team=${ids.team}&project=${row.projectId}&workItem=${row.objectId}`,
  })
  const workItems = rows
  const blocked = rows.filter(row => row.status === 'BLOCKED')
  return {
    organizationId: ids.organization, teamId: ids.team, projectId, generatedAt: '2026-09-13T10:00:00Z',
    sections: [
      { key: 'HUMAN_GATE', title: '等我决策', priority: 1, total: 0, truncated: false, items: [] },
      { key: 'REVIEW', title: '待我 Review', priority: 2, total: 0, truncated: false, items: [] },
      { key: 'BLOCKED', title: '被我阻塞', priority: 3, total: blocked.length, truncated: false, items: blocked.map(item) },
      { key: 'WORK_ITEM', title: '我的工作项', priority: 4, total: workItems.length, truncated: false, items: workItems.map(item) },
      { key: 'TASK_EXECUTION', title: '进行中的执行', priority: 5, total: 0, truncated: false, items: [] },
      { key: 'INBOX', title: '未读 Inbox', priority: 6, total: 0, truncated: false, items: [] },
    ],
  }
}

/**
 * The executable actions of one row, read from the generated state machine rather than retyped here.
 *
 * The home board renders exactly what the server published, so a fixture that invented an edge would
 * prove the page can draw a button the domain has no command for. `actions: false` stands for the
 * member the server granted nothing — the projection's way of saying "not yours to move".
 */
const actionLabels: Record<string, string> = {
  BACKLOG: '退回待办', READY: '准备工作项', IN_PROGRESS: '开始执行', IN_REVIEW: '提交评审',
  BLOCKED: '标记阻塞', DONE: '完成工作项', CANCELLED: '取消工作项',
}

function availabilityOf(row: DeskRow) {
  if (row.actions === false) return []
  return (allowedWorkItemTransitions[row.status] ?? []).map(target => ({
    actionId: `${row.status}-to-${target}`.toLowerCase().replaceAll('_', '-'),
    targetStatus: target,
    label: actionLabels[target] ?? target,
    strength: ['CANCELLED', 'ARCHIVED'].includes(target) ? 'DANGER' : 'PRIMARY',
    reversible: (allowedWorkItemTransitions[target] ?? []).includes(row.status),
    enabled: true, reason: null, reasonMessage: null, remedyLabel: null, remedyRoute: null,
  }))
}

function workItemSummary(row: DeskRow) {
  return {
    id: row.objectId, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace,
    projectId: row.projectId, key: `CRW-${row.objectId.slice(-2)}`, type: 'TASK', title: row.title,
    description: `${row.title}的协作说明`, status: row.status, priority: 'HIGH', labels: ['team-work'],
    dueAt: null, source: 'CREWSCOPE', sourceReference: null, version: 0, createdAt: '2026-09-01T01:00:00Z',
    createdByPrincipalId: ids.principal, updatedAt: row.updatedAt, updatedByPrincipalId: ids.principal,
    availableActions: availabilityOf(row),
  }
}

function json(route: Route, value: unknown) { return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(value) }) }
function notFound(route: Route) {
  return route.fulfill({
    status: 404, contentType: 'application/json',
    body: JSON.stringify({ code: 'not_found', message: 'Not found', correlationId: 'corr-404', retryable: false, currentVersion: null, details: {} }),
  })
}


