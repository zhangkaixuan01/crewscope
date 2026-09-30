import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import { baseURL, currentSession, onlyTeam, register } from '../real-backend'

/**
 * M9b-Q02 real-stack menu reach (§9.2 各菜单操作 row): every signed-in route renders against
 * the production backend — the page lands on its own path (never access-denied or a dead router),
 * paints a non-empty main workspace with a heading, and does not overflow horizontally. The
 * mocked menu matrix already pins per-menu geometry and operations; this walk proves the real
 * authorization and data contracts leave every entry reachable on a live, empty-ish team.
 */
const MENUS = [
  { path: '/today' },
  { path: '/work' },
  { path: '/conversation' },
  { path: '/search' },
  { path: '/setup' },
  { path: '/activity' },
  { path: '/inbox' },
  { path: '/team/observer' },
  { path: '/operations' },
  { path: '/audit' },
  { path: '/team/members' },
  { path: '/settings/repositories' },
  { path: '/settings/agents' },
  { path: '/settings/models' },
  { path: '/settings/integrations/lark' },
  { path: '/settings/integrations/github' },
  { path: '/account' },
]

let context: BrowserContext
let page: Page
let teamId: string

test.describe.configure({ mode: 'serial' })

test('signs in once for the real-stack menu walk', async ({ browser }) => {
  const suffix = `menu-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  // The walk shares one signed-in page across tests, so it lives in an explicitly owned
  // context — the per-test fixture context would be torn down when this test ends.
  context = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  page = await context.newPage()
  await register(page, `walk-${suffix}`.slice(0, 48), `walk-${suffix}@example.test`, 'Q02 Walk', 'Correct-Horse-Battery-Staple-47', false)
  await expect(page).toHaveURL(/\/onboarding$/)
  await page.getByRole('textbox', { name: '团队名称' }).fill(`Q02 Walk ${suffix}`.slice(0, 96))
  await page.getByRole('button', { name: '创建团队' }).click()
  await expect(page.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible()
  teamId = onlyTeam(await currentSession(page)).teamId
})

test('every menu route renders its own page without overflowing', async () => {
  expect(teamId).toBeTruthy()
  for (const menu of MENUS) {
    await page.goto(`${menu.path}?team=${teamId}`)
    await expect(page, `${menu.path} left its route`).not.toHaveURL(/\/(access-denied|not-found|login)/)
    await expect(page.locator('#main-workspace'), `${menu.path} has an empty workspace`).not.toBeEmpty()
    await expect(page.locator('h1'), `${menu.path} renders no heading`).not.toBeEmpty()
    const overflow = await page.evaluate(() =>
      document.documentElement.scrollWidth - document.documentElement.clientWidth)
    expect(overflow, `${menu.path} overflows horizontally by ${overflow}px`).toBeLessThanOrEqual(1)
  }
})

test.afterAll(async () => {
  await context?.close()
})
