#!/usr/bin/env node

/**
 * Checks the configuration contract shared by Compose, environment templates,
 * file-backed Secrets and Spring configuration metadata. This is deliberately a
 * static/read-only check: it never starts a service or reads a real Secret.
 */
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const localComposePath = join(root, 'compose.yaml')
const productionComposePath = join(root, 'deploy/team-beta/compose.yaml')
const demoComposePath = join(root, 'deploy/team-beta/compose.demo.yaml')
const productionEnvPath = join(root, 'deploy/team-beta/.env.example')
const rootEnvPath = join(root, '.env.example')
const secretReadmePath = join(root, 'deploy/team-beta/secrets.example/README.md')
const applicationConfigPath = join(root, 'crewscope-server/src/main/resources/application.yml')

const rootEnv = parseEnvTemplate(rootEnvPath)
const productionEnv = parseEnvTemplate(productionEnvPath)
assert.ok(rootEnv.has('CREWSCOPE_DB_URL'), 'local template must document database URL')
assert.ok(rootEnv.has('CREWSCOPE_REDIS_URL'), 'local template must document Redis URL')
const applicationConfig = readFileSync(applicationConfigPath, 'utf8')
assert.ok(
  hasYamlBlockPath(applicationConfig, ['crewscope', 'provider', 'github']),
  'GitHub Provider properties must remain under crewscope.provider.github',
)
assert.ok(
  !hasYamlBlockPath(applicationConfig, ['crewscope', 'github']),
  'crewscope.github is an invalid shadow prefix for GitHub Provider properties',
)
for (const key of ['CREWSCOPE_BACKEND_IMAGE', 'CREWSCOPE_WEB_IMAGE', 'CREWSCOPE_DATA_ROOT', 'CREWSCOPE_SECRETS_ROOT']) {
  assert.ok(productionEnv.has(key), `production template is missing ${key}`)
}

const productionCompose = readFileSync(productionComposePath, 'utf8')
const requiredProductionVariables = new Set(collectVariables(productionCompose).map(variable => variable.name))
for (const variable of requiredProductionVariables) {
  assert.ok(productionEnv.has(variable), `production Compose variable is not in .env.example: ${variable}`)
}

const demoCompose = readFileSync(demoComposePath, 'utf8')
for (const variable of collectVariables(demoCompose)) {
  // Demo-only image/build knobs have safe defaults. Variables without a default
  // must remain discoverable in one of the checked-in templates.
  if (!variable.hasDefault && !rootEnv.has(variable.name) && !productionEnv.has(variable.name)) {
    assert.fail(`demo Compose variable has no template or default: ${variable.name}`)
  }
}

const secretsSection = productionCompose.split(/\nsecrets:\s*\n/)[1] ?? ''
const secretNames = [...secretsSection.matchAll(/^  ([a-z0-9_]+):\s*$/gm)].map(match => match[1])
const documentedSecretNames = [...readFileSync(secretReadmePath, 'utf8').matchAll(/^- `([a-z0-9_]+)`/gm)]
  .map(match => match[1])
for (const name of secretNames) {
  assert.ok(documentedSecretNames.includes(name), `Secret ${name} is missing from the Secret README`)
  assert.match(
    secretsSection,
    new RegExp(`${name}:\\s+file:\\s*\\$\\{CREWSCOPE_SECRETS_ROOT:\\?[^}]+\\}/${name}`),
    `Compose Secret ${name} must resolve from CREWSCOPE_SECRETS_ROOT/${name}`,
  )
}
for (const name of documentedSecretNames.filter(name => name !== 'backup_passphrase')) {
  assert.ok(secretNames.includes(name), `Secret README entry is not wired into Compose: ${name}`)
}
assert.ok(documentedSecretNames.includes('backup_passphrase'), 'operator-only backup_passphrase must be documented')

