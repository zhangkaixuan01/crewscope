import { expect, test, type Page, type Route } from '@playwright/test'
import { authenticatedSession } from '../auth-session'

const ids = {
  organization: '00000000-0000-4000-8000-000000000001',
  team: '00000000-0000-4000-8000-000000000201',
  project: '00000000-0000-4000-8000-000000000401',
  workspace: '00000000-0000-4000-8000-000000000501',
  principal: '00000000-0000-4000-8000-000000000101',
  workItem: '00000000-0000-4000-8000-000000000601',
  unread: '00000000-0000-4000-8000-000000000911',
  read: '00000000-0000-4000-8000-000000000912',
  archived: '00000000-0000-4000-8000-000000000913',
  source: '00000000-0000-4000-8000-000000000951',
}

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-09-26T08:30:00Z'))
})

test('unmarks a read row into a persisted unread and the live counts follow', async ({ page }) => {
  const api = await mockInboxApi(page)
  await page.goto(`/inbox?team=${ids.team}&inboxItem=${ids.read}`)
  await expect(page.getByText('2 项待处理事实')).toBeVisible()
  await expect(page.getByText('1 项未读，处置状态由当前成员独立维护。')).toBeVisible()

  await page.getByRole('button', { name: '标为未读' }).click()
  await expect(page.getByText('2 项未读，处置状态由当前成员独立维护。')).toBeVisible()
  expect(api.putCommands).toHaveLength(1)
  // A persisted UNREAD is a positive version row (contract §5.1), never a synthetic zero.
  expect(api.putCommands[0]).toMatchObject({ itemId: ids.read, status: 'UNREAD', ifMatch: '"1"' })
  await expect(page.getByText('v2', { exact: true })).toBeVisible()
})

test('restores an archived row one step at a time and archives only after confirmation', async ({ page }) => {
  const api = await mockInboxApi(page)
  await page.goto(`/inbox?team=${ids.team}&inboxItem=${ids.archived}`)
  await expect(page.locator('.inbox-detail__hero').getByText('已归档', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '标记已读' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '标记已处理' })).toHaveCount(0)

  // Restore is the only single step out of the archive, and it lands on READ.
  await page.getByRole('button', { name: '恢复（回到已读）' }).click()
  await expect(page.getByText('3 项待处理事实')).toBeVisible()
  expect(api.putCommands.at(-1)).toMatchObject({ itemId: ids.archived, status: 'READ', ifMatch: '"3"' })
  await expect(page.getByText('v4', { exact: true })).toBeVisible()

  // Archiving asks first, and cancelling never sends the command.
  await page.getByRole('button', { name: '归档', exact: true }).click()
  await expect(page.getByRole('alertdialog')).toContainText('从「归档」筛选恢复')
  await page.getByRole('alertdialog').getByRole('button', { name: '取消' }).click()
  await expect(page.getByRole('alertdialog')).toHaveCount(0)
  expect(api.putCommands).toHaveLength(1)

  await page.getByRole('button', { name: '归档', exact: true }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: '归档', exact: true }).click()
  await expect(page.getByText('2 项待处理事实')).toBeVisible()
  await expect(page.getByText('v5', { exact: true })).toBeVisible()

  // From the archive itself, unmarking is the explicit two-step command: one CAS on the
  // archived version, landing two versions ahead on a persisted UNREAD row.
  await page.getByRole('button', { name: '恢复并标为未读' }).click()
  await expect(page.getByText('2 项未读，处置状态由当前成员独立维护。')).toBeVisible()
  expect(api.putCommands.at(-1)).toMatchObject({ itemId: ids.archived, status: 'UNREAD', ifMatch: '"5"' })
  await expect(page.getByText('v7', { exact: true })).toBeVisible()
})

