import { expect, test } from '@playwright/test'
import { permissions } from '../../src/app/auth'
import { ids, mockA05App, responsibilityLine, sessionFor, taskRow, workUrl, type A05World } from './fixtures'

/**
 * M9b-A05 委托编排：抽屉「交给 Agent 处理」的发送意图合同。八个场景对照边界契约
 * §3.1/§3.2 与 R42——分配并启动 / 仅分配 / 双击幂等 / 冲突责任 / 权限不足 /
 * 项目默认值 / 评论不执行 / TaskIntent 深链原入口。
 */
let world: A05World

test.beforeEach(async ({ page }) => {
  world = await mockA05App(page)
})

test('Assign and start lands the EXECUTOR responsibility, the Task and the TASK coordinate in one command', async ({ page }) => {
  await page.goto(workUrl())
  const drawer = page.getByRole('dialog', { name: 'A05-1 工作项详情' })
  await expect(drawer).toBeVisible()
  await drawer.getByRole('button', { name: '交给 Agent 处理' }).click()
  const delegate = page.getByRole('dialog', { name: '交给 Agent 处理' })

  // 未分配世界：两种意图都可选，「分配并启动」是默认；停用候选进封锁区而不是下拉框。
  await expect(delegate.getByRole('radio', { name: '分配并启动' })).toBeChecked()
  await expect(delegate.getByText('Architecture Reviewer · 已停用——Agent 已停用')).toBeVisible()
  // 项目默认值在表单内解析并标注来源，不要求成员重复选择（§3.1）。
  await expect(delegate.getByText('项目默认', { exact: true }).first()).toBeVisible()
  await expect(delegate.getByText(/责任：把 张凯旋的 Personal Agent 记为 EXECUTOR/)).toBeVisible()

  await delegate.getByLabel('执行目标').fill('为委托编排交付端到端验收')
  await delegate.getByRole('button', { name: '验证 Ref' }).click()
  await expect(delegate.getByText('PolicySnapshot Preflight 通过')).toBeVisible()
  await delegate.getByRole('button', { name: '分配并启动' }).click()

  await expect(delegate).toBeHidden()
  await expect(page.getByRole('heading', { name: '为委托编排交付端到端验收', exact: true })).toBeVisible()
  expect(new URL(page.url()).searchParams.get('task')).toBeTruthy()

  // 一条命令同时落责任与 Task（决策①），幂等键留下 TASK 恢复坐标（决策③）。
  expect(world.taskCreates).toHaveLength(1)
  const create = world.taskCreates[0]!
  expect(create.key).toBeTruthy()
  expect(create.ifMatch).toBe(`"${world.workItemVersion}"`)
  expect(create.body.executorAgentProfileId).toBe(ids.agentPersonalProfile)
  expect(create.body.executorAssignment).toEqual({ agentProfileId: ids.agentPersonalProfile })
  expect(create.body.codingTarget).toMatchObject({ repositoryBindingId: ids.repositoryBinding, baselineRef: 'main' })
  const executorLine = world.responsibilities.find(line => line.role === 'EXECUTOR')
  expect(executorLine?.actorAgentProfileId).toBe(ids.agentPersonalProfile)
  expect([...world.commandResults.values()]).toEqual([expect.objectContaining({
    result: expect.objectContaining({ type: 'TASK', resourceId: world.tasks[0]!.id }),
  })])
})

test('Assign-only saves the responsibility through the existing executor command and starts nothing', async ({ page }) => {
  await page.goto(workUrl())
  const drawer = page.getByRole('dialog', { name: 'A05-1 工作项详情' })
  await drawer.getByRole('button', { name: '交给 Agent 处理' }).click()
  const delegate = page.getByRole('dialog', { name: '交给 Agent 处理' })

  await delegate.getByRole('radio', { name: '仅分配' }).check()
  // 「仅分配」是另一个意图：Task brief 与编码目标都不出现，影响确认改为不启动。
  await expect(delegate.getByLabel('执行目标')).toBeHidden()
  await expect(delegate.getByText('启动：本次不启动任何执行')).toBeVisible()
  await delegate.getByRole('button', { name: '仅分配' }).click()

  await expect(delegate).toBeHidden()
  await expect(drawer.getByText('张凯旋的 Personal Agent').first()).toBeVisible()
  // 走的是既有责任端点，不是新命令：没有 Task、没有执行、没有 TASK 坐标。
  expect(world.taskCreates).toEqual([])
  expect(world.commandResults.size).toBe(0)
  expect(world.executorAssignments).toHaveLength(1)
  expect(world.executorAssignments[0]!.body.actorPrincipalId).toBe(ids.agentPersonalPrincipal)
})

test('A double submit inside the create window produces exactly one Task command', async ({ page }) => {
  world.createLatencyMs = 400
  await page.goto(workUrl())
  const drawer = page.getByRole('dialog', { name: 'A05-1 工作项详情' })
  await drawer.getByRole('button', { name: '交给 Agent 处理' }).click()
  const delegate = page.getByRole('dialog', { name: '交给 Agent 处理' })

  await delegate.getByLabel('执行目标').fill('双击提交只能产生一条耐久命令')
  await delegate.getByRole('button', { name: '验证 Ref' }).click()
  await expect(delegate.getByText('PolicySnapshot Preflight 通过')).toBeVisible()

  // 第一次点击后命令在途；第二次是真实落在同一坐标的物理点击，必须被待态挡住。
  const submit = delegate.getByRole('button', { name: '分配并启动' })
  await submit.click()
  const box = await submit.boundingBox()
  expect(box).not.toBeNull()
  await page.mouse.click(box!.x + box!.width / 2, box!.y + box!.height / 2)

  await expect(delegate).toBeHidden({ timeout: 10_000 })
  expect(world.taskCreates).toHaveLength(1)
  expect(world.tasks).toHaveLength(1)
})

