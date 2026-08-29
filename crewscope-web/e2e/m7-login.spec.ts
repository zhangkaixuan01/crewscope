import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page, type Route } from '@playwright/test'

type LoginMode = 'success' | 'invalid' | 'limited' | 'pending'

test('submits the formal login once with Enter and restores a safe target', async ({ page }) => {
  let loginCalls = 0
  let submittedBody: Record<string, unknown> | null = null
  await installIdentityApi(page, {
    loginMode: () => 'success',
    onLogin: async route => {
      loginCalls += 1
      submittedBody = route.request().postDataJSON() as Record<string, unknown>
      expect(route.request().headers()['x-xsrf-token']).toBe('csrf-browser-only')
      await new Promise(resolve => setTimeout(resolve, 80))
    },
  })
  const returnTo = encodeURIComponent('/today')

  await page.goto(`/login?returnTo=${returnTo}`)
  const identifier = page.getByRole('textbox', { name: '用户名或邮箱' })
  const password = page.locator('input[name="password"]')
  await expect(identifier).toBeFocused()
  await expect(identifier).toHaveAttribute('autocomplete', 'username')
  await expect(password).toHaveAttribute('autocomplete', 'current-password')
  await identifier.fill('alice@example.com')
  await password.fill('one-way-password')
  await password.press('Enter')
  await password.press('Enter')

  await expect(page).toHaveURL(/\/today$/)
  expect(loginCalls).toBe(1)
  expect(submittedBody).toEqual({ identifier: 'alice@example.com', password: 'one-way-password' })
  expect(page.url()).not.toContain('one-way-password')
  expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length }))).toEqual({ local: 0, session: 0 })
})

test('keeps invalid credentials non-enumerating and presents capacity limits separately', async ({ page }) => {
  let mode: LoginMode = 'invalid'
  await installIdentityApi(page, { loginMode: () => mode })
  await page.goto('/login')
  await page.getByRole('textbox', { name: '用户名或邮箱' }).fill('unknown@example.com')
  await page.locator('input[name="password"]').fill('wrong-password')
  await page.locator('input[name="password"]').press('Enter')

  const firstAlert = page.getByRole('alert')
  await expect(firstAlert).toBeFocused()
  await expect(firstAlert).toContainText('登录信息无效，请检查后重试。')
  await expect(firstAlert).not.toContainText(/账号不存在|密码错误|锁定|private/)
  await expect(page.locator('input[name="password"]')).toHaveValue('')

  mode = 'limited'
  await page.locator('input[name="password"]').fill('another-password')
  await page.locator('input[name="password"]').press('Enter')
  await expect(page.getByRole('alert')).toBeFocused()
  await expect(page.getByRole('alert')).toContainText('请求过于频繁，请稍后再试。')
})

test('redirects an existing Session safely and exposes an accessible offline state', async ({ page, context }) => {
  await installIdentityApi(page, { authenticated: true, loginMode: () => 'success' })
  await page.goto('/login?returnTo=https://attacker.example/work')
  await expect(page).toHaveURL(/\/conversation$/)

  await installIdentityApi(page, { authenticated: false, loginMode: () => 'success' })
  await page.goto('/login')
  await expect(page.getByRole('textbox', { name: '用户名或邮箱' })).toBeFocused()
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  await context.setOffline(true)
  await expect(page.getByRole('alert')).toContainText('当前处于离线状态')
  await expect(page.getByRole('alert')).toBeFocused()
  await expect(page.getByRole('button', { name: '进入 CrewScope' })).toBeDisabled()
  await context.setOffline(false)
})

async function installIdentityApi(
  page: Page,
  options: {
    authenticated?: boolean
    loginMode: () => LoginMode
    onLogin?: (route: Route) => Promise<void>
  },
): Promise<void> {
  let authenticated = Boolean(options.authenticated)
  await page.unroute('**/api/v1/**')
  await page.route('**/api/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/auth/session') {
      await route.fulfill(json(sessionPayload(authenticated)))
      return
    }
    if (path === '/api/v1/auth/login') {
      await options.onLogin?.(route)
      const mode = options.loginMode()
      if (mode === 'pending') return
      if (mode === 'invalid') {
        await route.fulfill(json(errorPayload('invalid_credentials', 'account does not exist: private'), 401))
        return
      }
      if (mode === 'limited') {
        await route.fulfill(json(errorPayload('too_many_requests', 'hash permit private'), 429))
        return
      }
      authenticated = true
      await route.fulfill(json({ authenticated: true, accountId: 'account-1', displayName: 'Alice' }))
      return
    }
    await route.fulfill(json([]))
  })
}

function sessionPayload(authenticated: boolean) {
  return {
    authenticated,
    registrationMode: 'OPEN',
    csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-browser-only' },
    account: authenticated ? {
      accountId: 'account-1', username: 'alice', displayName: 'Alice', platformRole: 'USER', securityVersion: 1, version: 1,
    } : null,
    principal: authenticated ? { principalId: 'principal-1', organizationId: 'org-1' } : null,
    teams: authenticated ? [{ teamId: 'team-1', name: 'Platform', memberId: 'member-1', permissions: ['scope:read'] }] : [],
    permissions: authenticated ? ['scope:read'] : [],
  }
}

function errorPayload(code: string, message: string) {
  return { code, message, correlationId: 'safe-correlation', retryable: code === 'too_many_requests', currentVersion: null, details: {} }
}

function json(body: unknown, status = 200) {
  return { status, contentType: 'application/json', body: JSON.stringify(body) }
}
