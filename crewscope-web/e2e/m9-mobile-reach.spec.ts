import { expect, test, type Page } from '@playwright/test'
import { ids, MENUS, mockApi } from './menu-walk'

// The two density sweeps visit every menu; a cold browser may spend longer than the normal unit
// test budget compiling lazy route chunks, which is not an interaction failure.
test.describe.configure({ timeout: 120_000 })

/**
 * M9-F11 核心验收项：390px 下每个菜单都到得了、每个控件都点得到。
 *
 * 这条必须是 E2E，静态扫描做不到——命中区是布局算完之后才存在的东西。实测的两个缺陷都
 * 只在运行时可见：`[data-density="compact"]` 把 `--cs-density-control-height` 压到 32px，
 * 于是窄屏紧凑模式下每个基础控件都矮了四分之一；`.mobile-menu-toggle` 是窄屏下唯一的导航
 * 入口，却只有 34×34。两者的 CSS 都合法、门禁全绿、四断点视觉基线也全绿——基线断言的是
 * 「长得一样」，可达性要断言的是「到得了」。
 *
 * 断言三件事：
 * 1. 命中区下限（`--cs-touch-min` = 44）；
 * 2. 没有横向溢出——390px 上出现横向滚动，等于右侧的控件在不滑动时点不到；
 * 3. 主操作可达——窄屏隐藏了侧栏，导航只能走汉堡菜单。
 */

const TOUCH_MIN = 44

/** 窄屏契约只在 `≤767px` 生效，桌面项目跳过整个文件而不是逐条跳过。 */
test.skip(({ viewport }) => (viewport?.width ?? 0) > 767, '命中区契约只约束 ≤767px')

test.beforeEach(async ({ page }) => mockApi(page))

for (const density of ['comfortable', 'compact'] as const) {
  test(`所有菜单在 390px / ${density} 密度下满足命中区下限且无横向溢出`, async ({ page }) => {
    await page.addInitScript(value => {
      localStorage.setItem('cs.pref.device.density.v1', JSON.stringify({ version: 1, value }))
    }, density)

    const failures: string[] = []
    for (const menu of MENUS) {
      await page.goto(`${menu.path}?team=${ids.team}`, { waitUntil: 'domcontentloaded' })
      // 等布局稳定：命中区是布局产物，DOM 就绪时还没有尺寸。
      await expect(page.locator('.app-shell')).toBeVisible()
      await page.waitForLoadState('networkidle')

      const overflow = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        clientWidth: document.documentElement.clientWidth,
      }))
      // 1px 容差留给子像素舍入；再多就是真的有元素伸出了视口。
      if (overflow.scrollWidth > overflow.clientWidth + 1) {
        failures.push(`${menu.path}（${menu.name}）横向溢出：scrollWidth ${overflow.scrollWidth} > 视口 ${overflow.clientWidth}`)
      }

      for (const offender of await undersizedTargets(page)) {
        failures.push(`${menu.path}（${menu.name}）命中区不足：${offender}`)
      }
    }

    expect(failures, failures.join('\n')).toEqual([])
  })
}

test('窄屏下侧栏收起，主导航仍然到得了', async ({ page }) => {
  await page.goto(`/today?team=${ids.team}`, { waitUntil: 'domcontentloaded' })
  await expect(page.getByRole('heading', { name: '我的工作台' })).toBeVisible()

  // 侧栏在 ≤767px 下 display:none——导航只剩汉堡菜单这一条路，它本身必须先满足命中区。
  await expect(page.locator('.app-shell__rail')).toBeHidden()
  const toggle = page.locator('.mobile-menu-toggle')
  await expect(toggle).toBeVisible()
  const box = await toggle.boundingBox()
  expect(box?.width ?? 0).toBeGreaterThanOrEqual(TOUCH_MIN)
  expect(box?.height ?? 0).toBeGreaterThanOrEqual(TOUCH_MIN)

  await toggle.click()
  const drawer = page.locator('.mobile-drawer, .app-shell__rail--mobile, [data-mobile-navigation]').first()
  await expect(drawer.or(page.getByRole('navigation').first())).toBeVisible()
})

test('移动导航抽屉支持键盘唤出、焦点归还与 Esc 关闭', async ({ page }) => {
  await page.goto(`/today?team=${ids.team}`, { waitUntil: 'domcontentloaded' })
  await expect(page.getByRole('heading', { name: '我的工作台' })).toBeVisible()
  const toggle = page.locator('.mobile-menu-toggle')
  await toggle.focus()
  await page.keyboard.press('Enter')

  const navigation = page.locator('#mobile-navigation')
  await expect(navigation).toBeVisible()
  await expect.poll(() => page.evaluate(() => document.activeElement?.id)).toBe('mobile-navigation')
  await page.keyboard.press('Escape')
  await expect(navigation).toBeHidden()
  await expect.poll(() => page.evaluate(() => document.activeElement?.className)).toContain('mobile-menu-toggle')
})

