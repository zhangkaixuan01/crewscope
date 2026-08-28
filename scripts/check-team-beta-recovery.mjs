#!/usr/bin/env node

import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import {
  appendFileSync,
  cpSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { gzipSync } from 'node:zlib'

const root = resolve(import.meta.dirname, '..')
const recoveryTool = join(root, 'scripts/team-beta-recovery.mjs')
const operations = join(root, 'deploy/team-beta/operations')
const commonScript = join(operations, 'common.sh')
const backupScript = join(operations, 'backup.sh')
const restoreScript = join(operations, 'restore.sh')
const retentionScript = join(operations, 'retain-backups.sh')
const ciWorkflow = join(root, '.github/workflows/ci.yml')
const gitIgnoreFile = join(root, '.gitignore')
const runbook = join(root, 'docs/runbooks/Team-Beta单机运维手册.md')
const evidenceDocument = join(root, 'docs/testing/M6-I10-Team-Beta备份恢复与Runbook.md')
const temporary = mkdtempSync(join(tmpdir(), 'crewscope-m6-i10-'))

try {
  execFileSync('sh', ['-n', commonScript, backupScript, restoreScript, retentionScript])
  execFileSync(process.execPath, ['--check', recoveryTool])

  const valid = createFixture('valid')
  const verified = JSON.parse(runRecovery(
    'verify-payload',
    valid.payload,
    valid.envelope,
    '26',
    '32',
  ))
  assert.equal(verified.schemaVersion, 32)
  assert.deepEqual(Object.keys(verified.components).sort(), ['artifacts', 'postgres', 'redis'])
  for (const component of Object.values(verified.components)) {
    assert.ok(component.bytes > 0)
    assert.match(component.sha256, /^[0-9a-f]{64}$/)
  }

  const badCiphertext = createFixture('bad-ciphertext')
  appendFileSync(badCiphertext.ciphertext, 'tampered')
  assertRecoveryFails('verify-ciphertext', badCiphertext.ciphertext, badCiphertext.envelope)

  const badComponent = createFixture('bad-component')
  appendFileSync(join(badComponent.payload, 'postgres.dump'), 'tampered')
  assertRecoveryFails('verify-payload', badComponent.payload, badComponent.envelope, '26', '32')

  const expired = createFixture('expired', { createdAt: instantFromNow(-25 * 60 * 60) })
  assertRecoveryFails('verify-payload', expired.payload, expired.envelope, '26', '32')
  const future = createFixture('future', { createdAt: instantFromNow(60) })
  assertRecoveryFails('verify-payload', future.payload, future.envelope, '26', '32')
  for (const schemaVersion of [25, 33]) {
    const incompatible = createFixture(`schema-${schemaVersion}`, { schemaVersion })
    assertRecoveryFails(
      'verify-payload',
      incompatible.payload,
      incompatible.envelope,
      '26',
      '32',
    )
  }

  const artifactRoot = createArtifactRoot('artifact-valid')
  const artifactSummary = JSON.parse(runRecovery('verify-artifacts', artifactRoot.root))
  assert.deepEqual(artifactSummary, { objects: 1, referencedObjects: 1, references: 1 })
  const relocatedRoot = join(temporary, 'artifact-relocated')
  cpSync(artifactRoot.root, relocatedRoot, { recursive: true })
  assertRecoveryFails('verify-artifacts', relocatedRoot)
  const relocatedSummary = JSON.parse(runRecovery('relocate-artifacts', relocatedRoot))
  assert.equal(relocatedSummary.relocated, 1)
  runRecovery('verify-artifacts', relocatedRoot)
  rmSync(artifactRoot.blob)
  assertRecoveryFails('verify-artifacts', artifactRoot.root)
  const corruptArtifact = createArtifactRoot('artifact-corrupt')
  appendFileSync(corruptArtifact.blob, 'tampered')
  assertRecoveryFails('verify-artifacts', corruptArtifact.root)
  const linkedArtifactRoot = createArtifactRoot('artifact-linked-root')
  const artifactRootLink = join(temporary, 'artifact-root-link')
  symlinkSync(linkedArtifactRoot.root, artifactRootLink)
  assertRecoveryFails('verify-artifacts', artifactRootLink)
  const linkedReferenceRoot = createArtifactRoot('artifact-linked-references')
  const referenceTarget = join(temporary, 'artifact-reference-target')
  cpSync(join(linkedReferenceRoot.root, 'references'), referenceTarget, { recursive: true })
  rmSync(join(linkedReferenceRoot.root, 'references'), { recursive: true })
  symlinkSync(referenceTarget, join(linkedReferenceRoot.root, 'references'))
  assertRecoveryFails('verify-artifacts', linkedReferenceRoot.root)

  const unsafeArchive = join(temporary, 'unsafe.tar.gz')
  writeFileSync(unsafeArchive, archiveWithEmptyFile('../escape'))
  assert.throws(() => execFileSync(
    'sh',
    ['-c', '. "$1"; validate_archive_entries "$2"', 'recovery-check', commonScript, unsafeArchive],
    { cwd: root, stdio: 'ignore' },
  ))

  assertRetentionContract()
  assertCredentialKeyContract(valid)
  assertFingerprintContract()
  assertStaticContracts()
  console.log(
    'Team Beta recovery contract passed: encrypted package integrity, Artifact verification, '
      + 'V26..V32 boundary, safe archives, retention and Runbook.',
  )
} finally {
  rmSync(temporary, { recursive: true, force: true })
}

function createFixture(name, overrides = {}) {
  const directory = join(temporary, name)
  const payload = join(directory, 'payload')
  mkdirSync(payload, { recursive: true })
  writeFileSync(join(payload, 'postgres.dump'), 'postgres-custom-format\n')
  writeFileSync(join(payload, 'artifacts.tar.gz'), 'artifact-archive\n')
  writeFileSync(join(payload, 'redis.rdb'), 'redis-rdb\n')
  const metadata = join(directory, 'metadata.json')
  writeFileSync(metadata, JSON.stringify({
    applicationVersion: '0.1.0-SNAPSHOT',
    backupClass: 'daily',
    backupId: `20260826T120000Z-${sha256(name).slice(0, 16)}`,
    createdAt: overrides.createdAt ?? instantFromNow(-60),
    credentialKeyIds: ['credential:v1', 'activity-cursor:v1', 'task-token:v1'],
    environmentFingerprint: 'b'.repeat(64),
    gitRevision: 'a'.repeat(40),
    maintenance: {
      ingressStopped: true,
      apiStopped: true,
      workerStopped: true,
      activeTaskExecutions: 0,
      activeActionDispatches: 0,
      activeNotificationDispatches: 0,
    },
    schemaVersion: overrides.schemaVersion ?? 32,
  }))
  runRecovery('create-manifest', payload, metadata)
  const ciphertext = join(directory, `${name}.bundle.enc`)
  const envelope = join(directory, `${name}.envelope.json`)
  writeFileSync(ciphertext, 'contract-ciphertext\n')
  runRecovery('create-envelope', ciphertext, join(payload, 'manifest.json'), envelope)
  runRecovery('verify-ciphertext', ciphertext, envelope)
  return { ciphertext, directory, envelope, payload }
}

function createArtifactRoot(name) {
  const artifactRoot = join(temporary, name)
  const artifactId = '0198a475-0831-7000-8000-000000000123'
  const content = Buffer.from('content-addressed-artifact')
  const hash = sha256(content)
  const referenceDirectory = join(artifactRoot, 'references', artifactId.slice(0, 2))
  const objectDirectory = join(artifactRoot, 'objects', 'sha256', hash.slice(0, 2))
  mkdirSync(referenceDirectory, { recursive: true })
  mkdirSync(objectDirectory, { recursive: true })
  const blob = join(objectDirectory, `${hash}.blob`)
  writeFileSync(blob, content)
  writeFileSync(join(referenceDirectory, `${artifactId}.json`), JSON.stringify({
    artifactId,
    contentType: 'text/plain',
    createdAt: '2026-08-26T12:00:00Z',
    dataClassification: 'INTERNAL',
    encryption: 'NONE',
    organizationId: '0198a475-0831-7000-8000-000000000001',
    producer: {
      agentRunId: null,
      principalId: '0198a475-0831-7000-8000-000000000002',
      stepExecutionId: null,
      taskExecutionId: null,
      traceId: null,
    },
    retentionUntil: null,
    schemaVersion: 1,
    sha256: hash,
    size: content.length,
    storageUri: pathToFileURL(blob).href,
    teamId: null,
    tombstone: null,
    visibility: 'ORGANIZATION',
    workspaceId: null,
  }))
  return { blob, root: artifactRoot }
}

function assertRetentionContract() {
  const backupRoot = join(temporary, 'retention')
  mkdirSync(backupRoot)
  for (const [backupClass, count] of [['daily', 8], ['weekly', 5]]) {
    const directory = join(backupRoot, backupClass)
    mkdirSync(directory)
    for (let index = 1; index <= count; index += 1) {
      const id = `202608${String(index).padStart(2, '0')}T120000Z-${String(index).padStart(16, '0')}`
      writeFileSync(join(directory, `${id}.bundle.enc`), 'bundle')
      writeFileSync(join(directory, `${id}.envelope.json`), '{}')
    }
  }
  const operatorEnvironment = join(temporary, 'retention.env')
  writeFileSync(operatorEnvironment, `CREWSCOPE_BACKUP_ROOT=${backupRoot}\n`)
  execFileSync(retentionScript, [operatorEnvironment], { stdio: 'ignore' })
  assert.equal(backupCount(backupRoot, 'daily'), 8)
  assert.equal(backupCount(backupRoot, 'weekly'), 5)
  execFileSync(retentionScript, [operatorEnvironment, '--apply'], { stdio: 'ignore' })
  assert.equal(backupCount(backupRoot, 'daily'), 7)
  assert.equal(backupCount(backupRoot, 'weekly'), 4)
}

function assertCredentialKeyContract(fixture) {
  const secrets = join(temporary, 'credential-secrets')
  mkdirSync(secrets)
  writeFileSync(join(secrets, 'credential_keys'), 'v1=contract-key-material\n')
  writeFileSync(join(secrets, 'activity_cursor_key'), 'contract-activity-cursor-key-material\n')
  writeFileSync(join(secrets, 'task_token_key'), 'contract-task-token-key-material\n')
  const environment = {
    ...process.env,
    CREWSCOPE_SECRETS_ROOT: secrets,
    CREWSCOPE_TEAM_ACTIVITY_CURSOR_CURRENT_KEY_ID: 'v1',
    CREWSCOPE_TASK_TOKEN_CURRENT_KEY_ID: 'v1',
  }
  const manifest = join(fixture.payload, 'manifest.json')
  execFileSync(
    'sh',
    ['-c', '. "$1"; verify_required_key_ids "$2"', 'credential-check', commonScript, manifest],
    { cwd: root, env: environment, stdio: 'ignore' },
  )
  const incompatible = join(temporary, 'missing-credential-key.json')
  const document = JSON.parse(readFileSync(manifest, 'utf8'))
  document.credentialKeyIds.push('credential:v2')
  writeFileSync(incompatible, JSON.stringify(document))
  assert.throws(() => execFileSync(
    'sh',
    ['-c', '. "$1"; verify_required_key_ids "$2"', 'credential-check', commonScript, incompatible],
    { cwd: root, env: environment, stdio: 'ignore' },
  ))
}

function assertFingerprintContract() {
  const metadata = join(temporary, 'fingerprint-input.json')
  const output = join(temporary, 'fingerprint.json')
  writeFileSync(metadata, JSON.stringify({
    backendImage: `crewscope-backend@sha256:${'1'.repeat(64)}`,
    dataRoot: temporary,
    datasetVersion: 'contract-v1',
    gitRevision: 'a'.repeat(40),
    schemaVersion: 32,
    seed: '20260825',
    webImage: `crewscope-web@sha256:${'2'.repeat(64)}`,
  }))
  const fingerprint = runRecovery('fingerprint', metadata, output)
  assert.match(fingerprint, /^[0-9a-f]{64}$/)
  const document = JSON.parse(readFileSync(output, 'utf8'))
  assert.equal(document.fingerprint, fingerprint)
  assert.deepEqual(Object.keys(document.disk).sort(), [
    'availableKiB',
    'capacity',
    'totalKiB',
    'usedKiB',
  ])
  assert.doesNotMatch(JSON.stringify(document.disk), new RegExp(temporary.replaceAll('/', '\\/')))
}

function backupCount(rootDirectory, backupClass) {
  return readdirSync(join(rootDirectory, backupClass))
    .filter(name => name.endsWith('.bundle.enc')).length
}

function assertStaticContracts() {
  const restore = readFileSync(restoreScript, 'utf8')
  const backup = readFileSync(backupScript, 'utf8')
  assert.match(restore, /restore target is not empty/)
  assert.match(restore, /CREWSCOPE_RESTORE_TARGET_SCHEMA/)
  assert.match(restore, /actual RTO exceeds four hours/)
  assert.match(restore, /actual RPO is outside the 24-hour boundary/)
  assert.match(backup, /assert_zero_activity/)
  assert.match(backup, /PUBLISH_STARTED=true/)
  assert.match(backup, /PUBLISHED=true/)
  assert.ok(
    backup.indexOf('mv "$STAGED_ENVELOPE" "$FINAL_ENVELOPE"')
      < backup.indexOf('mv "$STAGED_BUNDLE" "$FINAL_BUNDLE"'),
    'Envelope must publish before the Bundle commit marker',
  )
  assert.match(readFileSync(gitIgnoreFile, 'utf8'), /^deploy\/team-beta\/\.runtime\/$/m)
  assert.match(readFileSync(ciWorkflow, 'utf8'), /check-team-beta-recovery\.mjs/)
  assert.equal(existsSync(runbook), true, 'Team Beta Runbook is missing')
  assert.equal(existsSync(evidenceDocument), true, 'M6-I10 evidence document is missing')
}

function archiveWithEmptyFile(name) {
  const header = Buffer.alloc(512)
  header.write(name, 0, 100, 'utf8')
  writeOctal(header, 0o644, 100, 8)
  writeOctal(header, 0, 108, 8)
  writeOctal(header, 0, 116, 8)
  writeOctal(header, 0, 124, 12)
  writeOctal(header, Math.floor(Date.now() / 1000), 136, 12)
  header.fill(0x20, 148, 156)
  header.write('0', 156, 1, 'ascii')
  header.write('ustar\0', 257, 6, 'binary')
  header.write('00', 263, 2, 'ascii')
  const checksum = header.reduce((sum, byte) => sum + byte, 0)
  const checksumText = checksum.toString(8).padStart(6, '0')
  header.write(`${checksumText}\0 `, 148, 8, 'binary')
  return gzipSync(Buffer.concat([header, Buffer.alloc(1024)]))
}

function writeOctal(buffer, value, offset, length) {
  const text = value.toString(8).padStart(length - 1, '0')
  buffer.write(`${text}\0`, offset, length, 'ascii')
}

function runRecovery(...args) {
  return execFileSync(process.execPath, [recoveryTool, ...args], {
    cwd: root,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  }).trim()
}

function assertRecoveryFails(...args) {
  assert.throws(() => runRecovery(...args))
}

function instantFromNow(offsetSeconds) {
  return new Date(Date.now() + offsetSeconds * 1000).toISOString().replace(/\.\d{3}Z$/, 'Z')
}

function sha256(value) {
  return createHash('sha256').update(value).digest('hex')
}
