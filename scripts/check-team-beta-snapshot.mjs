#!/usr/bin/env node

// Test shell orchestration with an isolated Docker double. Real archive/data round trips remain
// the responsibility of m8-q02-local-runtime-gate.sh; this check never starts actual services.
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { appendFileSync, mkdirSync, mkdtempSync, readFileSync, renameSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const temporary = mkdtempSync(join(tmpdir(), 'crewscope-snapshot-contract-'))
const runtime = join(temporary, 'runtime')
const snapshot = join(temporary, 'snapshot')
const log = join(temporary, 'docker.jsonl')
const cleanEnv = Object.fromEntries(Object.entries(process.env)
  .filter(([key]) => !key.startsWith('CREWSCOPE_') && !key.startsWith('COMPOSE_')))
const env = { ...cleanEnv, PATH: `${temporary}:${process.env.PATH}`,
  CREWSCOPE_QUICKSTART_PROJECT_NAME: 'crewscope-snapshot-contract',
  CREWSCOPE_QUICKSTART_RUNTIME_ROOT: runtime, SNAPSHOT_TEST_LOG: log,
  SNAPSHOT_TEST_HAS_VOLUMES: 'true' }
const script = join(root, 'deploy/team-beta/snapshot.sh')
const run = (action, target = snapshot, overrides = {}) => execFileSync('sh', [script, action, target],
  { env: { ...env, ...overrides }, encoding: 'utf8', stdio: 'pipe', timeout: 15000 })
const calls = () => readFileSync(log, 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse)
const clearLog = () => writeFileSync(log, '')
const assertNoWrites = () => assert.ok(calls().every(args =>
  args[0] === 'info' || args[0] === 'image' || args[0] === 'ps'
    || (args[0] === 'volume' && args[1] === 'inspect')), 'refusal must precede Docker writes')

try {
  writeFileSync(join(temporary, 'docker'), `#!/usr/bin/env node
const fs = require('node:fs');
const args = process.argv.slice(2);
fs.appendFileSync(process.env.SNAPSHOT_TEST_LOG, JSON.stringify(args) + '\\n');
if (args[0] === 'info' || args[0] === 'image' || args[0] === 'ps') process.exit(0);
if (args[0] === 'volume' && args[1] === 'inspect') process.exit(process.env.SNAPSHOT_TEST_HAS_VOLUMES === 'true' ? 0 : 1);
if (args[0] === 'volume' && args[1] === 'create') process.exit(0);
if (args[0] === 'compose' && args.includes('down')) process.exit(0);
if (args[0] === 'run') {
  if (args.includes('-i')) fs.readFileSync(0);
  else process.stdout.write('isolated-archive-fixture');
  process.exit(0);
}
throw new Error('Unexpected Docker action: ' + args.join(' '));
`, { mode: 0o700 })
  clearLog()
  execFileSync('sh', [join(root, 'deploy/team-beta/quickstart.sh'), 'init'], { env, stdio: 'pipe' })
  mkdirSync(join(runtime, 'execution'))
  // The snapshot must capture newly added keys, not copy the pre-upgrade env then generate them.
  writeFileSync(join(runtime, '.env'), readFileSync(join(runtime, '.env'), 'utf8')
    .replace(/^CREWSCOPE_TASK_TOKEN_KEY_V1=.*\n/m, ''))
  run('backup')
  assert.equal(readFileSync(join(snapshot, 'env'), 'utf8'), readFileSync(join(runtime, '.env'), 'utf8'))
  assert.match(readFileSync(join(snapshot, 'env'), 'utf8'), /^CREWSCOPE_TASK_TOKEN_KEY_V1=.+$/m)
  const backupCalls = calls()
  const stopped = backupCalls.findIndex(args => args[0] === 'compose' && args.includes('down'))
  const archives = backupCalls.flatMap((args, index) => args[0] === 'run' ? [index] : [])
  assert.equal(archives.length, 8)
  assert.ok(stopped >= 0 && archives.every(index => index > stopped))
  assert.equal(readFileSync(join(snapshot, 'SHA256SUMS'), 'utf8').trim().split('\n').length, 12)
  clearLog()
  assert.throws(() => run('restore'), /Target env already exists/)
  assertNoWrites()

  const archive = join(snapshot, 'postgres-data.tar.gz')
  const original = readFileSync(archive)
  appendFileSync(archive, 'corrupt')
  clearLog()
  assert.throws(() => run('restore'), /checksum verification failed/)
  assertNoWrites()
  writeFileSync(archive, original)

  symlinkSync(runtime, join(temporary, 'runtime-alias'))
  clearLog()
  assert.throws(() => run('backup', join(temporary, 'runtime-alias/nested-backup')), /outside the runtime/)
  assertNoWrites()

  renameSync(runtime, join(temporary, 'original-runtime'))
  clearLog()
  run('restore', snapshot, { SNAPSHOT_TEST_HAS_VOLUMES: 'false' })
  assert.equal(readFileSync(join(runtime, '.env'), 'utf8'), readFileSync(join(snapshot, 'env'), 'utf8'))
  assert.equal(calls().filter(args => args[0] === 'volume' && args[1] === 'create').length, 7)
  assert.equal(calls().filter(args => args[0] === 'run' && args.includes('-i')).length, 8)
  assert.ok(!calls().some(args => args[0] === 'compose'), 'restore must leave services stopped')
  console.log('Snapshot orchestration passed (Docker double): complete keys/volumes, stop-before-archive, checksum/nonempty/path refusal, stopped restore. Not a real data round trip.')
} finally {
  rmSync(temporary, { recursive: true, force: true })
}
