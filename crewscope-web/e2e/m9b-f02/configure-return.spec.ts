import { expect, test } from '@playwright/test'
import { ids, mockF02App, origin, workUrl, type F02World } from './fixtures'

/**
 * M9b-F02 配置往返（§4.2）：离开任务去配置时 URL 只携带登记来源与对象 id，返回只接受
 * 站内登记路由；回来后 delegate 深链重开表单并恢复草稿，但不替成员自动执行任何命令。
 */
let world: F02World

test.beforeEach(async ({ page }) => {
  world = await mockF02App(page)
})

test('a model-side preflight gap round-trips through settings and reopens the draft', async ({ page }) => {
  world.preflightModelGap = true
  await page.goto(workUrl())
  const drawer = page.getByRole('dialog', { name: 'F02-1 工作项详情' })
  await drawer.getByRole('button', { name: '交给 Agent 处理' }).click()
  const delegate = page.getByRole('dialog', { name: '交给 Agent 处理' })

  // 模型类预检失败给出可往返的配置入口，而不是只留一句报错。
  await expect(delegate.getByText('这个执行范围没有可用模型 Binding。')).toBeVisible()
  const configure = delegate.getByRole('button', { name: '去配置模型并返回' })
  await expect(configure).toBeVisible()
  await delegate.getByLabel('执行目标').fill('模型缺口往返的草稿内容')

  await configure.click()
  await expect(page.getByRole('heading', { name: '模型与凭证' })).toBeVisible()
  const outgoing = new URL(page.url())
  expect(outgoing.pathname).toBe('/settings/models')
  expect(outgoing.searchParams.get('from')).toBe('work')
  expect(outgoing.searchParams.get('team')).toBe(ids.team)
  expect(outgoing.searchParams.get('project')).toBe(ids.project)
  expect(outgoing.searchParams.get('workItem')).toBe(ids.workItem)
  expect(outgoing.searchParams.get('delegate')).toBe('coding')
  // 凭据与草稿文本绝不进入查询串（§4.2）。
  expect(page.url()).not.toContain('草稿')

  // 修复模型事实后返回：settings 页只回登记过的来源坐标。
  world.preflightModelGap = false
  await page.getByRole('button', { name: '返回工作项' }).click()
  const detailAfter = page.getByRole('dialog', { name: 'F02-1 工作项详情' })
  await expect(detailAfter.getByRole('heading', { name: '配置往返验收工作项' })).toBeVisible()
  const returning = new URL(page.url())
  expect(returning.pathname).toBe('/work')
  expect(returning.searchParams.get('from')).toBeNull()
  expect(returning.searchParams.get('workItem')).toBe(ids.workItem)
  // delegate 深链由 WorkPage 一次性消费并剥参，其效果由下方重开的对话框验证，不依赖 URL 残留。
  expect(returning.searchParams.get('delegate')).toBeNull()

  // delegate 深链重开表单：草稿恢复、预检自动通过，但没有任何命令被自动提交。
  const reopened = page.getByRole('dialog', { name: '交给 Agent 处理' })
  await expect(reopened).toBeVisible()
  await expect(reopened.getByLabel('执行目标')).toHaveValue('模型缺口往返的草稿内容')
  await expect(reopened.getByText('PolicySnapshot Preflight 通过')).toBeVisible()
})

test('an execution-defaults gap offers the repository settings round trip', async ({ page }) => {
  world.defaultsGap = true
  await page.goto(workUrl())
  const drawer = page.getByRole('dialog', { name: 'F02-1 工作项详情' })
  await drawer.getByRole('button', { name: '交给 Agent 处理' }).click()
  const delegate = page.getByRole('dialog', { name: '交给 Agent 处理' })

  await expect(delegate.getByText('项目尚未设置默认仓库——下方已回退到可用仓库，可稍后在项目执行默认值中补配。')).toBeVisible()
  await delegate.getByRole('button', { name: '去补配执行默认值并返回' }).click()
  await expect(page.getByRole('heading', { name: 'CrewScope 仓库设置' })).toBeVisible()
  const outgoing = new URL(page.url())
  expect(outgoing.pathname).toBe('/settings/repositories')
  expect(outgoing.searchParams.get('from')).toBe('work')
  expect(outgoing.searchParams.get('delegate')).toBe('coding')

  await page.getByRole('button', { name: '返回工作项' }).click()
  await expect(page.getByRole('dialog', { name: '交给 Agent 处理' })).toBeVisible()
})

test('the setup centre registers itself as the return origin for its own next step', async ({ page }) => {
  await page.goto(`${origin}/setup?team=${ids.team}`)
  await expect(page.getByRole('heading', { name: 'Platform Engineering 的配置中心' })).toBeVisible()

  // Setup 的下一步把配置中心登记为来源；具体设置页据此提供「返回配置中心」。
  // 同名动作在健康卡/能力清单里也有，主动作（primary）排在最前。
  await page.getByRole('button', { name: '配置 Agent' }).first().click()
  await expect(page.getByRole('heading', { name: 'Agent 中心' })).toBeVisible()
  const outgoing = new URL(page.url())
  expect(outgoing.pathname).toBe('/settings/agents')
  expect(outgoing.searchParams.get('from')).toBe('setup')
  expect(outgoing.searchParams.get('team')).toBe(ids.team)

  await page.getByRole('button', { name: '返回配置中心' }).click()
  await expect(page.getByRole('heading', { name: 'Platform Engineering 的配置中心' })).toBeVisible()
  const returning = new URL(page.url())
  expect(returning.pathname).toBe('/setup')
  expect(returning.searchParams.get('from')).toBeNull()
  expect(returning.searchParams.get('team')).toBe(ids.team)
})

test('an unregistered origin never produces a return affordance', async ({ page }) => {
  await page.goto(`${origin}/settings/models?team=${ids.team}&from=evil.example%2Fwork`)
  await expect(page.getByRole('heading', { name: '模型与凭证' })).toBeVisible()
  await expect(page.getByRole('button', { name: '返回工作项' })).toHaveCount(0)
})
