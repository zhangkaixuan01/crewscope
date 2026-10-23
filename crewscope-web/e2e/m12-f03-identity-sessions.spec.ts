import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page, type Route } from '@playwright/test'

/**
 * M12-F03 mock-layer e2e for the two identity surfaces the backend shipped in I01:
 *
 * 1. **Login page policy surface** — with an OIDC registration the SSO entry renders beside the
 *    local form (coexist); under sso-only the local form disappears and a rescue line replaces
 *    the registration footer. Both stay axe-clean.
 * 2. **sso_error return banners** — the fixed four-code enum from the backend redirect maps to
 *    localized copy; the code leaves the address bar and an unknown value never reaches the DOM
 *    (folded into the generic denial — the full four-code enumeration is pinned in the vitest
 *    suite m12-oidc-login.spec.ts; here we exercise the browser plumbing: query → banner →
 *    cleaned URL).
 * 3. **local_login_disabled** — a 403 login verdict steers to the SSO entry without echoing the
 *    server's English message.
 * 4. **SSO return restore (M13b-F07/R09)** — the provider hop lands on `/`; a whitelisted
 *    destination parked in sessionStorage is consumed there exactly once, then normal routing
 *    resumes. A missing SSO entry under sso-only explains itself with an operable recheck
 *    (R44) instead of a dead end.
 * 5. **Account session list** — GET /auth/sessions renders rows with the current-device badge
 *    and the login identities through localized labels (raw enums never leak); revoking another
 *    device sends DELETE with the CSRF header and refreshes the list; a 404 folds into a
 *    refreshed notice; deleting the current session is a logout equivalent routed to /login.
 */
type Policy = 'local-only' | 'sso-only' | 'coexist'

const SSO_HREF = '/oauth2/authorization/team-beta'

interface SessionFact {
  sessionId: string
  createdAt: string
  lastAccessedAt: string
  /** Mirrors the API's derived "browser · OS" summary (M13b-A02); absent rows render 未知设备. */
  device?: string
  current: boolean
}

test('offers the SSO entry beside a working local form under coexist', async ({ page }) => {
  let authenticated = false
  let logins = 0
  await installIdentityApi(page, {
    policy: 'coexist',
    session: () => sessionPayload({ authenticated, ssoHref: SSO_HREF }),
    onLogin: async route => {
      logins += 1
      authenticated = true
      await route.fulfill(json({ authenticated: true, accountId: 'account-1', displayName: 'Alice' }))
    },
  })

  await page.goto(`/login?returnTo=${encodeURIComponent('/today')}`)
  // M13b-F07（R48）：入口 URL 里的 registrationId 是部署内唯一可区分的 provider 名字。
  const entry = page.getByRole('link', { name: '使用 team-beta 企业单点登录进入' })
  await expect(entry).toHaveAttribute('href', SSO_HREF)
  await expect(page.getByText('或使用账号密码')).toBeVisible()
  // Coexist means both doors work: the local submission still reaches the work desk.
  await page.getByRole('textbox', { name: '用户名或邮箱' }).fill('alice@example.com')
  await page.locator('input[name="password"]').fill('one-way-password')
  await page.locator('input[name="password"]').press('Enter')

  await expect(page).toHaveURL(/\/today$/)
  expect(logins).toBe(1)
})

test('hides the local form under sso-only and keeps the rescue line accessible', async ({ page }) => {
  await installIdentityApi(page, {
    policy: 'sso-only',
    session: () => sessionPayload({ policy: 'sso-only', ssoHref: SSO_HREF }),
  })

  await page.goto('/login')
  await expect(page.getByRole('link', { name: '使用 team-beta 企业单点登录进入' })).toBeVisible()
  await expect(page.locator('input[name="password"]')).toHaveCount(0)
  await expect(page.getByText('创建账号')).toHaveCount(0)
  await expect(page.getByText('无法完成单点登录？请联系你的管理员恢复访问。')).toBeVisible()
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
})

