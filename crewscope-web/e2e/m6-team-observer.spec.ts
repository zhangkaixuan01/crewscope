import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page, type Route } from '@playwright/test'
import { authenticatedSession } from './auth-session'

const ids = {
  organization: '00000000-0000-0000-0000-000000000001',
  team: '00000000-0000-4000-8000-000000000201',
  project: '00000000-0000-4000-8000-000000000401',
  workspace: '00000000-0000-4000-8000-000000000501',
  principal: '00000000-0000-4000-8000-000000000101',
  session: '00000000-0000-4000-8000-000000000801',
  invocation: '00000000-0000-4000-8000-000000000802',
}

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-08-27T08:30:00Z'))
})

test('resumes one read-only invocation, keeps prompt content inert and shares the summary across both modes', async ({ page }, testInfo) => {
  const calls = await mockObserverApi(page)
  await page.goto(`/conversation?team=${ids.team}&project=${ids.project}&assistant=team-observer`)

  await expect(page.getByRole('heading', { name: 'Team Observer', exact: true }).last()).toBeVisible()
  await expect(page.getByText('固定只读')).toBeVisible()
  await page.getByRole('button', { name: '生成只读摘要' }).click()

  await expect(page.getByText('团队摘要已生成')).toBeVisible()
  await expect(page.getByText('<script>window.promptAttack=true</script>')).toBeVisible()
  expect(await page.locator('script').evaluateAll(elements => elements.some(element => element.textContent?.includes('promptAttack')))).toBe(false)
  await expect(page.locator('.observer-section')).toHaveCount(5)
  expect(calls.invoke).toBe(1)
  expect(calls.resume).toBe(1)
  expect(calls.invocationIds).toEqual([ids.invocation, ids.invocation])

  const axe = await new AxeBuilder({ page }).analyze()
  expect(axe.violations).toEqual([])
  await expect(page).toHaveScreenshot(`m6-team-observer-conversation-${testInfo.project.name}.png`, { fullPage: true })

  if (testInfo.project.name === 'narrow-chromium') {
    // Context-header actions collapse on mobile; route navigation still reuses the app singleton.
    await page.evaluate(({ team, project }) => {
      history.pushState({}, '', `/team/observer?team=${team}&project=${project}&assistant=team-observer`)
      window.dispatchEvent(new PopStateEvent('popstate'))
    }, { team: ids.team, project: ids.project })
  } else {
    await page.getByRole('button', { name: /查看团队摘要/ }).click()
  }
  await expect(page).toHaveURL(/\/team\/observer/)
  await expect(page.getByText('<script>window.promptAttack=true</script>')).toBeVisible()
  await expect(page.getByText('CRW-214 等待 Reviewer Agent 复核。')).toBeVisible()
  expect(calls.invoke).toBe(1)
  await expect(page).toHaveScreenshot(`m6-team-observer-control-${testInfo.project.name}.png`, { fullPage: true })

  await page.getByRole('button', { name: /打开进展证据/ }).click()
  await expect(page).toHaveURL(new RegExp(`/activity\\?.*event=${ids.invocation}`))
  expect(calls.evidence).toBe(1)
})

test('keeps the authorized summary readable while all Agent and evidence calls are offline', async ({ page, context }) => {
  await mockObserverApi(page)
  await page.goto(`/team/observer?team=${ids.team}&project=${ids.project}`)
  await page.getByRole('button', { name: '生成团队摘要' }).click()
  await expect(page.getByText('CRW-214 等待 Reviewer Agent 复核。')).toBeVisible()

  await context.setOffline(true)
  await expect(page.getByRole('heading', { name: '当前离线' })).toBeVisible()
  await expect(page.getByText('CRW-214 等待 Reviewer Agent 复核。')).toBeVisible()
  await expect(page.getByRole('button', { name: '重读本次结果' })).toBeDisabled()
  await expect(page.getByRole('button', { name: /打开进展证据/ })).toBeDisabled()
  await context.setOffline(false)
})

