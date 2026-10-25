import { expect, test, type Page } from '@playwright/test'
import { ids, mockAuditApi, probeLayout } from './layout-audit-support'

/**
 * M13g 交互态布局几何审查（可复跑）：M13f 证明的是「默认态零变形」，但页面的大量像素
 * 面积藏在交互态里——面板折叠条、teleport 抽屉、非 teleport 弹层、命令面板与移动端
 * 导航抽屉只有打开后才参与布局。本 spec 在同一套 mock 数据面上执行确定性交互，然后
 * 对「交互后的整份文档」（含 teleport 到 body 的弹层）复用 M13f 盒探针判定：
 * 位置是否合理、是否展示完整、是否变形。
 *
 * 覆盖面（选择器全部来自组件的可访问名/既有类名，与 M13f 数据策略一致）：
 * - conversation 四种面板形态：左折叠 / 右折叠 / 双折叠 / 专注模式（消息列独占）
 * - work 工作项详情抽屉（BaseDrawer，teleport 到 body）
 * - 账号菜单弹层（BaseMenu，留在子树内）
 * - 命令面板（⌘K 全局快捷键）
 * - 窄视口移动端导航抽屉（AppShell mobile-menu-toggle）
 *
 * 弹层自身的裁剪语义（菜单内容超出滚动）属于设计截断；探针对横滚容器内部的排除
 * 规则继续生效，这里只抓「越出视口 / 页面级溢出 / 无意截断 / fixed 越界」四类铁证。
 */
const conversationPath = `/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}`
const workPath = `/work?team=${ids.team}&project=${ids.project}`

type InteractionCase = {
  name: string
  path: string
  /** narrow-only 的用例只在窄视口执行；desktop-only 反之；both 两视口都执行。 */
  scope: 'desktop' | 'narrow' | 'both'
  act: (page: Page) => Promise<void>
  /** 交互必须真正改变页面状态：断言不成立说明交互没生效，几何结论就是假绿。 */
  verify: (page: Page) => Promise<void>
}

async function settle(page: Page): Promise<void> {
  // 交互后的过渡动画（cs-drawer 300ms 级）用固定等待收口，与 M13f 口径一致不引入 race。
  await page.waitForTimeout(500)
}

const interactions: InteractionCase[] = [
  {
    name: 'conversation-left-pane-collapsed',
    path: conversationPath,
    scope: 'desktop',
    act: async page => {
      await page.getByRole('button', { name: '折叠对话列表' }).click()
    },
    verify: async page => {
      await expect(page.locator('.conversation-list-panel.collapsed')).toHaveCount(1)
    },
  },
  {
    // 右栏在 1440 视口默认自动折叠（竖排展开轨）；「展开」后的三栏并存才是挤压风险态。
    name: 'conversation-right-pane-expanded',
    path: conversationPath,
    scope: 'desktop',
    act: async page => {
      await expect(page.locator('.participant-panel.collapsed')).toHaveCount(1)
      await page.getByRole('complementary', { name: '对话参与者' })
        .getByRole('button', { name: '折叠参与者面板' })
        .click()
    },
    verify: async page => {
      await expect(page.locator('.participant-panel.collapsed')).toHaveCount(0)
    },
  },
  {
    name: 'conversation-both-panes-collapsed',
    path: conversationPath,
    scope: 'desktop',
    act: async page => {
      await page.getByRole('button', { name: '折叠对话列表' }).click()
      await expect(page.locator('.participant-panel.collapsed')).toHaveCount(1)
    },
    verify: async page => {
      await expect(page.locator('.conversation-list-panel.collapsed')).toHaveCount(1)
      await expect(page.locator('.participant-panel.collapsed')).toHaveCount(1)
    },
  },
  {
    name: 'conversation-focus-mode',
    path: conversationPath,
    scope: 'desktop',
    act: async page => {
      await page.locator('.focus-mode-toggle').click()
    },
    verify: async page => {
      await expect(page.locator('.conversation-workspace--focus')).toHaveCount(1)
    },
  },
  {
    name: 'conversation-account-menu-open',
    path: conversationPath,
    scope: 'desktop',
    act: async page => {
      await page.getByRole('button', { name: /^账号菜单/ }).click()
    },
    verify: async page => {
      await expect(page.getByRole('menu')).toBeVisible()
    },
  },
  {
    name: 'conversation-command-palette-open',
    path: conversationPath,
    scope: 'desktop',
    act: async page => {
      await page.keyboard.press('Meta+k')
    },
    verify: async page => {
      await expect(page.getByRole('dialog', { name: /命令/ })).toBeVisible()
    },
  },
  {
    name: 'work-item-detail-drawer-open',
    path: workPath,
    scope: 'both',
    act: async page => {
      await page.getByRole('button', { name: '打开 CRW-01 结算看板主链路' }).click()
    },
    verify: async page => {
      await expect(page.locator('.base-drawer')).toBeVisible()
    },
  },
  {
    name: 'conversation-mobile-navigation-open',
    path: conversationPath,
    scope: 'narrow',
    act: async page => {
      await page.locator('.mobile-menu-toggle').click()
    },
    verify: async page => {
      await expect(page.locator('#mobile-navigation')).toBeVisible()
    },
  },
]

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-08-27T08:30:00Z'))
  await mockAuditApi(page)
})

for (const interaction of interactions) {
  test(`interaction audit: ${interaction.name}`, async ({ page }, testInfo) => {
    const projectName = testInfo.project.name
    test.skip(
      (interaction.scope === 'desktop' && projectName !== 'desktop-chromium')
        || (interaction.scope === 'narrow' && projectName !== 'narrow-chromium')
        || (interaction.scope === 'both' && projectName !== 'desktop-chromium' && projectName !== 'narrow-chromium'),
      'case targets specific reference viewports',
    )
    await page.goto(interaction.path)
    // 页面骨架与 store 同步稳定后再交互，避免把加载态当作交互前形态。
    await page.waitForTimeout(900)
    await interaction.act(page)
    await interaction.verify(page)
    await settle(page)
    const issues = await probeLayout(page, `${interaction.name}@${projectName}`)
    if (issues.length > 0) {
      console.log(`[m13g-audit] ${interaction.name}@${projectName} -> ${issues.length} issue(s)`)
      for (const issue of issues) console.log(`[m13g-audit]   ${issue.kind}: ${issue.detail}`)
    }
    expect(issues, issues.map(issue => `${issue.kind}: ${issue.detail}`).join('\n')).toEqual([])
  })
}
