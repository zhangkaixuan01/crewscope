import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page, type Route } from '@playwright/test'

const ids = {
  organization: uuid(1), principal: uuid(101), team: uuid(201), project: uuid(401), workspace: uuid(501),
  event: uuid(901), olderEvent: uuid(902), actor: uuid(102), subject: uuid(601), binding: uuid(701),
  connection: uuid(702), correlation: uuid(801), domainEvent: uuid(802),
}

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-08-27T08:30:00Z'))
  await mockAuditApi(page)
})

test('combines filters, de-duplicates pages, explores Correlation and passes Axe with stable visuals', async ({ page }, testInfo) => {
  await page.goto(`/audit?team=${ids.team}&project=${ids.project}&from=2026-08-01T08:00&to=2026-08-27T08:00&auditEvent=${ids.event}`)

  await expect(page.getByRole('heading', { name: '团队审计中心' })).toBeVisible()
  await expect(page.getByRole('table')).toContainText('TEAM_ACCESS_DENIED')
  await expect(page.getByRole('heading', { name: '审计详情' })).toBeVisible()
  await expect(page.locator('body')).not.toContainText(/Authorization Context|Credential|Endpoint|Trace ID|Provider Body|原始 Payload/)

  await expect(page).toHaveScreenshot(`m6-audit-${testInfo.project.name}.png`, { fullPage: true })
  const axe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze()
  expect(axe.violations).toEqual([])

  await page.getByRole('button', { name: '关闭审计详情' }).click()
  await expect(page).not.toHaveURL(/auditEvent=/)
  await page.getByRole('button', { name: '加载更多' }).click()
  await expect(page.getByRole('button', { name: '查看详情' })).toHaveCount(2)

  const category = page.getByLabel('Category')
  const outcome = page.getByLabel('Outcome')
  await category.selectOption('SECURITY')
  await expect(category).toHaveValue('SECURITY')
  await outcome.selectOption('DENIED')
  await expect(category).toHaveValue('SECURITY')
  await expect(outcome).toHaveValue('DENIED')
  const apply = page.getByRole('button', { name: '应用筛选' })
  await expect(apply).toHaveAttribute('type', 'submit')
  await apply.click()
  await expect(page).toHaveURL(/category=SECURITY/)
  await expect(page).toHaveURL(/outcome=DENIED/)

  await page.getByRole('button', { name: '查看详情' }).first().focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('heading', { name: '审计详情' })).toBeFocused()
  await page.getByRole('button', { name: '查看关联链' }).click()
  await expect(page.getByRole('heading', { name: 'Correlation 链' })).toBeVisible()
  const object = page.locator('.correlation-objects button')
  await object.focus()
  await expect(object).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(new RegExp(`/activity\?.*correlation=${ids.correlation}`))
})

test('exports only an explicit bounded range and reports server authorization failures', async ({ page }) => {
  await page.goto(`/audit?team=${ids.team}&project=${ids.project}&from=2026-08-01T08:00&to=2026-08-27T08:00`)
  await expect(page.getByText('TEAM_ACCESS_DENIED')).toBeVisible()

  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出 JSON' }).click()
  expect((await download).suggestedFilename()).toBe('crewscope-audit-export.json')
  await expect(page.getByText('导出已生成并下载')).toBeVisible()

  await page.goto(`/audit?team=${ids.team}&project=${ids.project}&from=2026-08-01T08:00&to=2026-08-27T08:00&scenario=export-forbidden`)
  await page.getByRole('button', { name: '导出 JSON' }).click()
  await expect(page.getByText('服务端拒绝导出权限')).toBeVisible()
})

test('keeps cached Audit facts readable offline and on expired continuation', async ({ page, context }) => {
  await page.goto(`/audit?team=${ids.team}&project=${ids.project}&from=2026-08-01T08:00&to=2026-08-27T08:00&scenario=cursor-expired`)
  await expect(page.getByText('TEAM_ACCESS_DENIED')).toBeVisible()
  await page.getByRole('button', { name: '加载更多' }).click()
  await expect(page.getByText('审计续页 Cursor 已过期')).toBeVisible()
  await expect(page.getByText('TEAM_ACCESS_DENIED')).toBeVisible()

  await context.setOffline(true)
  await expect(page.getByText('正在展示最近同步的审计事实')).toBeVisible()
  await expect(page.getByRole('button', { name: '导出 JSON' })).toBeDisabled()
  await context.setOffline(false)
})