test('renders a localized banner for a returned sso_error and clears the code from the URL', async ({ page }) => {
  await installIdentityApi(page, {
    policy: 'coexist',
    session: () => sessionPayload({ authenticated: false, ssoHref: null }),
  })

  // `locked` is one of the four fixed codes; an unknown or hostile value folds into the generic
  // denial copy and its raw text never reaches the DOM.
  await page.goto(`/login?sso_error=${encodeURIComponent('locked')}`)
  const banner = page.getByRole('alert')
  await expect(banner).toContainText('这个账号已被登录防护暂时锁定')
  await expect(page).toHaveURL(/\/login$/)
  expect(page.url()).not.toContain('locked')

  await page.goto(`/login?sso_error=${encodeURIComponent('<script>alert(1)</script>')}`)
  await expect(page.getByRole('alert')).toContainText('身份提供方没有认可这次登录')
  const body = await page.locator('body').textContent()
  expect(body).not.toContain('<script>')
})

test('steers a local_login_disabled verdict to the SSO entry without echoing server copy', async ({ page }) => {
  await installIdentityApi(page, {
    policy: 'coexist',
    session: () => sessionPayload({ authenticated: false, ssoHref: SSO_HREF }),
    loginStatus: () => 403,
  })

  await page.goto('/login')
  await page.getByRole('textbox', { name: '用户名或邮箱' }).fill('alice@example.com')
  await page.locator('input[name="password"]').fill('one-way-password')
  await page.locator('input[name="password"]').press('Enter')

  await expect(page.getByRole('alert')).toBeFocused()
  await expect(page.getByRole('alert')).toContainText('账号密码登录已停用')
  await expect(page.getByRole('alert')).toContainText('请使用单点登录入口进入')
  await expect(page.getByRole('alert')).not.toContainText(/local login disabled|deployment policy/)
  await expect(page.getByRole('link', { name: '使用 team-beta 企业单点登录进入' })).toBeVisible()
})

// M13b-F07（R09）：SSO 回跳由后端固定落在 /，query returnTo 活不过 IdP 往返——白名单
// 目的地由 sessionStorage 承载，守卫在 / → today 的回跳特征处消费并放行到原目标；
// 消费即清，下一次访问 / 不再重放。
test('restores the parked SSO destination when the provider hop lands on /', async ({ page }) => {
  await installIdentityApi(page, {
    policy: 'coexist',
    authenticated: true,
    session: () => sessionPayload({ authenticated: true }),
  })
  await page.addInitScript(record => {
    // addInitScript replays on every navigation; the guard's real input is a record parked
    // once by the login page, so a localStorage sentinel keeps the second landing clean.
    if (localStorage.getItem('e2e-sso-return-seeded') === '1') return
    localStorage.setItem('e2e-sso-return-seeded', '1')
    sessionStorage.setItem('crewscope:sso-return:v1', JSON.stringify(record))
  }, { path: '/account', principalId: null, savedAt: Date.now() })

  await page.goto('/')
  await expect(page).toHaveURL(/\/account$/)
  expect(await page.evaluate(() => sessionStorage.getItem('crewscope:sso-return:v1'))).toBeNull()

  // The record is spent: a fresh landing on / goes through the normal first-screen ruling.
  await page.goto('/')
  await expect(page).toHaveURL(/\/today$/)
})

// M13b-F07（R44）：sso-only 且入口未配置是部署状态而非用户死端——说明缺失原因并保留
// 可操作的重新检查，而不是渲染零可操作元素。
test('explains the missing SSO entry under sso-only and offers a recheck', async ({ page }) => {
  let sessionReads = 0
  await installIdentityApi(page, {
    policy: 'sso-only',
    session: () => {
      sessionReads += 1
      return sessionPayload({ policy: 'sso-only', ssoHref: null })
    },
  })

  await page.goto('/login')
  await expect(page.getByText('本部署仅开放单点登录，但登录入口尚未配置。')).toBeVisible()
  await expect(page.locator('input[name="password"]')).toHaveCount(0)
  await expect(page.locator('a[href^="/oauth2/authorization/"]')).toHaveCount(0)
  const recheck = page.getByRole('button', { name: '重新检查' })
  await expect(recheck).toBeEnabled()
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  const readsAfterLoad = sessionReads
  await recheck.click()
  await expect(page.getByText('本部署仅开放单点登录，但登录入口尚未配置。')).toBeVisible()
  expect(sessionReads).toBeGreaterThan(readsAfterLoad)
})

