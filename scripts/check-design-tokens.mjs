#!/usr/bin/env node

/**
 * Enforces the Design System v2 baseline.
 *
 * Two scopes, because one rule is a mechanical rewrite and the other is a behavioural change:
 *
 * - The **production scope** is every production stylesheet, and it carries typography, layering,
 *   spacing, motion and colour. M9-F01/F01b moved 1215 typographic declarations onto the seven-step
 *   scale, 26 `z-index` declarations onto the ten semantic tiers, 2157 spacing lengths onto the
 *   eleven value-named steps and the last 15 bare durations onto the motion tokens; M9-F01c moved
 *   255 colour literals onto the semantic layer, fixed 14 `var()` references that pointed at
 *   tokens which had never existed, and moved 372 `--cs-brand-*` references off every property
 *   except `background` — because that palette deliberately does not follow the theme, so using
 *   it for text or borders is what made dark mode unreadable (86 axe violations from one cause).
 *   Bare values in all six are now a hard failure everywhere — with no count baseline, because
 *   there is nothing left to ratchet down.
 * - The **foundation scope** (`components/base/**` plus the three feedback singletons and
 *   `design/base.css`) additionally carries the four breakpoints. Breakpoints stay foundation-only
 *   on purpose: 23 distinct `max-width` values exist in production and collapsing them onto four
 *   tokens changes *when* rules apply, which is a behavioural change needing an explicit mapping
 *   table rather than a mechanical rewrite.
 *
 * Exceptions live in `docs/quality/M9-字号白名单.json` and are matched against the exact
 * registered declaration, so changing a whitelisted value fails the gate. A registered
 * declaration that no longer appears in its file also fails: the whitelist can only shrink.
 */
