import { expect, test, type Page, type Route } from '@playwright/test'
import { authenticatedSession } from '../auth-session'

/**
 * R41 保存视图与固定项目：本机存储的筛选定义。保存→刷新→作为默认自动套用；
 * 显式 URL 永远压过旧偏好；固定项目的名字每次经当前授权解析，撤权后是「项目已不可见」；
 * 本机存储不可用时页面照常工作，只是保存被拒绝并说明原因。
 */

const ids = {
  organization: '00000000-0000-4000-8000-000000000001',
  team: '00000000-0000-4000-8000-000000000201',
  project: '00000000-0000-4000-8000-000000000401',
  projectOps: '00000000-0000-4000-8000-000000000402',
  workspace: '00000000-0000-4000-8000-000000000501',
  principal: '00000000-0000-4000-8000-000000000101',
  workItem: '00000000-0000-4000-8000-000000000601',
}

interface World {
  projects: Array<{ id: string, key: string, name: string }>
  deskQueries: Array<string | null>
}

async function mockSavedViewsApi(page: Page, world: World): Promise<void> {
  await page.route(/\/api\/v1\//, async (route: Route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const method = request.method()
    if (method === 'GET' && path === '/api/v1/auth/session') return json(route, authenticatedSession(ids.organization, ids.principal, ids.team))
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
      world.deskQueries.push(url.search)
      return json(route, {
        organizationId: ids.organization, teamId: ids.team, projectId: null, generatedAt: '2026-09-26T08:00:00Z',
        sections: [{
          key: 'WORK_ITEM', title: '我的工作项', priority: 1, total: 1, truncated: false,
          items: [{
            objectType: 'WORK_ITEM', objectId: ids.workItem, projectId: ids.project,
            title: '修复登录提示', status: 'IN_PROGRESS', updatedAt: '2026-09-26T07:00:00Z',
            responsibilityRole: 'EXECUTOR', needsAction: true, urgency: 'NORMAL', progress: null,
            availableActions: [], route: `/work?team=${ids.team}&workItem=${ids.workItem}`,
          }],
        }],
      })
    }
    if (method === 'GET' && path.endsWith('/work-items')) return json(route, { items: [], nextCursor: null })
    return notFound(route)
  })
}

test('saves a desk definition, reloads it as the default and never overrides an explicit URL', async ({ page }) => {
  const world: World = { projects: [{ id: ids.project, key: 'CRW', name: 'CrewScope' }], deskQueries: [] }
  await mockSavedViewsApi(page, world)
  await page.goto(`/today?team=${ids.team}`)
  await expect(page.getByRole('heading', { name: '我的工作台', exact: true })).toBeVisible()

  // Commit the filters the definition will store, then save and pin them.
  await page.getByLabel('责任角色').selectOption('EXECUTOR')
  await page.getByRole('checkbox', { name: '仅看需要我行动' }).check()
  await expect(page).toHaveURL(/deskRole=EXECUTOR/)
  await page.getByRole('button', { name: '视图', exact: true }).click()
  await page.getByLabel('视图名称').fill('我的执行面')
  await page.getByRole('button', { name: '保存当前筛选' }).click()
  await expect(page.getByRole('menu', { name: '已保存视图' })).toContainText('我的执行面')
  await page.getByRole('button', { name: '置顶 我的执行面' }).click()
  await expect(page.getByRole('button', { name: '取消置顶 我的执行面' })).toBeVisible()

  // Re-entering without any desk filter of their own applies the stored default (replace, not push).
  await page.goto(`/today?team=${ids.team}`)
  await expect(page).toHaveURL(/deskRole=EXECUTOR&deskAction=true/)
  await expect(page.getByRole('button', { name: '视图', exact: true })).toBeVisible()
  expect(world.deskQueries.at(-1)).toContain('responsibilityRole=EXECUTOR')
  expect(world.deskQueries.at(-1)).toContain('onlyNeedsAction=true')

  // An explicit link still wins: a shared deskGroup coordinate is never rewritten by the preference.
  await page.goto(`/today?team=${ids.team}&deskGroup=role`)
  await expect(page.getByRole('button', { name: '视图', exact: true })).toBeVisible()
  const params = new URL(page.url()).searchParams
  expect(params.get('deskRole')).toBeNull()
  expect(params.get('deskGroup')).toBe('role')

  // The palette offers the same definitions as jump targets.
  await page.keyboard.press('Meta+k')
  await page.getByLabel('搜索动作或对象').fill('执行面')
  await page.getByRole('dialog', { name: '命令面板' }).getByRole('button', { name: '打开视图 我的执行面' }).click()
  await expect(page).toHaveURL(/deskRole=EXECUTOR&deskAction=true/)
})