test('keeps an archived row browsable through the archive filter entry', async ({ page }) => {
  await mockInboxApi(page)
  await page.goto(`/inbox?team=${ids.team}`)
  await page.getByLabel('我的处置').selectOption('ARCHIVED')
  await expect(page).toHaveURL(/disposition=ARCHIVED/)
  await expect(page.getByRole('button', { name: /查看 我的负责 详情/ })).toHaveCount(1)
})

test('reports each batch outcome and never blind-retries an unknown one', async ({ page }) => {
  const api = await mockInboxApi(page)
  api.batchPlan = [
    { itemId: ids.unread, behavior: 'conflict' },
    { itemId: ids.read, behavior: 'accept' },
    { itemId: ids.archived, behavior: 'unavailable' },
  ]
  await page.goto(`/inbox?team=${ids.team}`)
  await expect(page.getByRole('button', { name: /查看 我的负责 详情/ })).toHaveCount(3)

  for (const checkbox of await page.locator('input[type="checkbox"]').all()) await checkbox.setChecked(true)
  const bar = page.getByRole('toolbar', { name: '批量处置' })
  await expect(bar).toContainText('已选 3 项（跨页保留）')
  await bar.getByRole('button', { name: '批量标记已读' }).click()

  const report = page.getByRole('region', { name: '批量处置结果' })
  await expect(report).toContainText('批量标记已读结果')
  await expect(report).toContainText('3 项：成功 1 · 版本冲突 1 · 结果未知 1')
  await expect(report).toContainText('先刷新确认本次结果，不要直接重试')
  await expect(report).toContainText('回读列表后请基于当前状态重新确认')
  expect(api.putCommands.map(command => command.itemId)).toEqual([ids.unread, ids.read, ids.archived])

  await report.getByRole('button', { name: '回读最新列表' }).click()
  await expect(page.getByText('2 项待处理事实')).toBeVisible()
  expect(api.putCommands).toHaveLength(3)

  await page.getByRole('button', { name: '关闭批量结果' }).click()
  await expect(report).toHaveCount(0)
})

test('batch archiving asks once for the whole selection', async ({ page }) => {
  const api = await mockInboxApi(page)
  await page.goto(`/inbox?team=${ids.team}`)
  await expect(page.getByRole('button', { name: /查看 我的负责 详情/ })).toHaveCount(3)
  for (const checkbox of await page.locator('input[type="checkbox"]').all()) await checkbox.setChecked(true)

  await page.getByRole('toolbar', { name: '批量处置' }).getByRole('button', { name: '批量归档' }).click()
  await expect(page.getByRole('alertdialog')).toContainText('归档选中的 3 项')
  await page.getByRole('alertdialog').getByRole('button', { name: '归档', exact: true }).click()

  await expect(page.getByRole('region', { name: '批量处置结果' })).toContainText('成功 3')
  expect(api.putCommands.map(command => command.status)).toEqual(['ARCHIVED', 'ARCHIVED', 'ARCHIVED'])
  // The archived rows leave the open totals entirely.
  await expect(page.getByText('0 项待处理事实')).toBeVisible()
})

interface PutCommand { itemId: string, status: string, ifMatch: string, key: string }

interface MockApi {
  putCommands: PutCommand[]
  batchPlan: Array<{ itemId: string, behavior: 'accept' | 'conflict' | 'unavailable' }> | null
}

/**
 * The same mutable fixture the M6 inbox suite uses, extended with the F04 reversible semantics:
 * unmarking writes a persisted UNREAD row, an archive unmark lands two versions ahead in one
 * command, and the batch plan decides per item whether the command is accepted, conflicted or
 * answered by an unavailable server.
 */
