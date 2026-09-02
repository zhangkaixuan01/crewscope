#!/usr/bin/env node

import { readFile } from 'node:fs/promises'

const checks = [
  ['crewscope-agentscope/pom.xml', /agentscope-extensions-sandbox-kubernetes/, 'Kubernetes Sandbox must not be on the default runtime classpath', false],
  ['crewscope-infrastructure/pom.xml', /spring-boot-configuration-processor/, 'Infrastructure must generate Spring configuration metadata', true],
  ['crewscope-server/pom.xml', /spring-boot-configuration-processor/, 'Server must generate Spring configuration metadata', true],
  ['config/maven-dependency-analyze.allowlist', /^used\|/m, 'Maven dependency diagnostics must have a reviewed allowlist', true],
  ['scripts/check-config-contract.mjs', /Configuration contract passed/, 'configuration contract checker must be present', true],
  ['scripts/check-maven-dependency-report.mjs', /Unreviewed Maven dependency diagnostics/, 'Maven dependency report checker must be present', true],
  ['.github/workflows/release.yml', /docker\/build-push-action@v6/, 'tag-driven release workflow must publish images', true],
  ['scripts/check-release-contract.mjs', /Release contract passed/, 'release workflow contract checker must be present', true],
  ['crewscope-web/package.json', /"lint"\s*:/, 'web lint script must be available', true],
  ['crewscope-web/package.json', /"format:check"\s*:/, 'web format script must be available', true],
  ['crewscope-web/package.json', /"check:quality"\s*:/, 'aggregate web quality script must be available', true],
]
const violations = []
for (const [file, pattern, message, shouldMatch] of checks) {
  const content = await readFile(file, 'utf8')
  if (shouldMatch ? !pattern.test(content) : pattern.test(content)) violations.push(`${file}: ${message}`)
}

if (violations.length) {
  console.error('Dependency/configuration contract violations detected:')
  violations.forEach(v => console.error(`- ${v}`))
  process.exitCode = 1
} else {
  console.log('Dependency contract: PASS')
}
