#!/usr/bin/env node

/** Enforces the Design System v2 baseline for the migrated foundation layer. */
import { readdir, readFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const roots = [
  join(root, 'crewscope-web/src/components/base'),
  join(root, 'crewscope-web/src/components/feedback/StatePanel.vue'),
  join(root, 'crewscope-web/src/components/feedback/ToastHost.vue'),
  join(root, 'crewscope-web/src/components/feedback/ConfirmHost.vue'),
  join(root, 'crewscope-web/src/design/base.css'),
]
const files = []
async function collect(path) {
  const entries = await readdir(path, { withFileTypes: true }).catch(() => [])
  if (!entries.length) { files.push(path); return }
  for (const entry of entries) {
    const child = join(path, entry.name)
    if (entry.isDirectory()) await collect(child)
    else if (/\.(vue|css)$/.test(entry.name)) files.push(child)
  }
}
for (const path of roots) await collect(path)

const violations = []
for (const file of files) {
  const source = await readFile(file, 'utf8')
  const relative = file.slice(root.length + 1)
  for (const [index, line] of source.split(/\r?\n/).entries()) {
    const lineNumber = index + 1
    if (/font-size\s*:\s*(?!var\(--cs-text)[^;\n]*\b\d+px/.test(line)) violations.push(`${relative}:${lineNumber}: font-size must use a text token`)
    if (!/margin:\s*-1px/.test(line) && /(?:padding|margin|gap)(?:-[a-z]+)?\s*:\s*(?!var\(--cs-space|calc\(|0(?:[; }]|$))[^;\n]*\b\d+px/.test(line)) violations.push(`${relative}:${lineNumber}: spacing must use a space token`)
    if (!line.includes('transition-duration: .01ms') && /transition[^:]*:\s*[^;\n]*\b\d+ms/.test(line)) violations.push(`${relative}:${lineNumber}: transition duration must use a motion token`)
    const breakpoint = line.match(/@media\s*\(max-width:\s*(\d+)px\)/)
    if (breakpoint && !['640', '768', '1100', '1400'].includes(breakpoint[1])) violations.push(`${relative}:${lineNumber}: breakpoint ${breakpoint[1]}px is not a Design System token`)
  }
}

if (violations.length) {
  console.error('Design Token violations detected:')
  violations.forEach(violation => console.error(`- ${violation}`))
  process.exit(1)
}
console.log(`Design Token gate: PASS (${files.length} foundation files)`)
