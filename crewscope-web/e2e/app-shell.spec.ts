import { expect, test, type Route } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

const ids = {
  organization: '00000000-0000-0000-0000-000000000001',
  principal: '00000000-0000-0000-0000-000000000101',
  team: '00000000-0000-0000-0000-000000000201',
  secondTeam: '00000000-0000-0000-0000-000000000202',
  member: '00000000-0000-0000-0000-000000000301',
  project: '00000000-0000-0000-0000-000000000401',
  secondProject: '00000000-0000-0000-0000-000000000402',
  workspace: '00000000-0000-0000-0000-000000000501',
  secondWorkspace: '00000000-0000-0000-0000-000000000502',
  workItem: '00000000-0000-0000-0000-000000000601',
  secondPrincipal: '00000000-0000-0000-0000-000000000102',
  thirdPrincipal: '00000000-0000-0000-0000-000000000103',
  specialistAgent: '00000000-0000-0000-0000-000000000104',
}

test.beforeEach(async ({ page }) => {
  // Keep Today and date rendering deterministic so visual diffs represent UI changes instead of wall-clock drift.
  await page.clock.setFixedTime(new Date('2026-08-08T04:00:00Z'))
  const workItems = [
    workItem(ids.workItem, 'CRW-18', '共享范围与筛选状态', 'FEATURE', 'IN_PROGRESS', 'HIGH'),
    workItem('00000000-0000-0000-0000-000000000602', 'CRW-19', '修复工作项游标', 'BUG', 'READY', 'URGENT'),
    workItem('00000000-0000-0000-0000-000000000603', 'CRW-20', '准备阶段发布', 'TASK', 'BACKLOG', 'MEDIUM'),
    workItem('00000000-0000-0000-0000-000000000604', 'CRW-21', '审核协作入口', 'FEATURE', 'IN_REVIEW', 'HIGH'),
    workItem('00000000-0000-0000-0000-000000000605', 'CRW-22', '归档测试证据', 'TASK', 'DONE', 'LOW'),
  ]
  const comments = [{ id: '00000000-0000-0000-0000-000000000701', workItemId: ids.workItem, authorPrincipalId: ids.principal, content: '已确认交付范围。', source: 'CREWSCOPE', externalId: null, createdAt: '2026-08-08T03:00:00Z' }]
  const resources: Array<{ id: string; workItemId: string; resourceType: string; resourceReference: string; label: string | null; createdAt: string; createdByPrincipalId: string }> = [{ id: '00000000-0000-0000-0000-000000000801', workItemId: ids.workItem, resourceType: 'REPOSITORY', resourceReference: 'crewscope-java', label: '主仓库', createdAt: '2026-08-08T03:10:00Z', createdByPrincipalId: ids.principal }]
  let responsibilities = [
    responsibility('00000000-0000-0000-0000-000000000901', 'OWNER', ids.principal, 'USER', '张凯旋'),
    responsibility('00000000-0000-0000-0000-000000000902', 'EXECUTOR', ids.secondPrincipal, 'USER', '林晨'),
    responsibility('00000000-0000-0000-0000-000000000903', 'REVIEWER', ids.specialistAgent, 'SPECIALIST_AGENT', 'Architecture Reviewer'),
  ]
  const timeline = [
    timelineEvent('00000000-0000-0000-0000-000000001001', 'RESPONSIBILITY_ASSIGNED', '2026-08-08T03:20:00Z', '林晨'),
    timelineEvent('00000000-0000-0000-0000-000000001002', 'WORK_ITEM_CREATED', '2026-08-08T01:00:00Z', '张凯旋'),
  ]
  await page.route(/\/api\/v1\//, async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    if (request.method() === 'GET' && path.endsWith('/teams')) {
      await fulfillJson(route, [team(ids.team, 'Platform Engineering', ids.workspace), team(ids.secondTeam, 'Security Engineering', ids.secondWorkspace)])
      return
    }
    if (request.method() === 'GET' && path.endsWith(`/${ids.team}/work-projects`)) {
      await fulfillJson(route, { items: [project(ids.project, ids.team, ids.workspace, 'CRW', 'CrewScope')], nextCursor: null })
      return
    }
    if (request.method() === 'GET' && path.endsWith(`/${ids.secondTeam}/work-projects`)) {
      await fulfillJson(route, { items: [project(ids.secondProject, ids.secondTeam, ids.secondWorkspace, 'SEC', 'Runtime Security')], nextCursor: null })
      return
    }
    if (request.method() === 'GET' && path.endsWith('/members')) {
      await fulfillJson(route, [
        { id: ids.member, userPrincipalId: ids.principal, status: 'ACTIVE', joinMethod: 'CREATED_WITH_TEAM', joinedAt: '2026-08-08T01:00:00Z', version: 0 },
        { id: '00000000-0000-0000-0000-000000000302', userPrincipalId: ids.secondPrincipal, status: 'ACTIVE', joinMethod: 'INVITED', joinedAt: '2026-08-08T01:10:00Z', version: 0 },
        { id: '00000000-0000-0000-0000-000000000303', userPrincipalId: ids.thirdPrincipal, status: 'ACTIVE', joinMethod: 'INVITED', joinedAt: '2026-08-08T01:20:00Z', version: 0 },
      ])
      return
    }
    if (path.endsWith('/work-items') && request.method() === 'GET') {
      const status = url.searchParams.get('status')
      const matching = status ? workItems.filter(item => item.status === status) : workItems
      const after = url.searchParams.get('after')
      await fulfillJson(route, after
        ? { items: matching.slice(4), nextCursor: null }
        : { items: status ? matching : matching.slice(0, 4), nextCursor: status ? null : 'work-page-2' })
      return
    }
    if (path.endsWith('/work-items') && request.method() === 'POST') {
      const input = request.postDataJSON() as ReturnType<typeof workItem>
      workItems.unshift({ ...workItem(crypto.randomUUID(), input.key, input.title, input.type, 'BACKLOG', input.priority), description: input.description, labels: input.labels, dueAt: input.dueAt })
      await route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify({ commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(), committedVersion: 0, correlationId: crypto.randomUUID() }) })
      return
    }
    const transitionMatch = path.match(/\/work-items\/([^/]+)\/transitions$/)
    if (transitionMatch && request.method() === 'POST') {
      const item = workItems.find(candidate => candidate.id === transitionMatch[1])
      if (!item) return fulfillError(route, 404, 'work_item_not_found', 'WorkItem not found')
      const input = request.postDataJSON() as { targetStatus: string }
      if (input.targetStatus === 'BLOCKED') {
        item.version = 2
        return fulfillError(route, 409, 'optimistic_lock_conflict', 'WorkItem version changed', 2)
      }
      expect(request.headers()['if-match']).toBe(`"${item.version}"`)
      item.status = input.targetStatus
      item.version += 1
      await fulfillReceipt(route, item.version)
      return
    }
    const commentMatch = path.match(/\/work-items\/([^/]+)\/comments$/)
    if (commentMatch && request.method() === 'POST') {
      const input = request.postDataJSON() as { content: string }
      comments.push({ id: crypto.randomUUID(), workItemId: commentMatch[1]!, authorPrincipalId: ids.principal, content: input.content, source: 'CREWSCOPE', externalId: null, createdAt: '2026-08-08T04:00:00Z' })
      await fulfillReceipt(route, 0)
      return
    }
    const resourceMatch = path.match(/\/work-items\/([^/]+)\/resource-links$/)
    if (resourceMatch && request.method() === 'POST') {
      const input = request.postDataJSON() as { resourceType: string; resourceReference: string; label: string | null }
      resources.push({ id: crypto.randomUUID(), workItemId: resourceMatch[1]!, ...input, createdAt: '2026-08-08T04:10:00Z', createdByPrincipalId: ids.principal })
      await fulfillReceipt(route, 0)
      return
    }
    const responsibilityMatch = path.match(/\/work-items\/([^/]+)\/responsibilities$/)
    if (responsibilityMatch && request.method() === 'GET') {
      await fulfillJson(route, responsibilities.filter(entry => entry.workItemId === responsibilityMatch[1]))
      return
    }
    const ownerMatch = path.match(/\/work-items\/([^/]+)\/responsibilities\/owner$/)
    if (ownerMatch && request.method() === 'POST') {
      const input = request.postDataJSON() as { actorPrincipalId: string; expectedAssignmentId: string; expectedVersion: number }
      const owner = responsibilities.find(entry => entry.role === 'OWNER')!
      expect(input).toMatchObject({ expectedAssignmentId: owner.id, expectedVersion: owner.version })
      responsibilities = responsibilities.filter(entry => entry.role !== 'OWNER')
      responsibilities.unshift(responsibility(crypto.randomUUID(), 'OWNER', input.actorPrincipalId, 'USER', '周宁'))
      timeline.unshift(timelineEvent(crypto.randomUUID(), 'RESPONSIBILITY_ASSIGNED', '2026-08-08T04:20:00Z', '张凯旋'))
      await fulfillReceipt(route, 0)
      return
    }
    const assignmentMatch = path.match(/\/work-items\/([^/]+)\/responsibilities\/(executors|gate-reviewers|advisory-reviewers)$/)
    if (assignmentMatch && request.method() === 'POST') {
      const input = request.postDataJSON() as { actorPrincipalId: string }
      const role = assignmentMatch[2] === 'executors' ? 'EXECUTOR' : 'REVIEWER'
      const actorType = assignmentMatch[2] === 'gate-reviewers' ? 'USER' : assignmentMatch[2] === 'advisory-reviewers' ? 'SPECIALIST_AGENT' : input.actorPrincipalId === ids.specialistAgent ? 'TEAM_AGENT' : 'USER'
      responsibilities.push(responsibility(crypto.randomUUID(), role, input.actorPrincipalId, actorType, actorType === 'USER' ? '周宁' : 'Agent'))
      await fulfillReceipt(route, 0)
      return
    }
    const releaseMatch = path.match(/\/work-items\/([^/]+)\/responsibilities\/([^/]+)\/releases$/)
    if (releaseMatch && request.method() === 'POST') {
      const assignment = responsibilities.find(entry => entry.id === releaseMatch[2])!
      expect(request.headers()['if-match']).toBe(`"${assignment.version}"`)
      responsibilities = responsibilities.filter(entry => entry.id !== releaseMatch[2])
      await fulfillReceipt(route, assignment.version + 1)
      return
    }
    const timelineMatch = path.match(/\/work-items\/([^/]+)\/timeline$/)
    if (timelineMatch && request.method() === 'GET') {
      await fulfillJson(route, url.searchParams.get('after')
        ? { items: [timeline.at(-1), timelineEvent('00000000-0000-0000-0000-000000001003', 'COMMENT_ADDED', '2026-08-07T20:00:00Z', '张凯旋')], nextCursor: null }
        : { items: timeline, nextCursor: 'timeline-page-2' })
      return
    }
    const detailMatch = path.match(/\/work-items\/([^/]+)$/)
    if (detailMatch && request.method() === 'GET') {
      const item = workItems.find(candidate => candidate.id === detailMatch[1])
      if (!item) return fulfillError(route, 404, 'work_item_not_found', 'WorkItem not found')
      await route.fulfill({ status: 200, contentType: 'application/json', headers: { ETag: `"${item.version}"` }, body: JSON.stringify({ workItem: item, comments: comments.filter(entry => entry.workItemId === item.id), resourceLinks: resources.filter(entry => entry.workItemId === item.id) }) })
      return
    }
    await route.fulfill({ status: 404, contentType: 'application/json', body: '{"code":"not_found"}' })
  })
})

