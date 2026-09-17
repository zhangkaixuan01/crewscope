import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page, type Route } from '@playwright/test'
import { authenticatedSession } from './auth-session'
import { permissions } from '../src/app/auth'
import { allowedWorkItemTransitions, type WorkItemStatus } from '../src/domains/workitem/types'

/**
 * The list capabilities of the WorkItem collection, exercised against a server that behaves like the
 * real one: it lists one WorkProject by its updated-time keyset, publishes each row's availability
 * verdict alongside it, and answers every batch row through the same command endpoint a single card
 * uses.
 *
 * The point of every case here is what the member is told. A batch that reports "已对 3 项执行" while
 * one row was refused is the failure this file exists to prevent, so each test reads the per-row
 * summary rather than just the toast.
 */
const ids = {
  organization: '00000000-0000-0000-0000-000000000001', team: '00000000-0000-0000-0000-000000000201',
  project: '00000000-0000-0000-0000-000000000401', workspace: '00000000-0000-0000-0000-000000000501',
  principal: '00000000-0000-0000-0000-000000000101',
  first: '00000000-0000-0000-0000-000000000601', second: '00000000-0000-0000-0000-000000000602',
  third: '00000000-0000-0000-0000-000000000603', member: '00000000-0000-0000-0000-000000000301',
}

interface Row {
  id: string
  key: string
  title: string
  status: WorkItemStatus
  priority: 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT'
  updatedAt: string
  dueAt: string | null
  /** The server's verdict for one edge, when it should differ from the plain state machine. */
  refusedTarget?: { status: WorkItemStatus; reasonMessage: string }
}

interface World {
  session: unknown
  rows: Row[]
  commands: Array<{ workItemId: string; targetStatus: string; idempotencyKey: string | null }>
  /** Rows the server answers with a non-conflict 4xx, standing in for a permission refusal. */
  denyTransitionsFor: string[]
}

function world(rows: Row[]): World {
  return { session: authenticatedSession(ids.organization, ids.principal, ids.team), rows, commands: [], denyTransitionsFor: [] }
}

function row(overrides: Partial<Row> = {}): Row {
  return {
    id: ids.first, key: 'CRW-18', title: '建立团队看板', status: 'IN_PROGRESS', priority: 'HIGH',
    updatedAt: '2026-09-13T09:00:00Z', dueAt: null, ...overrides,
  }
}

