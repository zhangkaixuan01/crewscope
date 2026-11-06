import { expect, test } from '@playwright/test'
import { ids, mockAuditApi, probeLayout } from './layout-audit-support'

// 折叠/收窄态长期几何门禁（AppShell 窄轨双规则互指注释的另一半）。三维度：
// 1) collapsed rail × 主要路由（desktop 1440）
// 2) 900px 中间档 × 主要路由（rail 展开与折叠各一轮）
// 3) rail 内部盒几何（横向溢出断言 + 失败诊断转储）
// 历史判例：两份窄轨规则（.app-shell--collapsed 与 ≤1100 断点）漂移曾致
// scope-switcher 越出 rail（scrollW=135>76）与 rail-head 挤爆双侧（left=-4/right=79）。

const routes: Array<{ name: string, path: string }> = [
  { name: 'today', path: `/today?team=${ids.team}&project=${ids.project}` },
  { name: 'conversation', path: `/conversation?team=${ids.team}&project=${ids.project}&conversation=${ids.conversation}` },
  { name: 'work', path: `/work?team=${ids.team}&project=${ids.project}` },
  { name: 'inbox', path: `/inbox?team=${ids.team}&project=${ids.project}` },
  { name: 'settings-organization', path: `/settings/organization?team=${ids.team}&project=${ids.project}` },
  { name: 'settings-models', path: `/settings/models?team=${ids.team}&project=${ids.project}` },
  { name: 'settings-agents', path: `/settings/agents?team=${ids.team}&project=${ids.project}` },
  { name: 'account', path: '/account' },
]

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-08-27T08:30:00Z'))
  await mockAuditApi(page)
})

test('b2-rail-internal-geometry (collapsed)', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop-chromium', 'desktop only')
  const dumpRail = () => page.evaluate(() => {
    const rail = document.querySelector('.app-shell__rail') as HTMLElement
    const boxes = Array.from(rail.querySelectorAll<HTMLElement>('a, button, .scope-switcher-root, .rail-profile')).map(el => {
      const r = el.getBoundingClientRect()
      return {
        cls: el.className.split(' ').slice(0, 2).join('.'),
        text: (el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 16),
        w: Math.round(r.width), h: Math.round(r.height),
        left: Math.round(r.left), right: Math.round(r.right),
      }
    })
    return { railW: Math.round(rail.getBoundingClientRect().width), scrollW: rail.scrollWidth, clientW: rail.clientWidth, scrollH: rail.scrollHeight, clientH: rail.clientHeight, boxes }
  })
  const assertNoOverflow = (dump: Awaited<ReturnType<typeof dumpRail>>, label: string) => {
    console.log(`[b2-rail:${label}] railW=${dump.railW} scrollW=${dump.scrollW} clientW=${dump.clientW}`)
    for (const b of dump.boxes) console.log(`[b2-rail:${label}] ${b.cls} "${b.text}" ${b.w}x${b.h} left=${b.left} right=${b.right}`)
    expect(dump.scrollW <= dump.clientW, `${label}: rail horizontal overflow scrollW=${dump.scrollW} > clientW=${dump.clientW}`).toBe(true)
  }

  await page.goto(`/today?team=${ids.team}&project=${ids.project}`)
  await page.waitForTimeout(900)
  await page.locator('.rail-collapse').click()
  await page.waitForTimeout(400)
  assertNoOverflow(await dumpRail(), 'manual-collapse@1440')

  await page.setViewportSize({ width: 900, height: 800 })
  await page.waitForTimeout(400)
  assertNoOverflow(await dumpRail(), 'breakpoint@900')
})

for (const route of routes) {
  test(`b2-collapsed: ${route.name}`, async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== 'desktop-chromium', 'desktop only')
    await page.goto(route.path)
    await page.waitForTimeout(900)
    await page.locator('.rail-collapse').click()
    await page.waitForTimeout(400)
    const issues = await probeLayout(page, `collapsed@${route.name}`)
    for (const issue of issues) console.log(`[b2-collapsed] ${route.name} ${issue.kind}: ${issue.detail}`)
    expect(issues, issues.map(issue => `${issue.kind}: ${issue.detail}`).join('\n')).toEqual([])
  })
}

test.describe('900px middle band', () => {
  test.use({ viewport: { width: 900, height: 800 } })
  for (const route of routes) {
    test(`b2-900-expanded: ${route.name}`, async ({ page }, testInfo) => {
      test.skip(testInfo.project.name !== 'desktop-chromium', 'single project run')
      await page.goto(route.path)
      await page.waitForTimeout(900)
      const issues = await probeLayout(page, `900-expanded@${route.name}`)
      for (const issue of issues) console.log(`[b2-900] ${route.name} ${issue.kind}: ${issue.detail}`)
      expect(issues, issues.map(issue => `${issue.kind}: ${issue.detail}`).join('\n')).toEqual([])
    })
    test(`b2-900-collapsed: ${route.name}`, async ({ page }, testInfo) => {
      test.skip(testInfo.project.name !== 'desktop-chromium', 'single project run')
      await page.goto(route.path)
      await page.waitForTimeout(900)
      await page.locator('.rail-collapse').click()
      await page.waitForTimeout(400)
      const issues = await probeLayout(page, `900-collapsed@${route.name}`)
      for (const issue of issues) console.log(`[b2-900c] ${route.name} ${issue.kind}: ${issue.detail}`)
      expect(issues, issues.map(issue => `${issue.kind}: ${issue.detail}`).join('\n')).toEqual([])
    })
  }
})