// R38: a first failure carries no invocation id, so nothing can be resumed — the workspace says
// so, points at configuration, and regenerating is honestly framed as a new invocation.
test('recovers a first failure through regeneration and separates rereading from regenerating', async ({ page }) => {
  const calls = await mockObserverApi(page, { failFirstInvoke: true })
  await page.goto(`/team/observer?team=${ids.team}&project=${ids.project}`)

  await page.getByRole('button', { name: '生成团队摘要' }).click()
  await expect(page.getByText(/请勿当作重试/)).toBeVisible()
  await expect(page.getByRole('link', { name: '检查配置' })).toHaveAttribute('href', `/setup?team=${ids.team}`)

  await page.getByRole('button', { name: '重新生成摘要' }).click()
  await expect(page.getByText('团队摘要已生成')).toBeVisible()
  expect(calls.invoke).toBe(2)

  await expect(page.getByText('消耗新的模型时间与用量')).toBeVisible()
  await page.getByRole('button', { name: '重读本次结果' }).click()
  await expect(page.getByText('团队摘要已生成')).toBeVisible()
  expect(calls.summary).toBe(1)
  expect(calls.invoke).toBe(2)

  await page.getByRole('button', { name: '按最新重新生成' }).click()
  await expect(page.getByText('团队摘要已生成')).toBeVisible()
  expect(calls.invoke).toBe(3)
})

// R38: one failed evidence read stays beside its row; the summary survives and the row's own
// button is the retry for exactly that entry.
test('keeps one failed evidence row local without disturbing the summary', async ({ page }) => {
  const calls = await mockObserverApi(page, { failEvidenceAttempts: 1 })
  await page.goto(`/team/observer?team=${ids.team}&project=${ids.project}`)

  await page.getByRole('button', { name: '生成团队摘要' }).click()
  await expect(page.getByText('CRW-214 等待 Reviewer Agent 复核。')).toBeVisible()

  await page.getByRole('button', { name: /打开进展证据/ }).click()
  await expect(page.getByText('暂时无法打开这条证据')).toBeVisible()
  await expect(page.getByText('团队摘要已生成')).toBeVisible()
  await expect(page.getByText('CRW-214 等待 Reviewer Agent 复核。')).toBeVisible()

  await page.getByRole('button', { name: /打开进展证据/ }).click()
  await expect(page.getByText('暂时无法打开这条证据')).toBeHidden()
  await expect(page).toHaveURL(new RegExp(`/activity\\?.*event=${ids.invocation}`))
  expect(calls.evidence).toBe(2)
})

