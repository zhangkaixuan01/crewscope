import { expect, test, type Route } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

const id = {
  org: '00000000-0000-0000-0000-000000000001', principal: '00000000-0000-0000-0000-000000000101', team: uuid(2), project: uuid(3), workspace: uuid(4),
  member: uuid(5), member2: uuid(6), connection: uuid(7), binding: uuid(8), mapping: uuid(9), delivery: uuid(10), template: uuid(11), proof: uuid(12),
}

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-08-27T08:00:00Z'))
  let connectionVersion = 4
  const bindingVersion = 6
  let mappings = [mapping()]
  let deliveryVersion = 3
  await page.route(/\/api\/v1\//, async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    if (request.method() === 'GET' && path.endsWith('/teams')) return json(route, [team()])
    if (request.method() === 'GET' && path.endsWith('/work-projects')) return json(route, { items: [project()], nextCursor: null })
    if (request.method() === 'GET' && path.endsWith('/members')) return json(route, [
      { id: id.member, userPrincipalId: id.principal, status: 'ACTIVE', joinMethod: 'CREATED_WITH_TEAM', joinedAt: '2026-08-01T00:00:00Z', version: 0 },
      { id: id.member2, userPrincipalId: uuid(13), status: 'ACTIVE', joinMethod: 'INVITED', joinedAt: '2026-08-02T00:00:00Z', version: 0 },
    ])
    if (request.method() === 'GET' && path.endsWith('/lark/connections')) return json(route, [connection(connectionVersion)])
    if (request.method() === 'GET' && path.endsWith(`/lark/connections/${id.connection}`)) return versioned(route, connection(connectionVersion), connectionVersion)
    if (request.method() === 'GET' && path.endsWith(`/lark/bindings/${id.binding}/health`)) return json(route, { status: 'HEALTHY', retryable: false, retryAfterSeconds: null, evidenceCode: 'LARK_PROVIDER_HEALTHY', checkedAt: '2026-08-27T07:55:00Z' })
    if (request.method() === 'POST' && path.endsWith(`/lark/bindings/${id.binding}/preflight`)) {
      expect(request.headers()['if-match']).toBe(`"${bindingVersion}"`)
      return json(route, { providerBindingId: id.binding, version: bindingVersion, checkedAt: '2026-08-27T07:55:00Z' })
    }
    if (request.method() === 'POST' && path.endsWith(`/lark/connections/${id.connection}/rotate`)) {
      const input = request.postDataJSON() as { appId: string, appSecret: string }
      expect(input).toEqual({ appId: 'cli_updated', appSecret: 'rotated-once' })
      expect(request.headers()['if-match']).toBe(`"${connectionVersion}"`)
      expect(request.headers()['idempotency-key']).toBeTruthy()
      connectionVersion += 1
      return receipt(route, connectionVersion)
    }
    if (request.method() === 'POST' && path.endsWith('/lark/member-verifications')) {
      const input = request.postDataJSON() as { providerBindingId: string, openId: string }
      expect(input).toEqual({ providerBindingId: id.binding, openId: 'ou_exact_once' })
      expect(request.headers()['if-match']).toBe(`"${bindingVersion}"`)
      return receipt(route, 0, id.proof)
    }
    if (request.method() === 'POST' && path.endsWith('/lark/member-mappings')) {
      const input = request.postDataJSON() as { memberId: string, providerBindingId: string, proofId: string }
      expect(input).toEqual({ memberId: id.member2, providerBindingId: id.binding, proofId: id.proof })
      mappings = [...mappings, { ...mapping(), mappingId: uuid(14), memberId: id.member2 }]
      return receipt(route, 1)
    }
    if (request.method() === 'GET' && path.endsWith('/lark/member-mappings')) return json(route, { items: mappings, nextCursor: null })
    if (request.method() === 'GET' && path.endsWith('/lark/notification-templates')) return json(route, [{ ref: { templateId: id.template, version: 1 }, serverTemplateKey: 'review-requested', status: 'PUBLISHED', variables: [{ name: 'title', type: 'TEXT', maximumLength: 120 }] }])
    if (request.method() === 'GET' && path.endsWith(`/lark/notification-preferences/${id.member}`)) return versioned(route, { memberId: id.member, enabled: true, enabledItemTypes: ['REVIEW', 'CONFIRMATION'], mutedUntil: null, version: 1 }, 1)
    if (request.method() === 'PUT' && path.endsWith(`/lark/notification-preferences/${id.member}`)) {
      expect(request.headers()['if-match']).toBe('"1"')
      return receipt(route, 2)
    }
    if (request.method() === 'GET' && path.endsWith('/lark/notification-deliveries')) return json(route, { items: [delivery(deliveryVersion)], nextCursor: null })
    if (request.method() === 'GET' && path.endsWith(`/lark/notification-deliveries/${id.delivery}`)) return versioned(route, delivery(deliveryVersion), deliveryVersion)
    if (request.method() === 'POST' && path.endsWith(`/lark/notification-deliveries/${id.delivery}/redeliver`)) {
      expect(request.headers()['if-match']).toBe(`"${deliveryVersion}"`)
      deliveryVersion += 1
      return receipt(route, deliveryVersion)
    }
    return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 'fixture_not_found', message: path }) })
  })
})

