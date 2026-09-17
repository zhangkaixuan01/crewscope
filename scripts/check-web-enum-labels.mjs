#!/usr/bin/env node

/**
 * M9-Q01 裸枚举门禁。
 *
 * 服务端把每个枚举按 `{Enum}.name()` 序列化，所以一旦模板直接插值这类字段，成员看到的就是
 * `RESPONSIBILITY_ASSIGNMENT` 这种 Java 常量。M9 的收口做法是：每个领域的 `labels.ts` 用
 * `Record<Enum, string>` 把常量映射成成员语言，调用点统一走 `enumLabel()`。
 *
 * 这个门禁不是计数式基线，而是清单式棘轮：任何新的裸枚举插值都直接失败，已评审的例外写在
 * `docs/quality/M9-裸枚举豁免清单.json` 里并各自带理由；例外一旦失效（对应表达式已被改掉）
 * 同样失败，逼着清单只能变短，不会烂在仓库里。
 */
import { readFile, readdir } from 'node:fs/promises'
import { relative, resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const sourceRoot = resolve(root, 'crewscope-web/src')
const waiverPath = resolve(root, 'docs/quality/M9-裸枚举豁免清单.json')
const { waivers } = JSON.parse(await readFile(waiverPath, 'utf8'))

/**
 * 枚举字段名的后缀词表。按小写比较，否则 `\bcode` 永远匹配不到 `failureCode` 这类小驼峰字段
 * —— 第一版门禁正是因为区分大小写而漏掉了绝大多数真实站点。
 */
const SUFFIXES = [
  'status', 'type', 'kind', 'reason', 'code', 'level', 'priority', 'outcome',
  'visibility', 'disposition', 'source', 'result', 'scope', 'category', 'cause',
  'role', 'termination', 'classification', 'decision', 'phase', 'state', 'mode',
  'policy', 'identity', 'validity', 'verdict', 'severity', 'action', 'trigger',
]
const INTERPOLATION = /\{\{(.+?)\}\}/g
/** 已经过映射、三元本地化或 tone/text 辅助函数的表达式不算裸露。 */
const LOCALISED = /[Ll]abels?\b|enumLabel|presentation|Text\(|Tone\(|\?\s*'|===/
const TAIL = /([A-Za-z0-9_]+)\s*$/

async function templateFiles(dir) {
  const entries = await readdir(dir, { withFileTypes: true })
  const files = []
  for (const entry of entries) {
    const path = resolve(dir, entry.name)
    if (entry.isDirectory()) {
      // `generated/` 是 OpenAPI / 状态机产物，没有模板；`.story.vue` 是 Histoire 的编写夹具，
      // 里面的事实来自 `domains/demo/fixtures.ts`，本来就是人话而不是枚举常量。
      if (entry.name !== 'generated' && entry.name !== 'node_modules') files.push(...await templateFiles(path))
    } else if (entry.name.endsWith('.vue') && !entry.name.endsWith('.story.vue')) {
      files.push(path)
    }
  }
  return files
}

const files = (await templateFiles(sourceRoot)).sort()
const hits = []
for (const file of files) {
  const lines = (await readFile(file, 'utf8')).split('\n')
  lines.forEach((line, index) => {
    for (const match of line.matchAll(INTERPOLATION)) {
      const expression = match[1].trim()
      if (LOCALISED.test(expression)) continue
      // 读取 `??` 兜底之前那一段的最后一个标识符，也就是真正被插值的字段名。
      const tail = TAIL.exec(expression.split('??')[0])
      if (!tail) continue
      const word = tail[1].toLowerCase()
      if (!SUFFIXES.some(suffix => word.endsWith(suffix))) continue
      hits.push({ file: relative(sourceRoot, file), line: index + 1, expression })
    }
  })
}

const waived = new Set(waivers.map(item => `${item.file}::${item.expression}`))
const matched = new Set()
const violations = []
for (const hit of hits) {
  const key = `${hit.file}::${hit.expression}`
  if (waived.has(key)) matched.add(key)
  else violations.push(`${hit.file}:${hit.line}  {{ ${hit.expression} }}`)
}
const stale = [...waived].filter(key => !matched.has(key))

if (violations.length || stale.length) {
  console.error('裸枚举门禁失败：')
  if (violations.length) {
    console.error(`- 新增 ${violations.length} 处未本地化的枚举插值，请在对应 domain 的 labels.ts 里补 Record<Enum, string> 并改走 enumLabel()：`)
    violations.forEach(item => console.error(`    ${item}`))
  }
  if (stale.length) {
    console.error(`- ${stale.length} 条豁免已失效（对应插值不存在了），请从 docs/quality/M9-裸枚举豁免清单.json 删除：`)
    stale.forEach(key => console.error(`    ${key.replace('::', '  ')}`))
  }
  process.exit(1)
}
console.log(`裸枚举门禁：PASS（${files.length} 个模板，${hits.length} 处评审豁免，0 处新增）`)
