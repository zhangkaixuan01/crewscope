import { expect, test } from '@playwright/test'
import { ids, mockF02App, origin, type F02World } from './fixtures'

/**
 * M9b-F02 目标导向 Setup（L07）：先选目标，卡内聚合该目标的缺口并给出第一个可执行下一步；
 * 已就绪能力折叠、健康诊断默认收起；手机首屏看到的是下一步而不是大进度 Hero。
 */
let world: F02World

test.beforeEach(async ({ page }) => {
  world = await mockF02App(page)
})

test('each goal card aggregates its own gaps and round-trips its next step', async ({ page }) => {
  await page.goto(`${origin}/setup?team=${ids.team}`)
  const goals = page.getByRole('region', { name: '先选一个目标' })
  await expect(goals.getByRole('heading', { name: '先开始对话' })).toBeVisible()
  await expect(goals.getByRole('heading', { name: '先开始 Coding' })).toBeVisible()

  // 对话目标只差 Personal Agent；Coding 目标只差执行默认值——两卡的下一步不同。
  const conversation = goals.getByRole('article').filter({ hasText: '先开始对话' })
  const coding = goals.getByRole('article').filter({ hasText: '先开始 Coding' })
  await expect(conversation.getByText('还需 1 项')).toBeVisible()
  await expect(coding.getByText('还需 1 项')).toBeVisible()
  await expect(conversation.getByRole('button', { name: '配置 Agent' })).toBeVisible()

  await coding.getByRole('button', { name: '补配执行默认值' }).click()
  await expect(page.getByRole('heading', { name: 'CrewScope 仓库设置' })).toBeVisible()
  const outgoing = new URL(page.url())
  expect(outgoing.pathname).toBe('/settings/repositories')
  expect(outgoing.searchParams.get('from')).toBe('setup')
  expect(outgoing.searchParams.get('team')).toBe(ids.team)

  await page.getByRole('button', { name: '返回配置中心' }).click()
  await expect(page.getByRole('heading', { name: 'Platform Engineering 的配置中心' })).toBeVisible()
})

test('goal selection is an immediate view state, never persisted', async ({ page }) => {
  await page.goto(`${origin}/setup?team=${ids.team}`)
  const conversation = page.getByRole('region', { name: '先选一个目标' }).getByRole('article').filter({ hasText: '先开始对话' })
  await conversation.getByRole('button', { name: '选择此目标' }).click()
  // 选中后按钮改名，避免按名字重查时落到另一张卡的按钮上。
  const selected = conversation.getByRole('button', { name: '已选中' })
  await expect(selected).toHaveAttribute('aria-pressed', 'true')
  await selected.click()
  await expect(conversation.getByRole('button', { name: '选择此目标' })).toHaveAttribute('aria-pressed', 'false')
  // 刷新回到默认：选择只是即时的视图状态，不进入 URL 也不落任何存储。
  await conversation.getByRole('button', { name: '选择此目标' }).click()
  await page.reload()
  const conversationAfter = page.getByRole('region', { name: '先选一个目标' }).getByRole('article').filter({ hasText: '先开始对话' })
  await expect(conversationAfter.getByRole('button', { name: '选择此目标' })).toBeVisible()
})

test('ready capabilities stay folded and health diagnostics stay collapsed by default', async ({ page }) => {
  await page.goto(`${origin}/setup?team=${ids.team}`)
  const checklist = page.getByRole('region', { name: '能力与前置条件' })
  // TEAM_TASK 等四项已就绪能力默认折叠；展开后事实仍可见。
  await expect(checklist.getByRole('heading', { name: 'Team Task' })).toHaveCount(0)
  await checklist.getByRole('button', { name: '显示已就绪能力（4）' }).click()
  await expect(checklist.getByRole('heading', { name: 'Team Task' })).toBeVisible()

  // 健康诊断默认收起，只露整体状态；按需展开（标题说明里也提到“Agent 配置”，用行标题断言明细）。
  const health = page.getByRole('region', { name: '配置健康 · 四项组件' })
  await expect(health.getByRole('heading', { name: 'Agent 配置' })).toHaveCount(0)
  await health.getByRole('button', { name: '展开' }).click()
  await expect(health.getByRole('heading', { name: 'Agent 配置' })).toBeVisible()
})

test('a narrow viewport leads with the next step instead of a large hero', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 667 })
  await page.goto(`${origin}/setup?team=${ids.team}`)
  // L07：手机不铺大进度 Hero——下一步卡置顶且落在首屏内，目标卡紧随其后。
  const nextStep = page.getByRole('region', { name: 'Personal Conversation' })
  await expect(nextStep).toBeVisible()
  const box = await nextStep.boundingBox()
  expect(box?.y).toBeLessThan(320)
  const goals = page.getByRole('region', { name: '先选一个目标' })
  const goalBox = await goals.boundingBox()
  expect(goalBox && goalBox.y).toBeDefined()
  expect(goalBox!.y).toBeGreaterThan(box!.y)
})