test('Conversation and Today share the focused scope', async ({ page }) => {
  await page.goto(`/conversation?focus=CRW-18&team=${ids.team}&project=${ids.project}`)
  await expect(page.getByRole('heading', { name: 'CRW-18 · 对话工作区预览' })).toBeVisible()
  await expect(page.getByText(/不会创建 Conversation、TaskIntent、TaskExecution、AgentRun/)).toBeVisible()
  await expect(page.getByText('执行中', { exact: true })).toHaveCount(0)

  await page.getByRole('link', { name: '工作台', exact: true }).click()

  await expect(page).toHaveURL(new RegExp(`/today\\?.*focus=CRW-18.*team=${ids.team}.*project=${ids.project}`))
  await expect(page.getByRole('heading', { name: 'Platform Engineering', exact: true })).toBeVisible()
})

test('Work restores URL scope and ScopeSwitcher changes the Team', async ({ page }) => {
  await page.goto(`/work?team=${ids.secondTeam}&project=${ids.secondProject}`)
  await expect(page.getByRole('heading', { name: 'Runtime Security', exact: true, level: 1 })).toBeVisible()

  await page.getByRole('button', { name: /Security Engineering/ }).click()
  await page.getByRole('region', { name: '切换团队和项目' }).getByRole('button', { name: /Platform Engineering/ }).click()

  await expect(page).toHaveURL(new RegExp(`/work\\?.*team=${ids.team}.*project=${ids.project}`))
  await expect(page.getByRole('heading', { name: 'CrewScope', exact: true, level: 1 })).toBeVisible()
})