const localCompose = readFileSync(localComposePath, 'utf8')
assert.match(localCompose, /127\.0\.0\.1:5432:5432/, 'local PostgreSQL must bind to loopback')
assert.match(localCompose, /127\.0\.0\.1:6379:6379/, 'local Redis must bind to loopback')
assert.doesNotMatch(localCompose, /["'](?:5432:5432|6379:6379)["']/, 'local data services must not bind all interfaces')

const temp = mkdtempSync(join(tmpdir(), 'crewscope-config-contract-'))
try {
  const secrets = join(temp, 'secrets')
  const data = join(temp, 'data')
  mkdirSync(secrets)
  mkdirSync(data)
  for (const name of documentedSecretNames.filter(name => name !== 'backup_passphrase')) {
    writeFileSync(join(secrets, name), 'contract-only-placeholder\n')
  }
  const env = {
    ...process.env,
    CREWSCOPE_BACKEND_IMAGE: `registry.example/crewscope/backend@sha256:${'a'.repeat(64)}`,
    CREWSCOPE_WEB_IMAGE: `registry.example/crewscope/web@sha256:${'b'.repeat(64)}`,
    CREWSCOPE_DATA_ROOT: data,
    CREWSCOPE_SECRETS_ROOT: secrets,
    CREWSCOPE_DOCKER_SOCKET_PROXY_IMAGE: `registry.example/crewscope/docker-socket-proxy@sha256:${'c'.repeat(64)}`,
    CREWSCOPE_ALERTMANAGER_IMAGE: `registry.example/crewscope/alertmanager@sha256:${'d'.repeat(64)}`,
    CREWSCOPE_NODE_EXPORTER_IMAGE: `registry.example/crewscope/node-exporter@sha256:${'e'.repeat(64)}`,
    CREWSCOPE_BOOTSTRAP_ORGANIZATION_ID: '0198a475-0831-7000-8000-000000000001',
    CREWSCOPE_BOOTSTRAP_RUNTIME_PRINCIPAL_ID: '0198a475-0831-7000-8000-000000000002',
    CREWSCOPE_BOOTSTRAP_ORGANIZATION_NAME: 'CrewScope Team Beta',
  }
  const model = JSON.parse(execFileSync(
    'docker',
    ['compose', '-f', productionComposePath, 'config', '--format', 'json'],
    { cwd: root, env: { ...env, CREWSCOPE_BACKEND_IMAGE: env.CREWSCOPE_BACKEND_IMAGE, CREWSCOPE_WEB_IMAGE: env.CREWSCOPE_WEB_IMAGE }, encoding: 'utf8' },
  ))
  for (const name of ['postgres', 'redis']) {
    assert.ok(!model.services[name].ports, `production ${name} must not publish a host port`)
  }
  assert.equal(model.services.web.ports?.[0]?.host_ip, '127.0.0.1', 'production Web must bind loopback')
  for (const [name, service] of Object.entries(model.services)) {
    assert.match(service.image, /@sha256:[0-9a-f]{64}$/, `${name} image must use an immutable digest`)
  }
} finally {
  rmSync(temp, { recursive: true, force: true })
}

for (const pom of ['crewscope-infrastructure/pom.xml', 'crewscope-server/pom.xml']) {
  const content = readFileSync(join(root, pom), 'utf8')
  assert.match(content, /spring-boot-configuration-processor/, `${pom} must include Configuration Processor`)
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

console.log(`Configuration contract passed: ${requiredProductionVariables.size} production variables, ${secretNames.length} Compose Secrets, loopback data services and immutable images.`)

function parseEnvTemplate(path) {
  const keys = new Set()
  for (const line of readFileSync(path, 'utf8').split(/\r?\n/)) {
    const match = line.match(/^\s*(CREWSCOPE_[A-Z0-9_]+)\s*=/)
    if (match) keys.add(match[1])
  }
  return keys
}

function collectVariables(content) {
  const values = new Map()
  for (const match of content.matchAll(/\$\{(CREWSCOPE_[A-Z0-9_]+)(?:(:-|:\?)[^}]*)?\}/g)) {
    values.set(match[1], { name: match[1], hasDefault: Boolean(match[2]) })
  }
  return [...values.values()]
}

function hasYamlBlockPath(content, expectedPath) {
  const stack = []
  for (const line of content.split(/\r?\n/)) {
    if (!line.trim() || line.trimStart().startsWith('#')) continue
    const match = line.match(/^(\s*)([A-Za-z0-9_-]+):\s*(?:#.*)?$/)
    if (!match) continue
    const indentation = match[1].length
    while (stack.length > 0 && stack.at(-1).indentation >= indentation) stack.pop()
    stack.push({ indentation, key: match[2] })
    if (stack.map(item => item.key).join('.') === expectedPath.join('.')) return true
  }
  return false
}
