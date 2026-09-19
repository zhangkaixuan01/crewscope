#!/usr/bin/env node

import assert from 'node:assert/strict'
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { extname, join, relative, resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const requiredFiles = [
  'scripts/m7-release-gate.sh',
  'scripts/m7-q01-security-gate.sh',
  'scripts/m7-q02-convergence-gate.sh',
  'scripts/m7-q03-two-user-e2e-gate.sh',
  'scripts/m7-q04-registration-profile-gate.sh',
  'crewscope-web/playwright.m7-q03.config.ts',
  'crewscope-web/playwright.m7-q04.config.ts',
  'crewscope-web/e2e/m7-two-user-real.spec.ts',
  'crewscope-web/e2e/m7-registration-profiles-real.spec.ts',
  'docs/testing/M7-Q01-本地认证安全硬化与固定攻击集.md',
  'docs/testing/M7-Q02-身份邀请并发故障与事务收敛.md',
  'docs/testing/M7-Q03-双用户真实协作与会话恢复.md',
  'docs/testing/M7-Q04-Release-Gate.md',
]

for (const file of requiredFiles) {
  assert.ok(existsSync(join(root, file)), `M7 release dependency is missing: ${file}`)
}

const packageDocument = JSON.parse(readFileSync(join(root, 'crewscope-web/package.json'), 'utf8'))
assert.equal(
  packageDocument.scripts['test:e2e:m7-q04-profiles'],
  'playwright test --config playwright.m7-q04.config.ts',
)

const compose = readFileSync(join(root, 'deploy/team-beta/compose.yaml'), 'utf8')
const demoScript = readFileSync(join(root, 'deploy/team-beta/quickstart.sh'), 'utf8')
const profileGate = readFileSync(join(root, 'scripts/m7-q04-registration-profile-gate.sh'), 'utf8')
const authenticationController = readFileSync(
  join(root, 'crewscope-server/src/main/java/io/crewscope/server/api/AuthenticationController.java'),
  'utf8',
)
const authStore = readFileSync(join(root, 'crewscope-web/src/domains/identity/store.ts'), 'utf8')
assert.match(compose, /CREWSCOPE_REGISTRATION_MODE: \$\{CREWSCOPE_REGISTRATION_MODE:-OPEN\}/)
assert.match(demoScript, /OPEN\|INVITE_ONLY\|DISABLED/)
assert.match(demoScript, /set-registration-mode/)
assert.match(demoScript, /--force-recreate --wait api/)
assert.match(demoScript, /compose\.yaml/)
assert.match(authenticationController, /BrowserPermissionProjection\.account\(account\.platformRole\(\)\)/)
assert.doesNotMatch(authenticationController, /BrowserPermissionProjection\.account\([^)]*,/)
assert.match(authStore, /new Set\(\[\.\.\.session\.permissions, \.\.\.\(selected\?\.permissions \?\? \[\]\)\]\)/)

const recovery = readFileSync(join(root, 'scripts/team-beta-recovery.mjs'), 'utf8')
assert.match(recovery, /MINIMUM_SCHEMA_VERSION = 26/)
assert.match(recovery, /CURRENT_MAXIMUM_SCHEMA_VERSION = 36/)
assert.doesNotMatch(recovery, /CURRENT_MAXIMUM_SCHEMA_VERSION = (?:34|35)/)

const workflow = readFileSync(join(root, '.github/workflows/ci.yml'), 'utf8')
for (const dependency of [
  'backend',
  'frontend',
  'quality',
  'dependency_security_osv',
  'dependency_security_web',
  'image_security',
]) {
  assert.match(workflow, new RegExp(`${dependency.replaceAll('_', '[_-]')}`))
}
assert.match(workflow, /node scripts\/check-m7-release-contract\.mjs/)
assert.match(workflow, /node scripts\/check-test-report-zero-skips\.mjs/)
assert.match(workflow, /Enforce the M7 release gate/)

const defaultPlaywright = readFileSync(join(root, 'crewscope-web/playwright.config.ts'), 'utf8')
assert.match(defaultPlaywright, /testIgnore:[\s\S]*m7-two-user-real\.spec\.ts/)
assert.match(defaultPlaywright, /testIgnore:[\s\S]*m7-registration-profiles-real\.spec\.ts/)

/*
 * Zero skipped or focused tests, with one distinction the pattern has to make. The browser matrix runs
 * every spec under both `desktop-chromium` and `narrow-chromium`, so a spec that belongs to a single
 * project opts out of the other one with `test.skip(condition, reason)` at its top — that is not the
 * disabled test this contract forbids, and matching the call alone reported it as one. What the
 * contract forbids is a test that never runs anywhere: a focus marker, a todo, or a skip with no
 * condition. So only those stay forbidden, and a conditional skip has to state why to be accepted at
 * all — stricter than the call match it replaces.
 */
const forbidden = [
  { pattern: /@Disabled\b/, label: 'JUnit @Disabled' },
  { pattern: /\b(?:fdescribe|fit|xdescribe|xit)\s*\(/, label: 'focused or disabled JS test' },
  { pattern: /\b(?:test|it|describe)(?:\.describe)?\.(?:only|todo)\s*\(/, label: 'focused or todo JS test' },
]
const testRoots = [
  'crewscope-domain/src/test',
  'crewscope-application/src/test',
  'crewscope-infrastructure/src/test',
  'crewscope-integration/src/test',
  'crewscope-agentscope/src/test',
  'crewscope-server/src/test',
  'crewscope-web/src',
  'crewscope-web/e2e',
]
const violations = []
for (const testRoot of testRoots) {
  for (const file of collect(join(root, testRoot))) {
    const content = readFileSync(file, 'utf8')
    for (const rule of forbidden) {
      if (rule.pattern.test(content)) violations.push(`${relative(root, file)}: ${rule.label}`)
    }
    for (const skip of skipCalls(content)) {
      const where = `${relative(root, file)}: ${skip.line}`
      if (!skip.conditional) violations.push(`${where}: unconditional test.skip`)
      else if (!skip.reason) violations.push(`${where}: conditional test.skip without a reason`)
    }
  }
}
assert.deepEqual(violations, [], `M7 release tests must have no focused, todo or unconditioned skips:\n${violations.join('\n')}`)

console.log('M7 release contract passed: scoped Team permissions, registration Profiles, V26..V36 recovery, CI dependencies and no focused, todo or unconditioned skips.')

function collect(path) {
  if (!existsSync(path)) return []
  if (!statSync(path).isDirectory()) {
    return ['.java', '.js', '.mjs', '.ts', '.vue'].includes(extname(path)) ? [path] : []
  }
  return readdirSync(path, { withFileTypes: true })
    .flatMap(entry => collect(join(path, entry.name)))
}

/** Every `.skip(...)` call in the file, with the two shapes this contract separates. */
function skipCalls(content) {
  const calls = []
  for (const match of content.matchAll(/\b(?:test|it|describe)(?:\.describe)?\.skip\s*\(/g)) {
    const args = argumentsOf(content, match.index + match[0].length - 1).map(argument => argument.trim())
    calls.push({
      // A skip is conditional when its first argument is an expression rather than a reason string.
      conditional: args[0] !== undefined && args[0] !== '' && !/^['"`]/.test(args[0]),
      reason: /^['"`][^'"`]+['"`]$/.test(args[args.length - 1] ?? ''),
      line: content.slice(0, match.index).split('\n').length,
    })
  }
  return calls
}

/**
 * The arguments of the call whose opening parenthesis sits at `open`, split at the top level so that a
 * comma inside the condition (an arrow function parameter list, a callback body) is not a separator.
 */
function argumentsOf(content, open) {
  const args = []
  let current = ''
  let depth = 0
  let quote = ''
  for (let index = open; index < content.length; index += 1) {
    const character = content[index]
    if (quote) {
      current += character
      if (character === quote && content[index - 1] !== '\\') quote = ''
      continue
    }
    if (character === "'" || character === '"' || character === '`') {
      quote = character
      current += character
      continue
    }
    if (character === '(') {
      depth += 1
      if (depth > 1) current += character
      continue
    }
    if (character === ')') {
      depth -= 1
      if (depth === 0) return [...args, current]
      current += character
      continue
    }
    if (character === ',' && depth === 1) {
      args.push(current)
      current = ''
      continue
    }
    current += character
  }
  return [...args, current]
}