test('member management remains usable at the configured viewport', async ({ page }) => {
  await page.goto(`/team/members?team=${ids.team}&project=${ids.project}`)

  await expect(page.getByRole('heading', { name: 'Platform Engineering 成员' })).toBeVisible()
  await expect(page.getByRole('table', { name: '团队成员列表' })).toBeVisible()
  await expect(page.getByRole('button', { name: '添加成员', exact: true }).first()).toBeVisible()
})

test('Work restores filters and groups matching items on the Board', async ({ page }) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&view=board&status=all&type=FEATURE&priority=HIGH`)

  await expect(page.getByLabel('工作项看板')).toBeVisible()
  await expect(page.getByLabel('进行中').getByRole('button', { name: '打开 CRW-18 共享范围与筛选状态' })).toBeVisible()
  await expect(page.getByLabel('审查中').getByRole('button', { name: '打开 CRW-21 审核协作入口' })).toBeVisible()
  await expect(page.getByText('修复工作项游标')).toHaveCount(0)

  await page.getByRole('button', { name: '列表视图' }).click()
  await expect(page).toHaveURL(/view=list/)
  await page.reload()
  await expect(page.getByLabel('工作项列表')).toBeVisible()
  await expect(page.getByRole('combobox').nth(1)).toHaveValue('FEATURE')
})

test('Work clears local type and priority filters in one route update', async ({ page }) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&view=list&status=all&type=BUG&priority=HIGH`)
  await expect(page.getByText('没有符合筛选条件的工作项')).toBeVisible()

  await page.getByRole('button', { name: '清除本地筛选' }).click()

  await expect(page.getByLabel('工作项列表')).toBeVisible()
  await expect(page.getByRole('combobox').nth(1)).toHaveValue('all')
  await expect(page.getByRole('combobox').nth(2)).toHaveValue('all')
  const query = new URL(page.url()).searchParams
  expect(query.get('type')).toBe('all')
  expect(query.get('priority')).toBe('all')
})

