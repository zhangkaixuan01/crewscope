import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page, type Route } from '@playwright/test'
import { authenticatedSession } from './auth-session'

const ids = {
  organization: '00000000-0000-4000-8000-000000000001',
  team: '00000000-0000-4000-8000-000000000201',
  project: '00000000-0000-4000-8000-000000000401',
  workspace: '00000000-0000-4000-8000-000000000501',
  principal: '00000000-0000-4000-8000-000000000101',
  workItem: '00000000-0000-4000-8000-000000000601',
  ownership: '00000000-0000-4000-8000-000000000901',
  ownershipOlder: '00000000-0000-4000-8000-000000000902',
  execution: '00000000-0000-4000-8000-000000000903',
  source: '00000000-0000-4000-8000-000000000951',
}

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-08-27T08:30:00Z'))
  await mockInboxApi(page)
})

test('renders the five views, de-duplicates Cursor pages and passes Axe with stable visuals', async ({ page }, testInfo) => {
  await page.goto(`/inbox?team=${ids.team}&project=${ids.project}&inboxItem=${ids.ownership}`)

  await expect(page.getByRole('heading', { name: '我的 Inbox', exact: true })).toBeVisible()
  await expect(page.getByRole('navigation', { name: 'Inbox 五类视图' }).getByRole('button')).toHaveCount(5)
  await expect(page.getByText('4 项待处理事实')).toBeVisible()
  await expect(page.getByText('RESPONSIBILITY_ASSIGNMENT · revision 3')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Inbox 详情' })).toBeVisible()

  const axe = await new AxeBuilder({ page }).analyze()
  expect(axe.violations).toEqual([])
  await expect(page).toHaveScreenshot(`m6-inbox-${testInfo.project.name}.png`, { fullPage: true })

  await page.getByRole('button', { name: '加载更多' }).click()
  await expect(page.getByRole('button', { name: /查看 我的负责 详情/ })).toHaveCount(2)

  await page.getByRole('button', { name: /^我的执行/ }).click()
  await expect(page).toHaveURL(/inboxType=EXECUTION/)
  await expect(page.getByText('TASK_EXECUTION · revision 2')).toBeVisible()
})

test('refreshes a strong-ETag conflict and preserves the member disposition after reload', async ({ page }) => {
  await page.goto(`/inbox?team=${ids.team}&project=${ids.project}&inboxItem=${ids.ownership}`)
  await expect(page.getByRole('heading', { name: 'Inbox 详情' })).toBeVisible()

  await page.getByRole('button', { name: '标记已处理' }).click()
  await expect(page.getByText('处置版本已更新')).toBeVisible()
  await expect(page.getByText('v1', { exact: true })).toBeVisible()
  await expect(page.getByText('已读', { exact: true }).last()).toBeVisible()

  await page.getByRole('button', { name: '标记已处理' }).click()
  await expect(page.getByText('v2', { exact: true })).toBeVisible()
  await expect(page.getByText('已处理', { exact: true }).last()).toBeVisible()

  await page.reload()
  await expect(page.getByText('v2', { exact: true })).toBeVisible()
  await expect(page.getByText('已处理', { exact: true }).last()).toBeVisible()
})

test('keeps cached Inbox facts readable offline and opens only the server-authorized source route', async ({ page, context }) => {
  await page.goto(`/inbox?team=${ids.team}&project=${ids.project}&inboxItem=${ids.ownership}`)
  await expect(page.getByText('RESPONSIBILITY_ASSIGNMENT · revision 3')).toBeVisible()

  await context.setOffline(true)
  await expect(page.getByText('正在展示最近同步的 Inbox')).toBeVisible()
  await expect(page.getByRole('button', { name: '打开来源' })).toBeDisabled()
  await context.setOffline(false)

  const source = page.getByRole('button', { name: '打开来源' })
  await source.focus()
  await expect(source).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(new RegExp(`/work\\?.*workItem=${ids.workItem}`))
})

async function mockInboxApi(page: Page): Promise<void> {
  const items = [
    inbox(ids.ownership, 'OWNERSHIP', 'URGENT', 'RESPONSIBILITY_ASSIGNMENT', 3, '2026-08-27T09:00:00Z'),
    inbox(ids.ownershipOlder, 'OWNERSHIP', 'NORMAL', 'RESPONSIBILITY_ASSIGNMENT', 2, null),
    inbox(ids.execution, 'EXECUTION', 'HIGH', 'TASK_EXECUTION', 2, '2026-08-27T10:00:00Z'),
    inbox('00000000-0000-4000-8000-000000000904', 'EXCEPTION', 'URGENT', 'ACTION_DELIVERY', 1, null),
  ]
  let conflictInjected = false
  const commandKeys: string[] = []

  await page.route(/\/api\/v1\//, async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    if (request.method() === 'GET' && path === '/api/v1/auth/session') return fulfillJson(route, authenticatedSession(ids.organization, ids.principal, ids.team))
    if (request.method() === 'GET' && path.endsWith('/teams')) {
      return fulfillJson(route, [{
        id: ids.team, organizationId: ids.organization, name: 'Platform Engineering', status: 'ACTIVE',
        initializationStatus: 'READY', ownerMemberId: 'member-1', defaultWorkspaceId: ids.workspace, version: 1,
      }])
    }
    if (request.method() === 'GET' && path.endsWith(`/${ids.team}/work-projects`)) {
      return fulfillJson(route, { items: [{
        id: ids.project, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace,
        key: 'CRW', name: 'CrewScope', status: 'ACTIVE', version: 1,
        createdAt: '2026-08-27T07:00:00Z', createdByPrincipalId: ids.principal,
        updatedAt: '2026-08-27T07:00:00Z', updatedByPrincipalId: ids.principal,
      }], nextCursor: null })
    }
    if (request.method() === 'GET' && path.endsWith('/inbox/counts')) {
      const visible = items.filter(item => item.sourceStatus === 'OPEN' && item.dispositionStatus !== 'ARCHIVED')
      const byType = Object.fromEntries(['OWNERSHIP', 'EXECUTION', 'REVIEW', 'CONFIRMATION', 'EXCEPTION'].map(type => {
        const typed = visible.filter(item => item.itemType === type)
        return [type, { total: typed.length, unread: typed.filter(item => item.dispositionStatus === 'UNREAD').length }]
      }))
      return fulfillJson(route, { total: visible.length, unread: visible.filter(item => item.dispositionStatus === 'UNREAD').length, byType })
    }
    if (request.method() === 'GET' && path.endsWith('/inbox')) {
      expect(url.searchParams.has('memberId')).toBe(false)
      const types = url.searchParams.getAll('itemTypes')
      const sourceStatuses = url.searchParams.getAll('sourceStatuses')
      const dispositions = url.searchParams.getAll('dispositionStatuses')
      const matching = items.filter(item => (!types.length || types.includes(item.itemType))
        && (!sourceStatuses.length || sourceStatuses.includes(item.sourceStatus))
        && (!dispositions.length || dispositions.includes(item.dispositionStatus)))
      if (url.searchParams.get('after')) {
        // The overlapping first item proves the browser uses InboxItemId for page de-duplication.
        return fulfillJson(route, { items: [matching[0], matching[1]].filter(Boolean), nextCursor: null })
      }
      return fulfillJson(route, { items: matching.slice(0, 1), nextCursor: matching.length > 1 ? 'older-inbox-cursor' : null })
    }
    const target = path.match(/\/inbox\/([^/]+)\/target$/)
    if (request.method() === 'GET' && target) {
      return fulfillJson(route, { kind: 'WORK_ITEM', href: `/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}` })
    }
    const disposition = path.match(/\/inbox\/([^/]+)\/disposition$/)
    if (request.method() === 'PUT' && disposition) {
      const current = items.find(item => item.inboxItemId === disposition[1])!
      const key = request.headers()['idempotency-key']
      expect(key).toBeTruthy()
      commandKeys.push(key!)
      expect(request.headers()['if-match']).toBe(`"${current.dispositionVersion}"`)
      const input = request.postDataJSON() as { status: string }
      expect(Object.keys(input)).toEqual(['status'])
      if (input.status === 'ACTED' && !conflictInjected) {
        conflictInjected = true
        current.dispositionStatus = 'READ'
        current.dispositionVersion = 1
        current.etag = '"1"'
        return fulfillError(route, 409, 'optimistic_lock_conflict', 1)
      }
      expect(commandKeys.at(-1)).not.toBe(commandKeys.at(-2))
      current.dispositionStatus = input.status
      current.dispositionVersion += 1
      current.etag = `"${current.dispositionVersion}"`
      return route.fulfill({ status: 202, contentType: 'application/json', headers: { ETag: current.etag }, body: JSON.stringify(receipt(current.dispositionVersion)) })
    }
    const detail = path.match(/\/inbox\/([^/]+)$/)
    if (request.method() === 'GET' && detail) {
      const current = items.find(item => item.inboxItemId === detail[1])
      if (!current) return fulfillError(route, 404, 'inbox_item_not_found', null)
      return route.fulfill({ status: 200, contentType: 'application/json', headers: { ETag: current.etag, 'Cache-Control': 'no-store' }, body: JSON.stringify(current) })
    }
    return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify(errorEnvelope('not_found', null)) })
  })
}

function inbox(
  inboxItemId: string,
  itemType: string,
  priority: string,
  sourceType: string,
  sourceRevision: number,
  deadline: string | null,
) {
  return {
    inboxItemId, itemType, priority, deadline, openedAt: `2026-08-27T0${sourceRevision}:00:00Z`,
    sourceStatus: 'OPEN', closeReason: null, closedAt: null,
    dispositionStatus: 'UNREAD', dispositionVersion: 0, etag: '"0"',
    source: { type: sourceType, id: ids.source, revision: sourceRevision },
  }
}

function receipt(committedVersion: number) {
  return { commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(), committedVersion, correlationId: crypto.randomUUID() }
}

function fulfillJson(route: Route, value: unknown) {
  return route.fulfill({ status: 200, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(value) })
}

function fulfillError(route: Route, status: number, code: string, currentVersion: number | null) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(errorEnvelope(code, currentVersion)) })
}

function errorEnvelope(code: string, currentVersion: number | null) {
  return { code, message: code, correlationId: crypto.randomUUID(), retryable: code === 'optimistic_lock_conflict', currentVersion, details: {} }
}