test('An in-flight different Executor pins the form to start and blocks silent reassignment', async ({ page }) => {
  world.responsibilities.push(responsibilityLine(
    '00000000-0000-0000-0000-000000000902', 'EXECUTOR',
    ids.agentCodingPrincipal, 'PERSONAL_AGENT', 'CrewScope Coding Agent', ids.agentCodingProfile,
  ))
  world.tasks.push(taskRow(ids.seedTask, '既有执行仍在进行', 'ACTIVE', 'WAITING'))
  await page.goto(workUrl())
  const drawer = page.getByRole('dialog', { name: 'A05-1 工作项详情' })
  await drawer.getByRole('button', { name: '交给 Agent 处理' }).click()
  const delegate = page.getByRole('dialog', { name: '交给 Agent 处理' })

  // 已有执行者：不再提供分配意图；其他 Agent 标注冲突并给出释放指引，而不是静默替换。
  await expect(delegate.getByRole('radio', { name: '分配并启动' })).toHaveCount(0)
  await expect(delegate.getByRole('radio', { name: '仅分配' })).toHaveCount(0)
  await expect(delegate.getByRole('button', { name: '启动执行' })).toBeVisible()
  await expect(delegate.getByText(/Executor · CrewScope Coding Agent/)).toBeVisible()
  await expect(delegate.getByText('当前执行者', { exact: true }).first()).toBeVisible()
  await expect(delegate.getByText(/张凯旋的 Personal Agent · 责任冲突——已有其他执行者责任/)).toBeVisible()
  // R42：在途执行不被热注入，补充要求走评论或新一轮。
  await expect(delegate.getByText(/当前执行仍按原说明继续/)).toBeVisible()
})

test('Without responsibility:manage the form offers start only', async ({ page }) => {
  world.session = sessionFor([permissions.responsibilityManage])
  await page.goto(workUrl())
  const drawer = page.getByRole('dialog', { name: 'A05-1 工作项详情' })
  await drawer.getByRole('button', { name: '交给 Agent 处理' }).click()
  const delegate = page.getByRole('dialog', { name: '交给 Agent 处理' })

  // 委托权威仍在（Owner 本人），但责任管理权缺失时表单不出现任何分配选项。
  await expect(delegate.getByRole('radio', { name: '分配并启动' })).toHaveCount(0)
  await expect(delegate.getByRole('radio', { name: '仅分配' })).toHaveCount(0)
  await expect(delegate.getByRole('button', { name: '启动执行' })).toBeVisible()
})

test('Publishing a comment records the discussion and never starts an execution', async ({ page }) => {
  await page.goto(workUrl())
  const drawer = page.getByRole('dialog', { name: 'A05-1 工作项详情' })

  // R42 的呈现合同：评论按钮与文案都说明这只是记录讨论，@ 不会触发执行。
  await expect(drawer.getByText(/评论仅记录讨论，不会启动执行/)).toBeVisible()
  await drawer.getByLabel('添加评论').fill('@林晨 请评估委托编排的边界')
  await drawer.getByRole('button', { name: '发布评论' }).click()
  await expect(drawer.getByText('@林晨 请评估委托编排的边界')).toBeVisible()

  expect(world.commentPosts).toEqual([{ content: '@林晨 请评估委托编排的边界' }])
  expect(world.taskCreates).toEqual([])
  expect(world.tasks).toEqual([])
})

test('The delegate deep link still opens the upgraded form in start mode', async ({ page }, testInfo) => {
  world.responsibilities.push(responsibilityLine(
    '00000000-0000-0000-0000-000000000902', 'EXECUTOR',
    ids.agentCodingPrincipal, 'PERSONAL_AGENT', 'CrewScope Coding Agent', ids.agentCodingProfile,
  ))
  await page.goto(workUrl({ delegate: 'coding' }))
  const delegate = page.getByRole('dialog', { name: '交给 Agent 处理' })

  // TaskIntent 确认后的「配置 Coding Task」深链原样进入升级表单：责任已定 → 直接启动模式。
  await expect(delegate).toBeVisible()
  await expect(delegate.getByRole('radio', { name: '分配并启动' })).toHaveCount(0)
  await expect(delegate.getByRole('button', { name: '启动执行' })).toBeVisible()

  // narrow 一轮（A07 模式）：对话框完整落进视口、主操作可达。
  if (testInfo.project.name === 'narrow-chromium') {
    const box = await delegate.boundingBox()
    const viewport = page.viewportSize()!
    expect(box).not.toBeNull()
    expect(box!.x).toBeGreaterThanOrEqual(0)
    expect(box!.y).toBeGreaterThanOrEqual(0)
    expect(box!.x + box!.width).toBeLessThanOrEqual(viewport.width)
    expect(box!.y + box!.height).toBeLessThanOrEqual(viewport.height)
  }
})
