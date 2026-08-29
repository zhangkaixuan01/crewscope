import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page, type Route } from '@playwright/test'

type RegistrationMode = 'OPEN' | 'INVITE_ONLY' | 'DISABLED'
type RegistrationBehavior = 'success' | 'conflict'

test('creates an open account once and enters Onboarding', async ({ page }) => {
  let calls = 0
  let submittedBody: Record<string, unknown> | null = null
  await installRegistrationApi(page, {
    registrationMode: () => 'OPEN',
    behavior: () => 'success',
    onRegister: async route => {
      calls += 1
      submittedBody = route.request().postDataJSON() as Record<string, unknown>
      expect(route.request().headers()['x-xsrf-token']).toBe('csrf-browser-only')
      expect(route.request().headers()['idempotency-key']).toBeTruthy()
      await new Promise(resolve => setTimeout(resolve, 80))
    },
  })

  await page.goto('/register')
  await expect(page.getByRole('textbox', { name: '用户名' })).toBeFocused()
  await expect(page.locator('input[name="password"]')).toHaveAttribute('autocomplete', 'new-password')
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
  await fillRegistration(page)
  await page.locator('input[name="password"]').press('Enter')
  await page.locator('input[name="password"]').press('Enter')

  await expect(page).toHaveURL(/\/onboarding$/)
  expect(calls).toBe(1)
  expect(submittedBody).toEqual({
    username: 'alice', email: 'alice@example.com', displayName: 'Alice', password: 'correct horse battery staple',
  })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length }))).toEqual({ local: 0, session: 0 })
})

test('consumes an invitation Fragment in memory and joins the Team without Onboarding', async ({ page }) => {
  const token = 'A'.repeat(43)
  let submittedBody: Record<string, unknown> | null = null
  await installRegistrationApi(page, {
    registrationMode: () => 'INVITE_ONLY',
    behavior: () => 'success',
    invited: true,
    onRegister: async route => { submittedBody = route.request().postDataJSON() as Record<string, unknown> },
  })

  await page.goto(`/register#token=${token}`)
  await expect(page).toHaveURL(/\/register$/)
  await expect(page.getByText('已安全载入团队邀请')).toBeVisible()
  await expect(page.locator('body')).not.toContainText(token)
  await fillRegistration(page)
  await page.getByRole('button', { name: '创建账号并加入团队' }).click()

  await expect(page).toHaveURL(/\/conversation$/)
  expect(submittedBody).toEqual({
    username: 'alice', email: 'alice@example.com', displayName: 'Alice',
    password: 'correct horse battery staple', invitationToken: token,
  })
  expect(page.url()).not.toContain(token)
})

test('keeps registration conflicts non-enumerating and fails closed for unavailable modes', async ({ page }) => {
  let mode: RegistrationMode = 'OPEN'
  await installRegistrationApi(page, {
    registrationMode: () => mode,
    behavior: () => 'conflict',
  })

  await page.goto('/register')
  await fillRegistration(page)
  await page.getByRole('button', { name: '创建账号', exact: true }).click()
  const alert = page.getByRole('alert')
  await expect(alert).toBeFocused()
  await expect(alert).toContainText('用户名或邮箱暂不可用，请修改后重试。')
  await expect(alert).not.toContainText(/alice@example.com|已存在|private/)

  mode = 'INVITE_ONLY'
  await page.reload()
  await expect(page.getByRole('heading', { name: '通过团队邀请加入 CrewScope' })).toBeFocused()
  await expect(page.locator('form')).toHaveCount(0)

  mode = 'DISABLED'
  await page.reload()
  await expect(page.getByRole('heading', { name: '当前部署未开放新账号' })).toBeFocused()
  await expect(page.locator('form')).toHaveCount(0)
})

async function fillRegistration(page: Page): Promise<void> {
  await page.getByRole('textbox', { name: '用户名' }).fill('alice')
  await page.getByRole('textbox', { name: '邮箱' }).fill('alice@example.com')
  await page.getByRole('textbox', { name: '展示名' }).fill('Alice')
  await page.locator('input[name="password"]').fill('correct horse battery staple')
}

async function installRegistrationApi(
  page: Page,
  options: {
    registrationMode: () => RegistrationMode
    behavior: () => RegistrationBehavior
    invited?: boolean
    onRegister?: (route: Route) => Promise<void>
  },
): Promise<void> {
  let authenticated = false
  await page.route('**/api/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/auth/session') {
      await route.fulfill(json(sessionPayload(options.registrationMode(), authenticated)))
      return
    }
    if (path === '/api/v1/auth/register') {
      await options.onRegister?.(route)
      if (options.behavior() === 'conflict') {
        await route.fulfill(json(errorPayload('registration_conflict', 'email alice@example.com already exists: private'), 409))
        return
      }
      authenticated = true
      await route.fulfill(json(registrationPayload(Boolean(options.invited)), 201))
      return
    }
    await route.fulfill(json([]))
  })
}

function sessionPayload(registrationMode: RegistrationMode, authenticated: boolean) {
  return {
    authenticated,
    registrationMode,
    csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-browser-only' },
    account: authenticated ? {
      accountId: 'account-1', username: 'alice', displayName: 'Alice', platformRole: 'USER', securityVersion: 1, version: 1,
    } : null,
    principal: authenticated ? { principalId: 'principal-1', organizationId: 'organization-1' } : null,
    teams: authenticated ? [{ teamId: 'team-1', name: 'Platform', memberId: 'member-1', permissions: ['scope:read', 'conversation:use'] }] : [],
    permissions: authenticated ? ['scope:read', 'conversation:use'] : [],
  }
}

function registrationPayload(invited: boolean) {
  return {
    accountId: 'account-1', principalId: 'principal-1', organizationId: 'organization-1',
    teamId: invited ? 'team-1' : null, memberId: invited ? 'member-1' : null,
    onboardingRequired: !invited, commandId: 'command-1', domainEventId: 'event-1',
    committedVersion: 1, correlationId: 'correlation-1', replayed: false,
  }
}

function errorPayload(code: string, message: string) {
  return { code, message, correlationId: 'safe-correlation', retryable: false, currentVersion: null, details: {} }
}

function json(body: unknown, status = 200) {
  return { status, contentType: 'application/json', body: JSON.stringify(body) }
}