/**
 * 只统计**真的能点到**的元素：不可见、被禁用、滚出视口的不算，`display: inline` 的不算
 * （正文里的行内链接撑到 44px 会把行距一起撑开，那是改排版而不是改命中区），原生
 * 复选框与单选框不算（把 13px 的方框放大到 44px 是改外观，它们的命中区由包裹的 label 承担，
 * 而 label 会作为普通元素一起被量）。
 *
 * 宽度只对**没有文字**的元素断言：文字按钮的宽度由内容撑开，对它断言 44px 等于禁止窄按钮；
 * 图标按钮没有内容撑宽，宽度不兜住就会变成 44×24 这种只在一个方向上合规的假达标。
 */
async function undersizedTargets(page: Page): Promise<string[]> {
  return page.evaluate(min => {
    const selector = [
      'button', 'summary', 'a[href]', 'select', 'textarea',
      'input:not([type="hidden"])',
      '[role="button"]', '[role="tab"]', '[role="menuitem"]', '[role="option"]', '[role="switch"]',
    ].join(', ')
    const offenders: string[] = []
    for (const element of Array.from(document.querySelectorAll(selector))) {
      const style = getComputedStyle(element)
      if (style.display === 'none' || style.visibility === 'hidden' || style.display === 'inline') continue
      if (element.hasAttribute('disabled') || element.getAttribute('aria-disabled') === 'true') continue
      const type = element.getAttribute('type')
      if (type === 'checkbox' || type === 'radio') continue
      const box = element.getBoundingClientRect()
      if (box.width === 0 || box.height === 0) continue
      if (box.bottom < 0 || box.right < 0 || box.left > window.innerWidth) continue

      const text = (element.textContent ?? '').trim()
      const shortcomings: string[] = []
      if (box.height < min) shortcomings.push(`高 ${box.height.toFixed(1)}`)
      if (!text && box.width < min) shortcomings.push(`宽 ${box.width.toFixed(1)}`)
      if (!shortcomings.length) continue

      /*
       * 视觉盒不足时再探一次**命中区**：合同约束的是「可点区域」，而 `.touch-target` 的
       * 伪元素扩张器刻意让命中区大于视觉盒。伪元素不是节点，量不到，但
       * `elementFromPoint` 会把它算进来——命中点落在扩张器上时返回的就是宿主元素本身。
       * 探四角而不是探中心：中心必然命中，四角才能证明整个 44×44 都点得到。
       *
       * 探之前必须先滚到视口中央：`elementFromPoint` 对视口外的坐标一律返回 `null`，
       * 不滚的话首屏以下的每个元素都会「四角全空」而被判成不达标——那是探针的缺陷不是页面的。
       * 实测这一条误报了 `.owner-tabs`、`.project-focus__body` 与两处已挂扩张器的链接。
       */
      element.scrollIntoView({ block: 'center', inline: 'center' })
      const probe = element.getBoundingClientRect()
      const centerX = probe.left + probe.width / 2
      const centerY = probe.top + probe.height / 2
      /*
       * 探边缘中点而不是探四角，并且**与断言口径一致**：高度对所有元素断言，所以纵向两点必探；
       * 宽度只对没有文字的元素断言，所以横向两点只在那时才探。
       * 四角的问题是它要求一个完整的 44×44 矩形——横向排布里 24px 宽的面包屑项**在物理上**
       * 拿不到 44px 宽的命中区而不盖住邻项，对它探四角等于要求一个做不到也不该做的东西。
       */
      const reach = min / 2 - 1
      const probes = [[0, -reach], [0, reach], ...(text ? [] : [[-reach, 0], [reach, 0]])]
      const hits = probes.map(([dx, dy]) => document.elementFromPoint(centerX + dx, centerY + dy))
      const covered = hits.every(hit => Boolean(hit) && (hit === element || element.contains(hit)))
      if (covered) continue

      /*
       * 挂了扩张器却仍然探不通，只有一个原因值得记下来：命中点落在**别的元素**上。
       * 报出那个元素，否则下一个人只能看到「19.5 < 44」而看不到「下半个扩张区被相邻链接占了」。
       */
      const expanded = getComputedStyle(element, '::after').content !== 'none'
      const blockers = expanded
        ? [...new Set(hits.map(hit => {
            if (!hit) return '视口外'
            if (hit === element || element.contains(hit)) return ''
            const name = String((hit as HTMLElement).className).trim().split(/\s+/)[0]
            return `${hit.tagName.toLowerCase()}${name ? `.${name}` : ''}`
          }).filter(Boolean))].join('/')
        : ''
      const debug = blockers ? `，扩张区被 ${blockers} 占住` : ''

      // 自己没有 class 的裸标签（`<select>`、`<a>`）要靠最近的带 class 祖先才找得到规则出处。
      const own = element.className ? `.${String(element.className).trim().split(/\s+/).join('.')}` : ''
      const owner = own ? '' : (element.parentElement?.closest('[class]')?.className ?? '').toString().trim().split(/\s+/)[0]
      const identity = `${element.tagName.toLowerCase()}${own}${owner ? ` (in .${owner})` : ''}`
      offenders.push(`${identity}「${text.slice(0, 20) || element.getAttribute('aria-label') || '无文字'}」${shortcomings.join('、')} < ${min}${debug}`)
    }
    return offenders
  }, TOUCH_MIN)
}
