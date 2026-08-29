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
const demoCompose = readFileSync(join(root, 'deploy/team-beta/compose.demo.yaml'), 'utf8')
const demoScript = readFileSync(join(root, 'deploy/team-beta/demo.sh'), 'utf8')
const profileGate = readFileSync(join(root, 'scripts/m7-q04-registration-profile-gate.sh'), 'utf8')
const authenticationController = readFileSync(
  join(root, 'crewscope-server/src/main/java/io/crewscope/server/api/AuthenticationController.java'),
  'utf8',
)
const authStore = readFileSync(join(root, 'crewscope-web/src/domains/identity/store.ts'), 'utf8')
assert.match(compose, /CREWSCOPE_REGISTRATION_MODE: \$\{CREWSCOPE_REGISTRATION_MODE:-INVITE_ONLY\}/)
assert.match(demoCompose, /CREWSCOPE_REGISTRATION_MODE: \$\{CREWSCOPE_REGISTRATION_MODE:-OPEN\}/)
assert.match(demoScript, /OPEN\|INVITE_ONLY\|DISABLED/)
assert.match(demoScript, /set-registration-mode/)
assert.match(demoScript, /--force-recreate --wait api/)
assert.match(demoScript, /CREWSCOPE_DEMO_BUILD/)
assert.match(profileGate, /if \[ "\$CREWSCOPE_DEMO_BUILD" != true \]; then/)
assert.match(profileGate, /CREWSCOPE_Q04_BUILD_IMAGES=true/)
assert.match(authenticationController, /BrowserPermissionProjection\.account\(account\.platformRole\(\)\)/)
assert.doesNotMatch(authenticationController, /BrowserPermissionProjection\.account\([^)]*,/)
assert.match(authStore, /new Set\(\[\.\.\.session\.permissions, \.\.\.\(selected\?\.permissions \?\? \[\]\)\]\)/)

const recovery = readFileSync(join(root, 'scripts/team-beta-recovery.mjs'), 'utf8')
assert.match(recovery, /minimumSchemaVersion: 26, maximumSchemaVersion: 32/)
assert.doesNotMatch(recovery, /minimumSchemaVersion: 26, maximumSchemaVersion: 30/)

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

const forbidden = [
  { pattern: /@Disabled\b/, label: 'JUnit @Disabled' },
  { pattern: /\b(?:test|it|describe)\.(?:skip|todo|only)\s*\(/, label: 'skipped or focused JS test' },
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
  }
}
assert.deepEqual(violations, [], `M7 release tests must have zero skips/focus:\n${violations.join('\n')}`)

console.log('M7 release contract passed: scoped Team permissions, three registration Profiles, V26..V32 recovery, CI dependencies and zero skipped/focused tests.')

function collect(path) {
  if (!existsSync(path)) return []
  if (!statSync(path).isDirectory()) {
    return ['.java', '.js', '.mjs', '.ts', '.vue'].includes(extname(path)) ? [path] : []
  }
  return readdirSync(path, { withFileTypes: true })
    .flatMap(entry => collect(join(path, entry.name)))
}
