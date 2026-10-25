import { expect, test } from '@playwright/test'
import { baseURL, currentSession, register } from '../real-backend'

/**
 * 首跑命脉冒烟（M13h）：一个全新账号在真实 Compose 栈上从零走通产品的最短闭环——
 * 注册 → 建团 → 进对话 → 发出第一条消息。每一步都是这周真实断砖过的位置：
 * 注册（503 registration_unavailable 判例：组织锚未配置）、建团（422
 * modelProvider.contentHash 判例：平台目录漂移）在 mock e2e 全绿时都放过行，只有真实
 * 栈上的一条全新账号链路能守住。
 *
 * 栈形态与 CI 完全一致：quickstart 生成的无凭据环境（不含任何模型 API key）。因此
 * Agent 回转断言的是产品的失败设计而不是 LLM 输出：调用失败必须可见（role=alert 的
 * 操作区）、进入可恢复状态（重新连接）、不吞掉用户输入（草稿或已持久化消息二选一），
 * 且用户消息在刷新后仍从真实读模型返回。
 */
test('brand-new account registers, onboards its team and exchanges the first conversation message', async ({ browser }) => {
  test.setTimeout(300_000)
  const suffix = `first-run-${Date.now()}`.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  const messageText = `首跑冒烟第一条消息 ${suffix}`
  const context = await browser.newContext({
    baseURL,
    viewport: { width: 1440, height: 960 },
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  })
  const page = await context.newPage()

  // 注册：真实 POST（CSRF + 会话建立 + 组织锚）。503/422 判例在这一步直接显形。
  await register(page, `owner-${suffix}`.slice(0, 48), `owner-${suffix}@example.test`, '首跑冒烟', 'Correct-Horse-Battery-Staple-47', false)
  await expect(page).toHaveURL(/\/onboarding$/)

  // 建团：平台模型目录初始化（含 V74 采纳路径）+ 团队工作区就绪。目录漂移判例在此显形。
  await page.getByRole('textbox', { name: '团队名称' }).fill(`首跑团队 ${suffix}`.slice(0, 96))
  await page.getByRole('button', { name: '创建团队' }).click()
  await expect(page.getByRole('heading', { name: '你的工作入口已经就绪' })).toBeVisible({ timeout: 30_000 })

  const session = await currentSession(page)
  expect(session.authenticated).toBe(true)
  expect(session.teams).toHaveLength(1)
  const teamId = session.teams[0]!.teamId

  // 进对话页：真实团队作用域 + 会话列表读模型（section 的隐式角色是 region）。
  await page.goto(`/conversation?team=${teamId}`)
  await expect(page.getByRole('region', { name: '对话列表' })).toBeVisible({ timeout: 30_000 })

  // 建 Conversation：左栏「新建对话」→ 弹层提交 → 列表出现并进入。
  await page.getByRole('region', { name: '对话列表' }).getByRole('button', { name: '新建对话' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await dialog.getByRole('textbox', { name: '标题' }).fill(`首跑对话 ${suffix}`.slice(0, 96))
  await dialog.getByRole('button', { name: '创建对话' }).click()
  const conversationButton = page.getByRole('button', { name: new RegExp(`打开对话 首跑对话 ${suffix}`) })
  await expect(conversationButton).toBeVisible({ timeout: 30_000 })
  await conversationButton.click()
  await expect(page.locator('form[aria-label="发送消息"]')).toBeVisible({ timeout: 30_000 })

  // 第一条消息：会话所有者触发 Agent 调用（SSE），用户消息沿真实链路提交。
  // 发送前先等实时链路在线（composer 的输入指引换成在线文案）；发送后以「操作区出现
  // 调用状态」确认提交真正生效——事件流重连的瞬间 store 会让 invoke 静默无操作（草稿
  // 保留、无反馈），真实用户此时会再点一次，这里以同样的方式对冲这个窗口。
  const composer = page.locator('form[aria-label="发送消息"]')
  const actionRegion = page.getByRole('region', { name: 'Agent 当前操作' })
  const invocationStatus = actionRegion.getByRole('status').or(actionRegion.getByRole('alert'))
  await expect(composer.getByText('Enter 发送 · Shift + Enter 换行')).toBeVisible({ timeout: 30_000 })
  for (let attempt = 0; attempt < 10; attempt++) {
    await composer.locator('textarea').fill(messageText)
    await composer.getByRole('button', { name: '发送' }).click()
    try {
      await expect(invocationStatus).toBeVisible({ timeout: 3_000 })
      break
    } catch {
      if (attempt === 9) throw new Error('发送连续 10 次未触发 Agent 调用')
    }
  }

  // Agent 回转的设计边界（无凭据栈）：失败必须可见、终态化，绝不允许停在 connecting/running。
  // role=alert 的操作区由错误态渲染；文案来自服务端错误包络，断言区域与终态，不锁死文案。
  // 同时页面不得弹出全局未预期错误横幅（m13g 判例：mock 缺陷曾以静默 TypeError 横幅显形）。
  await expect(actionRegion.getByRole('alert')).toBeVisible({ timeout: 90_000 })
  await expect(page.getByRole('alert').filter({ hasText: '页面遇到未预期问题' })).toHaveCount(0)

  // 用户消息必须落到真实读模型：刷新后仍从服务端返回（同时证明会话 cookie 存活、
  // 错误态不阻塞页面重新可用）。
  await page.reload()
  await expect(page.getByRole('list', { name: '消息历史' })).toBeVisible({ timeout: 30_000 })
  const history = page.getByRole('list', { name: '消息历史' })
  // 只断言消息本体：作者呈现（自己的消息显示「你」）是展示细节，不该锁进命脉冒烟。
  await expect(history.getByText(messageText)).toBeVisible({ timeout: 30_000 })
  await expect(page.locator('form[aria-label="发送消息"]')).toBeVisible()
})