test('Work continues from the opaque Cursor', async ({ page }) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}`)
  await expect(page.getByText('归档测试证据')).toHaveCount(0)

  await page.getByRole('button', { name: '加载更多工作项' }).click()

  await expect(page.getByRole('button', { name: '打开 CRW-22 归档测试证据' })).toBeVisible()
})

test('Work creates a native WorkItem and refreshes the active query', async ({ page }) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}`)
  await page.getByRole('button', { name: '新建工作项' }).click()

  const dialog = page.getByRole('dialog', { name: '新建工作项' })
  await dialog.getByLabel('标题').fill('补充团队发布检查项')
  await dialog.getByLabel('优先级').selectOption('HIGH')
  await dialog.getByLabel('标签').fill('release, team')
  await dialog.getByRole('button', { name: '创建工作项' }).click()

  await expect(dialog).toBeHidden()
  await expect(page.getByText('补充团队发布检查项')).toBeVisible()
})

test('WorkItem detail supports deep links, Escape and focus restoration', async ({ page }) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}`)
  const dialog = page.getByRole('dialog', { name: 'CRW-18 工作项详情' })

  await expect(dialog).toBeVisible()
  await expect(page).toHaveURL(/focus=CRW-18/)
  await expect(dialog.getByRole('button', { name: '关闭工作项详情' })).toBeFocused()
  await page.keyboard.press('Shift+Tab')
  await expect(dialog.getByRole('button', { name: '交给 Agent 处理（规划中）' })).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(dialog.getByRole('button', { name: '关闭工作项详情' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  await expect(page).not.toHaveURL(/workItem=/)

  const card = page.getByRole('button', { name: '打开 CRW-18 共享范围与筛选状态' })
  await card.click()
  await page.getByRole('button', { name: '关闭工作项详情' }).click()
  await expect(card).toBeFocused()
})

test('WorkItem detail transitions, comments, links and continues in Conversation', async ({ page }) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}`)
  const dialog = page.getByRole('dialog', { name: 'CRW-18 工作项详情' })

  await dialog.getByRole('button', { name: '提交流转' }).click()
  await expect(dialog.getByText('审查中', { exact: true }).first()).toBeVisible()
  await dialog.getByLabel('添加评论').fill('补充端到端验收结论')
  await dialog.getByRole('button', { name: '发送评论' }).click()
  await expect(dialog.getByText('补充端到端验收结论')).toBeVisible()

  await dialog.getByLabel('引用').fill('https://example.com/evidence')
  await dialog.getByLabel('显示名称').fill('端到端证据')
  await dialog.getByRole('button', { name: '关联资源' }).click()
  await expect(dialog.getByText('端到端证据')).toBeVisible()

  await dialog.getByRole('button', { name: '与 Personal Agent 讨论' }).click()
  await expect(page).toHaveURL(/\/conversation\?/)
  expect(new URL(page.url()).searchParams.get('focus')).toBe('CRW-18')
  expect(new URL(page.url()).searchParams.get('workItem')).toBe(ids.workItem)
  await expect(page.getByRole('heading', { name: /CRW-18/ })).toBeVisible()
})