test.describe('work item list capabilities', () => {
  test('orders the loaded rows and keeps the sort in the URL', async ({ page }) => {
    const state = world([
      row({ id: ids.first, key: 'CRW-18', title: '低优先级事项', priority: 'LOW', updatedAt: '2026-09-13T09:00:00Z' }),
      row({ id: ids.second, key: 'CRW-19', title: '紧急事项', priority: 'URGENT', updatedAt: '2026-09-12T09:00:00Z' }),
    ])
    await mockApi(page, state)

    // 服务端按更新时间列出，所以首屏是「低优先级事项」在前。
    await page.goto(workUrl())
    await expect(listRows(page)).toHaveText([/低优先级事项/, /紧急事项/])
    await expect(page.locator('.sort-control')).toContainText('排序作用于当前 2 项')

    await page.getByRole('button', { name: /^按优先级排序/ }).click()

    await expect(page).toHaveURL(/sort=priority/)
    await expect(page).toHaveURL(/direction=desc/)
    await expect(listRows(page)).toHaveText([/紧急事项/, /低优先级事项/])

    // 再次点击同一键切换方向，而不是无声地什么都不做。
    await page.getByRole('button', { name: /按优先级排序（当前降序）/ }).click()
    await expect(page).toHaveURL(/direction=asc/)
    await expect(listRows(page)).toHaveText([/低优先级事项/, /紧急事项/])

    await expect(page.locator('.sort-control').getByRole('button', { name: '负责人' })).toBeDisabled()
    expect(await new AxeBuilder({ page }).analyze()).toEqual(expect.objectContaining({ violations: [] }))
  })

  test('runs one batch command per selected row through the single command endpoint', async ({ page }) => {
    const state = world([
      row({ id: ids.first, key: 'CRW-18', title: '建立团队看板' }),
      row({ id: ids.second, key: 'CRW-19', title: '修复 Cursor 重复项', status: 'IN_PROGRESS' }),
    ])
    await mockApi(page, state)

    await page.goto(workUrl())
    await page.getByRole('checkbox', { name: '选择 CRW-18 建立团队看板' }).check()
    await page.getByRole('checkbox', { name: '选择 CRW-19 修复 Cursor 重复项' }).check()
    await expect(page.locator('[aria-label="批量操作"]')).toContainText('已选 2 项（跨页保留）')

    await page.getByRole('group', { name: '批量改状态' }).getByRole('button', { name: /提交评审/ }).click()

    // 两行各发一条命令，各带独立幂等键：批量重试不会把已经执行过的行再执行一次。
    await expect.poll(() => state.commands.length).toBe(2)
    expect(new Set(state.commands.map(command => command.idempotencyKey)).size).toBe(2)
    expect(state.commands.every(command => command.targetStatus === 'IN_REVIEW')).toBe(true)

    const results = page.locator('[aria-label="批量操作"]').locator('.bulk-results__item')
    await expect(results).toHaveCount(2)
    await expect(results).toHaveText([/已执行/, /已执行/])
  })

  test('names every row a batch did not execute and why', async ({ page }) => {
    const state = world([
      row({ id: ids.first, key: 'CRW-18', title: '建立团队看板' }),
      // 行自己已发布的裁决说不可以：这一行根本不会被提交。
      row({ id: ids.second, key: 'CRW-19', title: '修复 Cursor 重复项', refusedTarget: { status: 'IN_REVIEW', reasonMessage: '已有评审在进行中' } }),
      // 裁决没说话，但命令侧拒绝：这是被拒绝，不是未确认。
      row({ id: ids.third, key: 'CRW-20', title: '整理发布说明' }),
    ])
    state.denyTransitionsFor = [ids.third]
    await mockApi(page, state)

    await page.goto(workUrl())
    for (const title of ['建立团队看板', '修复 Cursor 重复项', '整理发布说明']) {
      await page.getByRole('checkbox', { name: new RegExp(`选择 CRW-\\d+ ${title}`) }).check()
    }

    // 按钮上的比例就是预告：3 选中里有 1 行已被自己的裁决排除。
    await expect(page.getByRole('group', { name: '批量改状态' }).getByRole('button', { name: /提交评审/ })).toContainText('（2/3）')

    await page.getByRole('group', { name: '批量改状态' }).getByRole('button', { name: /提交评审/ }).click()

    const results = page.locator('[aria-label="批量操作"]').locator('.bulk-results__item')
    await expect(results).toHaveCount(3)
    await expect(results.nth(0)).toContainText('已执行')
    await expect(results.nth(1)).toContainText('已跳过')
    await expect(results.nth(1)).toContainText('已有评审在进行中')
    await expect(results.nth(2)).toContainText('被拒绝')
    // 被排除的行从未被提交 —— 预告与执行共用同一条裁决。
    await expect.poll(() => state.commands.map(command => command.workItemId)).toEqual([ids.first, ids.third])

    // 部分失败绝不汇总成「已完成」：选择保留，逐项结果留在屏幕上。
    await expect(page.locator('[aria-label="批量操作"]')).toContainText('已选 3 项（跨页保留）')
    await expect(page.locator('.bulk-results header p')).toContainText('已执行 1')
  })

  test('selects the visible page and keeps rows selected across pages', async ({ page }) => {
    const state = world([
      row({ id: ids.first, key: 'CRW-18', title: '建立团队看板', priority: 'HIGH', updatedAt: '2026-09-13T09:00:00Z' }),
      row({ id: ids.second, key: 'CRW-19', title: '修复 Cursor 重复项', priority: 'URGENT', updatedAt: '2026-09-12T09:00:00Z' }),
      row({ id: ids.third, key: 'CRW-20', title: '整理发布说明', priority: 'HIGH', updatedAt: '2026-09-11T09:00:00Z' }),
    ])
    await mockApi(page, state, { pageSize: 2 })

    await page.goto(workUrl())
    // 批量条随选择出现，所以在选中任何一项之前没有「全选当页」——页面上不常驻一条空工具条。
    await expect(page.locator('[aria-label="批量操作"]')).toHaveCount(0)
    await page.getByRole('checkbox', { name: '选择 CRW-18 建立团队看板' }).check()

    await page.getByRole('button', { name: '全选当页 2 项' }).click()
    await expect(page.locator('[aria-label="批量操作"]')).toContainText('已选 2 项（跨页保留）')

    // 换页不丢选择：第二页加载进来之后前两项仍在选中集合里。
    await page.getByRole('button', { name: '加载更多工作项' }).click()
    await page.getByRole('checkbox', { name: '选择 CRW-20 整理发布说明' }).check()
    await expect(page.locator('[aria-label="批量操作"]')).toContainText('已选 3 项（跨页保留）')

    // 被筛选掉的选择不会被悄悄丢掉，也不会被算进批量：两句话分开说。
    await page.locator('.filters select').nth(2).selectOption('HIGH')
    await expect(page.locator('[aria-label="批量操作"]')).toContainText('其中 1 项不在当前结果中，批量操作只作用于当前结果的 2 项')
  })

  test('says a batch cannot be submitted while offline instead of offering dead buttons', async ({ page, context }) => {
    const state = world([row({ id: ids.first, key: 'CRW-18', title: '建立团队看板' })])
    await mockApi(page, state)
    await page.goto(workUrl())
    await page.getByRole('checkbox', { name: '选择 CRW-18 建立团队看板' }).check()

    await context.setOffline(true)
    await expect(page.locator('[aria-label="批量操作"]')).toContainText('当前离线，批量操作不可提交')
    await expect(page.getByRole('group', { name: '批量改状态' })).toHaveCount(0)
    expect(state.commands).toEqual([])

    await context.setOffline(false)
    await expect(page.getByRole('group', { name: '批量改状态' })).toBeVisible()
  })

  test('offers no selection at all to a member who may not act on the list', async ({ page }) => {
    const session = authenticatedSession(ids.organization, ids.principal, ids.team)
    // 能读这个项目、但不能参与：页面照常渲染，只是没有任何动作可做。
    const readOnly = [permissions.scopeRead, permissions.workProjectsRead, permissions.workRead]
    session.permissions = readOnly
    session.teams[0]!.permissions = readOnly
    const state = { ...world([row()]), session }
    await mockApi(page, state)

    await page.goto(workUrl())

    // 没有权限就没有复选框：一个通向「点了没反应」的控件比没有控件更糟。
    await expect(page.getByRole('checkbox', { name: /选择/ })).toHaveCount(0)
    await expect(page.locator('[aria-label="批量操作"]')).toHaveCount(0)
  })
})

