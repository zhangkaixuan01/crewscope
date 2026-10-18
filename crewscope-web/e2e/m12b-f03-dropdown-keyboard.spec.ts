import { expect, test, type Locator, type Page, type Route } from '@playwright/test'
import { ids, mockApi } from './menu-walk'

/**
 * M12b-F03 验收契约：键盘行为从 6 种 → 1 种。
 *
 * 迁移前六套下拉各自手写 keydown：有的 Esc 不还焦、有的方向键不循环、有的连菜单语义都没有。
 * 现在合同只有一份（BaseMenu.vue），这组用例逐套抽测**同一份**合同：
 *   打开即聚焦首个可用条目（跳过 aria-disabled）；ArrowUp/Down 循环；Home/End 跳首尾；
 *   Escape 关闭并把焦点还给触发钮；Tab 从条目上关闭且不拦截。
 * mockApi（menu-walk 的空态兜底）打底，各用例只补自己需要的数据面——
 * 未覆盖的请求 route.fallback() 交回 mockApi，两条契约不复制对方的桩。
 */

const agentId = '00000000-0000-0000-0000-000000000171'
const memberId = '00000000-0000-0000-0000-000000000301'

function json(route: Route, value: unknown, headers: Record<string, string> = {}): Promise<void> {
  return route.fulfill({ status: 200, contentType: 'application/json', headers, body: JSON.stringify(value) })
}

const focusedText = (page: Page): Promise<string> =>
  page.evaluate(() => (document.activeElement as HTMLElement | null)?.textContent?.replace(/\s+/g, ' ').trim() ?? '')

const focusedRole = (page: Page): Promise<string> =>
  page.evaluate(() => document.activeElement?.getAttribute('role') ?? '')

/** 末项 End → 首项 Home → 再 End 的对称断言，不依赖具体文案。 */
async function assertRovingLoop(page: Page): Promise<void> {
  const first = await focusedText(page)
  await page.keyboard.press('End')
  await expect.poll(() => focusedText(page), { message: 'End 应跳到末项' }).not.toBe(first)
  const last = await focusedText(page)
  await page.keyboard.press('Home')
  await expect.poll(() => focusedText(page), { message: 'Home 应跳回首项' }).toBe(first)
  await page.keyboard.press('End')
  await expect.poll(() => focusedText(page), { message: 'End 应再次跳到同一末项' }).toBe(last)
}

async function assertEscapeAndTab(page: Page, trigger: Locator): Promise<void> {
  await page.keyboard.press('Escape')
  await expect(trigger).toHaveAttribute('aria-expanded', 'false')
  await expect(trigger).toBeFocused()

  await trigger.press('ArrowDown')
  await expect(trigger).toHaveAttribute('aria-expanded', 'true')
  await page.keyboard.press('Tab')
  await expect(trigger).toHaveAttribute('aria-expanded', 'false')
}

test('UserAccountMenu：menuitem 导航菜单的同一份键盘合同', async ({ page }) => {
  await mockApi(page)
  await page.goto('/today')
  // rail 与 topbar 各渲染一份账号菜单（desktop 看 rail、narrow 看 mobile-profile），
  // 按可见份收窄——不然 strict mode 撞两份、或落进被视口隐藏的那份。
  const trigger = page.locator('.rail-profile button[aria-label^="账号菜单："], .mobile-profile button[aria-label^="账号菜单："]').locator('visible=true')
  await expect(trigger).toBeVisible()

  // 触发钮上的 ArrowDown 也能开（不只 click），并聚焦首个可用条目。
  await trigger.press('ArrowDown')
  await expect(trigger).toHaveAttribute('aria-expanded', 'true')
  await expect.poll(() => focusedRole(page)).toBe('menuitem')
  await expect.poll(() => focusedText(page)).toBe('账号设置')

  await page.keyboard.press('ArrowDown')
  await expect.poll(() => focusedText(page)).toBe('退出当前设备')
  await page.keyboard.press('ArrowDown')
  await expect.poll(() => focusedText(page), { message: '末项再往下应循环回首项' }).toBe('账号设置')

  await assertRovingLoop(page)
  await assertEscapeAndTab(page, trigger)
})

