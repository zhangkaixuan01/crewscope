#!/usr/bin/env node

/**
 * M9-Q01 empty/forbidden state ratchet.
 *
 * Empty states are product entry points: wherever a member can actually take a next step, the panel
 * must carry an `#action` slot. Some states genuinely have no member-executable exit — the panel is
 * a fact about the platform, or the exit already sits in the same viewport (a Team switcher, the
 * search box, the sibling list). Those are recorded in the waiver list with a factual justification
 * instead of being given a decorative button. Waiver entries must keep matching a scanned panel —
 * once a site is migrated or removed its entry fails the gate — so the list can only get shorter.
 *
 * The checked-in baseline records the reviewed debt and this script only rejects regressions.
 */
import { readFile, readdir } from 'node:fs/promises'
import { relative, resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const sourceRoot = resolve(root, 'crewscope-web/src')
const baselinePath = resolve(root, 'docs/quality/M9-empty-state-baseline.json')
const baseline = JSON.parse(await readFile(baselinePath, 'utf8'))
const waiverDocument = JSON.parse(await readFile(resolve(root, 'docs/quality/M9-空状态豁免清单.json'), 'utf8'))
const waivers = waiverDocument.waivers ?? []

async function filesIn(dir) {
  const entries = await readdir(dir, { withFileTypes: true })
  const files = []
  for (const entry of entries) {
    const path = resolve(dir, entry.name)
    if (entry.isDirectory()) files.push(...await filesIn(path))
    else if (entry.name.endsWith('.vue') && !entry.name.endsWith('.story.vue')) files.push(path)
  }
  return files
}

/** The opening tag can span lines (formatted attributes), so read up to the first unquoted `>`. */
function openingTag(lines, index) {
  let text = ''
  for (let cursor = index; cursor < lines.length; cursor += 1) {
    text += `${text ? ' ' : ''}${lines[cursor].trim()}`
    let quote = null
    for (const character of lines[cursor]) {
      if (quote) { if (character === quote) quote = null; continue }
      if (character === '"' || character === "'") { quote = character; continue }
      if (character === '>') return text
    }
  }
  return text
}

/**
 * A bound `:title` resolves at runtime, so the panel has no static label to key a waiver on; those
 * panels use `null` and must be justified by file + state alone.
 */
function panelTitle(tag) {
  if (/\s:title=/.test(tag)) return null
  return tag.match(/\stitle="([^"]*)"/)?.[1] ?? null
}

const consumedWaivers = new Set()

function consumeWaiver(relativeFile, state, title) {
  const index = waivers.findIndex((waiver, position) => !consumedWaivers.has(position)
    && waiver.file === relativeFile
    && waiver.state === state
    && (waiver.title ?? null) === title)
  if (index === -1) return false
  consumedWaivers.add(index)
  return true
}

const metrics = { emptyWithoutAction: 0, forbiddenWithoutAction: 0 }
const offenders = []
for (const file of await filesIn(sourceRoot)) {
  const relativeFile = relative(sourceRoot, file)
  const lines = (await readFile(file, 'utf8')).split(/\r?\n/)
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index]
    const state = line.match(/<StatePanel\b[^>]*\bstate=["'](empty|forbidden)["']/)?.[1]
    if (!state) continue
    let hasAction = /#action/.test(line)
    if (!hasAction && !/\/>/.test(line)) {
      for (let cursor = index + 1; cursor < lines.length; cursor += 1) {
        if (/#action/.test(lines[cursor])) hasAction = true
        if (/<\/StatePanel>/.test(lines[cursor])) break
      }
    }
    if (hasAction) continue
    const title = panelTitle(openingTag(lines, index))
    if (consumeWaiver(relativeFile, state, title)) continue
    const key = `${state}WithoutAction`
    metrics[key] += 1
    offenders.push(`${relativeFile}:${index + 1} state="${state}" title=${title === null ? '<bound>' : `"${title}"`}`)
  }
}

const staleWaivers = waivers
  .map((waiver, position) => ({ waiver, position }))
  .filter(({ position }) => !consumedWaivers.has(position))
  .map(({ waiver }) => `${waiver.file} :: ${waiver.state} :: ${waiver.title ?? '<bound>'}`)

const violations = Object.entries(metrics)
  .filter(([key, value]) => value > Number(baseline[key] ?? 0))
  .map(([key, value]) => `${key}: ${value} > baseline ${baseline[key] ?? 0}`)
if (staleWaivers.length) {
  violations.push('empty-state waiver list has stale entries (the panel no longer matches):')
  violations.push(...staleWaivers.map(item => `  - ${item}`))
}
if (violations.length) {
  console.error('M9-Q01 empty-state gate failed:')
  violations.forEach(item => console.error(`- ${item}`))
  offenders.forEach(item => console.error(`- ${item}`))
  process.exit(1)
}
console.log(`M9-Q01 empty-state gate: PASS (${JSON.stringify(metrics)}, ${consumedWaivers.size} waived)`)
if (process.env.M9_Q01_VERBOSE === '1') offenders.forEach(item => console.log(`- ${item}`))
