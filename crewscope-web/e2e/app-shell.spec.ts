import { expect, test } from '@playwright/test'

test('Conversation and Control share the focused object', async ({ page }) => {
  await page.goto('/conversation?focus=CRW-18&team=platform')
  await expect(page.getByRole('heading', { name: /CRW-18/ })).toBeVisible()

  await page.getByRole('link', { name: '控制台', exact: true }).click()

  await expect(page).toHaveURL(/\/control\?focus=CRW-18&team=platform/)
  await expect(page.getByRole('heading', { name: 'Platform Engineering', exact: true })).toBeVisible()
})

test('AppShell visual baseline', async ({ page }, testInfo) => {
  await page.goto('/conversation?focus=CRW-18')
  await expect(page.getByRole('heading', { name: /CRW-18/ })).toBeVisible()
  await expect(page).toHaveScreenshot(`conversation-${testInfo.project.name}.png`, { fullPage: true })

  await page.goto('/control?focus=CRW-18')
  await expect(page.getByText('M0 产品骨架')).toBeVisible()
  await expect(page).toHaveScreenshot(`control-${testInfo.project.name}.png`, { fullPage: true })
})
