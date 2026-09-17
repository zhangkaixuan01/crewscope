import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { ids, MENUS, mockApi } from './menu-walk'

// Each assertion intentionally walks all 14 menus. Keep the per-test budget independent from the
// default 30s so a cold Vite route/module load cannot turn a complete menu sweep into a false fail.
test.describe.configure({ timeout: 120_000 })

/**
 * M9-F06 的缺口：暗色主题上线了，但**没有任何一处验证过它长什么样**。
 *
 * 此前关于暗色的唯一测试是 m9-theme-density.spec.ts，它断言 `data-theme="dark"` 被写到
 * <html> 上、localStorage 的 schema 带版本号——也就是「开关拨过去了」，而不是「拨过去之后
 * 页面是对的」。于是三类缺陷一路绿灯上线：
 *
 * 1. `--cs-action-primary` 在暗色下是 brand-300（浅绿），而前景借用了近白的
 *    `--cs-text-on-dark`——整个应用最重要的按钮只有 **1.59:1**，hover 态 1.20。
 *    同一个形状还出现在 danger 按钮（2.14）、Inbox 未读徽章（1.71）、Agent 图标（2.05）。
 * 2. 56 个文件里硬写了 255 处颜色，其中 37 处是 `background: white/#fff`。它们不跟主题，
 *    于是暗色下会出现整块白底面板——面板里的字用的是 `--cs-text`，在暗色下是近白色，
 *    结果是白底白字。
 * 3. 28 处阴影全是「深绿 + 低透明度」，压在 #111814 的画布上等于不存在。
 *
 * 所以这条 spec 断言两件事，而且是**互补**的，谁也不能替代谁：
 * - axe 的 color-contrast：查「文字读不读得出」，只能查渲染到的那一屏，但能算出
 *   静态门禁看不到的**混合底**（半透明遮罩叠出来的那种）。
 * - 近白底扫描：查「有没有一块不跟主题的白底」。这一类 axe 查不出——白底配深字的
 *   对比度完全合规，它只是在一片深色界面里突兀地亮着。
 *
 * 两条都逐个菜单走，而不是抽样：白底面板是按组件分布的，抽样必然漏。
 */

/**
 * 暗色下合法的浅色底只有「带色相的品牌/语义强调块」——主按钮是 brand-300，未读徽章是
 * `--cs-warning`，Agent 图标是 `--cs-agent`。它们都是**有饱和度**的颜色。
 * 不跟主题的白底恰恰相反：`white`、`#fff`、`#f7fbf8` 这些在三个通道上几乎相等。
 * 所以判据是「近白」而不是「亮」：三个通道都 ≥ 232，且通道极差 ≤ 12。
 * 用亮度阈值区分不开——brand-300 的相对亮度 0.57，比一半的白底还高。
 */
const NEAR_WHITE_CHANNEL = 232
const NEAR_WHITE_SPREAD = 12

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('cs.pref.device.theme.v1', JSON.stringify({ version: 1, value: 'dark' }))
  })
  await mockApi(page)
})

test('暗色主题下每个菜单都没有不跟主题的白底', async ({ page }) => {
  const failures: string[] = []
  for (const menu of MENUS) {
    await page.goto(`${menu.path}?team=${ids.team}`, { waitUntil: 'domcontentloaded' })
    await expect(page.locator('.app-shell')).toBeVisible()
    await expect.poll(() => page.locator('html').getAttribute('data-theme')).toBe('dark')
    await page.waitForLoadState('networkidle')

    for (const offender of await nearWhiteSurfaces(page, NEAR_WHITE_CHANNEL, NEAR_WHITE_SPREAD)) {
      failures.push(`${menu.path}（${menu.name}）${offender}`)
    }
  }
  expect(failures, failures.join('\n')).toEqual([])
})

test('暗色主题下每个菜单都满足 AA 对比度', async ({ page }) => {
  const failures: string[] = []
  for (const menu of MENUS) {
    await page.goto(`${menu.path}?team=${ids.team}`, { waitUntil: 'domcontentloaded' })
    await expect(page.locator('.app-shell')).toBeVisible()
    await page.waitForLoadState('networkidle')

    /*
     * 只跑 color-contrast 这一条规则。其余无障碍规则已经由各菜单自己的 spec 在浅色下查过，
     * 而它们与主题无关（标签、角色、焦点顺序不会因为换主题而变）；真正只在暗色下才成立的
     * 缺陷全部集中在颜色上。逐个菜单跑全量规则会把这条 spec 的耗时放大一个量级，
     * 换不到任何新信息。
     */
    const result = await new AxeBuilder({ page }).withRules(['color-contrast']).analyze()
    for (const violation of result.violations) {
      for (const node of violation.nodes) {
        failures.push(`${menu.path}（${menu.name}）${node.target.join(' ')}：${node.failureSummary?.replace(/\s+/g, ' ').trim()}`)
      }
    }
  }
  expect(failures, failures.join('\n')).toEqual([])
})