async function mockObserverApi(
  page: Page,
  options: { failFirstInvoke?: boolean, failEvidenceAttempts?: number } = {},
): Promise<{ invoke: number, resume: number, evidence: number, summary: number, invocationIds: string[] }> {
  const calls = { invoke: 0, resume: 0, evidence: 0, summary: 0, invocationIds: [] as string[] }
  await page.route(/\/api\/v1\//, async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    if (request.method() === 'GET' && path === '/api/v1/auth/session') return json(route, authenticatedSession(ids.organization, ids.principal, ids.team))
    if (request.method() === 'GET' && path.endsWith('/teams')) {
      return json(route, [{ id: ids.team, organizationId: ids.organization, name: 'Platform Engineering', status: 'ACTIVE', initializationStatus: 'READY', ownerMemberId: 'member-1', defaultWorkspaceId: ids.workspace, version: 1 }])
    }
    if (request.method() === 'GET' && path.endsWith(`/${ids.team}/work-projects`)) {
      return json(route, { items: [{ id: ids.project, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace, key: 'CRW', name: 'CrewScope', status: 'ACTIVE', version: 1, createdAt: '2026-08-27T07:00:00Z', createdByPrincipalId: ids.principal, updatedAt: '2026-08-27T07:00:00Z', updatedByPrincipalId: ids.principal }], nextCursor: null })
    }
    if (request.method() === 'GET' && path.endsWith('/conversations')) return json(route, { items: [], nextCursor: null })
    if (request.method() === 'POST' && path.endsWith('/team-observer/sessions')) {
      expect(request.postData()).toBeNull()
      return json(route, { sessionId: ids.session, observerProfileId: 'team-observer@1', mode: 'READ_ONLY', createdAt: '2026-08-27T08:00:00Z' })
    }
    if (request.method() === 'POST' && path.endsWith(`/sessions/${ids.session}/invocations`)) {
      calls.invoke += 1
      if (options.failFirstInvoke && calls.invoke === 1) {
        // A plain 5xx before any invocation header arrives: the client has no id to resume.
        return route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ code: 'observer_unavailable', message: 'observer_unavailable', correlationId: ids.invocation, retryable: true, currentVersion: null, details: {} }) })
      }
      expect(request.postDataJSON()).toEqual({ instruction: '总结当前团队进展、阻塞、Review、待确认事项和异常，并给出可核验的证据。', maxItemsPerSection: 10 })
      calls.invocationIds.push(ids.invocation)
      return sse(route, [observerEvent('STARTED', 0, null)], { 'X-CrewScope-Invocation-Id': ids.invocation })
    }
    if (request.method() === 'POST' && path.endsWith(`/invocations/${ids.invocation}/resume`)) {
      calls.resume += 1
      expect(request.postData()).toBeNull()
      calls.invocationIds.push(ids.invocation)
      return sse(route, [observerEvent('STARTED', 0, null), observerEvent('SUMMARY_COMPLETED', 1, summary())], {
        'X-CrewScope-Invocation-Id': ids.invocation,
        'X-CrewScope-Stream-Resumed': 'true',
      })
    }
    if (request.method() === 'GET' && path.endsWith(`/invocations/${ids.invocation}/summary`)) {
      calls.summary += 1
      return json(route, summary())
    }
    if (request.method() === 'GET' && path.endsWith(`/evidence/0`)) {
      calls.evidence += 1
      if (calls.evidence <= (options.failEvidenceAttempts ?? 0)) {
        return route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ code: 'evidence_unavailable', message: 'evidence_unavailable', correlationId: ids.invocation, retryable: true, currentVersion: null, details: {} }) })
      }
      return json(route, { evidenceIndex: 0, section: 'PROGRESS', dataScope: 'TEAM_ACTIVITY', summary: '<script>window.promptAttack=true</script>', path: `/api/v1/organizations/${ids.organization}/teams/${ids.team}/activity/${ids.invocation}`, authorized: true })
    }
    if (request.method() === 'GET' && path.endsWith('/activity/snapshot')) {
      return json(route, { items: [], hasMore: false, nextCursor: null, snapshotCursor: null })
    }
    return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 'not_found', message: 'not found', correlationId: ids.invocation, retryable: false, currentVersion: null, details: {} }) })
  })
  return calls
}

function summary() {
  const item = (section: string, summary: string, evidenceIndex: number, dataScope = 'TEAM_ACTIVITY') => ({ section, dataScope, summary, evidenceIndex })
  return {
    observerProfileId: 'team-observer@1', generatedAt: '2026-08-27T08:30:00Z',
    progress: [item('PROGRESS', '<script>window.promptAttack=true</script>', 0)],
    blockers: [item('BLOCKERS', 'MVP 发布演练仍等待恢复验证。', 1)],
    reviewBacklog: [item('REVIEW_BACKLOG', 'CRW-214 等待 Reviewer Agent 复核。', 2, 'WORK_ITEM_SUMMARY')],
    pendingConfirmations: [item('PENDING_CONFIRMATIONS', '生产通知重投需要管理员确认。', 3, 'TEAM_INBOX_SUMMARY')],
    anomalies: [item('ANOMALIES', 'Notification Lag 高于团队基线。', 4)],
  }
}

function observerEvent(type: string, sequence: number, summaryValue: unknown): string {
  return `id: ${sequence}\nevent: ${type}\ndata: ${JSON.stringify({ invocationId: ids.invocation, sequence, occurredAt: '2026-08-27T08:30:00Z', type, summary: summaryValue, errorCode: null })}\n\n`
}
function json(route: Route, value: unknown) { return route.fulfill({ status: 200, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(value) }) }
function sse(route: Route, frames: string[], headers: Record<string, string>) { return route.fulfill({ status: 200, contentType: 'text/event-stream', headers, body: frames.join('') }) }
