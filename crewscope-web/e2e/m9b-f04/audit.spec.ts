import { expect, test, type Page, type Route } from '@playwright/test'
import { authenticatedSession } from '../auth-session'

/**
 * R33/R34 审计查找与导出：主体筛选按姓名选人（AUDIT 目录含离职历史身份）；
 * 导出只跟随已应用条件，草稿漂移时禁用并给出「应用筛选」入口；完成后报告实际条数
 * 与可能的截断；第二页事件的深链在全新加载里通过 by-id 点查直达，不可见的事件如实说明。
 */

const ids = {
  organization: '00000000-0000-4000-8000-000000000001',
  team: '00000000-0000-4000-8000-000000000201',
  project: '00000000-0000-4000-8000-000000000401',
  workspace: '00000000-0000-4000-8000-000000000501',
  principal: '00000000-0000-4000-8000-000000000101',
  event: '00000000-0000-4000-8000-000000000901',
  olderEvent: '00000000-0000-4000-8000-000000000902',
  actor: '00000000-0000-4000-8000-000000000102',
  former: '00000000-0000-4000-8000-000000000110',
  unknownEvent: '00000000-0000-4000-8000-000000000999',
  subject: '00000000-0000-4000-8000-000000000601',
  correlation: '00000000-0000-4000-8000-000000000801',
  domainEvent: '00000000-0000-4000-8000-000000000802',
  binding: '00000000-0000-4000-8000-000000000701',
  connection: '00000000-0000-4000-8000-000000000702',
}

interface World {
  principalQueries: string[]
  auditQueries: string[]
  exportBodies: Array<Record<string, unknown>>
  pointReads: string[]
}

async function mockAuditApi(page: Page, world: World): Promise<void> {
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
    if (method === 'GET' && path.endsWith(`/${ids.team}/work-projects`)) return json(route, { items: [], nextCursor: null })
    if (method === 'GET' && path.endsWith(`/${ids.team}/members`)) return json(route, [])
    if (method === 'GET' && path.endsWith('/principals')) {
      world.principalQueries.push(url.search)
      return json(route, {
        items: [
          { principalId: ids.actor, kind: 'USER', displayName: '林一舟', status: 'ACTIVE', roles: ['成员'] },
          { principalId: ids.former, kind: 'USER', displayName: '陈旧账', status: 'ARCHIVED', roles: [] },
        ],
        nextCursor: null,
      })
    }
    if (method === 'GET' && /^\/api\/v1\/organizations\/[^/]+\/teams\/[^/]+\/audit-events\/[^/]+$/.test(path)) {
      const eventId = path.split('/').pop()!
      if (eventId === ids.unknownEvent) {
        return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 'aggregate_not_found', message: 'aggregate_not_found', correlationId: ids.correlation, retryable: false, currentVersion: null, details: {} }) })
      }
      world.pointReads.push(eventId)
      return json(route, audit(eventId === ids.olderEvent ? ids.olderEvent : ids.event, 'ACTION_DELIVERED', 'SUCCEEDED'))
    }
    if (method === 'GET' && path.endsWith('/audit-events')) {
      world.auditQueries.push(url.search)
      if (url.searchParams.get('after')) return json(route, { items: [audit(ids.olderEvent, 'ACTION_DELIVERED', 'SUCCEEDED')], nextCursor: null })
      return json(route, { items: [audit(ids.event, 'TEAM_ACCESS_DENIED', 'DENIED')], nextCursor: 'audit-cursor-2' })
    }
    if (method === 'POST' && path.endsWith('/audit-events/export')) {
      const body = request.postDataJSON() as Record<string, unknown>
      world.exportBodies.push(body)
      const maximumRows = Number(body.maximumRows)
      return json(route, { generatedAt: '2026-09-26T08:30:00Z', rowCount: 1, maximumRows, events: [audit(ids.event, 'TEAM_ACCESS_DENIED', 'DENIED')] })
    }
    return notFound(route)
  })
}

test('names the audit subject through the picker, including former team identities', async ({ page }) => {
  const world: World = { principalQueries: [], auditQueries: [], exportBodies: [], pointReads: [] }
  await mockAuditApi(page, world)
  await page.goto(`/audit?team=${ids.team}&from=2026-09-01T08:00&to=2026-09-26T08:00`)
  await expect(page.getByText('TEAM_ACCESS_DENIED')).toBeVisible()

  await page.getByRole('button', { name: '展开高级筛选' }).click()
  const initiator = page.getByLabel('Initiator')
  await initiator.fill('陈')
  await page.getByRole('option', { name: /陈旧账/ }).click()
  // The AUDIT directory rode the request; no identifier was typed anywhere.
  expect(world.principalQueries.every(query => query.includes('purpose=AUDIT'))).toBe(true)
  await expect(page.getByLabel('更换Initiator')).toBeVisible()

  // Two "应用筛选" buttons exist once the draft drifts (footer primary + inline entry); the
  // footer primary is the scoped one to commit the whole form.
  await page.locator('.audit-filter > footer > div > button[type="submit"]').click()
  await expect(page).toHaveURL(new RegExp(`initiator=${ids.former}`))
  await expect.poll(() => world.auditQueries.some(query => query.includes(`initiatorIds=${ids.former}`))).toBe(true)
})

