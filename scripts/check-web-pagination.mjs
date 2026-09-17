#!/usr/bin/env node

/**
 * M9-Q01 pagination guard.
 *
 * Cursor-paginated resources must be filtered by the API before the page is returned. A global
 * `.filter()` scan is intentionally avoided because member pickers, labels and de-duplication
 * legitimately filter in memory. Instead we look for the high-risk shape in production page and
 * domain code: a paginated resource (`state.*.value` / `resource.value`) immediately filtered in
 * the same expression. New violations fail; the reviewed baseline is zero.
 */
import { readFile, readdir } from 'node:fs/promises'
import { relative, resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const sourceRoot = resolve(root, 'crewscope-web/src')
const baseline = JSON.parse(await readFile(resolve(root, 'docs/quality/M9-pagination-baseline.json'), 'utf8'))

async function collect(dir) {
  const files = []
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const file = resolve(dir, entry.name)
    if (entry.isDirectory()) files.push(...await collect(file))
    else if (/\.(vue|ts)$/.test(entry.name) && !/\.(spec|test)\./.test(entry.name) && !entry.name.endsWith('.story.vue')) files.push(file)
  }
  return files
}

const offenders = []
for (const file of await collect(sourceRoot)) {
  const lines = (await readFile(file, 'utf8')).split(/\r?\n/)
  lines.forEach((line, index) => {
    // The resource names are deliberately explicit: these are the stores whose API contracts use
    // `nextCursor`. Other `.filter()` calls remain legal until their resource is made cursor-based.
    if (!/(?:state\.(?:teamActivity|audit|inbox|items|conversations|messages|history|revisions)\.value|(?:activity|audit|inbox|conversation|message|history|revision)Resource\.value)\s*\)?\s*\.filter\s*\(/.test(line)) return
    offenders.push(`${relative(sourceRoot, file)}:${index + 1}`)
  })
}

const count = offenders.length
if (count > Number(baseline.violations ?? 0)) {
  console.error(`M9-Q01 pagination gate failed: ${count} > baseline ${baseline.violations}`)
  offenders.forEach(item => console.error(`- ${item}`))
  process.exit(1)
}
console.log(`M9-Q01 pagination gate: PASS (${count} client-side filters on paginated resources)`)
