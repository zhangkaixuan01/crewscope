import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

test('updates identifier fields with strong version and current-password step-up', async ({ page }) => {
  const fixture = await installAccountApi(page)
  await page.goto('/account')

  await expect(page.getByRole('heading', { name: '身份与安全' })).toBeFocused()
  await expect(page.getByText('alice@example.com')).toBeVisible()
  await page.getByRole('button', { name: '编辑资料' }).click()
  await page.getByRole('textbox', { name: '用户名' }).fill('alice-next')
  await page.locator('input[name="profileCurrentPassword"]').fill('one-way-proof')
  await page.getByRole('button', { name: '保存资料' }).click()

  await expect(page.getByText('alice-next', { exact: true })).toBeVisible()
  expect(fixture.profilePatches).toEqual([{
    body: { username: 'alice-next', currentPassword: 'one-way-proof', securityVersion: 3 },
    ifMatch: '"4"', csrf: 'csrf-account-e2e',
  }])
  expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length }))).toEqual({ local: 0, session: 0 })
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBe(0)
})

test('reloads authoritative account facts after an optimistic version conflict', async ({ page }) => {
  await installAccountApi(page, { conflictFirstPatch: true })
  await page.goto('/account')
  await page.getByRole('button', { name: '编辑资料' }).click()
  await page.getByRole('textbox', { name: '展示名称' }).fill('Local Alice')
  await page.getByRole('button', { name: '保存资料' }).click()

  const alert = page.getByRole('alert')
  await expect(alert).toBeFocused()
  await expect(alert).toContainText('账号资料已在其他位置更新')
  await expect(page.getByText('Remote Alice', { exact: true })).toBeVisible()
  await expect(page.locator('body')).not.toContainText('private version detail')
})

test('changes password once and requires a fresh login after all Sessions are revoked', async ({ page }) => {
  const fixture = await installAccountApi(page)
  await page.goto('/account')
  await page.getByRole('button', { name: '修改密码' }).click()
  await page.locator('input[name="currentPassword"]').fill('current-password')
  await page.locator('input[name="newPassword"]').fill('new-password-value')
  await page.locator('input[name="confirmPassword"]').fill('new-password-value')
  await page.getByRole('button', { name: '修改密码并重新登录' }).click()

  await expect(page).toHaveURL(/\/login/)
  expect(fixture.passwordCommands).toEqual([{
    body: { currentPassword: 'current-password', newPassword: 'new-password-value', securityVersion: 3 },
    ifMatch: '"4"', csrf: 'csrf-account-e2e',
  }])
  expect(page.url()).not.toContain('password')
})

test('confirms all-device revocation with focus containment and no Session coordinate', async ({ page }) => {
  const fixture = await installAccountApi(page)
  await page.goto('/account')
  await page.getByRole('button', { name: '退出全部设备' }).click()

  const dialog = page.getByRole('dialog', { name: '退出全部设备？' })
  await expect(dialog).toContainText('包括当前设备')
  const password = page.locator('input[name="revokeCurrentPassword"]')
  await expect(password).toBeFocused()
  await password.fill('current-password')
  await page.getByRole('button', { name: '确认退出全部设备' }).click()

  await expect(page).toHaveURL(/\/login/)
  expect(fixture.sessionCommands).toEqual([{
    body: { currentPassword: 'current-password', securityVersion: 3 }, ifMatch: '"4"', csrf: 'csrf-account-e2e',
  }])
  expect(JSON.stringify(fixture.sessionCommands)).not.toContain('sessionId')
})

test('opens the AppShell account menu and logs out only the current device', async ({ page }) => {
  const fixture = await installAccountApi(page)
  await page.goto('/account')
  await page.getByRole('button', { name: '账号菜单：Alice' }).click()
  await expect(page.getByRole('menuitem', { name: '账号设置' })).toBeVisible()
  await page.getByRole('menuitem', { name: '退出当前设备' }).click()

  await expect(page).toHaveURL(/\/login/)
  expect(fixture.logouts).toBe(1)
})

test('keeps a failed all-device confirmation inside the dialog and clears its password', async ({ page }) => {
  await installAccountApi(page, { rejectSessionPassword: true })
  await page.goto('/account')
  await page.getByRole('button', { name: '退出全部设备' }).click()
  const password = page.locator('input[name="revokeCurrentPassword"]')
  await password.fill('wrong-password')
  await page.getByRole('button', { name: '确认退出全部设备' }).click()

  const dialog = page.getByRole('dialog')
  const alert = dialog.getByRole('alert')
  await expect(alert).toBeFocused()
  await expect(alert).toContainText('当前密码不正确')
  await expect(password).toHaveValue('')
  await expect(page.locator('body')).not.toContainText('private credential detail')
})