test('ScopeSwitcher：menuitemradio 层级菜单的同一份键盘合同', async ({ page }) => {
  await mockApi(page)
  await page.goto('/today')
  // rail 与 topbar 各渲染一份切换器（narrow 下 rail 整体隐藏），取可见份定位，
  // 否则要么撞 strict mode 要么落进被视口隐藏的那份。
  const trigger = page.locator('.app-shell__rail > .scope-switcher-root button[aria-haspopup="menu"], .topbar-scope button[aria-haspopup="menu"]').locator('visible=true')
  await expect(trigger).toBeVisible()

  // ArrowUp 开：从末项开始——与 ArrowDown 开（首项）互补的两个入口都归 BaseMenu。
  await trigger.press('ArrowUp')
  await expect(trigger).toHaveAttribute('aria-expanded', 'true')
  const last = await focusedText(page)

  // 菜单结构：header 的「重新加载」menuitem 是首项，其后才是 Team/WorkProject 两组 radio。
  await page.keyboard.press('Home')
  await expect.poll(() => focusedRole(page)).toBe('menuitem')
  // 图标按钮没有 textContent，首个 menuitem 的名字在 aria-label 上。
  const first = await page.evaluate(() => document.activeElement?.getAttribute('aria-label') ?? '')
  expect(first).toContain('重新加载')
  expect(first).not.toBe(last)

  await page.keyboard.press('ArrowDown')
  await expect.poll(() => focusedRole(page)).toBe('menuitemradio')
  expect(await focusedText(page)).toContain('Platform Engineering')

  // Team radio 之后进入 WorkProject 组：组头上的「创建 WorkProject」也是 menuitem
  // （mock 会话授全量权限所以它存在），再往下才是项目 radio。
  await page.keyboard.press('ArrowDown')
  await expect.poll(() => page.evaluate(() => document.activeElement?.getAttribute('aria-label') ?? '')).toContain('创建 WorkProject')
  await page.keyboard.press('ArrowDown')
  expect(await focusedText(page)).toContain('CrewScope')
  await page.keyboard.press('Home')
  await expect.poll(() => page.evaluate(() => document.activeElement?.getAttribute('aria-label') ?? ''), { message: 'Home 应回首项（重新加载）' }).toBe(first)
  await page.keyboard.press('ArrowUp')
  await expect.poll(() => focusedText(page), { message: '首项往上应循环到末项' }).toBe(last)

  await assertEscapeAndTab(page, trigger)
})

test('MemberActionMenu：确认对话框入口菜单的同一份键盘合同', async ({ page }) => {
  await mockApi(page)
  // `(\?|$)`：请求 URL 可能带查询串，`$` 锚死会把列表让回 mockApi 的空数组兜底。
  await page.route(/\/api\/v1\/organizations\/[^/]+\/teams\/[^/]+\/members(\?|$)/, async route =>
    json(route, [{
      id: memberId, userPrincipalId: ids.principal, displayName: '张三', status: 'ACTIVE',
      joinMethod: 'INVITATION', joinedAt: '2026-09-01T00:00:00Z', version: 2,
    }]))
  await page.goto(`/team/members?team=${ids.team}&project=${ids.project}`)
  const trigger = page.getByRole('button', { name: '更多操作：张三' })
  await expect(trigger).toBeVisible()

  await trigger.press('ArrowDown')
  await expect(trigger).toHaveAttribute('aria-expanded', 'true')
  await expect.poll(() => focusedRole(page)).toBe('menuitem')
  await assertRovingLoop(page)
  await assertEscapeAndTab(page, trigger)
})

