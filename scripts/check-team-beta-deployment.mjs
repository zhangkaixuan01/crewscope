#!/usr/bin/env node

/** Static contract for the intentionally small single-host Team Beta deployment. */
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync, mkdtempSync, mkdirSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const composeFile = join(root, 'deploy/team-beta/compose.yaml')
const scriptFile = join(root, 'deploy/team-beta/quickstart.sh')
const aliasFile = join(root, 'deploy/local-demo.sh')
const profileFile = join(root, 'crewscope-server/src/main/resources/application-team-beta.yml')
const gitIgnore = readFileSync(join(root, '.gitignore'), 'utf8')
const compose = readFileSync(composeFile, 'utf8')
const script = readFileSync(scriptFile, 'utf8')
const profile = readFileSync(profileFile, 'utf8')
// Never inherit operator overrides or real keys into a default-configuration test.
const cleanEnv = Object.fromEntries(Object.entries(process.env)
  .filter(([key]) => !key.startsWith('CREWSCOPE_') && !key.startsWith('COMPOSE_')))

assert.ok(existsSync(composeFile), 'Team Beta Compose file is missing')
assert.ok(existsSync(scriptFile), 'Team Beta quickstart script is missing')
assert.match(gitIgnore, /^deploy\/team-beta\/\.runtime\/$/m)

const model = JSON.parse(execFileSync(
  'docker',
  ['compose', '-f', composeFile, 'config', '--format', 'json'],
  {
    cwd: root,
    encoding: 'utf8',
    env: {
      ...cleanEnv,
      CREWSCOPE_DB_PASSWORD: 'contract-only-password',
      CREWSCOPE_WEB_PORT: '8080',
      CREWSCOPE_ENV_FILE: '/dev/null',
      CREWSCOPE_EXECUTION_ROOT: '/tmp/crewscope-contract/execution',
    },
  },
))
assert.deepEqual(Object.keys(model.services).sort(), ['api', 'postgres', 'redis', 'web'])
assert.equal(model.services.api.environment.SPRING_PROFILES_ACTIVE, 'team-beta')
assert.equal(model.services.api.environment.CREWSCOPE_SESSION_COOKIE_SECURE, 'false')
assert.equal(model.services.api.environment.CREWSCOPE_FLYWAY_ENABLED, 'true')
assert.equal(model.services.web.ports.length, 1)
assert.equal(String(model.services.web.ports[0].published), '8080')
for (const name of ['postgres', 'redis', 'api']) {
  assert.ok(!model.services[name].ports, `${name} must not publish a host port`)
}
for (const name of Object.keys(model.services)) {
  assert.ok(model.services[name].healthcheck, `${name} must have a healthcheck`)
}