test('WorkItem responsibility management and Timeline keep server policy boundaries visible', async ({ page }) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}`)
  const dialog = page.getByRole('dialog', { name: 'CRW-18 工作项详情' })

  await expect(dialog.getByText('Architecture Reviewer')).toBeVisible()
  await expect(dialog.getByText('不具有 Gate 效力')).toBeVisible()
  await dialog.getByLabel('新 Owner').selectOption(ids.thirdPrincipal)
  await dialog.getByRole('button', { name: '替换', exact: true }).click()
  await expect(dialog.getByText('周宁').first()).toBeVisible()

  await dialog.getByLabel('Gate Reviewer').selectOption(ids.secondPrincipal)
  await expect(dialog.getByText(/默认职责分离策略会拒绝/)).toBeVisible()
  await dialog.getByLabel('Gate Reviewer').selectOption(ids.thirdPrincipal)
  await dialog.locator('.responsibility-actions > form').nth(2).getByRole('button', { name: '添加' }).click()
  await expect(dialog.getByText('具备 Gate 审查效力')).toBeVisible()

  await dialog.getByRole('button', { name: /释放 Architecture Reviewer/ }).click()
  await expect(dialog.getByText('Architecture Reviewer')).toHaveCount(0)
  await expect(dialog.getByText('分配责任').first()).toBeVisible()
  await dialog.getByRole('button', { name: '加载更早活动' }).click()
  await expect(dialog.getByLabel('工作项时间线').getByText('添加评论')).toBeVisible()
})

test('Agent execution entry remains an explicit non-executing placeholder', async ({ page }) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}`)
  const dialog = page.getByRole('dialog', { name: 'CRW-18 工作项详情' })

  await dialog.getByRole('button', { name: '交给 Agent 处理（规划中）' }).click()

  await expect(dialog.getByText(/当前不会创建虚假执行/)).toBeVisible()
})