test('StatusTransitionMenu：可执行动作菜单跳过禁用条目', async ({ page }) => {
  await mockApi(page)
  await page.route(/\/api\/v1\/organizations\/[^/]+\/teams\/[^/]+\/work-projects\/[^/]+\/work-items(\?|$)/, async route => {
    // 第一条动作 enabled=false：打开时的初始聚焦必须跳过它——六套旧实现里
    // 只有 ActionMenu 做对了这件事，现在它是 BaseMenu 合同的一部分。
    const availableActions = [
      { actionId: 'complete', targetStatus: 'DONE', label: '标记完成', strength: 'SECONDARY', reversible: false,
        enabled: false, reason: 'GATE_NOT_PASSED', reasonMessage: '评审未通过前不能完成。', remedyLabel: null, remedyRoute: null },
      { actionId: 'submit-review', targetStatus: 'IN_REVIEW', label: '提交评审', strength: 'PRIMARY', reversible: true,
        enabled: true, reason: null, reasonMessage: null, remedyLabel: null, remedyRoute: null },
      { actionId: 'block', targetStatus: 'BLOCKED', label: '标记阻塞', strength: 'SECONDARY', reversible: true,
        enabled: true, reason: null, reasonMessage: null, remedyLabel: null, remedyRoute: null },
    ]
    return json(route, {
      items: [{
        id: '00000000-0000-0000-0000-000000000901', organizationId: ids.organization, teamId: ids.team,
        workspaceId: ids.workspace, projectId: ids.project, key: 'CRW-1', type: 'TASK',
        title: '键盘合同抽测条目', description: null, status: 'IN_PROGRESS', priority: 'HIGH',
        labels: [], dueAt: null, source: 'CREWSCOPE', sourceReference: null, version: 3,
        createdAt: '2026-10-16T08:00:00Z', createdByPrincipalId: ids.principal,
        updatedAt: '2026-10-17T08:00:00Z', updatedByPrincipalId: ids.principal, summary: null,
        availableActions,
      }],
      nextCursor: null,
    })
  })
  await page.goto(`/work?team=${ids.team}&project=${ids.project}`)
  const trigger = page.locator('.status-badge--interactive').first()
  await expect(trigger).toBeVisible()

  await trigger.press('ArrowDown')
  await expect(trigger).toHaveAttribute('aria-expanded', 'true')
  await expect.poll(() => focusedRole(page)).toBe('menuitem')
  await expect.poll(() => focusedText(page), { message: '初始聚焦必须跳过禁用的「标记完成」' }).toBe('提交评审')

  await page.keyboard.press('ArrowDown')
  await expect.poll(() => focusedText(page)).toBe('标记阻塞')
  await page.keyboard.press('ArrowDown')
  // 循环也只落在可用条目上，禁用条目被 roving 跳过。
  await expect.poll(() => focusedText(page), { message: '循环应跳过禁用条目' }).toBe('提交评审')
  await page.keyboard.press('Home')
  await expect.poll(() => focusedText(page)).toBe('提交评审')
  await page.keyboard.press('End')
  await expect.poll(() => focusedText(page)).toBe('标记阻塞')

  await page.keyboard.press('Escape')
  await expect(trigger).toHaveAttribute('aria-expanded', 'false')
  await expect(trigger).toBeFocused()
  await trigger.press('ArrowDown')
  await page.keyboard.press('Tab')
  await expect(trigger).toHaveAttribute('aria-expanded', 'false')
})

