import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page, type Route } from '@playwright/test'
import { authenticatedSession } from './auth-session'

const ids = {
  organization: '00000000-0000-4000-8000-000000000001',
  principal: '00000000-0000-4000-8000-000000000101',
  team: '00000000-0000-4000-8000-000000000201',
  member: '00000000-0000-4000-8000-000000000301',
  workspace: '00000000-0000-4000-8000-000000000501',
}

test('creates the first Team and exposes the verified Personal Agent', async ({ page }) => {
  const fixture = await installOnboardingApi(page)
  await page.goto('/onboarding')

  const teamName = page.getByRole('textbox', { name: '团队名称' })
  await expect(teamName).toBeFocused()
  await expect(page.getByLabel('将要创建的内容')).toContainText('Personal Agent')
  await teamName.fill('Platform Engineering')
  await page.getByRole('button', { name: '创建团队' }).click()

  await expect(page.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeFocused()
  await expect(page.getByLabel('已完成的初始化')).toContainText('张凯旋的 Personal Agent')
  expect(fixture.posts).toBe(1)
  expect(fixture.keys).toHaveLength(1)
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBe(0)

  await page.getByRole('button', { name: '进入团队对话' }).click()
  await expect(page).toHaveURL(new RegExp(`/conversation\\?team=${ids.team}$`))
})

test('skips first-Team onboarding for an account that already belongs to a Team', async ({ page }) => {
  const fixture = await installOnboardingApi(page, { initiallyComplete: true })

  await page.goto('/onboarding')

  await expect(page).toHaveURL(new RegExp(`/conversation\\?team=${ids.team}$`))
  await expect(page.getByRole('heading', { name: '团队对话' })).toBeVisible()
  expect(fixture.posts).toBe(0)
})

test('replays the same creation intent after an unavailable response', async ({ page }) => {
  const fixture = await installOnboardingApi(page, { failFirstPost: true })
  await page.goto('/onboarding')
  await page.getByRole('textbox', { name: '团队名称' }).fill('Platform Engineering')
  await page.getByRole('button', { name: '创建团队' }).click()

  const alert = page.getByRole('alert')
  await expect(alert).toContainText('团队初始化暂时不可用')
  await expect(alert).toBeFocused()
  await expect(page.locator('body')).not.toContainText('private worker details')
  await page.getByRole('button', { name: '安全重试' }).click()

  await expect(page.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()
  expect(fixture.posts).toBe(2)
  expect(fixture.keys[0]).toBe(fixture.keys[1])
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
})

async function installOnboardingApi(
  page: Page,
  options: { initiallyComplete?: boolean, failFirstPost?: boolean } = {},
) {
  let teamCreated = Boolean(options.initiallyComplete)
  let posts = 0
  const keys: string[] = []
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url())
    const path = url.pathname
    if (path === '/api/v1/auth/session') {
      return route.fulfill(json(authenticatedSession(
        ids.organization,
        ids.principal,
        teamCreated ? ids.team : null,
      )))
    }
    if (path === '/api/v1/onboarding' && route.request().method() === 'GET') {
      return route.fulfill(json(teamCreated
        ? { state: 'COMPLETE', onboardingRequired: false, activeTeamCount: 1 }
        : { state: 'TEAM_REQUIRED', onboardingRequired: true, activeTeamCount: 0 }))
    }
    if (path === '/api/v1/onboarding/team' && route.request().method() === 'POST') {
      posts += 1
      keys.push(route.request().headers()['idempotency-key'] ?? '')
      expect(route.request().headers()['x-xsrf-token']).toBe('csrf-e2e-session')
      expect(route.request().postDataJSON()).toEqual({ name: 'Platform Engineering' })
      if (options.failFirstPost && posts === 1) {
        return route.fulfill(json({
          code: 'onboarding_unavailable', message: 'private worker details', correlationId: 'corr-onboarding',
          retryable: true, currentVersion: null, details: {},
        }, 503))
      }
      teamCreated = true
      return route.fulfill(json({
        commandId: 'command-1', domainEventId: 'event-1', committedVersion: 0, correlationId: 'correlation-1',
      }, 202))
    }
    if (path.endsWith('/teams')) return route.fulfill(json([team()]))
    if (path.endsWith('/work-projects')) return route.fulfill(json({ items: [], nextCursor: null }))
    if (path.endsWith('/agent-profiles')) return route.fulfill(json({ items: [personalAgent()] }))
    if (path.includes('/conversations')) return route.fulfill(json({ items: [], nextCursor: null }))
    return route.fulfill(json([]))
  })
  return {
    get posts() { return posts },
    keys,
  }
}

function team() {
  return {
    id: ids.team, organizationId: ids.organization, name: 'Platform Engineering', status: 'ACTIVE',
    initializationStatus: 'READY', ownerMemberId: ids.member, defaultWorkspaceId: ids.workspace, version: 0,
  }
}

function personalAgent() {
  return {
    id: 'agent-1', principalId: 'agent-principal-1', displayName: '张凯旋的 Personal Agent',
    principalStatus: 'ACTIVE', organizationId: ids.organization, teamId: ids.team,
    workspaceId: ids.workspace, ownershipType: 'USER', ownerMemberId: ids.member,
    runtimeRole: 'PERSONAL', templateKey: 'personal-agent', templateVersion: 1,
    defaultProfile: true, status: 'ACTIVE', currentConfigurationRevision: 1,
    currentConfigurationHash: 'a'.repeat(64), createdAt: '2026-08-29T01:00:00Z',
    updatedAt: '2026-08-29T01:00:00Z', version: 0,
  }
}

function json(body: unknown, status = 200) {
  return { status, contentType: 'application/json', body: JSON.stringify(body) }
}
