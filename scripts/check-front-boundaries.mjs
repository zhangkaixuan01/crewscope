#!/usr/bin/env node
// M12b-Q01 前端边界门禁。三规则，全部从 dep-audit 评审的断言固化而来：
//   1. layering —— src/api 是传输层，不得 import src/domains（域模型）。断环（CommandReceipt
//      下沉 api/creationRecovery.ts）后应恒为零；此规则防回潮。
//   2. cycles —— 生产 import 图（排除 spec/story/spike/test 资产）的强连通分量大于 1 即违规。
//      type-only import 也算边：类型环与运行时环同样会让模块初始化顺序不可推理。
//   3. orphans —— 生产文件必须从 src/main.ts 可达（含路由懒加载的动态 import）。不可达即死代码。
// 豁免清单在下方显式登记，每条带理由；新增豁免必须在同笔提交里说明为什么它不是死代码。

import { readdir, readFile } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { dirname, join, normalize, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const webRoot = fileURLToPath(new URL('../crewscope-web/', import.meta.url))
const srcRoot = join(webRoot, 'src')

const EXEMPT_SEGMENTS = [
  ['stories', 'histoire 演示故事目录，非生产入口'],
  ['spikes', '方案验证 spike，各自携带独立 main.ts'],
  ['test', '测试 fixtures 与 histoire setup'],
  [join('domains', 'demo'), '演示 fixtures 域，仅 story 引用'],
]
const EXEMPT_FILES = new Set([
  join('api', 'generated', 'openapi.ts'), // OpenAPI 漂移金丝雀：故意无人引用，由 check-openapi-drift.mjs 单独校验
])
const EXEMPT_SUFFIXES = ['.spec.ts', '.spec.vue', '.story.vue']

function toRelative(file) { return relative(srcRoot, file).split(sep).join('/') }

function isExempt(relFile) {
  if (EXEMPT_FILES.has(relFile)) return true
  if (EXEMPT_SUFFIXES.some(suffix => relFile.endsWith(suffix))) return true
  return EXEMPT_SEGMENTS.some(([segment]) => relFile === segment || relFile.startsWith(`${segment}/`))
}

async function collect(dir, out) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) await collect(path, out)
    else if (/\.(ts|vue)$/.test(entry.name) && !entry.name.endsWith('.d.ts')) out.push(path) // ambient declarations are not graph nodes
  }
  return out
}

function extractImports(source, file) {
  const code = file.endsWith('.vue')
    ? (source.match(/<script[^>]*>[\s\S]*?<\/script>/g) ?? []).join('\n')
    : source
  const specs = new Set()
  for (const match of code.matchAll(/(?:import|export)\s[\s\S]*?from\s*['"]([^'"]+)['"]/g)) specs.add(match[1])
  for (const match of code.matchAll(/\bimport\s*\(\s*['"]([^'"]+)['"]\s*\)/g)) specs.add(match[1])
  for (const match of code.matchAll(/^\s*import\s*['"]([^'"]+)['"]/gm)) specs.add(match[1])
  return [...specs]
}

function resolveSpec(fromFile, spec) {
  if (!spec.startsWith('.')) return null // bare package specifiers never form app-internal edges
  const base = normalize(join(dirname(fromFile), spec))
  const candidates = /\.(ts|vue|css)$/.test(spec)
    ? [base]
    : [`${base}.ts`, `${base}.vue`, join(base, 'index.ts'), join(base, 'index.vue')]
  return candidates.find(candidate => existsSync(candidate)) ?? null
}

const files = await collect(srcRoot, [])
const production = [] // 图节点：生产 .ts/.vue
const adjacency = new Map()
const apiToDomains = []
for (const file of files) {
  const relFile = toRelative(file)
  if (isExempt(relFile)) continue
  production.push(file)
  const imports = extractImports(await readFile(file, 'utf8'), file)
  const targets = new Set()
  for (const spec of imports) {
    const target = resolveSpec(file, spec)
    if (!target || target.endsWith('.css')) continue
    if (isExempt(toRelative(target))) continue // 豁免资产不进图：演示引用不是生产边
    targets.add(target)
    if (relFile.startsWith('api/') && target.startsWith(join(srcRoot, 'domains'))) {
      apiToDomains.push(`${relFile} -> ${toRelative(target)} (${spec})`)
    }
  }
  adjacency.set(file, [...targets])
}

const layering = apiToDomains.map(edge => `layering: src/${edge} — the api layer must not import domain modules`)

// Tarjan strong-connectivity, iterative (the graph is small but recursion depth is unbounded).
const cycles = []
{
  const index = new Map(), low = new Map(), onStack = new Set(), stack = []
  let counter = 0
  for (const root of production) {
    if (index.has(root)) continue
    const work = [[root, 0]]
    while (work.length) {
      const frame = work[work.length - 1]
      const [node] = frame
      if (frame[1] === 0) { index.set(node, counter); low.set(node, counter); counter += 1; stack.push(node); onStack.add(node) }
      const peers = adjacency.get(node) ?? []
      let advanced = false
      while (frame[1] < peers.length) {
        const peer = peers[frame[1]]
        frame[1] += 1
        if (!index.has(peer)) { work.push([peer, 0]); advanced = true; break }
        if (onStack.has(peer)) low.set(node, Math.min(low.get(node), index.get(peer)))
      }
      if (advanced) continue
      if (low.get(node) === index.get(node)) {
        const component = []
        for (let member = stack.pop(); ; member = stack.pop()) {
          onStack.delete(member)
          component.push(member)
          if (member === node) break
        }
        if (component.length > 1) cycles.push(component)
      }
      work.pop()
      if (work.length) {
        const parent = work[work.length - 1][0]
        low.set(parent, Math.min(low.get(parent), low.get(node)))
      }
    }
  }
}
const cycleViolations = cycles.map(component =>
  `cycle: ${component.map(toRelative).sort().map(file => `src/${file}`).join(' <-> ')}`)

// Reachability from the single production entrypoint, lazy routes included.
const orphans = []
{
  const entry = join(srcRoot, 'main.ts')
  const seen = new Set([entry])
  const queue = [entry]
  while (queue.length) {
    for (const target of adjacency.get(queue.shift()) ?? []) {
      if (!seen.has(target)) { seen.add(target); queue.push(target) }
    }
  }
  for (const file of production) if (!seen.has(file)) orphans.push(`orphan: src/${toRelative(file)} is unreachable from src/main.ts`)
}

const violations = [...layering, ...cycleViolations, ...orphans]
if (violations.length) {
  console.error(`Front boundary violations detected (${violations.length}):`)
  for (const violation of violations.sort()) console.error(`- ${violation}`)
  process.exitCode = 1
} else {
  console.log(`Front boundaries: PASS (${production.length} production modules, 0 layering / 0 cycle / 0 orphan)`)
}
