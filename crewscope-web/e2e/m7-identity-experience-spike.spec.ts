import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

const visualStates = [
  ['login', '继续你的团队工作'],
  ['register', '加入 CrewScope'],
  ['onboarding', '从一个共同工作空间开始'],
  ['invite', '一起完成 CrewScope 的下一次交付'],
  ['account', '身份与安全'],
] as const

test('freezes the five identity surfaces at desktop and narrow viewports', async ({ page }, testInfo) => {
  for (const [state, heading] of visualStates) {
    await openState(page, state)
    await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible()
    await expect(page).toHaveScreenshot(`m7-${state}-${testInfo.project.name}.png`, {
      fullPage: true,
      animations: 'disabled',
    })
  }
})

test('keeps keyboard entry and error focus deterministic', async ({ page }) => {
  await openState(page, 'login')
  const identifier = page.getByRole('textbox', { name: '用户名或邮箱' })
  const password = page.locator('input[name="password"]')
  await expect(identifier).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(password).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(page.getByRole('button', { name: '显示密码' })).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(page.getByRole('checkbox', { name: '保持登录' })).toBeFocused()

  await openState(page, 'login-error')
  await expect(page.getByRole('alert')).toBeFocused()
  await expect(page.getByRole('alert')).toContainText('登录信息无效')

  await openState(page, 'locked')
  await expect(page.getByRole('alert')).toBeFocused()
  await expect(page.getByRole('button', { name: '进入 CrewScope' })).toBeDisabled()

  await openState(page, 'register')
  await expect(page.getByRole('textbox', { name: '用户名', exact: true })).toBeFocused()
})

test('covers loading, unavailable registration and expired invitation states', async ({ page }) => {
  await openState(page, 'loading')
  await expect(page.getByRole('status')).toContainText('正在确认你的会话')

  await openState(page, 'service-error')
  await expect(page.getByRole('alert')).toContainText('没有完成会话检查')

  await openState(page, 'registration-invite-only')
  await expect(page.getByRole('heading', { name: '通过团队邀请加入' })).toBeFocused()

  await openState(page, 'registration-closed')
  await expect(page.getByRole('heading', { name: '当前未开放注册' })).toBeFocused()

  await openState(page, 'invite-expired')
  await expect(page.getByRole('heading', { name: '这个邀请已失效' })).toBeFocused()
})

async function openState(page: Page, state: string): Promise<void> {
  await page.goto(`/m7-s04-fixture.html?state=${state}`)
  await expectNoOverflowOrAxeViolations(page)
}

async function expectNoOverflowOrAxeViolations(page: Page): Promise<void> {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)
  expect(overflow).toBe(false)
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
}
