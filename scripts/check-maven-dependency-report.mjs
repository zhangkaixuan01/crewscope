#!/usr/bin/env node

/**
 * Verifies Maven's dependency:analyze output against the reviewed baseline.
 * The Maven analyzer intentionally reports implementation classes supplied by
 * Spring/AgentScope starters as undeclared; the baseline makes those decisions
 * explicit while still failing when a new coordinate appears unexpectedly.
 */
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const reportPath = process.argv[2]
if (!reportPath) {
  console.error('Usage: node scripts/check-maven-dependency-report.mjs <maven-log>')
  process.exit(2)
}
const allowlistPath = join(root, 'config/maven-dependency-analyze.allowlist')
assert.ok(existsSync(allowlistPath), `Missing dependency allowlist: ${allowlistPath}`)
assert.ok(existsSync(reportPath), `Missing Maven dependency report: ${reportPath}`)

const allowlist = new Set()
for (const [index, raw] of readFileSync(allowlistPath, 'utf8').split(/\r?\n/).entries()) {
  const line = raw.trim()
  if (!line || line.startsWith('#')) continue
  assert.match(line, /^(used|unused|non)\|[^:]+:[^:]+$/, `Invalid allowlist entry at line ${index + 1}`)
  assert.ok(!allowlist.has(line), `Duplicate allowlist entry at line ${index + 1}: ${line}`)
  allowlist.add(line)
}

const diagnostics = new Set()
let kind = null
for (const line of readFileSync(reportPath, 'utf8').split(/\r?\n/)) {
  if (line.includes('Used undeclared dependencies found:')) kind = 'used'
  else if (line.includes('Unused declared dependencies found:')) kind = 'unused'
  else if (line.includes('Non-test scoped test only dependencies found:')) kind = 'non'
  else if (line.startsWith('[INFO] --- ')) kind = null
  else if (kind && line.startsWith('[WARNING]')) {
    const coordinate = line.replace(/^\[WARNING\]\s+/, '').trim().split(':')
    if (coordinate.length >= 2 && coordinate[0] && coordinate[1]) {
      diagnostics.add(`${kind}|${coordinate[0]}:${coordinate[1]}`)
    }
  }
}

const unexpected = [...diagnostics].filter(entry => !allowlist.has(entry)).sort()
assert.deepEqual(unexpected, [], `Unreviewed Maven dependency diagnostics:\n${unexpected.join('\n')}`)
console.log(`Maven dependency contract passed: ${diagnostics.size} reviewed diagnostics.`)
