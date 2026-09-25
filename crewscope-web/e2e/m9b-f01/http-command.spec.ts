import { readFile } from 'node:fs/promises'
import { resolve, sep, extname } from 'node:path'
import { expect, test, type Page } from '@playwright/test'

// A reserved non-loopback HTTP origin: Chromium must not grant localhost's secure-context exception.
const origin = 'http://crewscope-http.test'
const dist = resolve('dist')

async function isolatedApp(page: Page) {
  const keys: Array<string | undefined> = []
  const unexpected: string[] = []
  await page.route('**/*', async route => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.origin !== origin) { unexpected.push(url.origin); return route.abort() }
    if (url.pathname === '/api/v1/auth/session' && request.method() === 'GET') {
      return route.fulfill({ json: {
        authenticated: false, registrationMode: 'OPEN', account: null, principal: null, teams: [], permissions: [],
        csrf: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'isolated-fixture' },
      } })
    }
    if (url.pathname === '/api/v1/auth/register' && request.method() === 'POST') {
      keys.push(request.headers()['idempotency-key'])
      return route.fulfill({ status: 503, json: {
        code: 'service_unavailable', message: 'fixture upstream unavailable', correlationId: 'fixture',
        retryable: true, currentVersion: null, details: {},
      } })
    }
    if (url.pathname.startsWith('/api/')) { unexpected.push(url.pathname); return route.abort() }
    const file = resolve(dist, `.${url.pathname === '/register' ? '/index.html' : url.pathname}`)
    if (!file.startsWith(`${dist}${sep}`)) return route.abort()
    const mime: Record<string, string> = {
      '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css',
      '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.png': 'image/png', '.ico': 'image/x-icon',
    }
    try { return route.fulfill({ body: await readFile(file), contentType: mime[extname(file)] ?? 'application/octet-stream' }) }
    catch { unexpected.push(url.pathname); return route.abort() }
  })
  await page.goto(`${origin}/register`)
  await page.locator('input[name="username"]').fill('fixture-user')
  await page.locator('input[name="email"]').fill('fixture@example.test')
  await page.locator('input[name="displayName"]').fill('Fixture User')
  await page.locator('input[name="password"]').fill('isolated test password only')
  return { keys, unexpected }
}

test('ordinary HTTP registers with secure random bytes and retains the key after 503', async ({ page }) => {
  const { keys, unexpected } = await isolatedApp(page)
  expect(await page.evaluate(() => ({ secure: isSecureContext, uuid: typeof crypto.randomUUID, bytes: typeof crypto.getRandomValues })))
    .toEqual({ secure: false, uuid: 'undefined', bytes: 'function' })
  await page.locator('button[type="submit"]').click()
  await expect(page.getByRole('alert')).toContainText('注册结果尚未确认')
  await page.locator('button[type="submit"]').click()
  await expect.poll(() => keys.length).toBe(2)
  expect(keys[0]).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  expect(keys[1]).toBe(keys[0])
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0])
  expect(unexpected).toEqual([])
})

test('no secure random source gives a nearby error and sends no registration', async ({ page }) => {
  await page.addInitScript(() => Object.defineProperty(window, 'crypto', { configurable: true, value: undefined }))
  const { keys, unexpected } = await isolatedApp(page)
  await page.locator('button[type="submit"]').click()
  await expect(page.getByRole('alert')).toContainText('本次操作尚未发送')
  expect(keys).toEqual([])
  expect(unexpected).toEqual([])
})
