import { expect, test } from '@playwright/test'
import { ids, mockApi } from './menu-walk'

test.describe('M9-F06 theme and density preferences', () => {
  test.beforeEach(async ({ page }) => mockApi(page))

  test('applies persisted dark/compact preferences before the app mounts', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('cs.pref.device.theme.v1', JSON.stringify({ version: 1, value: 'dark' }))
      localStorage.setItem('cs.pref.device.density.v1', JSON.stringify({ version: 1, value: 'compact' }))
    })
    await page.goto('/login', { waitUntil: 'domcontentloaded' })

    await expect.poll(() => page.locator('html').getAttribute('data-theme')).toBe('dark')
    await expect.poll(() => page.locator('html').getAttribute('data-density')).toBe('compact')
  })

  test('rejects invalid persisted values and uses safe defaults', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('cs.pref.device.theme.v1', JSON.stringify({ version: 1, value: 'ultraviolet' }))
      localStorage.setItem('cs.pref.device.density.v1', JSON.stringify({ version: 99, value: 'compact' }))
    })
    await page.goto('/login', { waitUntil: 'domcontentloaded' })

    await expect.poll(() => page.locator('html').getAttribute('data-theme')).toBe('light')
    await expect.poll(() => page.locator('html').getAttribute('data-density')).toBe('comfortable')
  })

  test('keeps the preference schema versioned in local storage', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('cs.pref.device.theme.v1', JSON.stringify({ version: 1, value: 'system' }))
      localStorage.setItem('cs.pref.device.density.v1', JSON.stringify({ version: 1, value: 'comfortable' }))
    })
    await page.goto('/login', { waitUntil: 'domcontentloaded' })
    const values = await page.evaluate(() => ({
      theme: JSON.parse(localStorage.getItem('cs.pref.device.theme.v1') || 'null'),
      density: JSON.parse(localStorage.getItem('cs.pref.device.density.v1') || 'null'),
    }))
    expect(values.theme?.version).toBe(1)
    expect(values.density?.version).toBe(1)
  })

  test('restores the sidebar collapse preference after a reload', async ({ page, viewport }) => {
    test.skip((viewport?.width ?? 0) <= 767, '折叠侧栏按钮属于 >767px 的桌面 Rail，窄屏由抽屉导航替代，rail 本身 display:none')
    await page.goto(`/today?team=${ids.team}`, { waitUntil: 'domcontentloaded' })
    await expect(page.getByRole('heading', { name: '我的工作台' })).toBeVisible()
    const shell = page.locator('.app-shell')
    await expect(shell).not.toHaveClass(/app-shell--collapsed/)
    await page.getByRole('button', { name: '折叠侧栏' }).click()
    await expect(shell).toHaveClass(/app-shell--collapsed/)
    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(shell).toHaveClass(/app-shell--collapsed/)
  })
})
