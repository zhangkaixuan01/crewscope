#!/usr/bin/env node

/**
 * M9-Q01 modal accessibility ratchet.
 *
 * Every application modal must expose a programmatic focus target and a keyboard
 * focus contract. BaseDialog/BaseDrawer use the shared composable; domain modals
 * may keep a local handler while they are migrated, but must still declare a
 * tabindex so focus can be restored deterministically.
 */
import { readFile, readdir } from 'node:fs/promises'
import { relative, resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const sourceRoot = resolve(root, 'crewscope-web/src')
const offenders = []

async function collect(dir) {
  const files = []
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const file = resolve(dir, entry.name)
    if (entry.isDirectory()) files.push(...await collect(file))
    else if (entry.name.endsWith('.vue') && !entry.name.endsWith('.story.vue')) files.push(file)
  }
  return files
}

for (const file of await collect(sourceRoot)) {
  const source = await readFile(file, 'utf8')
  const modalCount = (source.match(/<[A-Za-z][^>]*role=["']dialog["'][^>]*aria-modal=["']true["']|<[A-Za-z][^>]*aria-modal=["']true["'][^>]*role=["']dialog["']/g) ?? []).length
  if (!modalCount) continue

  const hasFocusTarget = /tabindex=["']-1["']|useFocusTrap\s*\(/.test(source)
  const hasKeyboardContract = /useFocusTrap\s*\(|(?:@keydown|addEventListener\(['"]keydown|function\s+(?:handle|trap|closeOnEscape)[A-Za-z]*\s*\()/.test(source)
  if (!hasFocusTarget) offenders.push(`${relative(sourceRoot, file)}: modal has no tabindex=-1 or shared focus trap`)
  if (!hasKeyboardContract) offenders.push(`${relative(sourceRoot, file)}: modal has no keyboard focus contract`)
}

if (offenders.length) {
  console.error('M9-Q01 dialog-focus gate failed:')
  offenders.forEach(item => console.error(`- ${item}`))
  process.exit(1)
}
console.log(`M9-Q01 dialog-focus gate: PASS (${(await collect(sourceRoot)).length} Vue sources scanned)`)