test('blocks export while the draft drifted and restores it through the apply entry', async ({ page }) => {
  const world: World = { principalQueries: [], auditQueries: [], exportBodies: [], pointReads: [] }
  await mockAuditApi(page, world)
  await page.goto(`/audit?team=${ids.team}&from=2026-09-01T08:00&to=2026-09-26T08:00`)
  await expect(page.getByText('TEAM_ACCESS_DENIED')).toBeVisible()
  await expect(page.getByText('已应用条件')).toBeVisible()

  await page.getByLabel('Category').selectOption('SECURITY')
  const exportButton = page.getByRole('button', { name: '导出 CSV' })
  await expect(exportButton).toBeDisabled()
  await expect(page.getByText('筛选已修改但未应用')).toBeVisible()

  await page.locator('.export-control .inline-apply').click()
  await expect(page).toHaveURL(/category=SECURITY/)
  await expect(exportButton).toBeEnabled()
  await expect(page.getByText('筛选已修改但未应用')).toBeHidden()
})

test('exports the applied conditions and reports the shipped count with truncation', async ({ page }) => {
  const world: World = { principalQueries: [], auditQueries: [], exportBodies: [], pointReads: [] }
  await mockAuditApi(page, world)
  await page.goto(`/audit?team=${ids.team}&from=2026-09-01T08:00&to=2026-09-26T08:00&category=SECURITY`)
  await expect(page.getByText('TEAM_ACCESS_DENIED')).toBeVisible()

  await page.getByLabel('导出上限').fill('1')
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出 CSV' }).click()
  expect((await download).suggestedFilename()).toMatch(/^crewscope-audit-SECURITY-.*\.csv$/)
  await expect(page.getByText('已导出 1 条并下载')).toBeVisible()
  await expect(page.getByText('达到行数上限，结果可能不完整')).toBeVisible()
  expect(world.exportBodies[0]).toMatchObject({ maximumRows: 1, categories: ['SECURITY'] })
})

test('opens a second-page event from a deep link in a fresh load', async ({ page }) => {
  const world: World = { principalQueries: [], auditQueries: [], exportBodies: [], pointReads: [] }
  await mockAuditApi(page, world)
  await page.goto(`/audit?team=${ids.team}&from=2026-09-01T08:00&to=2026-09-26T08:00`)
  await expect(page.getByText('TEAM_ACCESS_DENIED')).toBeVisible()

  // Load page two first so the target is known, then arrive by deep link in a fresh document:
  // the first page does not contain the row, so the by-id point read must fill the detail.
  await page.getByRole('button', { name: '加载更多' }).click()
  await expect(page.getByRole('button', { name: '查看详情' })).toHaveCount(2)
  await page.goto(`/audit?team=${ids.team}&from=2026-09-01T08:00&to=2026-09-26T08:00&auditEvent=${ids.olderEvent}`)

  await expect(page.getByRole('heading', { name: '审计详情' })).toBeVisible()
  await expect(page.locator('.audit-detail__hero')).toContainText('ACTION_DELIVERED')
  expect(world.pointReads).toContain(ids.olderEvent)
})

test('explains a deep-linked event that is not visible in the current Team', async ({ page }) => {
  const world: World = { principalQueries: [], auditQueries: [], exportBodies: [], pointReads: [] }
  await mockAuditApi(page, world)
  await page.goto(`/audit?team=${ids.team}&from=2026-09-01T08:00&to=2026-09-26T08:00&auditEvent=${ids.unknownEvent}`)
  await expect(page.getByText('TEAM_ACCESS_DENIED')).toBeVisible()
  await expect(page.getByText('深链的审计事件在当前 Team 不可见')).toBeVisible()
  await expect(page.getByRole('heading', { name: '审计详情' })).toBeHidden()
})

function audit(eventId: string, eventType: 'TEAM_ACCESS_DENIED' | 'ACTION_DELIVERED', outcome: 'DENIED' | 'SUCCEEDED') {
  return {
    eventId, eventType, sourceSchemaVersion: 1,
    category: outcome === 'DENIED' ? 'SECURITY' : 'ACTION', outcome,
    retentionLevel: outcome === 'DENIED' ? 'EXTENDED' : 'STANDARD',
    occurredAt: outcome === 'DENIED' ? '2026-09-26T08:00:00Z' : '2026-09-25T08:00:00Z',
    identity: { initiatorId: ids.actor, actorType: 'USER', actorId: ids.actor, agentPrincipalId: null },
    subject: { type: 'WORK_ITEM', id: ids.subject },
    provider: { providerBindingId: ids.binding, connectionId: ids.connection, externalOperationHash: 'a'.repeat(64) },
    correlation: { correlationId: ids.correlation, causationId: null, domainEventId: ids.domainEvent },
    summary: { reasonCode: outcome.toLowerCase() },
  }
}

function json(route: Route, value: unknown) { return route.fulfill({ status: 200, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(value) }) }
function notFound(route: Route) { return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 'not_found', message: 'not_found', correlationId: ids.correlation, retryable: false, currentVersion: null, details: {} }) }) }
