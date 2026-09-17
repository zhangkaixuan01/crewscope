import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { ids, MENUS, mockApi } from './menu-walk'

// 与 m9-dark-theme.spec.ts 同一理由：逐菜单走完全部 14 个入口，冷启动的 Vite 路由/模块加载
// 不能把一次完整走查变成假失败。
test.describe.configure({ timeout: 180_000 })

/**
 * M9-Q01 §3 把「Axe 明暗双主题 × 双密度」记为**部分完成**：暗色有 color-contrast 专项
 * （m9-dark-theme.spec.ts），浅色下各菜单的 axe 扫描散在 m6/m9 各条 spec 里，而**双密度这一维
 * 从来没有和 axe 组合过**——既有的每一处扫描都跑在默认的舒适密度上。
 *
 * 密度恰恰是布局型无障碍缺陷的成因：它改控制高度、内边距与换行，紧凑密度下标签更容易被挤出
 * 可视区、命中区更容易低于下限、文本更容易溢出容器。主题改的是颜色，密度改的是几何，两者
 * 覆盖的缺陷面不重叠，所以两个维度都要在紧凑密度下重跑：
 *
 * - 浅色 × 紧凑跑完整 WCAG A/AA 规则集（与 m6-audit.spec.ts、m6-operations.spec.ts 同一套 tags）；
 * - 暗色 × 紧凑只跑 color-contrast——其余规则与主题无关（标签、角色、焦点顺序不随主题变化），
 *   暗色下的对比度缺陷全部集中在颜色上，这是 m9-dark-theme.spec.ts 已经确立的取舍。
 */

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('cs.pref.device.density.v1', JSON.stringify({ version: 1, value: 'compact' }))
  })
  await mockApi(page)
})

test('浅色紧凑密度下每个菜单都满足 WCAG A/AA', async ({ page }) => {
  const failures: string[] = []
  for (const menu of MENUS) {
    await openMenu(page, menu.path)
    // 密度没有生效就等于在量舒适密度，必须先把前提断言掉。
    await expect.poll(() => page.locator('html').getAttribute('data-density')).toBe('compact')

    const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze()
    for (const violation of result.violations) {
      for (const node of violation.nodes) {
        failures.push(`${menu.path}（${menu.name}）${violation.id} ${node.target.join(' ')}`)
      }
    }
  }
  expect(failures, failures.join('\n')).toEqual([])
})

test('暗色紧凑密度下每个菜单都满足 AA 对比度', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('cs.pref.device.theme.v1', JSON.stringify({ version: 1, value: 'dark' }))
  })
  const failures: string[] = []
  for (const menu of MENUS) {
    await openMenu(page, menu.path)
    await expect.poll(() => page.locator('html').getAttribute('data-theme')).toBe('dark')
    await expect.poll(() => page.locator('html').getAttribute('data-density')).toBe('compact')

    const result = await new AxeBuilder({ page }).withRules(['color-contrast']).analyze()
    for (const violation of result.violations) {
      for (const node of violation.nodes) {
        failures.push(`${menu.path}（${menu.name}）${node.target.join(' ')}：${node.failureSummary?.replace(/\s+/g, ' ').trim()}`)
      }
    }
  }
  expect(failures, failures.join('\n')).toEqual([])
})

async function openMenu(page: Page, path: string): Promise<void> {
  await page.goto(`${path}?team=${ids.team}`, { waitUntil: 'domcontentloaded' })
  await expect(page.locator('.app-shell')).toBeVisible()
  await page.waitForLoadState('networkidle')
}
