import { expect, test } from '@playwright/test'
import { f03Ids, f03WorkUrl, mockF03App, taskObjective, type F03World } from './fixtures'

/**
 * M9b-F03 统一工作区的执行选择规范（契约 §4.1 / §4.7）：
 * `taskExecution` 是唯一写侧参数，`attempt` 只是旧深链的只读别名；两个拼写同现且不等是
 * 坐标冲突——写动作全部禁止、由成员显式择一；过期的显式选择按历史证据只读呈现，绝不
 * 悄悄 fallback 到列表里的任何一条。
 */
let world: F03World

test.beforeEach(async ({ page }) => {
  world = await mockF03App(page)
})

test('restores a legacy attempt deep link read-only and rewrites unified on the next explicit choice', async ({ page }) => {
  await page.goto(f03WorkUrl({ attempt: f03Ids.previousExecution }))
  const dialog = page.getByRole('dialog', { name: `${taskObjective} Task 详情` })
  await expect(dialog).toBeVisible()

  // 别名深链被当作用户的显式选择：工作区锚定那条 COMPLETED 历史执行，而不是服务端当前执行。
  const chips = dialog.locator('.execution-selector__list button')
  await expect(chips).toHaveCount(2)
  await expect(chips.filter({ hasText: 'Attempt 1' })).toHaveAttribute('aria-current', 'true')
  await expect(chips.filter({ hasText: 'Attempt 2' })).not.toHaveAttribute('aria-current', 'true')

  // 下一次显式选择走统一参数：taskExecution 写入、attempt 别名被清除，两种拼写不会再叠加成冲突。
  await chips.filter({ hasText: 'Attempt 2' }).click()
  await expect.poll(() => new URL(page.url()).searchParams.get('taskExecution')).toBe(f03Ids.currentExecution)
  expect(new URL(page.url()).searchParams.get('attempt')).toBeNull()
  await expect(chips.filter({ hasText: 'Attempt 2' })).toHaveAttribute('aria-current', 'true')
})

test('keeps a stale execution selection read-only instead of falling back to a live one', async ({ page }) => {
  await page.goto(f03WorkUrl({ taskExecution: f03Ids.ghostExecution }))
  const dialog = page.getByRole('dialog', { name: `${taskObjective} Task 详情` })
  await expect(dialog).toBeVisible()

  // 幽灵执行不回退到任何列表项：历史视图明说自己在看什么，主动作条同样声明只读语义；
  // chips 保留可点（成员可显式切回列表内执行），但没有一条被标成当前锚定。
  await expect(dialog.locator('.execution-history')).toContainText('正在查看历史执行')
  await expect(dialog.locator('.workspace-action')).toContainText('历史执行视图')
  await expect(dialog.locator('.execution-selector__list button[aria-current="true"]')).toHaveCount(0)

  // 回到当前执行是成员的显式动作，写的是统一参数。
  await dialog.getByRole('button', { name: '回到当前执行' }).click()
  await expect.poll(() => new URL(page.url()).searchParams.get('taskExecution')).toBe(f03Ids.currentExecution)
  await expect(dialog.locator('.execution-history')).toHaveCount(0)
  await expect(dialog.locator('.execution-selector__list button').first()).toBeVisible()
})

test('blocks every write on a taskExecution/attempt conflict until one side is chosen', async ({ page }) => {
  await page.goto(f03WorkUrl({ taskExecution: f03Ids.currentExecution, attempt: f03Ids.previousExecution }))
  const dialog = page.getByRole('dialog', { name: `${taskObjective} Task 详情` })
  await expect(dialog).toBeVisible()

  // 冲突以最高优先级呈现：执行选择区报警 + 主动作条声明禁写，执行 chips 不出现（没有既成事实）。
  const alert = dialog.getByRole('alert').filter({ hasText: '执行坐标冲突' })
  await expect(alert).toBeVisible()
  await expect(dialog.locator('.workspace-action')).toContainText('执行坐标冲突')
  await expect(dialog.locator('.execution-selector__list')).toHaveCount(0)

  // 控制面板照常可见，但走完暂停确认也不会发出任何 Task 命令——gate 在页面写侧。
  await dialog.getByRole('button', { name: '暂停当前 Task' }).click()
  const confirm = page.getByRole('dialog', { name: '暂停当前执行' })
  await confirm.getByLabel('暂停原因').fill('冲突态的写动作必须被拒绝')
  await confirm.getByRole('button', { name: '确认暂停' }).click()
  await expect(alert).toBeVisible()
  expect(world.taskCommandPosts).toEqual([])

  // 择一即解：统一参数保留所选执行，另一侧拼写与 review 残留一并清除，chips 恢复。
  await alert.getByRole('button', { name: '用此执行 · taskExecution' }).click()
  await expect.poll(() => new URL(page.url()).searchParams.get('taskExecution')).toBe(f03Ids.currentExecution)
  expect(new URL(page.url()).searchParams.get('attempt')).toBeNull()
  expect(new URL(page.url()).searchParams.get('review')).toBeNull()
  await expect(dialog.getByRole('alert')).toHaveCount(0)
  await expect(dialog.locator('.execution-selector__list button').first()).toBeVisible()
})

test('reports the executing verdict with one primary control and no synthesized completion', async ({ page }) => {
  await page.goto(f03WorkUrl({ taskExecution: f03Ids.currentExecution }))
  const dialog = page.getByRole('dialog', { name: `${taskObjective} Task 详情` })
  await expect(dialog).toBeVisible()

  // RUNNING 执行 → 第 7 级「正在执行」：一句事实 + 唯一主操作（取消），定位到执行区而非直接执行。
  const strip = dialog.locator('.workspace-action')
  await expect(strip).toHaveAttribute('data-action-state', 'EXECUTING')
  await expect(strip).toContainText('正在执行')
  await expect(strip.getByRole('button', { name: '取消' })).toBeVisible()
  await strip.getByRole('button', { name: '取消' }).click()
  await expect(new URL(page.url()).searchParams.get('taskExecution')).toBe(f03Ids.currentExecution)
  expect(world.taskCommandPosts).toEqual([])

  // §4.1 分区顺序：五个锚分区按概览 → 讨论 → 执行 → 变更与测试 → 审查与交付排布。
  const sections = dialog.locator('.workspace-section')
  await expect(sections).toHaveCount(5)
  await expect(sections.nth(0)).toHaveAttribute('aria-label', '概览')
  await expect(sections.nth(1)).toHaveAttribute('aria-label', '讨论')
  await expect(sections.nth(2)).toHaveAttribute('aria-label', '执行')
  await expect(sections.nth(3)).toHaveAttribute('aria-label', '变更与测试')
  await expect(sections.nth(4)).toHaveAttribute('aria-label', '审查与交付')
})
