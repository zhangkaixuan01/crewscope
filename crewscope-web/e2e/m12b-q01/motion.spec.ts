import { expect, test, type Page } from '@playwright/test'
import { mockF05App, workUrl } from '../m9b-f05/fixtures'

// M12b-Q01：全部 e2e 都跑在 contextOptions.reducedMotion: 'reduce' 下，动效本身从未被验证
// （Review §5.4 盲区）——transition 被误删、motion token 漂移或归零开关失灵时，没有任何用例
// 会变红。本 spec 逐页把 reduced-motion 覆盖回 no-preference，在真实页面 DOM 上断言按压与
// 进出场动效真实存在；最后反向断言 reduce 下时长确实归零（两类用户都被守护）。

type Sample = { transform: string; present: boolean }

function isIdentity(transform: string): boolean {
  return transform === 'none' || transform === 'matrix(1, 0, 0, 1, 0, 0)'
}

/** rAF 采样循环：抓 BaseDrawer 面板每帧的 transform 与挂载状态，不与过渡时长赛跑。 */
async function installSampler(page: Page): Promise<void> {
  await page.evaluate(() => {
    const scope = window as unknown as { __csSamples?: Sample[] }
    scope.__csSamples = []
    const sample = () => {
      const drawer = document.querySelector('.base-drawer')
      scope.__csSamples!.push({ transform: drawer ? getComputedStyle(drawer).transform : 'none', present: drawer !== null })
      if (scope.__csSamples!.length < 120) requestAnimationFrame(sample)
    }
    requestAnimationFrame(sample)
  })
}

async function readSamples(page: Page): Promise<Sample[]> {
  return page.evaluate(() => (window as unknown as { __csSamples?: Sample[] }).__csSamples ?? [])
}

test.describe('M12b-Q01 motion-level DOM assertions', () => {
  test('button press feedback: active scale transform with a real transition duration', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'no-preference' })
    await mockF05App(page)
    await page.goto(workUrl())
    const button = page.getByRole('button', { name: '新建工作项', exact: true })
    await expect(button).toBeVisible()

    const resting = await button.evaluate(el => {
      const style = getComputedStyle(el)
      return { transform: style.transform, property: style.transitionProperty, duration: style.transitionDuration }
    })
    expect(resting.transform).toBe('none')
    // 按压回弹走 transform 过渡：属性缺失或时长为零（token 漂移/归零开关失灵）都在这里红。
    // duration 是逐属性的逗号列表（背景/边框/文字/transform 各一项），逐项断言。
    expect(resting.property).toContain('transform')
    for (const value of resting.duration.split(',')) expect(parseFloat(value)).toBeGreaterThan(0.05)

    // mouse.down 后保持 :active；transform 有 120ms 过渡，等过渡推进到位再读计算样式。
    await button.hover()
    await page.mouse.down()
    await page.waitForTimeout(200)
    expect(await button.evaluate(el => getComputedStyle(el).transform)).toBe('matrix(0.97, 0, 0, 0.97, 0, 0)')
    await page.mouse.up()
    await expect.poll(() => button.evaluate(el => getComputedStyle(el).transform)).toBe('none')
  })

  test('drawer enter and leave play transform transitions and stay mounted while animating', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'no-preference' })
    await mockF05App(page)
    await page.goto(workUrl())
    // 锚定 AppShell 移动端主导航：它是 BaseDrawer 唯一以 :open 动态切换的用法，Transition 的
    // enter/leave 在这里真实运行。工作项/Task 详情抽屉是外层 v-if + :key 整树重挂（M12b-F05
    // 的订阅治理决定），首渲染不经 Transition——那条路无动画是登记在案的裁定，不是本断言对象。
    await page.setViewportSize({ width: 390, height: 720 })
    const openMenu = page.getByRole('button', { name: '打开主导航' })
    await expect(openMenu).toBeVisible()
    const dialog = page.getByRole('dialog', { name: '移动端主导航' })

    // 进场：汉堡点击把 :open 从 false 翻到 true，cs-drawer enter 过渡从 translateX(-100%) 滑入。
    await installSampler(page)
    await openMenu.click()
    await expect(dialog).toBeVisible()
    await page.waitForTimeout(500)
    const enterSamples = (await readSamples(page)).filter(sample => sample.present)
    expect(enterSamples.length).toBeGreaterThan(0)
    expect(enterSamples.some(sample => !isIdentity(sample.transform))).toBe(true)
    expect(isIdentity(enterSamples[enterSamples.length - 1]!.transform)).toBe(true)

    // 出场：关闭后过渡期间元素必须仍在 DOM（leave-active 生效的证据），帧间 transform 离开 identity。
    await installSampler(page)
    await dialog.getByRole('button', { name: '关闭主导航' }).click()
    await expect(dialog).toBeHidden()
    await page.waitForTimeout(500)
    const leaveSamples = await readSamples(page)
    expect(leaveSamples.length).toBeGreaterThan(0)
    const animating = leaveSamples.filter(sample => sample.present)
    expect(animating.length).toBeGreaterThan(0)
    expect(animating.some(sample => !isIdentity(sample.transform))).toBe(true)
    expect(leaveSamples[leaveSamples.length - 1]!.present).toBe(false)
  })

  test('reduced motion (the e2e default) collapses transition durations to zero', async ({ page }) => {
    // 不调用 emulateMedia：沿用 playwright.config 的 contextOptions.reducedMotion: 'reduce'。
    await mockF05App(page)
    await page.goto(workUrl())
    const button = page.getByRole('button', { name: '新建工作项', exact: true })
    await expect(button).toBeVisible()
    // tokens.css 把 --cs-motion-* 归零；Chromium 把 0 时长的 computed 值序列化为 1e-05s，
    // 所以用阈值断言归零，而不是绑定引擎的序列化格式。若归零块被删，reduce 用户会看到全部动效。
    const reduced = await button.evaluate(el => getComputedStyle(el).transitionDuration)
    for (const value of reduced.split(',')) expect(parseFloat(value)).toBeLessThan(0.001)
  })
})
