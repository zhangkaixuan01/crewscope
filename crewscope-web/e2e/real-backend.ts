import { execFileSync } from 'node:child_process'
import { expect, type BrowserContext, type Page } from '@playwright/test'

/**
 * Shared plumbing for the real-backend specs (M7-Q03 and M9b-Q01): account factory, session
 * facts, and CSRF+idempotent command posts against a production Compose stack. No Vite server
 * and no HTTP mocks are involved — every request hits the real API.
 *
 * A gate script owns the stack and exports its coordinates through `CREWSCOPE_REAL_*`; the
 * M7-Q03 gate predates that convention and only exports `CREWSCOPE_Q03_*`, so it stays the
 * fallback (its port 18080 is also the local default).
 */

export type Session = {
  authenticated: boolean
  csrf: { headerName: string, token: string }
  account: { accountId: string, username: string } | null
  principal: { principalId: string, organizationId: string } | null
  teams: Array<{ teamId: string, memberId: string, permissions: string[] }>
}

export type AgentPage = {
  items: Array<{ id: string, principalId: string, ownerMemberId: string | null, defaultProfile: boolean }>
}

export const baseURL = process.env.CREWSCOPE_REAL_BASE_URL
  ?? process.env.CREWSCOPE_Q03_BASE_URL
  ?? 'http://127.0.0.1:18080'

const apiContainer = process.env.CREWSCOPE_REAL_API_CONTAINER
  ?? process.env.CREWSCOPE_Q03_API_CONTAINER
  ?? 'crewscope-m7-q03-api-1'

const redisContainer = process.env.CREWSCOPE_REAL_REDIS_CONTAINER
  ?? process.env.CREWSCOPE_Q03_REDIS_CONTAINER
  ?? 'crewscope-m7-q03-redis-1'

export async function register(
  page: Page,
  username: string,
  email: string,
  displayName: string,
  password: string,
  invited: boolean,
): Promise<void> {
  if (!invited) await page.goto('/register')
  await page.getByRole('textbox', { name: '用户名' }).fill(username)
  await page.getByRole('textbox', { name: '邮箱' }).fill(email)
  await page.getByRole('textbox', { name: '展示名' }).fill(displayName)
  await page.locator('input[name="password"]').fill(password)
  await page.getByRole('button', {
    name: invited ? '创建账号并加入团队' : '创建账号',
    exact: true,
  }).click()
}

export async function login(page: Page, identifier: string, password: string): Promise<void> {
  await expect(page.getByRole('textbox', { name: '用户名或邮箱' })).toBeVisible()
  await page.getByRole('textbox', { name: '用户名或邮箱' }).fill(identifier)
  await page.locator('input[name="password"]').fill(password)
  await page.getByRole('button', { name: '进入 CrewScope' }).click()
  await expect(page).not.toHaveURL(/\/login/)
}

export async function currentSession(page: Page): Promise<Session> {
  return getJson<Session>(page, '/api/v1/auth/session')
}

export function onlyTeam(session: Session): Session['teams'][number] {
  expect(session.authenticated).toBe(true)
  expect(session.teams).toHaveLength(1)
  return session.teams[0]!
}

export function teamPath(session: Session, teamId: string, suffix: string): string {
  const organizationId = session.principal?.organizationId
  if (!organizationId) throw new Error('Authenticated Organization is missing')
  return `/api/v1/organizations/${organizationId}/teams/${teamId}/${suffix}`
}

export async function getJson<T>(page: Page, path: string): Promise<T> {
  const response = await page.request.get(path)
  expect(response.ok(), `${path} returned ${response.status()}`).toBe(true)
  return response.json() as Promise<T>
}

export async function command(
  page: Page,
  session: Session,
  path: string,
  body: Record<string, unknown>,
): Promise<void> {
  const response = await page.request.post(path, {
    data: body,
    headers: {
      [session.csrf.headerName]: session.csrf.token,
      'Idempotency-Key': crypto.randomUUID(),
    },
  })
  expect(response.status(), `${path} command failed: ${await response.text()}`).toBe(202)
}

export async function sessionCookie(context: BrowserContext): Promise<string> {
  const cookie = (await context.cookies(baseURL)).find(value => value.name === 'CREWSCOPE_SESSION')
  expect(cookie).toBeTruthy()
  return cookie!.value
}

/**
 * Full Cookie header value for Node-side fetches. The server's CSRF is a double-submit
 * CookieServerCsrfTokenRepository, so the XSRF-TOKEN cookie must travel alongside
 * CREWSCOPE_SESSION (and the echoed header) or the write is rejected with csrf_rejected
 * before authentication is even considered — a bare session value is not enough.
 */
export async function cookieHeader(context: BrowserContext): Promise<string> {
  return (await context.cookies(baseURL))
    .map(cookie => `${cookie.name}=${cookie.value}`)
    .join('; ')
}

export async function logout(page: Page, session: Session): Promise<void> {
  const response = await page.request.post('/api/v1/auth/logout', {
    headers: { [session.csrf.headerName]: session.csrf.token },
  })
  expect(response.status()).toBe(204)
}

export function restartApi(): void {
  execFileSync('docker', ['restart', apiContainer], { stdio: 'ignore', timeout: 30_000 })
}

export function expireBrowserSessions(): void {
  // Two compose generations share this helper: the ACL-hardened stack (redis copies its secret to
  // /tmp/redis_acl and enforces a password) and the simplified single-host stack (bare
  // `redis-server --appendonly yes`, no ACL). Authenticate only when the ACL file exists.
  const script = String.raw`
set -eu
if [ -f /tmp/redis_acl ]; then
  password=$(sed -n 's/^user default on >\([^ ]*\).*/\1/p' /tmp/redis_acl)
  test -n "$password"
  export REDISCLI_AUTH="$password"
fi
redis-cli --user default --scan --pattern 'crewscope:session:sessions:*' | while IFS= read -r key; do
  redis-cli --user default expire "$key" 1 >/dev/null
done
`
  execFileSync('docker', ['exec', redisContainer, 'sh', '-ec', script], {
    stdio: 'ignore',
    timeout: 30_000,
  })
}
