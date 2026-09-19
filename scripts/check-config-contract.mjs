#!/usr/bin/env node

/** Static configuration contract for the small single-host Compose deployment. */
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const composePath = join(root, 'deploy/team-beta/compose.yaml')
const profilePath = join(root, 'crewscope-server/src/main/resources/application-team-beta.yml')
const compose = readFileSync(composePath, 'utf8')
const profile = readFileSync(profilePath, 'utf8')
const rootEnv = readFileSync(join(root, '.env.example'), 'utf8')
const localCompose = readFileSync(join(root, 'compose.yaml'), 'utf8')
const application = readFileSync(join(root, 'crewscope-server/src/main/resources/application.yml'), 'utf8')

assert.ok(existsSync(composePath), 'Team Beta Compose file is missing')
assert.match(compose, /POSTGRES_PASSWORD: \$\{CREWSCOPE_DB_PASSWORD:\?/)
assert.match(compose, /SPRING_PROFILES_ACTIVE: team-beta/)
assert.match(compose, /CREWSCOPE_REDIS_URL: redis:\/\/redis:6379/)
assert.match(compose, /CREWSCOPE_SESSION_COOKIE_SECURE: \$\{CREWSCOPE_SESSION_COOKIE_SECURE:-false\}/)
assert.match(compose, /127\.0\.0\.1|0\.0\.0\.0/)
assert.doesNotMatch(compose, /SPRING_CONFIG_IMPORT|secrets:|@sha256:/)
assert.doesNotMatch(compose, /docker-socket-proxy|prometheus|otel-collector|alertmanager/)
assert.match(profile, /transport: local/)
assert.match(profile, /secret-source: environment/)
assert.match(profile, /execution-profile: all/)
assert.match(profile, /login-defense:\s+enabled: true/)
assert.match(rootEnv, /CREWSCOPE_DB_URL=/)
assert.match(rootEnv, /CREWSCOPE_REDIS_URL=/)
assert.match(localCompose, /postgres/)
assert.match(localCompose, /redis/)
// Simplifying Team Beta must not remove unrelated source-development/network contracts.
assert.match(localCompose, /127\.0\.0\.1:5432:5432/)
assert.match(localCompose, /127\.0\.0\.1:6379:6379/)
assert.doesNotMatch(localCompose, /["'](?:5432:5432|6379:6379)["']/)
assert.ok(hasYamlBlockPath(application, ['crewscope', 'provider', 'github']))
assert.ok(!hasYamlBlockPath(application, ['crewscope', 'github']))

for (const pom of ['crewscope-infrastructure/pom.xml', 'crewscope-server/pom.xml']) {
  assert.match(readFileSync(join(root, pom), 'utf8'), /spring-boot-configuration-processor/)
}
for (const metadata of [
  'crewscope-infrastructure/target/classes/META-INF/spring-configuration-metadata.json',
  'crewscope-server/target/classes/META-INF/spring-configuration-metadata.json',
]) {
  if (!existsSync(join(root, metadata))) continue
  const document = JSON.parse(readFileSync(join(root, metadata), 'utf8'))
  assert.ok(Array.isArray(document.groups), `${metadata} must contain metadata groups`)
  assert.ok(document.groups.some(group => String(group.name).startsWith('crewscope.')), `${metadata} has no CrewScope groups`)
}

console.log('Configuration contract passed: simple Team Beta env, four services, HTTP transport and no external Secret/immutable-image requirements.')

function hasYamlBlockPath(content, expectedPath) {
  const stack = []
  for (const line of content.split(/\r?\n/)) {
    const match = line.match(/^(\s*)([A-Za-z0-9_-]+):\s*(?:#.*)?$/)
    if (!match) continue
    const indentation = match[1].length
    while (stack.length && stack.at(-1).indentation >= indentation) stack.pop()
    stack.push({ indentation, key: match[2] })
    if (stack.map(item => item.key).join('.') === expectedPath.join('.')) return true
  }
  return false
}