test('lists browser sessions and login identities through localized labels', async ({ page }) => {
  await installIdentityApi(page, { policy: 'coexist', authenticated: true, sessions: twoSessions() })

  await page.goto('/account#sessions')
  // Rows carry the server-derived device summary; the gateway's fallback maps a row without
  // one onto the unknown-device label.
  await expect(page.getByRole('table').filter({ hasText: 'Chrome · macOS' })).toBeVisible()
  await expect(page.getByTestId('session-row-current')).toContainText('本机')
  await expect(page.getByTestId('session-row-other')).toContainText('未知设备')
  // Login identities render localized provider and status labels — the raw enum never leaks.
  await expect(page.getByTestId('login-identity-local')).toContainText('账号密码')
  await expect(page.getByTestId('login-identity-local')).toContainText('可用')
  await expect(page.getByTestId('login-identity-oidc/team-beta')).toContainText('企业单点登录')
  expect(await page.locator('#sessions').textContent()).not.toContain('ACTIVE')
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
})

test('revokes another device with the CSRF header and refreshes the list', async ({ page }) => {
  const deletes: { path: string, csrf: string | undefined }[] = []
  let sessionReads = 0
  await installIdentityApi(page, {
    policy: 'coexist',
    authenticated: true,
    sessions: twoSessions(),
    onSessionsRead: () => {
      sessionReads += 1
    },
    onDelete: async (route, path) => {
      deletes.push({ path, csrf: route.request().headers()['x-xsrf-token'] })
      await route.fulfill({ status: 204 })
    },
  })

  await page.goto('/account#sessions')
  await page.getByTestId('session-row-other').getByRole('button', { name: '撤销' }).click()

  await expect(page.getByTestId('session-row-other')).toHaveCount(0)
  expect(deletes).toEqual([{ path: '/api/v1/auth/sessions/session-other', csrf: 'csrf-f03-e2e' }])
  expect(sessionReads).toBe(2)
})

test('deleting the current session is a logout equivalent routed to the login page', async ({ page }) => {
  let authenticated = true
  await installIdentityApi(page, {
    policy: 'coexist',
    authenticated: true,
    sessions: twoSessions(),
    session: () => sessionPayload({ authenticated }),
    onDelete: async route => {
      authenticated = false
      await route.fulfill({ status: 204 })
    },
  })

  await page.goto('/account#sessions')
  await page.getByTestId('session-row-current').getByRole('button', { name: '退出本设备' }).click()
  // M12b-F02：退出本设备确认为危险确认（role=alertdialog），初始焦点合同不变（安全侧）。
  const dialog = page.getByRole('alertdialog')
  await expect(dialog).toContainText('退出本设备？')
  await dialog.getByRole('button', { name: '退出本设备', exact: true }).click()

  await expect(page).toHaveURL(/\/login$/)
  await expect(dialog).toHaveCount(0)
})

test('folds a vanished session 404 into a refreshed notice', async ({ page }) => {
  let sessionReads = 0
  await installIdentityApi(page, {
    policy: 'coexist',
    authenticated: true,
    sessions: twoSessions(),
    onSessionsRead: () => {
      sessionReads += 1
    },
    deleteStatus: 404,
  })

  await page.goto('/account#sessions')
  await page.getByTestId('session-row-other').getByRole('button', { name: '撤销' }).click()

  await expect(page.getByText('该会话已经不存在，列表已刷新。')).toBeVisible()
  expect(sessionReads).toBe(2)
})

/** The other-device row deliberately lacks `device` — pre-M13b-A02 rows render the fallback. */
function twoSessions(): SessionFact[] {
  return [
    { sessionId: 'session-current', createdAt: '2026-10-15T08:00:00Z', lastAccessedAt: '2026-10-15T09:30:00Z', device: 'Chrome · macOS', current: true },
    { sessionId: 'session-other', createdAt: '2026-10-14T08:00:00Z', lastAccessedAt: '2026-10-14T20:00:00Z', current: false },
  ]
}