for (const forbidden of [
  'docker-socket-proxy',
  'otel-collector',
  'prometheus',
  'alertmanager',
  'backup-metrics',
  'SPRING_CONFIG_IMPORT',
  'external-file',
  'CREWSCOPE_DEPLOYMENT_TRANSPORT: https',
]) {
  assert.doesNotMatch(compose, new RegExp(forbidden.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')), `simple Compose still contains ${forbidden}`)
}
assert.match(compose, /SPRING_PROFILES_ACTIVE: team-beta/)
assert.match(compose, /CREWSCOPE_DB_URL: jdbc:postgresql:\/\/postgres/)
assert.match(script, /One generated env file/)
assert.match(script, /compose\.yaml/)
assert.match(script, /bootstrap_password/)
assert.match(aliasFile && readFileSync(aliasFile, 'utf8'), /quickstart\.sh/)
assert.match(profile, /transport: local/)
assert.match(profile, /config-source: environment/)
assert.match(profile, /secret-source: environment/)
assert.match(profile, /enabled: false/)
assert.match(profile, /execution-profile: all/)
assert.match(profile, /login-defense:\s+enabled: true/)
for (const target of ['/var/crewscope/agent-runtime', '/var/crewscope/template-agent-runtime',
  '/var/crewscope/task-agent-runtime', '/var/crewscope/coding-agent-runtime']) {
  assert.ok(model.services.api.volumes.some(volume => volume.target === target), `missing runtime volume: ${target}`)
}
assert.ok(model.services.api.volumes.some(volume => volume.target === '/var/run/docker.sock'))
const nginx = readFileSync(join(root, 'deploy/team-beta/nginx.conf'), 'utf8')
assert.match(nginx, /proxy_set_header X-Forwarded-For \$remote_addr;/)
assert.doesNotMatch(nginx, /\$proxy_add_x_forwarded_for/)
for (const header of ['Forwarded', 'X-Forwarded-Port', 'X-Forwarded-Prefix', 'X-Forwarded-Ssl']) {
  assert.ok(nginx.includes(`proxy_set_header ${header} "";`), `untrusted ${header} must be stripped`)
}
for (const gate of ['scripts/m7-q03-two-user-e2e-gate.sh', 'scripts/m7-q04-registration-profile-gate.sh']) {
  const source = readFileSync(join(root, gate), 'utf8')
  assert.match(source, /" build\n/, `${gate} must build this revision, not test cached images`)
}

// Exercise the real first-start initializer, not only regular expressions over shell source.
const runtime = mkdtempSync(join(tmpdir(), 'crewscope-quickstart-contract-'))
try {
  const env = { ...cleanEnv, CREWSCOPE_QUICKSTART_RUNTIME_ROOT: runtime,
    CREWSCOPE_QUICKSTART_PROJECT_NAME: 'crewscope-init-contract', CREWSCOPE_WEB_PORT: '18089' }
  execFileSync('sh', [scriptFile, 'init'], { env, encoding: 'utf8' })
  const envPath = join(runtime, '.env')
  const initial = readFileSync(envPath, 'utf8')
  for (const key of ['DB_PASSWORD', 'BOOTSTRAP_PASSWORD', 'MONITORING_PASSWORD', 'CREDENTIAL_KEYS',
    'TEAM_ACTIVITY_CURSOR_KEY_V1', 'INVITATION_TOKEN_HMAC_KEY', 'TASK_TOKEN_KEY_V1', 'LOGIN_DEFENSE_HMAC_KEY', 'CODING_DIFF_CURSOR_SECRET']) {
    assert.match(initial, new RegExp(`^CREWSCOPE_${key}=.+$`, 'm'))
  }
  assert.equal(statSync(envPath).mode & 0o777, 0o600)
  assert.equal(statSync(join(runtime, 'bootstrap_password')).mode & 0o777, 0o600)
  execFileSync('sh', [scriptFile, 'init'], { env, encoding: 'utf8' })
  assert.equal(readFileSync(envPath, 'utf8'), initial, 'restarts must not rotate encryption keys')
  writeFileSync(envPath, initial.replace(/^CREWSCOPE_LOGIN_DEFENSE_HMAC_KEY=.*\n/m, ''))
  execFileSync('sh', [scriptFile, 'init'], { env, encoding: 'utf8' })
  const upgraded = readFileSync(envPath, 'utf8')
  assert.match(upgraded, /^CREWSCOPE_LOGIN_DEFENSE_HMAC_KEY=.+$/m)
  assert.equal(upgraded.match(/^CREWSCOPE_CREDENTIAL_KEYS=.*$/m)[0], initial.match(/^CREWSCOPE_CREDENTIAL_KEYS=.*$/m)[0])
  execFileSync('sh', [scriptFile, 'config'], { env, encoding: 'utf8' })
  const blankKey = upgraded.replace(/^CREWSCOPE_LOGIN_DEFENSE_HMAC_KEY=.*$/m, 'CREWSCOPE_LOGIN_DEFENSE_HMAC_KEY=')
  writeFileSync(envPath, blankKey)
  assert.throws(() => execFileSync('sh', [scriptFile, 'init'], { env, stdio: 'pipe' }), /empty CREWSCOPE_LOGIN_DEFENSE_HMAC_KEY/)
  assert.equal(readFileSync(envPath, 'utf8'), blankKey, 'an explicit empty key must not silently rotate on every restart')
  writeFileSync(envPath, 'CREWSCOPE_DB_PASSWORD=partial\nCREWSCOPE_BOOTSTRAP_PASSWORD=partial\n')
  assert.throws(() => execFileSync('sh', [scriptFile, 'init'], { env, stdio: 'pipe' }),
    /Incomplete/, 'partial env files must fail with recovery guidance')
  assert.equal(readFileSync(envPath, 'utf8'), 'CREWSCOPE_DB_PASSWORD=partial\nCREWSCOPE_BOOTSTRAP_PASSWORD=partial\n')

  // These checks invoke only path validation/init; no container or real directory is modified.
  const paths = join(root, 'deploy/team-beta/paths.sh')
  const validate = candidate => execFileSync('sh', ['-c',
    'fail() { echo "$*" >&2; exit 2; }; . "$1"; assert_dedicated_directory "$2" "$3"',
    'path-contract', paths, candidate, root], { env, stdio: 'pipe' })
  validate(join(runtime, 'dedicated/not-created'))
  assert.equal(existsSync(join(runtime, 'dedicated')), false)
  for (const candidate of ['/', '/tmp', root, `${root}/..`, `${runtime}/../..`]) {
    assert.throws(() => validate(candidate))
  }
  symlinkSync(root, join(runtime, 'repository-link'))
  assert.throws(() => validate(join(runtime, 'repository-link')))
  mkdirSync(join(runtime, 'env-link-case'))
  symlinkSync(envPath, join(runtime, 'env-link-case/.env'))
  assert.throws(() => execFileSync('sh', [scriptFile, 'init'], {
    env: { ...env, CREWSCOPE_QUICKSTART_RUNTIME_ROOT: join(runtime, 'env-link-case') }, stdio: 'pipe',
  }), /must not be a symlink/)
  assert.throws(() => execFileSync('sh', [scriptFile, 'init'], {
    env: { ...env, CREWSCOPE_QUICKSTART_PROJECT_NAME: '-invalid' }, stdio: 'pipe',
  }), /must start/)
} finally {
  rmSync(runtime, { recursive: true, force: true })
}

execFileSync(process.execPath, [join(root, 'scripts/check-team-beta-snapshot.mjs')], { env: cleanEnv, stdio: 'inherit' })
console.log('Team Beta deployment contract passed: four-service HTTP Compose, atomic/repeatable initialization, partial-env/path rejection, sanitized forwarding, no TLS/observability/socket proxy requirements.')
