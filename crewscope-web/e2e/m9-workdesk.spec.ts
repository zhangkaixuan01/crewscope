import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page, type Route } from '@playwright/test'
import { authenticatedSession } from './auth-session'

const ids = {
  organization: '00000000-0000-0000-0000-000000000001', team: '00000000-0000-0000-0000-000000000201',
  project: '00000000-0000-0000-0000-000000000401', workspace: '00000000-0000-0000-0000-000000000501', principal: '00000000-0000-0000-0000-000000000101',
}

test.beforeEach(async ({ page }) => mockWorkDeskApi(page))

test('shows personal workdesk and keeps filters in the URL', async ({ page }) => {
  await page.goto(`/today?team=${ids.team}`)
  await expect(page.getByRole('heading', { name: '我的工作台' })).toBeVisible()
  await expect(page.getByText('修复登录提示')).toBeVisible()
  await expect(page.getByRole('heading', { name: '需要我行动' })).toBeVisible()

  await page.locator('select').first().selectOption(ids.project)
  await page.getByRole('checkbox', { name: /仅看需要我行动/ }).check()
  await expect(page).toHaveURL(new RegExp(`deskProject=${ids.project}.*deskAction=true|deskAction=true.*deskProject=${ids.project}`))
  expect(await new AxeBuilder({ page }).analyze()).toEqual(expect.objectContaining({ violations: [] }))
})

async function mockWorkDeskApi(page: Page): Promise<void> {
  await page.route(/\/api\/v1\//, async route => {
    const request = route.request(); const url = new URL(request.url()); const path = url.pathname
    if (request.method() === 'GET' && path === '/api/v1/auth/session') return json(route, authenticatedSession(ids.organization, ids.principal, ids.team))
    if (request.method() === 'GET' && path.endsWith('/teams')) return json(route, [{ id: ids.team, organizationId: ids.organization, name: 'Platform Engineering', status: 'ACTIVE', initializationStatus: 'READY', ownerMemberId: 'member-1', defaultWorkspaceId: ids.workspace, version: 1 }])
    if (request.method() === 'GET' && path.endsWith(`/${ids.team}/work-projects`)) return json(route, { items: [{ id: ids.project, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace, key: 'CRW', name: 'CrewScope', status: 'ACTIVE', version: 1, createdAt: '2026-08-27T07:00:00Z', createdByPrincipalId: ids.principal, updatedAt: '2026-08-27T07:00:00Z', updatedByPrincipalId: ids.principal }], nextCursor: null })
    if (request.method() === 'GET' && path.endsWith(`/${ids.team}/members`)) return json(route, [])
    if (request.method() === 'GET' && path.endsWith('/work-desk')) return json(route, workDesk())
    return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 'not_found', message: 'Not found', correlationId: 'corr-404', retryable: false, currentVersion: null, details: {} }) })
  })
}

function workDesk() {
  const item = { objectType: 'WORK_ITEM', objectId: '00000000-0000-0000-0000-000000000601', projectId: ids.project, title: '修复登录提示', status: 'OPEN', updatedAt: '2026-09-13T09:00:00Z', responsibilityRole: 'OWNER', needsAction: true, urgency: 'HIGH', progress: 40, availableActions: ['OPEN'], route: `/work?team=${ids.team}&project=${ids.project}&workItem=00000000-0000-0000-0000-000000000601` }
  return { organizationId: ids.organization, teamId: ids.team, projectId: null, generatedAt: '2026-09-13T10:00:00Z', sections: [
    { key: 'HUMAN_GATE', title: '等我决策', priority: 1, total: 0, truncated: false, items: [] },
    { key: 'REVIEW', title: '待我 Review', priority: 2, total: 0, truncated: false, items: [] },
    { key: 'BLOCKED', title: '被我阻塞', priority: 3, total: 0, truncated: false, items: [] },
    { key: 'WORK_ITEM', title: '我的工作项', priority: 4, total: 1, truncated: false, items: [item] },
    { key: 'TASK_EXECUTION', title: '进行中的执行', priority: 5, total: 0, truncated: false, items: [] },
    { key: 'INBOX', title: '未读 Inbox', priority: 6, total: 0, truncated: false, items: [] },
  ] }
}

function json(route: Route, value: unknown) { return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(value) }) }
