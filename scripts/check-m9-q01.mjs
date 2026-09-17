#!/usr/bin/env node

/**
 * M9-Q01 regression gate.  The repository intentionally carries a reviewed
 * baseline while the product migration is in progress; a change may not make
 * any of the tracked experience debt worse.  The baseline is checked in so
 * CI remains deterministic and the debt can be ratcheted down explicitly.
 */
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const sourceRoot = resolve(root, 'crewscope-web/src')
const baselinePath = resolve(root, 'docs/quality/M9-门禁基线.json')
const baseline = JSON.parse(await readFile(baselinePath, 'utf8'))

async function sourceFiles(dir) {
  const { readdir } = await import('node:fs/promises')
  const entries = await readdir(dir, { withFileTypes: true })
  const files = []
  for (const entry of entries) {
    const path = resolve(dir, entry.name)
    if (entry.isDirectory()) files.push(...await sourceFiles(path))
    else if (/\.(vue|ts)$/.test(entry.name) && !/\.(spec|test)\./.test(entry.name)) files.push(path)
  }
  return files
}

/**
 * Only an HTML element's `title` attribute is a violation here.  The rule exists because `title` is
 * unreachable on touch and unreliable for assistive tech, so copy must not live in it.  A `title=`
 * inside a PascalCase/kebab-case tag is a *prop* instead: StatePanel renders it as a visible <h3>,
 * AppShell as an <h1>, AuthCard as an <h2> and Histoire's Variant/Story are its own API — none of
 * them carries information through the attribute.  Counting those made the metric unfixable: all
 * 313 recorded "violations" were props and there was not a single real HTML `title` in the tree.
 */
function countBareTitleAttributes(source) {
  let count = 0
  for (const match of source.matchAll(/\stitle=["'][^"']*["']/g)) {
    const openTag = source.lastIndexOf('<', match.index)
    const closeTag = source.lastIndexOf('>', match.index)
    // Not inside an opening tag at all: no tag name to reason about, so keep it visible rather than
    // silently dropping it.
    if (openTag <= closeTag) {
      count += 1
      continue
    }
    const tagName = source.slice(openTag + 1).match(/^([A-Za-z][A-Za-z0-9-]*)/)?.[1] ?? ''
    if (/^[a-z][a-z0-9]*$/.test(tagName)) count += 1
  }
  return count
}

// `bareTitleAttributes` is at zero, so nothing in the tree can prove the classifier still works.
// This is the regression list for it: a real HTML element must be caught, a component prop must not,
// and a `title=` that belongs to no opening tag must stay visible rather than be dropped.
const TITLE_CLASSIFIER_SAMPLES = [
  ['<button title="导出">导出</button>', 1],
  ['<input class="field" title="名称" />', 1],
  ["<span class='chip' title='提示'>x</span>", 1],
  ['<svg title="图表">', 1],
  ['const x = a title="不属于任何开始标签"', 1],
  ['<StatePanel state="loading" title="正在加载" />', 0],
  ['<AuthCard title="登录">', 0],
  ['<Variant title="light">', 0],
  ['<base-tooltip text="x">', 0],
  ['<svg><title>纯文本标题</title></svg>', 0],
]
for (const [sample, expected] of TITLE_CLASSIFIER_SAMPLES) {
  const actual = countBareTitleAttributes(sample)
  if (actual !== expected) {
    console.error(`M9-Q01 title classifier self-test failed: ${JSON.stringify(sample)} classified as ${actual}, expected ${expected}`)
    process.exit(1)
  }
}

const files = await sourceFiles(sourceRoot)
const sources = await Promise.all(files.map(async file => await readFile(file, 'utf8')))
const text = sources.join('\n')
const metrics = {
  // `confirm` is also a domain verb here (delivery confirm, task-intent confirm, useConfirm), so an
  // unqualified match counted dozens of legitimate calls and left the metric unable to fail.  Only
  // the browser dialogs are interesting: `window.`-qualified calls, plus bare `alert`/`prompt`,
  // which are not used as domain names anywhere in this codebase.
  nativeDialogs: (text.match(/\bwindow\s*\.\s*(?:alert|confirm|prompt)\s*\(|(?<![.\w$])(?:alert|prompt)\s*\(/g) ?? []).length,
  // Scoped to fields a member has to fill in.  A `title`/`aria-label` that merely *mentions* an
  // identifier (the copy-to-clipboard button on the member table) helps rather than burdens, and
  // counting it made the metric unratchetable.
  uuidInputs: (text.match(/<(?:input|textarea)\b[^>]*(?:placeholder|aria-label)=["'][^"']*(?:UUID|Principal ID)[^"']*["']/gi) ?? []).length,
  bareTitleAttributes: sources.reduce((total, source) => total + countBareTitleAttributes(source), 0),
}

const violations = Object.entries(metrics)
  .filter(([key, value]) => value > Number(baseline[key] ?? 0))
  .map(([key, value]) => `${key}: ${value} > baseline ${baseline[key]}`)

if (violations.length) {
  console.error('M9-Q01 regression gate failed:')
  violations.forEach(item => console.error(`- ${item}`))
  process.exit(1)
}
console.log(`M9-Q01 gate: PASS (${files.length} production files, ${JSON.stringify(metrics)})`)