function workUrl(): string {
  return `/work?team=${ids.team}&project=${ids.project}`
}

function listRows(page: Page) {
  return page.getByLabel('工作项列表').locator('.work-item-card__open h3')
}

const actionLabels: Record<string, string> = {
  BACKLOG: '退回待办', READY: '准备工作项', IN_PROGRESS: '开始执行', IN_REVIEW: '提交评审',
  BLOCKED: '标记阻塞', DONE: '完成工作项', CANCELLED: '取消工作项',
}

/**
 * The verdict the server publishes with each row, read from the generated state machine so the
 * fixture cannot advertise an edge the domain has no command for.
 */
function availabilityOf(item: Row) {
  return (allowedWorkItemTransitions[item.status] ?? []).map(target => {
    const refused = item.refusedTarget?.status === target
    return {
      actionId: `${item.status}-to-${target}`.toLowerCase().replaceAll('_', '-'),
      targetStatus: target,
      label: actionLabels[target] ?? target,
      strength: ['CANCELLED', 'ARCHIVED'].includes(target) ? 'DANGER' : 'PRIMARY',
      reversible: (allowedWorkItemTransitions[target] ?? []).includes(item.status),
      enabled: !refused,
      reason: refused ? 'REVIEWER_REQUIRED' : null,
      reasonMessage: refused ? item.refusedTarget!.reasonMessage : null,
      remedyLabel: null, remedyRoute: null,
    }
  })
}

function summaryOf(item: Row) {
  return {
    id: item.id, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace,
    projectId: ids.project, key: item.key, type: 'TASK', title: item.title,
    description: `${item.title}的协作说明`, status: item.status, priority: item.priority,
    labels: ['team-work'], dueAt: item.dueAt, source: 'CREWSCOPE', sourceReference: null, version: 0,
    createdAt: '2026-09-01T01:00:00Z', createdByPrincipalId: ids.principal,
    updatedAt: item.updatedAt, updatedByPrincipalId: ids.principal, availableActions: availabilityOf(item),
  }
}