async function installAccountApi(page: Page, options: { conflictFirstPatch?: boolean, rejectSessionPassword?: boolean } = {}) {
  let authenticated = true
  let profile = accountProfile()
  let patchAttempts = 0
  let logouts = 0
  const profilePatches: RequestFact[] = []
  const passwordCommands: RequestFact[] = []
  const sessionCommands: RequestFact[] = []
  await page.route('**/api/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/auth/session') return route.fulfill(json(session(authenticated, profile)))
    if (path === '/api/v1/auth/logout' && request.method() === 'POST') {
      logouts += 1
      authenticated = false
      expect(request.headers()['x-xsrf-token']).toBe('csrf-account-e2e')
      return route.fulfill({ status: 204 })
    }
    if (path === '/api/v1/account' && request.method() === 'GET') {
      return route.fulfill(json(profile, 200, { ETag: `"${profile.version}"` }))
    }
    if (path === '/api/v1/account' && request.method() === 'PATCH') {
      patchAttempts += 1
      profilePatches.push(fact(request))
      if (options.conflictFirstPatch && patchAttempts === 1) {
        profile = { ...profile, displayName: 'Remote Alice', version: 5, updatedAt: '2026-08-29T02:00:00Z' }
        return route.fulfill(json(error('optimistic_lock_conflict', 'private version detail', 5), 409))
      }
      const body = request.postDataJSON() as Record<string, unknown>
      profile = { ...profile, ...publicProfileFields(body), version: profile.version + 1, updatedAt: '2026-08-29T02:00:00Z' }
      return route.fulfill(json(profile, 200, { ETag: `"${profile.version}"` }))
    }
    if (path === '/api/v1/account/password' && request.method() === 'POST') {
      passwordCommands.push(fact(request))
      authenticated = false
      profile = { ...profile, version: profile.version + 1, securityVersion: profile.securityVersion + 1 }
      return route.fulfill({ status: 204, headers: { ETag: `"${profile.version}"` } })
    }
    if (path === '/api/v1/account/sessions/revoke' && request.method() === 'POST') {
      sessionCommands.push(fact(request))
      if (options.rejectSessionPassword) {
        return route.fulfill(json(error('invalid_credentials', 'private credential detail'), 401))
      }
      authenticated = false
      profile = { ...profile, version: profile.version + 1, securityVersion: profile.securityVersion + 1 }
      return route.fulfill({ status: 204, headers: { ETag: `"${profile.version}"` } })
    }
    return route.fulfill(json([]))
  })
  return {
    profilePatches, passwordCommands, sessionCommands,
    get logouts() { return logouts },
  }
}

interface RequestFact { body: Record<string, unknown>, ifMatch: string | undefined, csrf: string | undefined }

function fact(request: import('@playwright/test').Request): RequestFact {
  return { body: request.postDataJSON() as Record<string, unknown>, ifMatch: request.headers()['if-match'], csrf: request.headers()['x-xsrf-token'] }
}

function publicProfileFields(body: Record<string, unknown>) {
  return Object.fromEntries(Object.entries(body).filter(([key]) => ['username', 'email', 'displayName'].includes(key)))
}

function accountProfile() {
  return {
    accountId: 'account-1', username: 'alice', email: 'alice@example.com', displayName: 'Alice',
    status: 'ACTIVE', platformRole: 'USER', securityVersion: 3, version: 4,
    createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-29T00:00:00Z',
  }
}

function session(authenticated: boolean, profile: ReturnType<typeof accountProfile>) {
  return {
    authenticated, registrationMode: 'OPEN',
    csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-account-e2e' },
    account: authenticated ? {
      accountId: profile.accountId, username: profile.username, displayName: profile.displayName,
      platformRole: profile.platformRole, securityVersion: profile.securityVersion, version: profile.version,
    } : null,
    principal: authenticated ? { principalId: 'principal-1', organizationId: 'organization-1' } : null,
    teams: [], permissions: [],
  }
}

function error(code: string, message: string, currentVersion: number | null = null) {
  return { code, message, correlationId: 'correlation-account', retryable: false, currentVersion, details: {} }
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}) {
  return { status, contentType: 'application/json', headers, body: JSON.stringify(body) }
}