test('Responsibility and Timeline facts remain consistent after reload and Cursor replay', async ({ page }) => {
  const url = `/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}`
  await page.goto(url)
  let dialog = page.getByRole('dialog', { name: 'CRW-18 工作项详情' })

  await dialog.getByLabel('新 Owner').selectOption(ids.thirdPrincipal)
  await dialog.getByRole('button', { name: '替换', exact: true }).click()
  await dialog.getByRole('button', { name: '加载更早活动' }).click()
  await expect(dialog.getByLabel('工作项时间线').getByText('添加评论')).toBeVisible()

  await page.reload()
  dialog = page.getByRole('dialog', { name: 'CRW-18 工作项详情' })
  await expect(dialog.getByText('周宁').first()).toBeVisible()
  await expect(dialog.getByText('分配责任').first()).toBeVisible()
  await dialog.getByRole('button', { name: '加载更早活动' }).click()
  await expect(dialog.getByLabel('工作项时间线').getByText('添加评论')).toHaveCount(1)
})

test('WorkItem detail refreshes after an optimistic version conflict', async ({ page }) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}`)
  const dialog = page.getByRole('dialog', { name: 'CRW-18 工作项详情' })

  await dialog.getByLabel('目标状态').selectOption('BLOCKED')
  await dialog.getByRole('button', { name: '提交流转' }).click()

  await expect(dialog.getByText('检测到并发更新')).toBeVisible()
  await expect(dialog.getByText(/服务端当前版本为 v2/)).toBeVisible()
  await expect(dialog.getByText('v2', { exact: true })).toBeVisible()
})

test('AppShell visual baseline', async ({ page }, testInfo) => {
  await page.goto(`/conversation?focus=CRW-18&team=${ids.team}&project=${ids.project}`)
  await expect(page.getByRole('heading', { name: /CRW-18/ })).toBeVisible()
  await expect(page).toHaveScreenshot(`conversation-${testInfo.project.name}.png`, { fullPage: true })

  await page.goto(`/today?team=${ids.team}&project=${ids.project}`)
  await expect(page.getByText('先确认范围，再推进今天的团队工作。')).toBeVisible()
  await expect(page).toHaveScreenshot(`control-${testInfo.project.name}.png`, { fullPage: true })
})

test('M1 Work visual baseline', async ({ page }, testInfo) => {
  await page.goto(`/work?team=${ids.team}&project=${ids.project}&view=list&status=all&type=all&priority=all`)
  await expect(page.getByLabel('工作项列表')).toBeVisible()
  await expect(page).toHaveScreenshot(`work-list-${testInfo.project.name}.png`, { fullPage: true })

  await page.getByRole('button', { name: '看板视图' }).click()
  await expect(page.getByLabel('工作项看板')).toBeVisible()
  await expect(page).toHaveScreenshot(`work-board-${testInfo.project.name}.png`, { fullPage: true })

  await page.getByLabel('进行中').getByRole('button', { name: /打开 CRW-18/ }).click()
  await expect(page.getByRole('dialog', { name: 'CRW-18 工作项详情' }).getByText('Architecture Reviewer')).toBeVisible()
  await expect(page).toHaveScreenshot(`work-detail-${testInfo.project.name}.png`, { fullPage: true })
})

test('M1 primary pages meet automated WCAG 2.2 AA checks', async ({ page }) => {
  const routes = [
    { path: `/today?team=${ids.team}&project=${ids.project}`, ready: () => page.getByText('先确认范围，再推进今天的团队工作。') },
    { path: `/work?team=${ids.team}&project=${ids.project}`, ready: () => page.getByLabel('工作项列表') },
    { path: `/team/members?team=${ids.team}&project=${ids.project}`, ready: () => page.getByRole('table', { name: '团队成员列表' }) },
    { path: `/work?team=${ids.team}&project=${ids.project}&workItem=${ids.workItem}`, ready: () => page.getByRole('dialog', { name: 'CRW-18 工作项详情' }) },
  ]

  for (const route of routes) {
    await page.goto(route.path)
    await expect(route.ready()).toBeVisible()
    const result = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
      .analyze()
    expect(result.violations, `${route.path}\n${formatAxeViolations(result.violations)}`).toEqual([])
  }
})

function team(id: string, name: string, workspaceId: string) {
  return { id, organizationId: ids.organization, name, status: 'ACTIVE', initializationStatus: 'READY', ownerMemberId: ids.member, defaultWorkspaceId: workspaceId, version: 0 }
}

function project(id: string, teamId: string, workspaceId: string, key: string, name: string) {
  return { id, organizationId: ids.organization, teamId, workspaceId, key, name, status: 'ACTIVE', version: 0, createdAt: '2026-08-08T01:00:00Z', createdByPrincipalId: ids.principal, updatedAt: '2026-08-08T02:00:00Z', updatedByPrincipalId: ids.principal }
}

function workItem(id: string, key: string, title: string, type: string, status: string, priority: string) {
  return { id, organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace, projectId: ids.project, key, type, title, description: `${title}的协作说明`, status, priority, labels: ['team-work'], dueAt: null, source: 'CREWSCOPE', sourceReference: null, version: 0, createdAt: '2026-08-08T01:00:00Z', createdByPrincipalId: ids.principal, updatedAt: '2026-08-08T02:00:00Z', updatedByPrincipalId: ids.principal }
}

function responsibility(id: string, role: string, actorPrincipalId: string, actorType: string, actorDisplayName: string) {
  return { id, workItemId: ids.workItem, role, actorPrincipalId, actorType, actorMemberId: actorType === 'USER' ? crypto.randomUUID() : null, actorDisplayName, status: 'ACTIVE', assignedByPrincipalId: ids.principal, assignedAt: '2026-08-08T03:20:00Z', acceptedAt: '2026-08-08T03:20:00Z', version: 0 }
}

function timelineEvent(eventId: string, eventType: string, occurredAt: string, actorDisplayName: string) {
  return { eventId, domainEventId: eventId, source: 'DOMAIN_EVENT', eventType, schemaVersion: '1', aggregateType: 'WorkItem', aggregateId: ids.workItem, aggregateVersion: 0, actorType: 'USER', actorPrincipalId: ids.principal, actorDisplayName, correlationId: crypto.randomUUID(), causationId: null, occurredAt, outcome: 'SUCCEEDED', payload: { workItemId: ids.workItem } }
}

function fulfillJson(route: Route, value: unknown): Promise<void> {
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(value) })
}

function fulfillReceipt(route: Route, committedVersion: number): Promise<void> {
  return route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify({ commandId: crypto.randomUUID(), domainEventId: crypto.randomUUID(), committedVersion, correlationId: crypto.randomUUID() }) })
}

function fulfillError(route: Route, status: number, code: string, message: string, currentVersion: number | null = null): Promise<void> {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code, message, correlationId: crypto.randomUUID(), retryable: status >= 500 || status === 409, currentVersion, details: {} }) })
}

function formatAxeViolations(violations: Array<{ id: string; impact?: string | null; nodes: Array<{ target: unknown }> }>): string {
  return violations
    .map(violation => `${violation.id} (${violation.impact ?? 'unknown'}): ${violation.nodes.map(node => JSON.stringify(node.target)).join(', ')}`)
    .join('\n')
}
