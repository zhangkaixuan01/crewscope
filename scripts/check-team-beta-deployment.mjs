import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const composeFile = join(root, 'deploy/team-beta/compose.yaml')
const demoFile = join(root, 'deploy/team-beta/compose.demo.yaml')
const demoScriptFile = join(root, 'deploy/team-beta/demo.sh')
const secretPreparationFile = join(
  root,
  'deploy/team-beta/operations/prepare-secret-permissions.sh',
)
const backendDockerfile = join(root, 'deploy/team-beta/backend.Dockerfile')
const webDockerfile = join(root, 'deploy/team-beta/web.Dockerfile')
const nginxConfig = join(root, 'deploy/team-beta/nginx.conf')
const prometheusConfig = join(root, 'deploy/team-beta/prometheus.yaml')
const prometheusAlertsConfig = join(root, 'deploy/team-beta/prometheus-alerts.yaml')
const backupHealthScript = join(root, 'deploy/team-beta/operations/backup-health.sh')
const backupScheduleScript = join(root, 'deploy/team-beta/operations/manage-backup-schedule.sh')
const ciWorkflow = join(root, '.github/workflows/ci.yml')
const gitIgnoreFile = join(root, '.gitignore')
const expectedServices = [
  'alertmanager',
  'api',
  'backup-metrics',
  'docker-socket-proxy',
  'otel-collector',
  'postgres',
  'prometheus',
  'redis',
  'web',
  'worker',
]
const digest = `sha256:${'a'.repeat(64)}`
const temporary = mkdtempSync(join(tmpdir(), 'crewscope-m6-i09-'))