import { readdir, readFile } from 'node:fs/promises'
import { join, relative as relativeTo, resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const web = join(root, 'crewscope-web/src')

const foundationRoots = [
  join(web, 'components/base'),
  join(web, 'components/feedback/StatePanel.vue'),
  join(web, 'components/feedback/ToastHost.vue'),
  join(web, 'components/feedback/ConfirmHost.vue'),
  join(web, 'design/base.css'),
]

/**
 * `spikes/` holds the M4/M7 experiment fixtures — a record of what was tried, not a surface.
 * `stories/` holds the Histoire preview harnesses, the same category as the `*.story.vue`
 * files skipped below: they exist to show a component, and are never rendered to a member.
 */
const SKIP_DIRS = new Set(['generated', 'node_modules', 'spikes', 'stories'])

async function collect(path, into) {
  const entries = await readdir(path, { withFileTypes: true }).catch(() => [])
  if (!entries.length) { into.push(path); return }
  for (const entry of entries) {
    if (entry.isDirectory()) {
      if (!SKIP_DIRS.has(entry.name)) await collect(join(path, entry.name), into)
    } else if (/\.(vue|css)$/.test(entry.name) && !entry.name.endsWith('.story.vue')) {
      into.push(join(path, entry.name))
    }
  }
}

const foundationFiles = []
for (const path of foundationRoots) await collect(path, foundationFiles)
const typographyFiles = []
await collect(web, typographyFiles)

const whitelistPath = join(root, 'docs/quality/M9-字号白名单.json')
const whitelist = JSON.parse(await readFile(whitelistPath, 'utf8'))
const registered = [
  ...whitelist.fluidDisplayType,
  ...whitelist.monospaceLineNumbers.entries,
  ...whitelist.spacingExceptions,
  ...whitelist.brandStepExceptions,
].map(entry => ({ ...entry, seen: false }))

const violations = []

/**
 * A `font:` shorthand is only legal when every length in it is a Token reference. `font: inherit`
 * is the one keyword form the codebase uses and carries no value of its own.
 */
function shorthandIsBare(value) {
  if (/^\s*(inherit|initial|unset)\s*;?\s*$/.test(value)) return false
  const withoutTokens = value.replace(/var\(--cs-[a-z0-9-]+\)/g, '')
  return /\b[\d.]+(px|rem|em|%)/.test(withoutTokens)
}

const SPACING_PROPERTIES = [
  'padding', 'margin', 'gap', 'row-gap', 'column-gap',
  'padding-top', 'padding-right', 'padding-bottom', 'padding-left',
  'margin-top', 'margin-right', 'margin-bottom', 'margin-left',
  'padding-inline', 'padding-block', 'margin-inline', 'margin-block',
  'padding-inline-start', 'padding-inline-end', 'margin-inline-start', 'margin-inline-end',
  // `scroll-margin-*` is how a deep-linked panel keeps clear of the sticky header when it is
  // scrolled into view — the same rhythm decision, so it obeys the same scale.
  'scroll-margin', 'scroll-margin-top', 'scroll-margin-block', 'scroll-margin-inline',
  'scroll-padding', 'scroll-padding-top', 'scroll-padding-block', 'scroll-padding-inline',
].sort((a, b) => b.length - a.length).join('|')
const SPACING = new RegExp(`(?<![-\\w])(${SPACING_PROPERTIES})\\s*:\\s*([^;{}\\n]+)`, 'g')

/**
 * Spacing may only be expressed with the value-named steps. `%`, `vw`/`vh` and `auto` are not
 * rhythm — they are fluid layout (`padding: 0 5vw`, `margin: 0 auto`) — so they pass. A negative
 * offset is written `calc(var(--cs-space-4) * -1)`: it still has to name a step, because the four
 * "pull the hint line up under its field" sites had drifted to -4/-5/-6/-8px for one intent.
 */
function spacingIsBare(value) {
  const withoutTokens = value.replace(/var\(--cs-[a-z0-9-]+\)/g, '')
  return /-?\b[\d.]+(px|rem|em)\b/.test(withoutTokens)
}

/*
 * 颜色字面量与悬空 Token 引用。
 *
 * 颜色是这套设计系统里最后一片没有被 Token 兜住的存量：255 处硬写颜色分布在 56 个文件，
 * 其中 37 处是 `background: white/#fff`、14 处是 `rgb(255 255 255 / N%)` 的玻璃底、
 * 20 处是模态遮罩（六种基色九种浓度表达同一个意图）、28 处是各写一遍的阴影。
 * 它的后果不是「不统一」而是「暗色主题不工作」：brand-* 色阶按 ADR-027 §1.3 刻意不跟主题，
 * `#fff` 更不跟，于是暗色下会出现白底配近白字的面板、深底上读不出的阴影、以及
 * `color: white` 压在翻成浅色的语义底上（实测 danger 2.14 / warning 1.71 / agent 2.05:1）。
 * 两个 Token 定义文件当然可以写字面量——那里就是取值的唯一来源。
 *
 * 悬空引用是同一片区域里更隐蔽的一类：`var(--cs-brand-500)` 指向一个**从未存在过**的色阶
 * （调色板刻意从 600 跳到 400），CSS 会把整条声明按无效丢弃。实测 14 处，其中
 * `.progress-fill` 与 `.desk-progress i::after` 的 background 都指向它——两条进度条的填充
 * 根本没有颜色。这类缺陷不报错、不警告、样式文件也完全合法，只能靠门禁发现。
 * 组件局部自定义属性（`.app-shell { --cs-rail-width: 244px }`）算已定义，所以也要收集。
 */
/*
 * brand-* 色阶只能当**填色**。
 *
 * ADR-027 §1.3 的决定是「暗色只覆盖语义色，brand-* 刻意不跟主题」。那对填色块成立
 * （深绿底配近白字在两个主题下都读得出），对文字和描边却是致命的：实测 372 处把 brand 色阶
 * 当前景、底色、描边或光圈用，暗色下就是深绿字压深底（brand-700 2.03:1，共 68 处）、
 * 白底面板（brand-50 当底，14 处）、以及明暗反转的强调描边（brand-200 描边对表面的对比度
 * 从浅色的 1.29 跳到暗色的 12.52，同一个意图的强调程度差了十倍）。
 * 逐菜单跑 axe 一次报出 86 条，全部是这一个根因——而当时静态门禁全绿，因为它只核算
 * 已登记的配对，而这几档 Token 那时根本不存在。
 *
 * 现在它们有了：--cs-text-brand / -strong、--cs-surface-accent / -strong、
 * --cs-border-accent / -strong、--cs-ring-brand，全部跟主题。于是这条规则可以是硬失败：
 * 填色（`background*`）放行，其余属性一律禁止。`accent-color` 也在禁止之列——原生勾选框
 * 勾中时的底色同样要跟主题，实测 brand-600 压在暗色面板上只有 2.3:1，看不出勾没勾。
 * 唯一的登记例外是前景与底色写在同一条规则里的那一处（见白名单 brandStepExceptions）。
 */
const BRAND_FILL_ONLY = /(?<![-\w])([a-z-]+)\s*:\s*([^;{}]*var\(--cs-brand-\d+\)[^;{}]*)/g
const BRAND_FILL_PROPERTY = /^background(-color|-image)?$/

const TOKEN_FILES = ['design/tokens.css', 'design/auth-tokens.css']
const COLOUR_LITERAL = /#[0-9a-fA-F]{3,8}\b|(?<![-\w])(?:white|black)(?![-\w])|\b(?:rgba?|hsla?)\([^)]*\)/g
/*
 * 先剔注释再扫：这套代码的说明注释按惯例会引用被替换掉的旧取值（「原先这里写的是 #f5faf6」），
 * 那是记录而不是声明。只剔 `/* *\/` 块——`//` 在 CSS 里不是注释，在 script 块里剔掉它
 * 会顺手吃掉 `https://` 之后的半行。
 */
const withoutComments = source => source.replace(/\/\*[\s\S]*?\*\//g, match => match.replace(/[^\n]/g, ' '))

const definedTokens = new Set()
for (const file of typographyFiles) {
  if (!TOKEN_FILES.some(name => file.endsWith(name))) continue
  const source = await readFile(file, 'utf8')
  for (const match of source.matchAll(/(--cs-[a-z0-9-]+)\s*:/g)) definedTokens.add(match[1])
}

for (const file of typographyFiles) {
  const source = await readFile(file, 'utf8')
  const rel = relativeTo(root, file)
  const webRel = relativeTo(join(root, 'crewscope-web'), file)
  const waivers = registered.filter(entry => entry.file === webRel)
  const foundation = foundationFiles.includes(file)
  const isTokenFile = TOKEN_FILES.some(name => file.endsWith(name))
  const bare = withoutComments(source)

  if (!isTokenFile) {
    for (const [index, line] of bare.split(/\r?\n/).entries()) {
      for (const match of line.matchAll(COLOUR_LITERAL)) {
        violations.push(`${rel}:${index + 1}: 颜色必须使用语义 Token，实测硬写「${match[0]}」——字面量不跟主题走`)
      }
    }
  }

  if (!isTokenFile) {
    for (const [index, line] of bare.split(/\r?\n/).entries()) {
      for (const match of line.matchAll(BRAND_FILL_ONLY)) {
        if (BRAND_FILL_PROPERTY.test(match[1]) || match[1].startsWith('--')) continue
        if (waivers.some(entry => line.includes(entry.declaration))) continue
        violations.push(`${rel}:${index + 1}: brand-* 色阶刻意不跟主题（ADR-027 §1.3），只能当填色，实测「${match[1]}: ${match[2].trim()}」——请改用 --cs-text-brand / --cs-surface-accent / --cs-border-accent / --cs-ring-brand`)
      }
    }
  }

  /* 本文件里定义的局部自定义属性与 Token 文件里的定义同等有效。 */
  const local = new Set([...bare.matchAll(/(--cs-[a-z0-9-]+)\s*:/g)].map(match => match[1]))
  for (const [index, line] of bare.split(/\r?\n/).entries()) {
    /* 带 fallback 的引用不会整条失效，那是一种有意的降级写法，不算悬空。 */
    for (const match of line.matchAll(/var\(\s*(--cs-[a-z0-9-]+)\s*\)/g)) {
      if (definedTokens.has(match[1]) || local.has(match[1])) continue
      violations.push(`${rel}:${index + 1}: var(${match[1]}) 指向一个未定义的 Token，整条声明会被 CSS 丢弃`)
    }
  }

  for (const [index, line] of source.split(/\r?\n/).entries()) {
    const at = `${rel}:${index + 1}`
    const waived = waivers.filter(entry => line.includes(entry.declaration))
    waived.forEach(entry => { entry.seen = true })
    const isWaived = declaration => waived.some(entry => entry.declaration.includes(declaration))

    for (const match of line.matchAll(/font-size\s*:\s*([^;{}\n]*)/g)) {
      if (match[1].includes('var(--cs-text') || /\b(inherit|initial|unset)\b/.test(match[1])) continue
      if (isWaived('font-size')) continue
      violations.push(`${at}: font-size 必须使用字号 Token（§10.3.1 七档阶梯），实测「${match[1].trim()}」`)
    }
    for (const match of line.matchAll(/(?<![-a-z])font\s*:\s*([^;{}\n]*)/g)) {
      if (!shorthandIsBare(match[1])) continue
      if (isWaived('font:')) continue
      violations.push(`${at}: font 简写里的字号与行高同样要走 Token，实测「${match[1].trim()}」`)
    }
    for (const match of line.matchAll(/font-weight\s*:\s*([^;{}\n]*)/g)) {
      if (match[1].includes('var(--cs-weight') || /\b(inherit|normal)\b/.test(match[1])) continue
      violations.push(`${at}: font-weight 必须使用 regular/medium/semibold 三档 Token，实测「${match[1].trim()}」`)
    }
    /*
     * 层级只有语义档。允许 0–9 的裸值，因为那是组件自己子树内部的局部层叠（连接线之上
     * 的节点圆点、输入框之上的自定义勾选框），不是全局层级；一旦写到 10 以上，就必然是在
     * 跟别的组件抢层级，必须走 Token。
     */
    for (const match of line.matchAll(/z-index\s*:\s*([^;{}\n]*)/g)) {
      const value = match[1].trim()
      if (value.includes('var(--cs-z-') || /\b(auto|inherit)\b/.test(value)) continue
      if (/^[0-9]$/.test(value)) continue
      violations.push(`${at}: z-index 必须使用 --cs-z-* 语义档（10 以下的局部层叠除外），实测「${value}」`)
    }
    for (const match of line.matchAll(/line-height\s*:\s*([^;{}\n]*)/g)) {
      if (match[1].includes('var(--cs-leading') || /\b(inherit|normal)\b/.test(match[1])) continue
      // `line-height: 1` and `0` set a box's height for an icon; they are not reading rhythm.
      if (/^\s*[01]\s*$/.test(match[1])) continue
      violations.push(`${at}: line-height 必须使用 tight/normal/relaxed 三档 Token，实测「${match[1].trim()}」`)
    }

    for (const match of line.matchAll(SPACING)) {
      if (!spacingIsBare(match[2])) continue
      if (isWaived(`${match[1]}:`)) continue
      violations.push(`${at}: ${match[1]} 必须使用间距 Token（§10.3.2 十一档按值命名），实测「${match[2].trim()}」`)
    }

    /*
     * 动效只有 --cs-motion-fast/base/slow 三档时长，曲线只有 --cs-ease-out/--cs-ease-in-out。
     * 时长与曲线必须是两个 Token：此前并存的 `--cs-transition-fast: 120ms ease` 把曲线烤进了
     * 时长，它的调用点因此无法再指定曲线，规范 §4.4「曲线只使用这两条」在那个形状下写不出来。
     * `0ms` 与 `.01ms` 是 prefers-reduced-motion 下的关闭写法，不是取值。
     */
    for (const match of line.matchAll(/(?:transition|animation)(?:-duration)?\s*:\s*([^;{}\n]*)/g)) {
      const bare = match[1].replace(/var\(--cs-[a-z0-9-]+\)/g, '').match(/[\d.]+m?s\b/g) ?? []
      if (!bare.some(value => !/^(0ms|0s|\.01ms)$/.test(value))) continue
      violations.push(`${at}: 动效时长必须使用 --cs-motion-* 三档 Token，实测「${match[1].trim()}」`)
    }

    if (!foundation) continue
    /*
     * 断点只有四档，但 `max-width` 查询表达的是「比某一档窄」，也就是那一档的**排他下侧**：
     * 媒体查询无法求值 `calc(var(--cs-bp-md) - 1px)`，所以「比 md 窄」只能写成 767px。
     * 因此允许的取值是四档本身与它们各自减一，其余仍是野值。
     */
    const breakpoint = line.match(/@media\s*\(max-width:\s*(\d+)px\)/)
    const allowed = ['640', '768', '1100', '1400'].flatMap(value => [value, String(Number(value) - 1)])
    if (breakpoint && !allowed.includes(breakpoint[1])) violations.push(`${at}: breakpoint ${breakpoint[1]}px 不是四档断点 Token（也不是它们的排他下侧 N-1）`)
  }
}

for (const entry of registered) {
  if (!entry.seen) violations.push(`docs/quality/M9-字号白名单.json: 豁免已失效，请删除该条目 → ${entry.file} 「${entry.declaration}」`)
}

/*
 * 契约 Token 定义了却没人消费，等于合同里的一条少了实现而没有任何信号。两次实测都踩在这里：
 * `--cs-density-panel-gap` 定义在 tokens.css、被 compact 正确改写、零引用——于是紧凑模式
 * 实际上只把按钮高度从 38px 压到 32px，页面留白一点没变；`--cs-touch-min` 定义了 44px，
 * 而窄屏下紧凑模式的控件高度仍是 32px。两者门禁都全绿，视觉基线也全绿。
 * 定义处不算消费，所以只统计 tokens.css 之外的引用。
 */
const tokensSource = await readFile(join(web, 'design/tokens.css'), 'utf8')
const contractTokens = [
  ...[...tokensSource.matchAll(/(--cs-density-[a-z-]+)\s*:/g)].map(match => match[1]),
  '--cs-touch-min',
]
const consumers = await Promise.all(
  typographyFiles.filter(file => !file.endsWith('design/tokens.css')).map(file => readFile(file, 'utf8')),
)
for (const token of new Set(contractTokens)) {
  if (consumers.some(source => source.includes(`var(${token})`))) continue
  violations.push(`crewscope-web/src/design/tokens.css: ${token} 定义了但零引用——合同里的一条少了实现却不会有任何信号`)
}

/*
 * 对比度是算出来的。这条规则的存在理由是实测：整套语义色都是在**纯白**上挑的，而它们实际
 * 落在 canvas、surface-subtle 与自家 soft 底上——danger 4.54 / success 4.11 / warning 4.20 /
 * info 4.18，全部低于 AA 的 4.5，而「soft 底 + 语义前景 + 12px」就是状态徽章的组合。
 * 更糟的一处在暗色：--cs-action-primary 被换成 brand-300（浅绿），配近白字只有 1.59:1，
 * 也就是整个应用最重要的按钮在暗色下几乎看不见——而它一路绿灯上线，因为暗色主题的唯一
 * 一条测试只断言了 data-theme 属性被写到 <html> 上。
 *
 * axe 只能查**渲染到的那一屏**，所以它当时只报出了一条（WorkItem 抽屉里的「重试」）。
 * 前景与底色的配对关系是写在 Token 里的静态事实，静态算一遍就能把两个主题一次覆盖干净。
 * 门槛取 4.5（AA 对正文的要求，而语义色几乎都用在 12–13px 上）；取值本身按 5.0 选，
 * 那 0.5 的余量是留给实测踩到过的混合底（半透明遮罩叠出来的 #efefef）。
 */
function parseTokenBlock(source, selector) {
  const start = source.indexOf(selector)
  if (start < 0) return {}
  const body = source.slice(source.indexOf('{', start) + 1, source.indexOf('}', start))
  return Object.fromEntries([...body.matchAll(/(--cs-[a-z0-9-]+)\s*:\s*([^;]+);/g)].map(m => [m[1], m[2].trim()]))
}

function channel(value) {
  const c = value / 255
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
}

function luminance(hex) {
  const full = hex.length === 4 ? `#${[...hex.slice(1)].map(c => c + c).join('')}` : hex
  const [r, g, b] = [1, 3, 5].map(at => channel(parseInt(full.slice(at, at + 2), 16)))
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

function contrast(one, other) {
  const [high, low] = [luminance(one), luminance(other)].sort((a, b) => b - a)
  return (high + 0.05) / (low + 0.05)
}

const SURFACES = ['--cs-canvas', '--cs-surface', '--cs-surface-subtle', '--cs-surface-raised']
/*
 * 只登记**真的会渲染在一起**的配对，否则门禁会开始报告不存在的问题。
 * 每一条都对得上一个调用点：语义色配自家 soft 是状态徽章与各种提示条，
 * action-primary-text 配 action-primary 是 BaseButton 的 primary 档，
 * nav-active-text 配 surface-selected 是侧栏当前项，diff-*-text 配 diff-*-bg 是 Diff 视图。
 */
/*
 * `--cs-surface-accent` 与 `-accent-strong` 加进 SURFACES 是因为迁移把 21 处品牌着色面板
 * 收到了这两档上——那些面板承载正文、状态徽章和说明文字，和普通表面一样要核算。
 */
const TINTED = [...SURFACES, '--cs-surface-accent', '--cs-surface-accent-strong']
const CONTRAST_PAIRS = [
  ...['danger', 'success', 'warning', 'info', 'agent'].map(name => ({
    foreground: `--cs-${name}`, backgrounds: [...TINTED, `--cs-${name}-soft`],
  })),
  { foreground: '--cs-text', backgrounds: TINTED },
  { foreground: '--cs-text-secondary', backgrounds: TINTED },
  { foreground: '--cs-text-muted', backgrounds: TINTED },
  /*
   * 品牌墨色文字的两档。登记它们的理由与语义色完全一样，只是缺陷更深：这两档此前根本不存在，
   * 184 处直接写 `color: var(--cs-brand-700)`，而 brand-* 按 ADR-027 §1.3 刻意不跟主题——
   * 于是暗色下是深绿字压深底，实测低到 1.18:1，而静态门禁当时看不到（它只核算已登记的配对），
   * 逐菜单跑 axe 才把 86 条报出来。现在这件事在两个主题下都是算出来的。
   */
  { foreground: '--cs-text-brand', backgrounds: TINTED },
  { foreground: '--cs-text-brand-strong', backgrounds: TINTED },
  /*
   * 实心块的三组配对。它们和 action-primary 是同一个形状：底在暗色下翻成浅色，
   * 前景必须跟着翻，否则就是白字压浅底。只登记真的存在的三处（danger 按钮、未读徽章、
   * Agent 图标）——出现第四处实心语义块时，它的配对应该跟着那次改动一起加进来。
   */
  { foreground: '--cs-text-on-semantic', backgrounds: ['--cs-danger', '--cs-warning', '--cs-agent'] },
  /* brand-* 色阶按设计不跟主题，所以压在它们上面的近白字在两个主题下是同一个事实。 */
  { foreground: '--cs-text-on-dark', backgrounds: ['--cs-brand-600', '--cs-brand-700', '--cs-brand-800', '--cs-brand-950'] },
  { foreground: '--cs-code-text', backgrounds: ['--cs-code-surface'] },
  { foreground: '--cs-nav-active-text', backgrounds: [...SURFACES, '--cs-surface-selected'] },
  /*
   * 语法高亮的六档前景。它们不只压在通用的四种底上：Diff 里一行代码的底就是
   * --cs-diff-addition-bg / --cs-diff-deletion-bg，高亮色**确实**渲染在那上面。
   * 只登记 SURFACES 曾经漏掉这件事——加上两档 Diff 底之后六档最低 5.58，都在线上。
   */
  ...[
    '--cs-code-keyword',
    '--cs-code-string',
    '--cs-code-comment',
    '--cs-code-function',
    '--cs-code-number',
    '--cs-code-type',
  ].map(foreground => ({ foreground, backgrounds: [...SURFACES, '--cs-diff-addition-bg', '--cs-diff-deletion-bg'] })),
  { foreground: '--cs-action-primary-text', backgrounds: ['--cs-action-primary', '--cs-action-primary-hover'] },
  { foreground: '--cs-diff-addition-text', backgrounds: ['--cs-diff-addition-bg'] },
  { foreground: '--cs-diff-deletion-text', backgrounds: ['--cs-diff-deletion-bg'] },
  { foreground: '--cs-diff-hunk-text', backgrounds: ['--cs-diff-hunk-bg'] },
]

const light = parseTokenBlock(tokensSource, ':root {')
const dark = { ...light, ...parseTokenBlock(tokensSource, '[data-theme="dark"]') }
for (const [theme, palette] of [['浅色', light], ['暗色', dark]]) {
  const resolve = (token, seen = new Set()) => {
    const value = palette[token]
    if (!value || seen.has(token)) return null
    const reference = value.match(/^var\((--cs-[a-z0-9-]+)\)$/)
    if (reference) return resolve(reference[1], new Set([...seen, token]))
    return /^#[0-9a-fA-F]{3,8}$/.test(value) ? value : null
  }
  for (const { foreground, backgrounds } of CONTRAST_PAIRS) {
    const fg = resolve(foreground)
    if (!fg) { violations.push(`crewscope-web/src/design/tokens.css: ${theme}主题缺少 ${foreground}，对比度无法核算`); continue }
    for (const background of backgrounds) {
      const bg = resolve(background)
      if (!bg) { violations.push(`crewscope-web/src/design/tokens.css: ${theme}主题缺少 ${background}，对比度无法核算`); continue }
      const measured = contrast(fg, bg)
      if (measured >= 4.5) continue
      violations.push(`crewscope-web/src/design/tokens.css: ${theme}主题 ${foreground}（${fg}）压在 ${background}（${bg}）上只有 ${measured.toFixed(2)}:1，低于 AA 的 4.5`)
    }
  }
}

/*
 * index.html 的 `theme-color` 是唯一一处**必须**硬写取值的地方：那段引导脚本刻意跑在样式表
 * 之前（否则首屏会闪一下浅色主题），那时读不到任何自定义属性。于是它只能是 --cs-canvas
 * 两档取值的镜像，而镜像会漂移——实测浅色这一档写的是 #f5faf6，画布是 #f3f5f2，
 * 也就是浏览器地址栏和它下面的页面根本不是同一个颜色。这条门禁盯的就是这个等式。
 */
const indexHtml = withoutComments(await readFile(join(root, 'crewscope-web/index.html'), 'utf8'))
const expectedCanvas = { 浅色: light['--cs-canvas'], 暗色: dark['--cs-canvas'] }
const declaredCanvas = [...indexHtml.matchAll(/#[0-9a-fA-F]{6}\b/g)].map(match => match[0])
for (const [theme, expected] of Object.entries(expectedCanvas)) {
  if (declaredCanvas.includes(expected)) continue
  violations.push(`crewscope-web/index.html: theme-color 缺少${theme}主题的画布色 ${expected}（--cs-canvas 的镜像），实测「${declaredCanvas.join('、') || '无'}」`)
}
for (const value of new Set(declaredCanvas)) {
  if (Object.values(expectedCanvas).includes(value)) continue
  violations.push(`crewscope-web/index.html: theme-color 的 ${value} 不是任一主题的 --cs-canvas 取值，两处真值已经漂移`)
}

if (violations.length) {
  console.error('Design Token 门禁失败：')
  violations.forEach(violation => console.error(`- ${violation}`))
  process.exit(1)
}
console.log(`Design Token 门禁：PASS（${typographyFiles.length} 个文件的排版、层级、间距、动效与颜色，零字面量、零悬空引用、brand 色阶只当填色；其中 ${foundationFiles.length} 个基础件另查断点，${CONTRAST_PAIRS.length} 组配色双主题核算对比度，theme-color 对齐画布，${registered.length} 处登记豁免）`)
