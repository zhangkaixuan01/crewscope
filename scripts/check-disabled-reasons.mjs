#!/usr/bin/env node

/** M9-Q01 ratchet for disabled controls.
 *
 * A primitive control cannot know why its caller is disabled, so base components and story/spike
 * fixtures are intentionally outside this scan. Submission/network locks are also transient
 * guards: the surrounding UI already exposes loading/offline state and adding a tooltip to every
 * text field during a request would be noise. The gate focuses on business preconditions and
 * permission guards, accepts an explicit explanation in the local template context, and keeps a
 * reviewed baseline while the remaining surfaces migrate incrementally.
 *
 * A control whose disabled state is decided purely by whether the member filled the form in is
 * neither: the form itself already carries the reason through its own validation affordances, so
 * those sites are recorded in the waiver list with a factual justification instead of being given
 * a redundant explanation. Waiver entries must keep matching a scanned binding — once a site is
 * migrated or removed, its entry fails the gate — so the list can only get shorter.
 */
import { readFile, readdir } from 'node:fs/promises'
import { relative, resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const sourceRoot = resolve(root, 'crewscope-web/src')
const baseline = JSON.parse(await readFile(resolve(root, 'docs/quality/M9-disabled-baseline.json'), 'utf8'))
const waiverDocument = JSON.parse(await readFile(resolve(root, 'docs/quality/M9-禁用态豁免清单.json'), 'utf8'))
const waivers = waiverDocument.waivers ?? []

async function collect(dir) {
  const files = []
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const path = resolve(dir, entry.name)
    if (entry.isDirectory()) files.push(...await collect(path))
    else if (entry.name.endsWith('.vue') && !entry.name.endsWith('.story.vue')) files.push(path)
  }
  return files
}

// The same submission lock shows up bare (`pending`), behind a path (`command.phase === 'pending'`,
// `agentStore.state.agents.loadingMore`) and through optional chaining
// (`commandForSelection?.phase === 'pending'`), so the path qualifier is optional. `commandsPhase`,
// `selectedLog.phase` and `testsPhase` stay as their own atoms: the identifier itself is not
// `phase`, so the general rule cannot see them.
const QUALIFIER = String.raw`(?:[A-Za-z_$][\w$]*\s*\??\.\s*)*`
const transientAtom = new RegExp('^(?:' + [
  '!?online', 'pending', 'submitting', 'busy', 'loading', 'commandPending', 'sendingMessage',
  'importing', 'commentSubmitting', 'agentLoadingMore', 'loadingMore', 'retryable', 'disabled',
  'resyncing',
  String.raw`Boolean\((?:pending|commandPending)\)`,
  QUALIFIER + String.raw`phase\s*===\s*['"](?:loading|pending|accepting|cancelling)['"]`,
  QUALIFIER + 'loadingMore',
  String.raw`state\s*===\s*['"]locked['"]`,
  'item\\.disabled', 'option\\.disabled', 'tab\\.disabled',
  String.raw`action\.enabled\s*===\s*false`,
  String.raw`historyResource\.loadingMore`,
  String.raw`commandsPhase\s*===\s*['"]loading['"]`,
  String.raw`selectedLog\.phase\s*===\s*['"]loading['"]`,
  String.raw`testsPhase\s*===\s*['"]loading['"]`,
].join('|') + ')$')

/**
 * Peels a group that wraps the *whole* expression before it is split on `||`:
 * `(!online || commandPending)` -> `!online || commandPending`.
 *
 * The previous implementation instead stripped a leading `(` and a trailing `)` from each split
 * part. That did handle the grouped form, but it also ate the closing paren of a call, turning
 * `Boolean(pending)` into `Boolean(pending` — which can never match the atom list entry written for
 * exactly that shape. Every `Boolean(pending) || !online` submission lock was therefore reported as
 * unexplained business debt, inflating the metric by 11.
 */