test('SavedViewsControl：本机视图菜单的同一份键盘合同', async ({ page }) => {
  await mockApi(page)
  await page.goto(`/work?team=${ids.team}&project=${ids.project}`)
  const trigger = page.getByRole('button', { name: '视图', exact: true })
  await expect(trigger).toBeVisible()

  // 先用真实用户路径存一条视图——菜单里才有可遍历的条目（本机存储按账号隔离，
  // 每个用例独立 context，互不串台）。
  await trigger.click()
  await page.getByLabel('视图名称').fill('键盘抽测视图')
  await page.getByRole('button', { name: '保存当前筛选' }).click()
  await expect(page.getByRole('menuitem', { name: '应用视图 键盘抽测视图' })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(trigger).toHaveAttribute('aria-expanded', 'false')

  await trigger.press('ArrowDown')
  await expect(trigger).toHaveAttribute('aria-expanded', 'true')
  await expect.poll(() => focusedRole(page)).toBe('menuitem')
  await assertRovingLoop(page)
  await assertEscapeAndTab(page, trigger)
})

test('ModelOptionPicker：listbox 搜索选择器的让渡合同', async ({ page }) => {
  await mockApi(page)
  const agentProfile = {
    id: agentId, principalId: ids.principal, displayName: '键盘抽测 Agent', principalStatus: 'ACTIVE',
    organizationId: ids.organization, teamId: ids.team, workspaceId: ids.workspace,
    ownershipType: 'USER', ownerMemberId: memberId, runtimeRole: 'CODING',
    templateKey: 'coding', templateVersion: 1, defaultProfile: false, status: 'ACTIVE',
    currentConfigurationRevision: 2, currentConfigurationHash: 'a'.repeat(64),
    createdAt: '2026-08-08T01:00:00Z', updatedAt: '2026-08-08T04:00:00Z', version: 2,
  }
  const model = (modelId: string, suffix: string) => ({
    connectionId: `00000000-0000-0000-0000-0000000020${suffix}`,
    connectionOwnerType: 'USER', connectionOwnerId: ids.principal,
    providerKey: 'deepseek', providerDisplayName: 'DeepSeek',
    catalogEntryId: `00000000-0000-0000-0000-0000000021${suffix}`,
    modelId, catalogRevision: 4, modelDisplayName: modelId, region: 'cn',
    contextWindowTokens: 128000, maximumOutputTokens: 120000, capabilities: ['TOOLS'],
    price: { inputPerMillionTokens: '0.1', outputPerMillionTokens: '0.2', cachedInputPerMillionTokens: '0.02', currencyCode: 'USD' },
  })
  await page.route(/\/api\/v1\//, async route => {
    const { pathname } = new URL(route.request().url())
    if (route.request().method() !== 'GET') return route.fallback()
    if (pathname.endsWith('/agent-profiles')) return json(route, { items: [agentProfile] })
    // 配置面板 fail-closed：没有 template 元数据就不渲染可配置槽位——主模型 picker 在
    // memberConfigurableSlots 里，必须喂一条与 agentProfile 的 templateKey/version 对得上的模板。
    if (pathname.endsWith('/agent-templates')) {
      // 只应答 USER 请求：TEAM 那次给同一条会让 findExactTemplate 的坐标去重前出现双匹配。
      const ownershipType = new URL(route.request().url()).searchParams.get('ownershipType')
      const items = ownershipType === 'USER'
        ? [{
            publisherType: 'ORGANIZATION', publisherId: ids.organization, key: 'coding', version: 1,
            runtimeRole: 'CODING', allowedOwnershipTypes: ['USER'],
            allowedExecutionScopes: ['PERSONAL', 'TEAM'], declaredCapabilities: ['TOOLS'],
            requiredModelCapabilities: ['TOOLS'], approvedSkillKeys: [],
            memberConfigurableSlots: ['primary', 'fallback'], administratorConfigurableSlots: [],
            creatable: true, platformManaged: false, contentHash: 'd'.repeat(64),
            status: 'ACTIVE', lifecycleVersion: 1,
          }]
        : []
      return json(route, { items })
    }
    if (/\/agent-profiles\/[^/]+$/.test(pathname)) return json(route, agentProfile, { ETag: '"2"' })
    if (pathname.endsWith('/model-catalog')) return json(route, { items: [model('deepseek-v4-flash', '01'), model('deepseek-chat', '02')] })
    // 强 ETag 是读-改-写契约的一半：这个响应头丢了，配置面板会把「配置已变更」误判成冲突。
    if (pathname.endsWith('/configurations/current')) {
      return json(route, {
        revision: 2, previousRevision: 1, templateKey: 'coding', templateVersion: 1,
        templateContentHash: 'b'.repeat(64),
        personalBinding: { executionScope: 'PERSONAL', kind: 'EXPLICIT', primary: null, fallback: null },
        teamBinding: { executionScope: 'TEAM', kind: 'EXPLICIT', primary: null, fallback: null },
        supplementalInstructions: null, approvedSkillKeys: [], memoryPolicy: null, budgetPolicy: null,
        generateOptions: { temperature: null, topP: null, maximumOutputTokens: 120000, reasoningMode: 'DEFAULT',
          cacheEnabled: true, parallelToolCalls: true, seed: null, maximumAttempts: 2 },
        policyPackId: 'default', policyPackVersion: 1, configurationHash: 'c'.repeat(64),
        createdAt: '2026-08-08T01:00:00Z',
      }, { ETag: '"2"' })
    }
    return route.fallback()
  })
  await page.goto(`/settings/agents?agent=${agentId}&team=${ids.team}&project=${ids.project}`)
  // 触发钮的可访问名就是 label 的「主模型」（placeholder 文本只是视觉内容）；同 label 里的
  // native fallback select 带 aria-hidden 且不是 button，不会撞 strict mode。
  const trigger = page.getByRole('button', { name: '主模型', exact: true }).first()
  await expect(trigger).toBeVisible()

  await trigger.click()
  // initialFocus=none + 组件自聚焦搜索框：这是 listbox 形态与 menu 形态的分界。
  await expect(page.getByLabel('搜索模型')).toBeFocused()
  // 搜索框让渡方向键：ArrowDown 把 roving 焦点交给第一个 option。
  await page.keyboard.press('ArrowDown')
  await expect.poll(() => focusedRole(page)).toBe('option')
  const first = await focusedText(page)
  await page.keyboard.press('ArrowDown')
  await expect.poll(() => focusedText(page)).not.toBe(first)
  await page.keyboard.press('ArrowUp')
  await expect.poll(() => focusedText(page)).toBe(first)

  // 搜索词收窄后，roving 只在幸存条目里循环。
  await page.keyboard.press('Escape')
  await expect(trigger).toHaveAttribute('aria-expanded', 'false')
  await expect(trigger).toBeFocused()
  await trigger.click()
  await page.getByLabel('搜索模型').fill('chat')
  await page.keyboard.press('ArrowDown')
  await expect.poll(() => focusedText(page)).toContain('chat')
  await page.keyboard.press('ArrowDown')
  await expect.poll(() => focusedText(page), { message: '单条结果应原地循环' }).toContain('chat')
})