/** The shapes the M12-I01 contract answers for the identity surface; anonymous by default. */
function sessionPayload(options: { authenticated?: boolean, ssoHref?: string | null, policy?: Policy }) {
  const authenticated = options.authenticated ?? false
  const policy = options.policy ?? 'coexist'
  const ssoHref = options.ssoHref === undefined ? (policy === 'local-only' ? null : SSO_HREF) : options.ssoHref
  return {
    authenticated,
    registrationMode: 'OPEN' as const,
    identityPolicy: policy,
    ssoAuthorizationUrl: authenticated ? null : ssoHref,
    csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-f03-e2e' },
    account: authenticated ? {
      accountId: 'account-1', username: 'alice', displayName: 'Alice', platformRole: 'USER' as const,
      securityVersion: 1, version: 1,
      loginIdentities: [
        { provider: 'local', status: 'ACTIVE', lastAuthenticatedAt: '2026-10-15T08:00:00Z' },
        { provider: 'oidc/team-beta', status: 'ACTIVE', lastAuthenticatedAt: null },
      ],
    } : null,
    principal: authenticated ? { principalId: 'principal-1', organizationId: 'org-1' } : null,
    organization: null,
    teams: [],
    permissions: authenticated ? ['scope:read'] : [],
  }
}

async function installIdentityApi(page: Page, options: {
  policy?: Policy
  /** Signed-in account pages need an authenticated projection; anonymous (login page) by default. */
  authenticated?: boolean
  /** Overrides the default payload; used where the session flips mid-test (login, sign-out). */
  session?: () => Record<string, unknown>
  sessions?: SessionFact[]
  loginStatus?: () => number
  onLogin?: (route: Route) => Promise<void>
  onDelete?: (route: Route, path: string) => Promise<void>
  deleteStatus?: number
  onSessionsRead?: () => void
}): Promise<void> {
  const policy = options.policy ?? 'coexist'
  // The list is live state: a successful delete removes the row so the post-revoke refresh
  // answers with one fewer session — the page reloads rather than mutating its projection.
  let liveSessions = [...(options.sessions ?? [])]
  await page.route('**/api/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/auth/session') {
      const payload = options.session ? options.session() : sessionPayload({ policy, authenticated: options.authenticated })
      await route.fulfill(json(payload))
      return
    }
    if (path === '/api/v1/auth/login' && request.method() === 'POST') {
      if (options.onLogin) {
        await options.onLogin(route)
        return
      }
      const status = options.loginStatus?.() ?? 200
      if (status === 403) {
        await route.fulfill(json(errorPayload('local_login_disabled', 'local login disabled: deployment policy'), 403))
        return
      }
      await route.fulfill(json({ authenticated: true, accountId: 'account-1', displayName: 'Alice' }))
      return
    }
    if (path === '/api/v1/auth/sessions' && request.method() === 'GET') {
      options.onSessionsRead?.()
      await route.fulfill(json({ sessions: liveSessions }))
      return
    }
    if (/^\/api\/v1\/auth\/sessions\/[^/]+$/.test(path) && request.method() === 'DELETE') {
      const sessionId = path.split('/').pop()!
      if (options.onDelete) {
        await options.onDelete(route, path)
        liveSessions = liveSessions.filter(session => session.sessionId !== sessionId)
        return
      }
      if (options.deleteStatus === 404) {
        await route.fulfill(json(errorPayload('session_not_found', 'session ended elsewhere: private'), 404))
        return
      }
      await route.fulfill({ status: 204 })
      liveSessions = liveSessions.filter(session => session.sessionId !== sessionId)
      return
    }
    if (path === '/api/v1/account' && request.method() === 'GET') {
      await route.fulfill(json(accountProfile(), 200, { ETag: '"4"' }))
      return
    }
    await route.fulfill(json([]))
  })
}

function accountProfile() {
  return {
    accountId: 'account-1', username: 'alice', email: 'alice@example.com', displayName: 'Alice',
    status: 'ACTIVE', platformRole: 'USER', securityVersion: 3, version: 4,
    createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-29T00:00:00Z',
  }
}

function errorPayload(code: string, message: string) {
  return { code, message, correlationId: 'safe-correlation', retryable: false, currentVersion: null, details: {} }
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}) {
  return { status, contentType: 'application/json', headers, body: JSON.stringify(body) }
}