function stripOuterParens(expression) {
  let current = expression.trim()
  while (current.startsWith('(') && current.endsWith(')')) {
    let depth = 0
    let wrapsWhole = true
    for (let index = 0; index < current.length; index += 1) {
      if (current[index] === '(') depth += 1
      else if (current[index] === ')') {
        depth -= 1
        if (depth === 0 && index !== current.length - 1) { wrapsWhole = false; break }
      }
    }
    if (!wrapsWhole) break
    current = current.slice(1, -1).trim()
  }
  return current
}

function isTransientGuard(expression) {
  return stripOuterParens(expression)
    .split(/\|\|/)
    .map(part => part.trim())
    .every(part => transientAtom.test(part))
}

// The metric is small and heuristic, so these samples are the regression list for the classifier:
// a lock must read as transient, a permission or precondition guard must not.
const TRANSIENT_GUARD_SAMPLES = [
  ['!online', true],
  ['Boolean(pending)', true],
  ['Boolean(pending) || !online', true],
  ['(!online || commandPending)', true],
  ["command.phase === 'pending'", true],
  ["agentStore.state.agents.phase === 'loading'", true],
  ['agents.loadingMore', true],
  ["setupStore.state.phase === 'loading' || !online", true],
  ['!canMutate', false],
  ["!canMutate || connections.some(item => item.status === 'ACTIVE')", false],
  ['!scope || !online', false],
  ['!valid', false],
  ["!online || diagnosticsPhase === 'error'", false],
]
for (const [sample, expected] of TRANSIENT_GUARD_SAMPLES) {
  if (isTransientGuard(sample) !== expected) {
    console.error(`M9-Q01 transient-guard self-test failed: ${JSON.stringify(sample)} read as ${!expected}, expected ${expected}`)
    process.exit(1)
  }
}

const normalize = value => value.replace(/\s+/g, ' ').trim()
const consumedWaivers = new Set()

function consumeWaiver(relativeFile, expression) {
  const index = waivers.findIndex((waiver, position) => !consumedWaivers.has(position)
    && waiver.file === relativeFile
    && normalize(waiver.expression) === normalize(expression))
  if (index === -1) return false
  consumedWaivers.add(index)
  return true
}

let unexplained = 0
const offenders = []
for (const file of await collect(sourceRoot)) {
  const relativeFile = relative(sourceRoot, file)
  if (relativeFile.startsWith('base/') || relativeFile.startsWith('spikes/') || relativeFile.startsWith('stories/')) continue
  const lines = (await readFile(file, 'utf8')).split(/\r?\n/)
  lines.forEach((line, index) => {
    const binding = line.match(/:disabled\s*=\s*(["'])(.*?)\1/)
    if (!binding || isTransientGuard(binding[2].trim())) return
    const context = lines.slice(Math.max(0, index - 3), Math.min(lines.length, index + 4)).join(' ')
    if (/aria-describedby|BaseTooltip|disabledReason|disabled-reason|explanation/i.test(context)) return
    if (consumeWaiver(relativeFile, binding[2].trim())) return
    unexplained += 1
    offenders.push(`${relativeFile}:${index + 1}`)
  })
}

const staleWaivers = waivers
  .map((waiver, position) => ({ waiver, position }))
  .filter(({ position }) => !consumedWaivers.has(position))
  .map(({ waiver }) => `${waiver.file} :: ${waiver.expression}`)

const failures = []
if (unexplained > Number(baseline.unexplained ?? 0)) {
  failures.push(`disabled-reason gate failed: ${unexplained} > baseline ${baseline.unexplained}`)
}
if (staleWaivers.length) {
  failures.push(`disabled-reason waiver list has stale entries (the binding no longer matches):`)
  failures.push(...staleWaivers.map(item => `  - ${item}`))
}
if (failures.length) {
  console.error('M9-Q01 disabled-reason gate failed:')
  failures.forEach(item => console.error(item))
  offenders.forEach(item => console.error(`- ${item}`))
  process.exit(1)
}
console.log(`M9-Q01 disabled-reason gate: PASS (${unexplained} unexplained bindings, ${consumedWaivers.size} waived)`)
