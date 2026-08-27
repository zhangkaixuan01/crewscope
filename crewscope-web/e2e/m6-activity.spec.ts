import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page, type Route } from '@playwright/test'

const ids = {
  organization: '00000000-0000-0000-0000-000000000001',
  team: '00000000-0000-0000-0000-000000000201',
  project: '00000000-0000-0000-0000-000000000401',
  workspace: '00000000-0000-0000-0000-000000000501',
  principal: '00000000-0000-0000-0000-000000000101',
  workItem: '00000000-0000-0000-0000-000000000601',
}

test.beforeEach(async ({ page }) => mockActivityApi(page))

test('renders, resumes, de-duplicates and passes Axe in both viewports', async ({ page }, testInfo) => {
  await page.goto(`/activity?team=${ids.team}&project=${ids.project}`)
  await expect(page.getByText('TASK_COMPLETED')).toHaveCount(1)
  await expect(page.getByText('REVIEW_APPROVED')).toBeVisible()
  await expect(page.getByText('实时').first()).toBeVisible()

  const results = await new AxeBuilder({ page }).analyze()
  expect(results.violations).toEqual([])
  await expect(page).toHaveScreenshot(`m6-activity-${testInfo.project.name}.png`, { fullPage: true })

  const evidence = page.getByRole('link', { name: /WorkItem/ }).first()
  await evidence.focus()
  await expect(evidence).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(/\/work\?.*workItem=/)
})

test('keeps cached Activity readable while offline', async ({ page, context }) => {
  await page.goto(`/activity?team=${ids.team}&project=${ids.project}`)
  await expect(page.getByText('TASK_COMPLETED')).toBeVisible()

  await context.setOffline(true)
  await expect(page.getByText('正在展示最近同步的 Activity')).toBeVisible()
  await expect(page.getByText('TASK_COMPLETED')).toBeVisible()
  await context.setOffline(false)
})

async function mockActivityApi(page: Page): Promise<void> {
  await page.route(/\/api\/v1\//, async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
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
    if (request.method() === 'GET' && path.endsWith('/activity/snapshot')) {
      return fulfillJson(route, {
        items: [activity('event-review', 'REVIEW_APPROVED', 'REVIEW', 'APPROVED', 4)],
        hasMore: true, nextCursor: 'older-cursor', snapshotCursor: 'snapshot-cursor',
      })
    }
    if (request.method() === 'GET' && path.endsWith('/activity/events')) {
      expect(url.searchParams.get('after')).toBe('snapshot-cursor')
      const completed = activity('event-task', 'TASK_COMPLETED', 'EXECUTION', 'COMPLETED', 5)
      return fulfillSse(route, [
        { id: 'team-cursor-1', event: completed.eventType, data: completed },
        { id: 'team-cursor-2', event: completed.eventType, data: completed },
      ])
    }
    const detail = path.match(/\/activity\/([^/]+)$/)
    if (request.method() === 'GET' && detail) return fulfillJson(route, activity(detail[1]!, 'TASK_COMPLETED', 'EXECUTION', 'COMPLETED', 5))
    return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 'not_found', message: 'Not found', correlationId: 'corr-404', retryable: false, currentVersion: null, details: {} }) })
  })
}

function activity(eventId: string, eventType: string, category: string, outcome: string, sequence: number) {
  return {
    eventId, domainEventId: `${eventId}-domain`, teamSequence: sequence, eventType, category, visibility: 'TEAM',
    subject: { type: eventType.startsWith('REVIEW') ? 'REVIEW_REQUEST' : 'TASK', id: eventType.startsWith('REVIEW') ? 'review-1' : 'task-1' },
    actor: { type: 'MEMBER', principalId: ids.principal },
    references: [{ type: 'WORK_ITEM', id: ids.workItem }, { type: 'ARTIFACT', id: 'artifact-1' }],
    occurredAt: `2026-08-27T08:0${sequence}:00Z`,
    payload: { schemaName: 'activity-summary', schemaVersion: 1, values: { outcome, evidence: 'SAFE_REFERENCE' } },
  }
}

function fulfillJson(route: Route, value: unknown) {
  return route.fulfill({ status: 200, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(value) })
}

function fulfillSse(route: Route, frames: Array<{ id: string, event: string, data: unknown }>) {
  const body = frames.map(frame => `id:${frame.id}\nevent:${frame.event}\ndata:${JSON.stringify(frame.data)}\n\n`).join('')
  return route.fulfill({ status: 200, contentType: 'text/event-stream', headers: { 'Cache-Control': 'no-store' }, body })
}