test('manages exact mapping, one-way credential rotation, DND and failed delivery recovery', async ({ page }) => {
  await page.goto(`/settings/integrations/lark?team=${id.team}&project=${id.project}`)
  await expect(page.getByRole('heading', { name: '飞书与团队通知' })).toBeVisible()
  await expect(page.getByText('LARK_PROVIDER_HEALTHY')).toBeVisible()

  await page.getByRole('button', { name: /轮换凭证/ }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('App ID').fill('cli_updated')
  await dialog.getByLabel('App Secret').fill('rotated-once')
  await dialog.getByRole('button', { name: '确认提交' }).click()
  await expect(dialog).toBeHidden()
  await expect(page.getByText('rotated-once')).toHaveCount(0)

  await page.getByRole('button', { name: '成员映射' }).click()
  await page.getByLabel(/CrewScope 成员/).selectOption(id.member2)
  await page.getByLabel(/精确飞书 open_id/).fill('ou_exact_once')
  await page.getByRole('button', { name: /验证身份/ }).click()
  await expect(page.getByText('ou_exact_once')).toHaveCount(0)
  await page.getByRole('button', { name: /确认映射/ }).click()
  await expect(page.locator('tbody tr')).toHaveCount(2)

  await page.getByRole('button', { name: '通知中心' }).click()
  await page.getByLabel('DND 至').fill('2026-08-28T09:00')
  await page.getByRole('button', { name: '保存偏好' }).click()
  await page.getByRole('button', { name: /REVIEW/ }).click()
  await expect(page.getByRole('complementary', { name: '通知投递详情' })).toBeVisible()
  await page.getByRole('button', { name: /再次投递/ }).click()
  await expect(page.getByText('命令已受理')).toBeVisible()
})

test('is keyboard reachable, Axe clean and stable at both frozen viewports', async ({ page }, testInfo) => {
  await page.goto(`/settings/integrations/lark?team=${id.team}&project=${id.project}&tab=notification&delivery=${id.delivery}`)
  await expect(page.getByRole('heading', { name: '飞书与团队通知' })).toBeVisible()
  await page.keyboard.press('Tab')
  await expect(page.locator(':focus')).toBeVisible()
  const results = await new AxeBuilder({ page }).analyze()
  expect(results.violations).toEqual([])
  await page.locator(':focus').evaluate(element => (element as HTMLElement).blur())
  await expect(page).toHaveScreenshot(`m6-lark-notification-${testInfo.project.name}.png`, { fullPage: true })
})

test('keeps public provider facts readable and disables every external mutation offline', async ({ page, context }) => {
  await page.goto(`/settings/integrations/lark?team=${id.team}&project=${id.project}&tab=notification&delivery=${id.delivery}`)
  await expect(page.getByRole('heading', { name: '飞书与团队通知' })).toBeVisible()
  await expect(page.getByText('LARK_RETRY_EXHAUSTED')).toBeVisible()

  await context.setOffline(true)
  await expect(page.getByText('离线只读')).toBeVisible()
  await expect(page.getByText('正在展示最近同步的通知投递')).toBeVisible()
  await expect(page.getByRole('button', { name: /再次投递/ })).toBeDisabled()
  await expect(page.getByRole('button', { name: '保存偏好' })).toBeDisabled()
  await context.setOffline(false)
})

function team() { return { id: id.team, organizationId: id.org, name: 'Platform Engineering', status: 'ACTIVE', initializationStatus: 'READY', ownerMemberId: id.member, defaultWorkspaceId: id.workspace, version: 1 } }
function project() { return { id: id.project, organizationId: id.org, teamId: id.team, workspaceId: id.workspace, key: 'CRW', name: 'CrewScope', status: 'ACTIVE', version: 1, createdAt: '2026-08-01T00:00:00Z', createdByPrincipalId: id.principal, updatedAt: '2026-08-01T00:00:00Z', updatedByPrincipalId: id.principal } }
function connection(version: number) { return { connectionId: id.connection, teamId: id.team, providerBindingId: id.binding, providerBindingVersion: 6, maskedAppId: '****9x2k', status: 'ACTIVE', credentialStatus: 'ACTIVE', expiresAt: null, createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-27T07:50:00Z', version } }
function mapping() { return { mappingId: id.mapping, memberId: id.member, providerBindingId: id.binding, status: 'ACTIVE', terminalReason: null, verifiedAt: '2026-08-20T08:00:00Z', updatedAt: '2026-08-20T08:00:00Z', version: 1 } }
function delivery(version: number) { return { organizationId: id.org, teamId: id.team, deliveryId: id.delivery, recipientMemberId: id.member, itemType: 'REVIEW', template: { templateId: id.template, version: 1 }, providerBindingId: id.binding, status: 'FAILED_FINAL', attemptCount: 3, failureCode: 'RETRY_EXHAUSTED', evidenceCode: 'LARK_RETRY_EXHAUSTED', redeliveryOf: null, createdAt: '2026-08-27T07:00:00Z', updatedAt: '2026-08-27T07:30:00Z', version } }
async function json(route: Route, body: unknown) { await route.fulfill({ status: 200, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(body) }) }
async function versioned(route: Route, body: unknown, version: number) { await route.fulfill({ status: 200, contentType: 'application/json', headers: { ETag: `"${version}"`, 'Cache-Control': 'no-store' }, body: JSON.stringify(body) }) }
async function receipt(route: Route, version: number, eventId = uuid(20)) { await route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify({ commandId: uuid(19), domainEventId: eventId, committedVersion: version, correlationId: uuid(18) }) }) }
function uuid(index: number): string { return `00000000-0000-4000-8000-${String(index).padStart(12, '0')}` }
