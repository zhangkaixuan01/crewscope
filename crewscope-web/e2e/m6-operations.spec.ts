import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page, type Route } from '@playwright/test'
import { authenticatedSession } from './auth-session'

const ids = {
  organization: '00000000-0000-0000-0000-000000000001', team: '00000000-0000-4000-8000-000000000201',
  project: '00000000-0000-4000-8000-000000000401', workspace: '00000000-0000-4000-8000-000000000501',
  principal: '00000000-0000-4000-8000-000000000101', job: '00000000-0000-4000-8000-000000000601',
  command: '00000000-0000-4000-8000-000000000701', outbox: '00000000-0000-4000-8000-000000000801',
  event: '00000000-0000-4000-8000-000000000802',
}

test.beforeEach(async ({ page }) => { await page.clock.setFixedTime(new Date('2026-08-27T08:30:00Z')) })

test('shows bounded health, strongly confirms a generation command and passes the release UI gates', async ({ page }, testInfo) => {
  const calls = await mockOperationsApi(page)
  await page.goto(`/operations?team=${ids.team}&project=${ids.project}`)

  await expect(page.getByRole('heading', { name: '团队执行链路' })).toBeVisible()
  await expect(page.locator('.health-card')).toHaveCount(5)
  await expect(page.getByText('Projection 与恢复管理')).toBeVisible()
  await expect(page.getByRole('navigation', { name: 'MVP 演示证据' }).getByRole('link')).toHaveCount(5)
  await expect(page.getByText('team-activity')).toBeVisible()

  const axe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze()
  expect(axe.violations).toEqual([])
  await expect(page).toHaveScreenshot(`m6-operations-${testInfo.project.name}.png`, { fullPage: true })

  const validate = page.getByRole('button', { name: '验证', exact: true })
  await validate.focus()
  await validate.click()
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByRole('heading', { name: '验证影子代际' })).toBeFocused()
  await dialog.getByLabel('确认短语').fill('wrong')
  await expect(dialog.getByRole('button', { name: '确认执行' })).toBeDisabled()
  await dialog.getByLabel('确认短语').fill('VALIDATE team-activity 5')
  await dialog.getByRole('button', { name: '确认执行' }).click()

  await expect(page.getByText('命令已接受')).toBeVisible()
  expect(calls.validate).toBe(1)
  expect(calls.validateBody).toEqual({
    expectedDefinitionVersion: 2, rebuildJobId: ids.job, expectedGenerationVersion: 3,
    expectedJobVersion: 2, confirmation: 'VALIDATE team-activity 5',
  })
  expect(calls.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/)
  expect(calls.health).toBeGreaterThanOrEqual(2)
  expect(calls.diagnostics).toBeGreaterThanOrEqual(2)
})

test('retains health and diagnostics while disabling management commands offline', async ({ page, context }) => {
  await mockOperationsApi(page)
  await page.goto(`/operations?team=${ids.team}&project=${ids.project}`)
  await expect(page.getByText('team-activity')).toBeVisible()

  await context.setOffline(true)
  await expect(page.getByText(/离线期间保留当前摘要/)).toBeVisible()
  await expect(page.getByText('team-activity')).toBeVisible()
  await expect(page.getByRole('button', { name: '验证', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: /执行恢复/ })).toBeDisabled()
  await context.setOffline(false)
})

async function mockOperationsApi(page: Page) {
  const calls = { health: 0, diagnostics: 0, validate: 0, validateBody: null as unknown, idempotencyKey: null as string | null }
  await page.route(/\/api\/v1\//, async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() === 'GET' && path === '/api/v1/auth/session') return json(route, authenticatedSession(ids.organization, ids.principal, ids.team))
    if (request.method() === 'GET' && path.endsWith('/teams')) return json(route, [{
      id: ids.team, organizationId: ids.organization, name: 'Platform Engineering', status: 'ACTIVE', initializationStatus: 'READY',
      ownerMemberId: 'member-1', defaultWorkspaceId: ids.workspace, version: 1,
    }])
    if (request.method() === 'GET' && path.endsWith(`/${ids.team}/work-projects`)) return json(route, { items: [{
      id: ids.project, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace, key: 'CRW', name: 'CrewScope',
      status: 'ACTIVE', version: 1, createdAt: '2026-08-27T07:00:00Z', createdByPrincipalId: ids.principal,
      updatedAt: '2026-08-27T07:00:00Z', updatedByPrincipalId: ids.principal,
    }], nextCursor: null })
    if (request.method() === 'GET' && path.endsWith('/operations/health')) {
      calls.health += 1
      return json(route, health())
    }
    if (request.method() === 'GET' && path.endsWith('/operations/diagnostics')) {
      calls.diagnostics += 1
      return json(route, diagnostics())
    }
    if (request.method() === 'POST' && path.endsWith('/generations/5/validate')) {
      calls.validate += 1
      calls.validateBody = request.postDataJSON()
      calls.idempotencyKey = request.headers()['idempotency-key'] ?? null
      return json(route, {
        commandId: ids.command, projectionName: 'team-activity', generation: 5, rebuildJobId: ids.job,
        generationStatus: 'VALIDATING', rebuildStatus: 'VALIDATING', generationVersion: 4, rebuildJobVersion: 3, pointerVersion: null,
      })
    }
    return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({
      code: 'not_found', message: 'not found', correlationId: ids.command, retryable: false, currentVersion: null, details: {},
    }) })
  })
  return calls
}

function health() {
  return {
    observedAt: '2026-08-27T08:30:00Z', health: 'DEGRADED',
    components: ['PROJECTION', 'OUTBOX', 'DEAD_LETTER', 'CURSOR', 'NOTIFICATION'].map((component, index) => ({
      component, health: index === 0 ? 'DEGRADED' : 'HEALTHY', backlog: index === 0 ? 4 : 0,
      inFlight: index === 1 ? 1 : 0, failures: 0, affected: index === 0 ? 2 : 0,
      oldestOutstandingAgeSeconds: index === 0 ? 28 : 0, stale: false,
    })),
  }
}
function diagnostics() {
  return {
    summary: health(),
    projections: [{
      projectionName: 'team-activity', definitionVersion: 2, activeGeneration: 4, pointerVersion: 7, activeGenerationVersion: 9,
      shadowGeneration: 5, shadowStatus: 'VALIDATING', shadowGenerationVersion: 3, rebuildJobId: ids.job, rebuildJobVersion: 2,
      lagSeconds: 4, gapCount: 0, deadLetterCount: 1, latestFailureCode: null, startConfirmation: 'START REBUILD team-activity',
      validateConfirmation: 'VALIDATE team-activity 5', switchConfirmation: 'SWITCH team-activity 5',
      cancelConfirmation: 'CANCEL team-activity 5', failConfirmation: 'FAIL team-activity 5',
    }],
    recoveryCandidates: [{
      type: 'OUTBOX_DEAD_LETTER', action: 'REPLAY_OUTBOX_DEAD_LETTER', outboxEventId: ids.outbox, domainEventId: ids.event,
      expectedVersion: 6, referenceHash: 'a'.repeat(64), confirmation: `REPLAY OUTBOX ${ids.outbox}:6`,
    }],
  }
}
function json(route: Route, value: unknown) { return route.fulfill({ status: 200, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(value) }) }
