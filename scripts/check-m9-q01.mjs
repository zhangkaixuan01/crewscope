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

const files = await sourceFiles(sourceRoot)
const text = (await Promise.all(files.map(file => readFile(file, 'utf8')))).join('\n')
const metrics = {
  nativeDialogs: (text.match(/\b(?:window\.)?(?:alert|confirm|prompt)\s*\(/g) ?? []).length,
  uuidInputs: (text.match(/(?:placeholder|aria-label|label)=["'][^"']*(?:UUID|Principal ID)[^"']*["']/gi) ?? []).length,
  bareTitleAttributes: (text.match(/\stitle=["'][^"']*["']/g) ?? []).length,
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