/**
 * 这一条严格说不属于暗色主题，而是被暗色主题**暴露出来**的：`<button>` 的 UA 默认底色
 * `buttonface` 会按 `color-scheme` 取不同的值（浅色 #efefef、暗色 #6b6b6b），而
 * design/base.css 的归零只写了 `border: 0`，没有写 `background`。于是每一个「看起来是纯文字」
 * 的按钮其实都端着一块原生灰底。放在这条 spec 里是因为它就是在这里被抓到的
 * （`.advanced-toggle` 在暗色下 3.1:1），而且两个主题共用同一份归零——修一次就都好了。
 * 它和上面两条查的不是同一件事：近白扫描按通道判近白，#6b6b6b 落不进去；axe 只在文字
 * 恰好读不出时才报，底色是灰的这件事本身它不管。
 */
test('没有任何元素端着浏览器默认的按钮底色', async ({ page }) => {
  const failures: string[] = []
  for (const menu of MENUS) {
    await page.goto(`${menu.path}?team=${ids.team}`, { waitUntil: 'domcontentloaded' })
    await expect(page.locator('.app-shell')).toBeVisible()
    await page.waitForLoadState('networkidle')

    for (const offender of await uaButtonFaces(page)) failures.push(`${menu.path}（${menu.name}）${offender}`)
  }
  expect(failures, failures.join('\n')).toEqual([])
})

/**
 * `buttonface` 的取值不写死，而是**量出来**：把一个未经样式的 `<button>` 放进 Shadow DOM
 * 里读它的计算样式。我们的 `button` 归零选择器不跨 shadow 边界，所以那一个按钮保留着
 * 纯 UA 外观。写死 #6b6b6b 的话，换一版 Chromium 或换个平台这条就会静默失效。
 */
async function uaButtonFaces(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const host = document.createElement('div')
    document.body.append(host)
    const probe = host.attachShadow({ mode: 'open' }).appendChild(document.createElement('button'))
    const pristine = getComputedStyle(probe).backgroundColor
    host.remove()

    const offenders = new Set<string>()
    for (const element of Array.from(document.querySelectorAll('body *'))) {
      if (getComputedStyle(element).backgroundColor !== pristine) continue
      const name = String(element.className).trim().split(/\s+/).filter(Boolean).join('.')
      offenders.add(`${element.tagName.toLowerCase()}${name ? `.${name}` : ''} 的底色仍是 UA 的 buttonface（${pristine}）`)
    }
    return [...offenders]
  })
}

/**
 * 只看**自己画了底**的元素：`background-color` 为 transparent 的元素露出的是祖先的底，
 * 报它等于把同一处缺陷沿着 DOM 报一路。渐变也要看——`linear-gradient(..., #fff)` 是
 * 白底缺陷里最常见的一种写法，而它落在 `background-image` 上。
 */
async function nearWhiteSurfaces(page: Page, channelFloor: number, spreadCeiling: number): Promise<string[]> {
  return page.evaluate(([floor, spread]) => {
    const offenders: string[] = []
    const nearWhite = (r: number, g: number, b: number) =>
      Math.min(r, g, b) >= floor && Math.max(r, g, b) - Math.min(r, g, b) <= spread

    for (const element of Array.from(document.querySelectorAll('body *'))) {
      const style = getComputedStyle(element)
      if (style.display === 'none' || style.visibility === 'hidden') continue
      const box = element.getBoundingClientRect()
      // 0 尺寸的元素画不出可见的底；纯装饰的 1px 线也不构成「一块白底」。
      if (box.width < 4 || box.height < 4) continue

      const culprits: string[] = []
      const colour = style.backgroundColor.match(/^rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?/)
      if (colour) {
        const [r, g, b] = [1, 2, 3].map(at => Number(colour[at]))
        const alpha = colour[4] === undefined ? 1 : Number(colour[4])
        // 半透明的近白底同样是缺陷（玻璃底忘了跟主题），但透明度极低的不算。
        if (alpha > 0.2 && nearWhite(r, g, b)) culprits.push(`background-color ${style.backgroundColor}`)
      }
      for (const stop of style.backgroundImage.matchAll(/rgba?\((\d+),\s*(\d+),\s*(\d+)/g)) {
        const [r, g, b] = [1, 2, 3].map(at => Number(stop[at]))
        if (nearWhite(r, g, b)) culprits.push(`渐变色标 ${stop[0]})`)
      }
      if (!culprits.length) continue

      const name = String(element.className).trim().split(/\s+/).filter(Boolean).join('.')
      const identity = `${element.tagName.toLowerCase()}${name ? `.${name}` : ''}`
      offenders.push(`${identity} 在暗色下仍是近白底：${[...new Set(culprits)].join('、')}`)
    }
    // 同一个组件的每一个实例都会报一次（列表里的 20 张卡片）；去重后才看得出有几处缺陷。
    return [...new Set(offenders)]
  }, [channelFloor, spreadCeiling])
}