async function mockApi(page: Page, state: World, options: { pageSize?: number } = {}): Promise<void> {
  const pageSize = options.pageSize ?? 50
  await page.route(/\/api\/v1\//, async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()

    if (method === 'GET' && path === '/api/v1/auth/session') return json(route, state.session)
    if (method === 'GET' && path.endsWith('/teams')) return json(route, [{
      id: ids.team, organizationId: ids.organization, name: 'Platform Engineering', status: 'ACTIVE',
      initializationStatus: 'READY', ownerMemberId: 'member-1', defaultWorkspaceId: ids.workspace, version: 1,
    }])
    if (method === 'GET' && path.endsWith(`/${ids.team}/work-projects`)) return json(route, {
      items: [{
        id: ids.project, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace,
        key: 'CRW', name: 'CrewScope', status: 'ACTIVE', version: 1, createdAt: '2026-08-27T07:00:00Z',
        createdByPrincipalId: ids.principal, updatedAt: '2026-08-27T07:00:00Z', updatedByPrincipalId: ids.principal,
      }],
      nextCursor: null,
    })
    if (method === 'GET' && path.endsWith(`/${ids.team}/members`)) return json(route, [{
      id: ids.member, userPrincipalId: ids.principal, displayName: '张凯旋', status: 'ACTIVE',
      joinMethod: 'BOOTSTRAP', joinedAt: '2026-08-01T00:00:00Z', version: 1,
    }])

    const rowOf = (pattern: RegExp) => {
      const match = pattern.exec(path)
      return match ? state.rows.find(candidate => candidate.id === match[1]!) ?? null : null
    }

    if (method === 'GET' && path.endsWith('/work-items')) {
      // 服务端按 updated-time keyset 排序并游标分页：排序参数不存在，所以页面只能排已加载的行。
      const ordered = [...state.rows].sort((left, right) => right.updatedAt.localeCompare(left.updatedAt))
      const after = new URL(request.url()).searchParams.get('after')
      const start = after ? ordered.findIndex(item => item.id === after) + 1 : 0
      const items = ordered.slice(start, start + pageSize)
      const hasMore = start + pageSize < ordered.length
      return json(route, { items: items.map(summaryOf), nextCursor: hasMore ? items.at(-1)!.id : null })
    }

    const target = rowOf(/\/work-items\/([^/]+)$/)
    if (method === 'GET' && target) return json(route, { workItem: summaryOf(target), comments: [], resourceLinks: [] })

    const availability = rowOf(/\/work-items\/([^/]+)\/transitions\/availability$/)
    if (availability) return json(route, { transitions: availabilityOf(availability) })

    if (/\/work-items\/[^/]+\/responsibilities$/.test(path)) return json(route, [])
    if (/\/work-items\/[^/]+\/timeline$/.test(path)) return json(route, { items: [], nextCursor: null })

    const transition = rowOf(/\/work-items\/([^/]+)\/transitions$/)
    if (transition && method === 'POST') {
      const body = request.postDataJSON() as { targetStatus: WorkItemStatus }
      state.commands.push({
        workItemId: transition.id,
        targetStatus: body.targetStatus,
        idempotencyKey: request.headers()['idempotency-key'] ?? null,
      })
      if (state.denyTransitionsFor.includes(transition.id)) {
        return route.fulfill({
          status: 403, contentType: 'application/json',
          body: JSON.stringify({
            code: 'policy_denied', message: '只有负责人可以推进该工作项',
            correlationId: 'corr-denied', retryable: false, currentVersion: null, details: {},
          }),
        })
      }
      transition.status = body.targetStatus
      return json(route, receipt(state.commands.length))
    }

    if (method === 'POST' && /\/work-items\/[^/]+\/responsibilities\//.test(path)) {
      return json(route, receipt(state.commands.length))
    }

    return notFound(route)
  })
}

function receipt(sequence: number) {
  return { commandId: `cmd-${sequence}`, domainEventId: `evt-${sequence}`, committedVersion: sequence, correlationId: `corr-${sequence}` }
}

function json(route: Route, value: unknown) {
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(value) })
}

function notFound(route: Route) {
  return route.fulfill({
    status: 404, contentType: 'application/json',
    body: JSON.stringify({ code: 'not_found', message: 'Not found', correlationId: 'corr-404', retryable: false, currentVersion: null, details: {} }),
  })
}
