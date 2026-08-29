import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page, type Route } from '@playwright/test'
import { authenticatedSession } from './auth-session'

const ids = {
  organization: '00000000-0000-4000-8000-000000000001',
  principal: '00000000-0000-4000-8000-000000000101',
  team: '00000000-0000-4000-8000-000000000201',
}

test('holds the first protected screen behind Session recovery without a workspace flash', async ({ page }) => {
  let release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/v1/auth/session') {
      await gate
      return route.fulfill(json(authenticatedSession(ids.organization, ids.principal, ids.team)))
    }
    return route.fulfill(json([]))
  })

  await page.goto('/conversation')
  await expect(page.getByRole('heading', { name: '正在确认你的会话' })).toBeFocused()
  await expect(page.locator('.app-shell')).toHaveCount(0)
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  release()
  await expect(page.locator('.app-shell')).toBeVisible()
  await expect(page.getByRole('heading', { name: '团队对话' })).toBeVisible()
})

test('restores an anonymous Session and preserves the protected return target', async ({ page }) => {
  await installSessionApi(page, () => anonymousSession())

  await page.goto('/today?team=team-1')

  await expectLoginReturnTo(page, '/today?team=team-1')
  await expect(page.getByRole('textbox', { name: '用户名或邮箱' })).toBeFocused()
  await expect(page.locator('.app-shell')).toHaveCount(0)
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
})

test('uses restored permissions and follows a cross-tab sign-out', async ({ page }) => {
  let authenticated = true
  const session = authenticatedSession(ids.organization, ids.principal, ids.team)
  session.permissions = session.permissions.filter(permission => permission !== 'audit:read')
  session.teams[0]!.permissions = [...session.permissions]
  await installSessionApi(page, () => authenticated ? session : anonymousSession())

  await page.goto('/audit')
  await expect(page).toHaveURL(/\/access-denied\?from=\/audit$/)
  await expect(page.getByRole('heading', { name: '当前账号无法访问这个区域' })).toBeVisible()

  authenticated = false
  await page.evaluate(() => {
    const channel = new BroadcastChannel('crewscope-auth')
    channel.postMessage({ type: 'signed-out' })
    channel.close()
  })

  await expectLoginReturnTo(page, '/access-denied?from=/audit')
  await expect(page.getByRole('textbox', { name: '用户名或邮箱' })).toBeFocused()
})

test('turns a business API 401 into one global Session recovery', async ({ page }) => {
  let sessionCalls = 0
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/v1/auth/session') {
      sessionCalls += 1
      return route.fulfill(json(sessionCalls === 1
        ? authenticatedSession(ids.organization, ids.principal, ids.team)
        : anonymousSession()))
    }
    if (path.endsWith('/teams')) {
      return route.fulfill(json({
        code: 'authentication_required', message: 'Session expired private', correlationId: 'corr-401',
        retryable: false, currentVersion: null, details: {},
      }, 401))
    }
    return route.fulfill(json([]))
  })

  await page.goto('/conversation')

  await expectLoginReturnTo(page, '/conversation')
  await expect(page.getByRole('textbox', { name: '用户名或邮箱' })).toBeFocused()
  expect(sessionCalls).toBe(2)
  await expect(page.locator('body')).not.toContainText('Session expired private')
})

async function installSessionApi(page: Page, session: () => ReturnType<typeof anonymousSession> | ReturnType<typeof authenticatedSession>): Promise<void> {
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/v1/auth/session') return route.fulfill(json(session()))
    if (path.endsWith('/teams')) return route.fulfill(json([]))
    return route.fulfill(json([]))
  })
}

function anonymousSession() {
  return {
    authenticated: false,
    registrationMode: 'OPEN' as const,
    csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-anonymous-e2e' },
    account: null,
    principal: null,
    teams: [],
    permissions: [],
  }
}

function json(body: unknown, status = 200) {
  return { status, contentType: 'application/json', body: JSON.stringify(body) }
}

async function expectLoginReturnTo(page: Page, returnTo: string): Promise<void> {
  await expect(page).toHaveURL(/\/login\?returnTo=/)
  await expect.poll(() => page.evaluate(() => new URL(window.location.href).searchParams.get('returnTo'))).toBe(returnTo)
}