try {
  const secrets = join(temporary, 'secrets')
  const data = join(temporary, 'data')
  mkdirSync(secrets)
  mkdirSync(data)
  for (const name of [
    'database_password',
    'bootstrap_password',
    'monitoring_password',
    'credential_keys',
    'activity_cursor_key',
    'diff_cursor_secret',
    'task_token_key',
    'redis_url',
    'redis_acl',
    'login_defense_hmac_key',
    'invitation_token_hmac_key',
  ]) {
    writeFileSync(join(secrets, name), 'contract-only-placeholder\n', { mode: 0o600 })
  }
  const env = {
    ...process.env,
    CREWSCOPE_BACKEND_IMAGE: `registry.example/crewscope/backend@${digest}`,
    CREWSCOPE_WEB_IMAGE: `registry.example/crewscope/web@${digest}`,
    CREWSCOPE_DATA_ROOT: data,
    CREWSCOPE_SECRETS_ROOT: secrets,
    CREWSCOPE_DOCKER_SOCKET_PROXY_IMAGE: `registry.example/crewscope/docker-socket-proxy@${digest}`,
    CREWSCOPE_ALERTMANAGER_IMAGE: `registry.example/crewscope/alertmanager@${digest}`,
    CREWSCOPE_NODE_EXPORTER_IMAGE: `registry.example/crewscope/node-exporter@${digest}`,
    CREWSCOPE_BOOTSTRAP_ORGANIZATION_ID: '0198a475-0831-7000-8000-000000000001',
    CREWSCOPE_BOOTSTRAP_RUNTIME_PRINCIPAL_ID: '0198a475-0831-7000-8000-000000000002',
    CREWSCOPE_BOOTSTRAP_ORGANIZATION_NAME: 'CrewScope Team Beta',
  }
  const model = composeConfig(['-f', composeFile], env)
  assert.deepEqual(Object.keys(model.services).sort(), expectedServices)

  for (const [name, service] of Object.entries(model.services)) {
    assert.match(service.image, /@sha256:[0-9a-f]{64}$/, `${name} image is not immutable`)
    assert.ok(service.healthcheck, `${name} has no healthcheck`)
    if (name !== 'web') {
      assert.ok(!service.ports, `${name} unexpectedly publishes a host port`)
    }
  }
  assert.equal(model.services.web.ports.length, 1)
  assert.equal(model.networks.backend.internal, true)
  assert.equal(model.networks.observability.internal, true)
  assert.equal(model.networks['provider-egress'].internal ?? false, false)
  for (const name of ['api', 'worker']) {
    assert.ok(
      Object.hasOwn(model.services[name].networks, 'provider-egress'),
      `${name} requires the dedicated Provider egress network`,
    )
  }
  for (const name of expectedServices.filter(name => !['api', 'worker'].includes(name))) {
    assert.ok(
      !Object.hasOwn(model.services[name].networks, 'provider-egress'),
      `${name} must not join the Provider egress network`,
    )
  }

  for (const name of ['api', 'worker', 'web']) {
    const service = model.services[name]
    assert.equal(service.read_only, true, `${name} root filesystem must be read-only`)
    assert.ok(service.cap_drop.includes('ALL'), `${name} must drop all capabilities`)
    assert.ok(
      service.security_opt.includes('no-new-privileges:true'),
      `${name} must disable privilege escalation`,
    )
    assert.notEqual(service.user?.split(':')[0], '0', `${name} must not run as root`)
  }

  const socket = '/var/run/docker.sock'
  assert.ok(volumeTargets(model.services['docker-socket-proxy']).includes(socket))
  assert.ok(!volumeTargets(model.services.worker).includes(socket), 'worker must not own Docker socket')
  for (const name of expectedServices.filter(name => name !== 'docker-socket-proxy')) {
    assert.ok(!volumeTargets(model.services[name]).includes(socket), `${name} owns Docker socket`)
  }
  assert.equal(model.services.worker.environment.DOCKER_HOST, 'tcp://docker-socket-proxy:2375')
  const runtimeRoots = {
    api: ['personal-agent', 'template-agent'],
    worker: ['task-agent', 'coding-agent'],
  }
  for (const [name, ownedRoots] of Object.entries(runtimeRoots)) {
    const mounts = model.services[name].volumes ?? []
    for (const ownedRoot of ownedRoots) {
      const runtimeRoot = join(data, 'runtime', ownedRoot)
      const runtimeMount = mounts.find(volume => volume.target === runtimeRoot)
      assert.ok(runtimeMount, `${name} must mount its ${ownedRoot} runtime root`)
      assert.equal(runtimeMount.source, runtimeRoot)
      assert.notEqual(runtimeMount.read_only, true, `${name} ${ownedRoot} runtime root must be writable`)
    }
    const otherRoots = Object.entries(runtimeRoots)
      .filter(([owner]) => owner !== name)
      .flatMap(([, roots]) => roots)
    for (const otherRoot of otherRoots) {
      assert.ok(
        !mounts.some(volume => volume.target === join(data, 'runtime', otherRoot)),
        `${name} must not mount the ${otherRoot} runtime root`,
      )
    }
  }
  assert.equal(model.services['docker-socket-proxy'].environment.BUILD, '0')
  assert.equal(model.services['docker-socket-proxy'].environment.VOLUMES, '0')
  assert.equal(model.services.web.networks.backend.ipv4_address, '172.30.0.10')
  assert.equal(model.networks.backend.ipam.config[0].subnet, '172.30.0.0/24')
  assert.equal(model.services.api.environment.CREWSCOPE_LOGIN_DEFENSE_TRUSTED_PROXIES, '172.30.0.10/32')
  const restoreNetworkModel = composeConfig(['-f', composeFile], {
    ...env,
    CREWSCOPE_BACKEND_SUBNET: '172.31.0.0/24',
    CREWSCOPE_WEB_INTERNAL_IP: '172.31.0.10',
  })
  assert.equal(restoreNetworkModel.networks.backend.ipam.config[0].subnet, '172.31.0.0/24')
  assert.equal(restoreNetworkModel.services.web.networks.backend.ipv4_address, '172.31.0.10')
  assert.equal(
    restoreNetworkModel.services.api.environment.CREWSCOPE_LOGIN_DEFENSE_TRUSTED_PROXIES,
    '172.31.0.10/32',
  )
  assert.equal(dependency(model.services.worker, 'api'), 'service_healthy')
  assert.equal(dependency(model.services.web, 'api'), 'service_healthy')
  assert.equal(dependency(model.services.api, 'postgres'), 'service_healthy')
  assert.equal(dependency(model.services.api, 'redis'), 'service_healthy')
  assert.deepEqual(
    model.services.api.healthcheck.test,
    ['CMD', 'wget', '-q', '--spider', 'http://127.0.0.1:8080/actuator/health/readiness'],
  )
  assert.deepEqual(
    model.services.worker.healthcheck.test,
    ['CMD', 'wget', '-q', '--spider', 'http://127.0.0.1:8081/actuator/health/readiness'],
  )
  assert.deepEqual(
    model.services.prometheus.healthcheck.test,
    ['CMD', '/bin/wget', '-q', '--spider', 'http://127.0.0.1:9090/-/ready'],
  )
  const redisCommand = model.services.redis.command.join(' ')
  assert.match(redisCommand, /cp \/run\/secrets\/redis_acl \/tmp\/redis_acl/)
  assert.match(redisCommand, /chown redis:redis \/tmp\/redis_acl/)
  assert.match(redisCommand, /chmod 0400 \/tmp\/redis_acl/)
  assert.match(redisCommand, /--aclfile \/tmp\/redis_acl/)
  assert.doesNotMatch(redisCommand, /--aclfile \/run\/secrets\/redis_acl/)
  assert.deepEqual(model.services.prometheus.group_add, ['10001'])

  assertRole(model.services.api, 'server', true, false)
  assertRole(model.services.worker, 'worker', false, true)
  assertAuthenticationRole(model.services.api, true, 'https', 'INVITE_ONLY', 'true')
  assertAuthenticationRole(model.services.worker, false, 'https', 'INVITE_ONLY', 'true')
  assert.equal(model.services.api.environment.CREWSCOPE_PASSWORD_HASH_PERMITS, '4')
  assert.equal(model.services.worker.environment.CREWSCOPE_PASSWORD_HASH_PERMITS, '4')
  assert.deepEqual(secretSources(model.services.prometheus), ['monitoring_password'])
  assert.ok(secretSources(model.services.api).includes('bootstrap_password'))
  assert.ok(secretSources(model.services.api).includes('monitoring_password'))
  assert.ok(secretSources(model.services.api).includes('login_defense_hmac_key'))
  assert.ok(secretSources(model.services.api).includes('invitation_token_hmac_key'))
  assert.ok(!secretSources(model.services.worker).includes('login_defense_hmac_key'))
  assert.ok(!secretSources(model.services.worker).includes('invitation_token_hmac_key'))
  assert.ok(!secretSources(model.services.prometheus).includes('bootstrap_password'))
  for (const name of ['api', 'worker']) {
    const environment = model.services[name].environment
    for (const key of Object.keys(environment)) {
      assert.doesNotMatch(
        key,
        /(?:_PASSWORD|_API_KEY|_SECRET|_TOKEN_KEY|_CREDENTIAL_KEYS|_REDIS_URL)$/,
        `${name} injects ${key} through environment instead of configtree`,
      )
    }
    assert.equal(environment.SPRING_CONFIG_IMPORT, 'configtree:/run/secrets/')
  }

  assertDockerfile(backendDockerfile, ['USER 10001:10001', ' AS build', ' AS runtime'])
  assertDockerfile(webDockerfile, ['USER 101:101', ' AS build', ' AS runtime'])
  const nginx = readFileSync(nginxConfig, 'utf8')
  assert.match(nginx, /location \/api\//)
  assert.doesNotMatch(nginx, /location \/actuator/)
  assert.match(nginx, /map \$http_x_forwarded_proto \$crewscope_forwarded_proto/)
  assert.match(nginx, /proxy_set_header X-Forwarded-Proto \$crewscope_forwarded_proto/)
  assert.match(nginx, /map \$http_host \$crewscope_forwarded_host/)
  assert.match(nginx, /proxy_set_header Host \$crewscope_forwarded_host/)
  assert.match(nginx, /proxy_set_header X-Forwarded-Host \$crewscope_forwarded_host/)
  const tlsExample = readFileSync(join(root, 'deploy/team-beta/nginx-host-tls.conf.example'), 'utf8')
  for (const header of [
    'Strict-Transport-Security',
    'Content-Security-Policy',
    'X-Content-Type-Options',
    'Referrer-Policy',
    'Permissions-Policy',
  ]) {
    assert.match(tlsExample, new RegExp(`add_header ${header}`), `TLS example is missing ${header}`)
  }
  const prometheus = readFileSync(prometheusConfig, 'utf8')
  assert.match(prometheus, /username: crewscope-prometheus/)
  assert.match(prometheus, /password_file: \/run\/secrets\/monitoring_password/)
  assert.match(prometheus, /rule_files:/)
  assert.match(prometheus, /alertmanager:9093/)
  assert.match(prometheus, /backup-metrics:9100/)
  assert.doesNotMatch(prometheus, /bootstrap_password/)
  const alerts = readFileSync(prometheusAlertsConfig, 'utf8')
  for (const alert of ['CrewScopeApiUnavailable', 'CrewScopeWorkerUnavailable', 'CrewScopeBackupStale']) {
    assert.match(alerts, new RegExp(alert), `Prometheus alerts are missing ${alert}`)
  }
  const backupHealth = readFileSync(backupHealthScript, 'utf8')
  assert.match(backupHealth, /crewscope_backup_age_seconds/)
  assert.match(backupHealth, /CREWSCOPE_BACKUP_ROOT/)
  const schedule = readFileSync(backupScheduleScript, 'utf8')
  assert.match(schedule, /install\|uninstall/)
  assert.match(schedule, /crewscope-backup-health.timer/)
  assert.match(
    schedule,
    /REPOSITORY_ROOT=.*SCRIPT_DIR\/\.\.\/\.\.\/\.\./,
    'backup schedule must resolve the repository root above deploy/team-beta/operations',
  )

  const ci = readFileSync(ciWorkflow, 'utf8')
  assert.match(ci, /image_security:/)
  assert.match(ci, /deploy\/team-beta\/backend\.Dockerfile/)
  assert.match(ci, /deploy\/team-beta\/web\.Dockerfile/)
  assert.equal((ci.match(/severity: CRITICAL/g) ?? []).length, 2)
  assert.match(ci, /IMAGE_SECURITY_RESULT/)

  // Demo Secret and data material must never be staged with the deployment directory.
  const gitIgnore = readFileSync(gitIgnoreFile, 'utf8')
  assert.match(gitIgnore, /^deploy\/team-beta\/\.runtime\/$/m)
  const trackedSecretMaterial = execFileSync(
    'git',
    ['ls-files', '--', '.env', 'deploy/team-beta/.env', 'deploy/team-beta/.runtime',
      'deploy/team-beta/secrets'],
    { cwd: root, encoding: 'utf8' },
  ).trim()
  assert.equal(trackedSecretMaterial, '', 'runtime Secret material must not be tracked')

  const demo = composeConfig(
    ['--profile', 'demo', '-f', composeFile, '-f', demoFile],
    {
      ...env,
      CREWSCOPE_BACKEND_IMAGE: 'crewscope-backend:demo',
      CREWSCOPE_WEB_IMAGE: 'crewscope-web:demo',
    },
  )
  assert.deepEqual(Object.keys(demo.services).sort(), expectedServices)
  for (const name of expectedServices) {
    assert.deepEqual(demo.services[name].profiles, ['demo'])
  }
  assert.ok(demo.services.api.build)
  assert.ok(demo.services.worker.build)
  assert.ok(demo.services.web.build)
  assertAuthenticationRole(demo.services.api, true, 'local', 'OPEN', 'false')
  assertAuthenticationRole(demo.services.worker, false, 'local', 'INVITE_ONLY', 'false')
  assert.equal(demo.services.api.environment.CREWSCOPE_PASSWORD_HASH_PERMITS, '2')
  assert.equal(demo.services.worker.environment.CREWSCOPE_PASSWORD_HASH_PERMITS, '2')
  const demoScript = readFileSync(demoScriptFile, 'utf8')
  assert.match(demoScript, /Open registration:/)
  assert.match(demoScript, /Operator login:/)
  assert.match(demoScript, /Operator username: crewscope-monitor/)
  assert.match(demoScript, /Operator password file:/)
  assert.match(demoScript, /Prometheus machine user \(not a Web login\): crewscope-prometheus/)
  assert.doesNotMatch(demoScript, /Bootstrap password:/)
  const secretPreparation = readFileSync(secretPreparationFile, 'utf8')
  assert.match(secretPreparation, /chown 0:10001 "\$secret_file"/)
  assert.match(secretPreparation, /chmod 0440 "\$secret_file"/)
  assert.match(secretPreparation, /chmod 0600 "\$redis_acl"/)
  assert.match(secretPreparation, /chmod 0600 "\$CREWSCOPE_BACKUP_PASSPHRASE_FILE"/)
  assert.match(secretPreparation, /runtime_directory in .* runtime/)
  assert.match(secretPreparation, /CREWSCOPE_DATA_ROOT\/metrics/)
  assert.doesNotMatch(secretPreparation, /(?:cat|head|tail) "?\$secret_file/)

  assertMissingConfigurationFails()
  assertBackupHealthContract()
  console.log('Team Beta deployment contract passed: 10 services, Worker Docker API isolation, backup metrics, M7 authentication role isolation, immutable production images and external Secrets.')
} finally {
  rmSync(temporary, { recursive: true, force: true })
}

function composeConfig(args, env) {
  const output = execFileSync(
    'docker',
    ['compose', ...args, 'config', '--format', 'json'],
    { cwd: root, env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
  )
  return JSON.parse(output)
}

function volumeTargets(service) {
  return (service.volumes ?? []).map(volume => volume.target)
}

function secretSources(service) {
  return (service.secrets ?? []).map(secret => secret.source).sort()
}

function dependency(service, name) {
  return service.depends_on?.[name]?.condition
}

function assertRole(service, role, flyway, worker) {
  const env = service.environment
  assert.equal(env.CREWSCOPE_EXECUTION_PROFILE, role)
  assert.equal(env.CREWSCOPE_AGENT_EXECUTION_OWNERSHIP_SCOPE, role)
  assert.equal(env.CREWSCOPE_FLYWAY_ENABLED, String(flyway))
  for (const key of [
    'CREWSCOPE_OUTBOX_ENABLED',
    'CREWSCOPE_ACTION_WORKER_ENABLED',
    'CREWSCOPE_NOTIFICATION_WORKER_ENABLED',
    'CREWSCOPE_PROJECTION_SUPERVISOR_ENABLED',
  ]) {
    assert.equal(env[key], String(worker), `${role} has the wrong ${key}`)
  }
}

function assertAuthenticationRole(service, api, transport, registrationMode, secureCookie) {
  const env = service.environment
  assert.equal(env.CREWSCOPE_DEPLOYMENT_TRANSPORT, transport)
  assert.equal(env.CREWSCOPE_BROWSER_SESSION_ENABLED, String(api))
  assert.equal(env.CREWSCOPE_SECURITY_MODE, api ? 'local' : 'bootstrap')
  assert.equal(env.CREWSCOPE_LOGIN_DEFENSE_ENABLED, String(api))
  assert.equal(env.CREWSCOPE_INVITATION_TOKEN_ENABLED, String(api))
  assert.equal(env.CREWSCOPE_OPERATOR_BOOTSTRAP_ENABLED, String(api))
  assert.equal(env.CREWSCOPE_REGISTRATION_MODE, registrationMode)
  assert.equal(
    env.CREWSCOPE_REGISTRATION_ORGANIZATION_ID,
    env.CREWSCOPE_DEPLOYMENT_BOOTSTRAP_ORGANIZATION_ID,
  )
  assert.equal(env.CREWSCOPE_SESSION_COOKIE_SECURE, secureCookie)
}

function assertDockerfile(path, requiredFragments) {
  const source = readFileSync(path, 'utf8')
  for (const line of source.split('\n').filter(line => line.trimStart().startsWith('ARG ') && line.includes('_IMAGE='))) {
    assert.match(line, /@sha256:[0-9a-f]{64}$/)
  }
  for (const fragment of requiredFragments) {
    assert.ok(source.includes(fragment), `${path} is missing ${fragment}`)
  }
}

function assertMissingConfigurationFails() {
  const emptyEnvFile = join(temporary, 'empty.env')
  writeFileSync(emptyEnvFile, '')
  assert.throws(() => execFileSync(
    'docker',
    ['compose', '--env-file', emptyEnvFile, '-f', composeFile, 'config'],
    {
      cwd: root,
      env: { PATH: process.env.PATH, HOME: process.env.HOME },
      stdio: 'ignore',
    },
  ))
}

function assertBackupHealthContract() {
  const backupRoot = join(temporary, 'backup-health')
  const dailyRoot = join(backupRoot, 'daily')
  const environmentFile = join(temporary, 'backup-health.env')
  const metricsFile = join(temporary, 'backup-health.prom')
  mkdirSync(dailyRoot, { recursive: true })
  writeFileSync(join(dailyRoot, 'fixture.bundle.enc'), 'contract fixture\n')
  writeFileSync(environmentFile, `CREWSCOPE_BACKUP_ROOT=${backupRoot}\n`, { mode: 0o600 })

  const output = execFileSync(
    'sh',
    [backupHealthScript, environmentFile, '--prometheus', metricsFile],
    { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
  )
  assert.match(output.trim(), /^latestDailyBackupEpoch=\d+\nageSeconds=\d+$/)
  const metrics = readFileSync(metricsFile, 'utf8')
  assert.match(metrics, /^crewscope_backup_age_seconds \d+$/m)
  assert.match(metrics, /^crewscope_backup_last_success_timestamp_seconds \d+$/m)
}
