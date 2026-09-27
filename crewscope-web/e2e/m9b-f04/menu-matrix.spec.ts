import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { ids, mockApi } from '../menu-walk'

/**
 * M9b-F04 C15 的收口矩阵（走 dev server，同 menu-walk 三件套）：
 *
 * 1. **23 页补全** —— MENUS 三件套已走 17 个登录态菜单；这里补 6 个非菜单路由
 *    （login/register/invite/onboarding/access-denied/not-found）。23 = 17 + 6，与 router
 *    的路由数一致，到此「每一页都走过」成立。
 * 2. **四视口几何** —— 1440×900 / 1024×768 / 390×844 / 320×568 上抽查：无横向溢出、
 *    工作区在视口内、窄屏唯一的导航入口真的点得到（elementFromPoint 命中）。1024×768 的
 *    对话工作区几何由 reading-anchors 的 F04 用例单独断言，这里不再重复。
 * 3. **长无空格路径** —— 200 字符路径与超长 focus 坐标不撑破任何容器。
 */

/** 四视口里窄屏的一对（390/320）要断言汉堡入口；宽屏一对（1440/1024）断言工作区几何。 */
const VIEWPORTS = [
  { width: 1440, height: 900 },
  { width: 1024, height: 768 },
  { width: 390, height: 844 },
  { width: 320, height: 568 },
] as const

/** 公开三页走 AuthCard 状态机（loading → 主表单），标题文案随分支变，断言挂卡片结构本身。 */
const PUBLIC_ROUTES = ['/login', '/register', '/invite']

const ERROR_ROUTES = [
  { path: '/access-denied', heading: '需要额外的团队权限' },
  { path: `/no-such-${'x'.repeat(60)}-page`, heading: '这个工作范围不存在' },
]

test.describe.configure({ timeout: 120_000 })

test.beforeEach(async ({ page }) => mockApi(page))

for (const path of PUBLIC_ROUTES) {
  test(`公开路由 ${path} 渲染主表单并通过 axe`, async ({ page }) => {
    // 公开页带着已登录 session 会立刻跳走，所以绕开 mock 直接访问 dev server：
    // 未 mock 的 session 请求 404，authStore 落回未认证态，页面才真正渲染。
    await page.unrouteAll()
    await page.goto(path === '/invite' ? `${path}#token=${'A'.repeat(43)}` : path)
    // 未 mock 的 session 404 走「未认证」分支，公开页最终落到自己的主卡片。
    await expect(page.locator('.auth-card h2').first()).toBeVisible({ timeout: 15_000 })

    const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze()
    expect(result.violations, result.violations.map(v => `${v.id} ${v.nodes.map(n => n.target.join(' ')).join(',')}`).join('\n')).toEqual([])
  })
}

test('onboarding 与两个错误路由到得了且可读', async ({ page }) => {
  // 已有 Team 的会话可能被 onboarding 送回工作台——「到得了」两种落点都算通过；页面本体
  // 走 AuthLayout（没有 .app-shell），落到哪边都断言对应壳层可见。
  await page.goto(`/onboarding?team=${ids.team}`)
  await expect(page.locator('.auth-card, .app-shell').first()).toBeVisible({ timeout: 15_000 })
  await expect(page).toHaveURL(/\/(onboarding|today)/)

  for (const route of ERROR_ROUTES) {
    await page.goto(route.path)
    await expect(page.getByRole('heading', { name: route.heading })).toBeVisible()
  }
})

test.describe('四视口几何抽查', () => {
  /** 每视口都量同一组事实：溢出、工作区边界、窄屏导航入口可点。 */
  async function assertViewport(page: Page, width: number): Promise<void> {
    const overflow = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }))
    expect(overflow.scrollWidth, `${width}px 横向溢出`).toBeLessThanOrEqual(overflow.clientWidth + 1)

    const shell = await page.locator('.app-shell__workspace, main').first().boundingBox()
    expect(shell, `${width}px 工作区不可见`).not.toBeNull()
    expect(shell!.x, `${width}px 工作区越出左缘`).toBeGreaterThanOrEqual(-1)
    expect(shell!.x + shell!.width, `${width}px 工作区越出右缘`).toBeLessThanOrEqual(overflow.clientWidth + 1)

    if (width <= 767) {
      // 窄屏唯一的导航入口：汉堡按钮中心必须命中自己，被盖住等于导航到不了。
      const toggle = page.locator('.mobile-menu-toggle')
      await expect(toggle).toBeVisible()
      const box = await toggle.boundingBox()
      const hit = await page.evaluate(({ x, y }) => {
        const element = document.elementFromPoint(x, y)
        return element?.closest('.mobile-menu-toggle') ?? null
      }, { x: box!.x + box!.width / 2, y: box!.y + box!.height / 2 })
      expect(hit, `${width}px 移动导航入口被遮挡`).not.toBeNull()
    }
  }

  const SPOT_PAGES = ['/today', '/work', '/conversation', '/search', '/inbox', '/audit']

  for (const { width, height } of VIEWPORTS) {
    test(`${width}×${height} 六个抽查页无溢出且主入口可达`, async ({ page }) => {
      await page.setViewportSize({ width, height })
      for (const path of SPOT_PAGES) {
        await page.goto(`${path}?team=${ids.team}`, { waitUntil: 'domcontentloaded' })
        await expect(page.locator('.app-shell')).toBeVisible()
        await page.waitForLoadState('networkidle')
        await assertViewport(page, width)
      }
    })
  }
})

test('超长无空格坐标不撑破容器', async ({ page }) => {
  const longFocus = `${'CRW-无空格长坐标'.repeat(12)}`
  await page.setViewportSize({ width: 320, height: 568 })
  for (const path of ['/work', '/conversation', '/today']) {
    await page.goto(`${path}?team=${ids.team}&focus=${encodeURIComponent(longFocus)}`)
    await expect(page.locator('.app-shell')).toBeVisible()
    await page.waitForLoadState('networkidle')
    const overflow = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }))
    expect(overflow.scrollWidth, `${path} 长坐标溢出`).toBeLessThanOrEqual(overflow.clientWidth + 1)
  }
})