async function mockInboxApi(page: Page): Promise<MockApi> {
  const items = [
    item(ids.unread, 'OWNERSHIP', 'URGENT', 3, 'UNREAD', 0),
    item(ids.read, 'OWNERSHIP', 'NORMAL', 2, 'READ', 1),
    item(ids.archived, 'OWNERSHIP', 'HIGH', 1, 'ARCHIVED', 3),
  ]
  const api: MockApi = { putCommands: [], batchPlan: null }

  await page.route(/\/api\/v1\//, async (route: Route) => {
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
        createdAt: '2026-09-26T07:00:00Z', createdByPrincipalId: ids.principal,
        updatedAt: '2026-09-26T07:00:00Z', updatedByPrincipalId: ids.principal,
      }], nextCursor: null })
    }
    if (request.method() === 'GET' && path.endsWith('/inbox/counts')) {
      const visible = items.filter(entry => entry.sourceStatus === 'OPEN' && entry.dispositionStatus !== 'ARCHIVED')
      const byType = Object.fromEntries(['OWNERSHIP', 'EXECUTION', 'REVIEW', 'CONFIRMATION', 'EXCEPTION'].map(type => {
        const typed = visible.filter(entry => entry.itemType === type)
        return [type, { total: typed.length, unread: typed.filter(entry => entry.dispositionStatus === 'UNREAD').length }]
      }))
      return fulfillJson(route, { total: visible.length, unread: visible.filter(entry => entry.dispositionStatus === 'UNREAD').length, byType })
    }
    if (request.method() === 'GET' && path.endsWith('/inbox')) {
      const dispositions = url.searchParams.getAll('dispositionStatuses')
      const matching = items.filter(entry => !dispositions.length || dispositions.includes(entry.dispositionStatus))
      return fulfillJson(route, { items: matching, nextCursor: null })
    }
    const disposition = path.match(/\/inbox\/([^/]+)\/disposition$/)
    if (request.method() === 'PUT' && disposition) {
      const current = items.find(entry => entry.inboxItemId === disposition[1])!
      const key = request.headers()['idempotency-key']!
      const ifMatch = request.headers()['if-match']!
      const input = request.postDataJSON() as { status: string }
      api.putCommands.push({ itemId: current.inboxItemId, status: input.status, ifMatch, key })
      expect(ifMatch).toBe(current.etag)
      const planned = api.batchPlan?.find(step => step.itemId === current.inboxItemId)
      if (planned?.behavior === 'unavailable') return fulfillError(route, 503, 'service_unavailable', null)
      if (planned?.behavior === 'conflict') {
        current.dispositionStatus = input.status
        current.dispositionVersion += 1
        current.etag = `"${current.dispositionVersion}"`
        return fulfillError(route, 409, 'optimistic_lock_conflict', current.dispositionVersion)
      }
      // The server's own two-step: ARCHIVED→READ→UNREAD commits one command two versions ahead.
      const wasArchived = current.dispositionStatus === 'ARCHIVED'
      current.dispositionStatus = input.status
      current.dispositionVersion += wasArchived && input.status === 'UNREAD' ? 2 : 1
      current.etag = `"${current.dispositionVersion}"`
      return route.fulfill({ status: 202, contentType: 'application/json', headers: { ETag: current.etag }, body: JSON.stringify(receipt(current.dispositionVersion)) })
    }
    const detail = path.match(/\/inbox\/([^/]+)$/)
    if (request.method() === 'GET' && detail) {
      const current = items.find(entry => entry.inboxItemId === detail[1])
      if (!current) return fulfillError(route, 404, 'inbox_item_not_found', null)
      return route.fulfill({ status: 200, contentType: 'application/json', headers: { ETag: current.etag, 'Cache-Control': 'no-store' }, body: JSON.stringify(current) })
    }
    return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify(errorEnvelope('not_found', null)) })
  })
  return api
}

function item(inboxItemId: string, itemType: string, priority: string, sourceRevision: number, dispositionStatus: string, dispositionVersion: number) {
  return {
    inboxItemId, itemType, priority, deadline: null, openedAt: `2026-09-26T0${sourceRevision}:00:00Z`,
    sourceStatus: 'OPEN', closeReason: null, closedAt: null,
    dispositionStatus, dispositionVersion, etag: `"${dispositionVersion}"`,
    source: { type: 'RESPONSIBILITY_ASSIGNMENT', id: ids.source, revision: sourceRevision },
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
