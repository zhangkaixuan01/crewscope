import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'

test.beforeEach(async ({ page }) => {
  await page.goto('/m4-s03-fixture.html')
})

test('replays the shared Diff fixture without overflow or accessibility violations', async ({ page }, testInfo) => {
  await expect(page.getByRole('heading', { name: '实时变更' })).toBeVisible()
  await expect(page.getByLabel('Diff 已同步')).toContainText('G3')
  await expect(page.getByLabel('变更文件列表').locator('article')).toHaveCount(5)
  await expect(page.getByText('预览已截断 · 完整 Patch 已归档')).toBeVisible()

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)
  expect(overflow).toBe(false)
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  await expect(page).toHaveScreenshot(`m4-diff-stream-${testInfo.project.name}.png`, {
    fullPage: true,
    animations: 'disabled',
  })
})