test('pins a project and shows it as invisible once the authorization drops it', async ({ page }) => {
  const world: World = {
    projects: [{ id: ids.project, key: 'CRW', name: 'CrewScope' }, { id: ids.projectOps, key: 'OPS', name: 'Operations' }],
    deskQueries: [],
  }
  await mockSavedViewsApi(page, world)
  await page.goto(`/today?team=${ids.team}`)
  await expect(page.getByRole('heading', { name: '我的工作台', exact: true })).toBeVisible()

  // The wrapping <label>项目<select> carries the option texts in its label string, so exact
  // label matching can never equal「项目」— the combobox role names it cleanly instead.
  await page.getByRole('combobox', { name: '项目' }).selectOption(ids.project)
  await page.getByRole('button', { name: '固定当前项目 CrewScope' }).click()
  await expect(page.getByRole('list', { name: '固定的项目' })).toContainText('CRW · CrewScope')

  // The chip navigates by identifier, and its label comes from the authorization, not the pin.
  await page.getByRole('button', { name: 'CRW · CrewScope', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`deskProject=${ids.project}`))

  world.projects = [world.projects[1]!]
  await page.goto(`/today?team=${ids.team}`)
  await expect(page.getByRole('list', { name: '固定的项目' })).toContainText('项目已不可见')
  await expect(page.getByRole('list', { name: '固定的项目' })).not.toContainText('CrewScope')

  await page.getByRole('button', { name: '取消固定 已不可见的项目' }).click()
  await expect(page.getByRole('list', { name: '固定的项目' })).not.toContainText('项目已不可见')
})

test('keeps the page working with local storage denied and says why a save failed', async ({ page }) => {
  await page.addInitScript(() => {
    // Full getter denial cannot even boot under the dev server: vite's dev-time vue-router reads
    // its timeline-layer preference with a bare localStorage access during module evaluation.
    // The product's own access points are guarded (cursorStore safeLocalStorage, f05Storage store()),
    // so a storage that reads but refuses every write exercises the same user-facing contract:
    // the page works and saves are rejected with「本机存储不可用或已满」.
    const refuse = (): never => { throw new DOMException('full', 'QuotaExceededError') }
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      value: { length: 0, clear: refuse, getItem: () => null, key: () => null, removeItem: () => {}, setItem: refuse },
    })
  })
  const world: World = { projects: [{ id: ids.project, key: 'CRW', name: 'CrewScope' }], deskQueries: [] }
  await mockSavedViewsApi(page, world)
  await page.goto(`/today?team=${ids.team}`)

  // Page defaults render; nothing about a stored preference can crash the desk.
  await expect(page.getByRole('heading', { name: '我的工作台', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '视图', exact: true })).toBeVisible()

  await page.getByRole('button', { name: '视图', exact: true }).click()
  await expect(page.getByRole('menu', { name: '已保存视图' })).toContainText('本机存储，不跨设备同步')
  await page.getByLabel('视图名称').fill('存不进本机的视图')
  await page.getByRole('button', { name: '保存当前筛选' }).click()
  await expect(page.getByRole('menu', { name: '已保存视图' }).getByRole('alert')).toContainText('本机存储不可用或已满，本次未能保存视图。')
})

function json(route: Route, value: unknown) {
  return route.fulfill({ status: 200, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(value) })
}

function notFound(route: Route) {
  return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 'not_found', message: 'not_found', correlationId: 'corr', retryable: false, currentVersion: null, details: {} }) })
}
