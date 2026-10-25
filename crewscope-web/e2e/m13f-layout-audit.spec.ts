import { expect, test } from '@playwright/test'
import { ids, mockAuditApi, probeLayout } from './layout-audit-support'

/** 审查对象路由：带 team/project 坐标的 authenticated 页面全集。 */
const routes: Array<{ name: string, path: string }> = [
  { name: 'today', path: `/today?team=${ids.team}&project=${ids.project}` },
  { name: 'conversation', path: `/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}` },
  { name: 'search', path: `/search?team=${ids.team}&project=${ids.project}&q=Provider` },
  { name: 'setup', path: `/setup?from=work` },
  { name: 'work', path: `/work?team=${ids.team}&project=${ids.project}` },
  { name: 'activity', path: `/activity?team=${ids.team}&project=${ids.project}` },
  { name: 'inbox', path: `/inbox?team=${ids.team}&project=${ids.project}` },
  { name: 'team-observer', path: `/team/observer?team=${ids.team}&project=${ids.project}` },
  { name: 'knowledge', path: `/knowledge?team=${ids.team}&project=${ids.project}` },
  { name: 'knowledge-index', path: `/knowledge/index?team=${ids.team}&project=${ids.project}` },
  { name: 'skills', path: `/skills?team=${ids.team}&project=${ids.project}` },
  { name: 'observability', path: `/observability?team=${ids.team}&project=${ids.project}` },
  { name: 'operations', path: `/operations?team=${ids.team}&project=${ids.project}` },
  { name: 'audit', path: `/audit?team=${ids.team}&project=${ids.project}` },
  { name: 'team-members', path: `/team/members?team=${ids.team}&project=${ids.project}` },
  { name: 'settings-organization', path: `/settings/organization?team=${ids.team}&project=${ids.project}` },
  { name: 'settings-quota', path: `/settings/quota?team=${ids.team}&project=${ids.project}` },
  { name: 'settings-repositories', path: `/settings/repositories?team=${ids.team}&project=${ids.project}` },
  { name: 'settings-agents', path: `/settings/agents?team=${ids.team}&project=${ids.project}` },
  { name: 'settings-models', path: `/settings/models?team=${ids.team}&project=${ids.project}` },
  { name: 'settings-lark', path: `/settings/integrations/lark?team=${ids.team}&project=${ids.project}` },
  { name: 'settings-github', path: `/settings/integrations/github?team=${ids.team}&project=${ids.project}` },
  { name: 'control', path: `/control?team=${ids.team}&project=${ids.project}` },
  { name: 'account', path: `/account` },
  { name: 'onboarding', path: `/onboarding` },
  { name: 'access-denied', path: `/access-denied` },
  { name: 'not-found', path: `/no-such-route` },
]

type LayoutIssue = { route: string, kind: string, detail: string }

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-08-27T08:30:00Z'))
  await mockAuditApi(page)
})

for (const route of routes) {
  test(`layout audit: ${route.name}`, async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== 'desktop-chromium' && testInfo.project.name !== 'narrow-chromium', 'audit targets the two reference viewports')
    await page.goto(route.path)
    // 空态/内容态的骨架与 store 同步稳定：与 f09 一致用固定等待，审查不引入 race。
    await page.waitForTimeout(900)
    const issues = await probeLayout(page, `${route.name}@${testInfo.project.name}`)
    if (issues.length > 0) {
      console.log(`[m13f-audit] ${route.name}@${testInfo.project.name} -> ${issues.length} issue(s)`)
      for (const issue of issues) console.log(`[m13f-audit]   ${issue.kind}: ${issue.detail}`)
    }
    // 审查报告期先收口为零违规基准（发现的问题修复后此断言即长期门禁）。
    expect(issues, issues.map(issue => `${issue.kind}: ${issue.detail}`).join('\n')).toEqual([])
  })
}