async function mockAuditApi(page: Page): Promise<void> {
  await page.route(/\/api\/v1\//, async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const scenario = new URL(page.url()).searchParams.get('scenario')
    if (request.method() === 'GET' && path.endsWith('/teams')) return fulfillJson(route, [{ id: ids.team, organizationId: ids.organization, name: 'Platform Engineering', status: 'ACTIVE', initializationStatus: 'READY', ownerMemberId: 'member-1', defaultWorkspaceId: ids.workspace, version: 1 }])
    if (request.method() === 'GET' && path.endsWith(`/${ids.team}/work-projects`)) return fulfillJson(route, { items: [{ id: ids.project, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace, key: 'CRW', name: 'CrewScope', status: 'ACTIVE', version: 1, createdAt: '2026-08-27T07:00:00Z', createdByPrincipalId: ids.principal, updatedAt: '2026-08-27T07:00:00Z', updatedByPrincipalId: ids.principal }], nextCursor: null })
    if (request.method() === 'GET' && path.endsWith('/audit-events')) {
      if (url.searchParams.get('categories')) expect(url.searchParams.getAll('categories')).toEqual(['SECURITY'])
      if (url.searchParams.get('outcomes')) expect(url.searchParams.getAll('outcomes')).toEqual(['DENIED'])
      if (url.searchParams.get('after')) {
        if (scenario === 'cursor-expired') return fulfillError(route, 410, 'cursor_expired')
        return fulfillJson(route, { items: [audit(ids.event, 'TEAM_ACCESS_DENIED', 'DENIED'), audit(ids.olderEvent, 'ACTION_DELIVERED', 'SUCCEEDED')], nextCursor: null })
      }
      return fulfillJson(route, { items: [audit(ids.event, 'TEAM_ACCESS_DENIED', 'DENIED')], nextCursor: 'audit-cursor-2' })
    }
    if (request.method() === 'POST' && path.endsWith('/audit-events/export')) {
      if (scenario === 'export-forbidden') return fulfillError(route, 403, 'forbidden')
      const body = request.postDataJSON() as Record<string, unknown>
      expect(body.maximumRows).toBe(1000)
      expect(body.occurredFrom).toBe('2026-08-01T00:00:00.000Z')
      expect(body.occurredBefore).toBe('2026-08-27T00:00:00.000Z')
      expect(request.headers().accept).toBe('application/vnd.crewscope.audit-export+json')
      return fulfillJson(route, { generatedAt: '2026-08-27T08:30:00Z', rowCount: 1, maximumRows: 1000, events: [audit(ids.event, 'TEAM_ACCESS_DENIED', 'DENIED')] })
    }
    if (request.method() === 'GET' && path.includes('/correlations/')) {
      const href = `/activity?team=${ids.team}&correlation=${ids.correlation}&objectType=WORK_ITEM&objectId=${ids.subject}`
      return fulfillJson(route, {
        correlationId: ids.correlation,
        events: [{ eventId: ids.event, source: 'AUDIT', eventType: 'TEAM_ACCESS_DENIED', actorType: 'USER', actorId: ids.actor, outcome: 'DENIED', occurredAt: '2026-08-27T08:00:00Z', references: [{ type: 'WORK_ITEM', id: ids.subject, href }] }],
        objects: [{ type: 'WORK_ITEM', id: ids.subject, href, relatedEventIds: [ids.event] }], hasMore: false, nextCursor: null,
      })
    }
    return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 'not_found', message: 'not_found', correlationId: ids.correlation, retryable: false, currentVersion: null, details: {} }) })
  })
}

function audit(eventId: string, eventType: string, outcome: 'DENIED' | 'SUCCEEDED') {
  return {
    eventId, eventType, sourceSchemaVersion: 1, category: outcome === 'DENIED' ? 'SECURITY' : 'ACTION', outcome,
    retentionLevel: outcome === 'DENIED' ? 'EXTENDED' : 'STANDARD', occurredAt: outcome === 'DENIED' ? '2026-08-27T08:00:00Z' : '2026-08-26T08:00:00Z',
    identity: { initiatorId: ids.actor, actorType: 'USER', actorId: ids.actor, agentPrincipalId: null },
    subject: { type: 'WORK_ITEM', id: ids.subject }, provider: { providerBindingId: ids.binding, connectionId: ids.connection, externalOperationHash: 'a'.repeat(64) },
    correlation: { correlationId: ids.correlation, causationId: null, domainEventId: ids.domainEvent }, summary: { reasonCode: outcome.toLowerCase() },
  }
}

function fulfillJson(route: Route, value: unknown) { return route.fulfill({ status: 200, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(value) }) }
function fulfillError(route: Route, status: number, code: string) { return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code, message: code, correlationId: ids.correlation, retryable: status === 410, currentVersion: null, details: {} }) }) }
function uuid(index: number): string { return `00000000-0000-4000-8000-${String(index).padStart(12, '0')}` }
