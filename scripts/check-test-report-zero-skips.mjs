#!/usr/bin/env node

import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const reactorModules = [...readFileSync(join(root, 'pom.xml'), 'utf8').matchAll(/<module>([^<]+)<\/module>/g)]
  .map(match => match[1].trim())
// Runtime worktrees under var/ can contain intentionally failing coding-evaluation reports. Only
// reports produced by the modules declared in the root Maven Reactor belong to this release gate.
const reports = reactorModules.flatMap(module => collectModuleReports(join(root, module)))
if (reports.length === 0) fail('No Maven XML test reports were found')

let tests = 0
let failures = 0
let errors = 0
let skipped = 0
for (const report of reports) {
  const document = readFileSync(report, 'utf8')
  const suite = /<testsuite\b[^>]*>/.exec(document)?.[0]
  if (!suite) fail(`Invalid test report: ${relative(root, report)}`)
  tests += attribute(suite, 'tests')
  failures += attribute(suite, 'failures')
  errors += attribute(suite, 'errors')
  skipped += attribute(suite, 'skipped')
}
if (tests === 0 || failures !== 0 || errors !== 0 || skipped !== 0) {
  fail(`Maven report gate failed: tests=${tests}, failures=${failures}, errors=${errors}, skipped=${skipped}`)
}
console.log(`Maven report gate passed: ${tests} tests, zero failures, errors and skips across ${reports.length} suites.`)

function attribute(element, name) {
  const value = new RegExp(`\\b${name}="(\\d+)"`).exec(element)?.[1]
  if (value === undefined) fail(`testsuite is missing ${name}`)
  return Number(value)
}

function collectModuleReports(modulePath) {
  return ['surefire-reports', 'failsafe-reports'].flatMap(reportDirectory => {
    const path = join(modulePath, 'target', reportDirectory)
    if (!existsSync(path)) return []
    return readdirSync(path, { withFileTypes: true })
      .filter(entry => entry.isFile() && /^TEST-.*\.xml$/.test(entry.name))
      .map(entry => join(path, entry.name))
  })
}

function fail(message) {
  console.error(message)
  process.exit(1)
}
