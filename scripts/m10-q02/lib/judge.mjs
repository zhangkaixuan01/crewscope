/**
 * Gate-side judge for the M10-Q02 comparison driver (S01 §4: correctness = asserting
 * structural outcomes, not semantic review).
 *
 * The product never runs the hidden JudgeTest — its TestEvidence is derived from the
 * agent tool session's command evidence — so correctness is judged here, against the
 * frozen M4 pipeline: `evaluate.mjs materialize` reproduces the deterministic baseline
 * commit f053fd1e…, the delivered patch is applied, the hidden JudgeTest is copied in,
 * and the frozen sandbox image (`maven@sha256:29a1658b…`, the exact sha the product's
 * maven-java-17 BuildProfile pins) runs `-Dtest=<X>JudgeTest test` with no network —
 * dependencies come from the shared named volume, warmed once so both arms judge
 * under identical cache conditions.
 *
 * Verdict semantics follow the M4 suite policy: PATH_VIOLATION when changed paths are
 * empty, exceed maximumChangedFiles, or leave the task's allowedPaths.
 */
import { execFileSync, spawnSync } from 'node:child_process'
import { cpSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'

const REPO_ROOT = resolve(import.meta.dirname, '..', '..', '..')
const EVALUATE = join(REPO_ROOT, 'evaluation', 'm4', 'coding-v1', 'scripts', 'evaluate.mjs')
const SUITE = JSON.parse(readFileSync(join(REPO_ROOT, 'evaluation', 'm4', 'coding-v1', 'suite.json'), 'utf8'))
const JUDGE_ROOT = join(REPO_ROOT, 'evaluation', 'm4', 'coding-v1', 'judge-tests')
export const BASELINE_COMMIT = SUITE.fixture.baselineCommit
export const MAXIMUM_CHANGED_FILES = SUITE.policy.maximumChangedFiles

const MAVEN_IMAGE = 'maven@sha256:29a1658b1f3078e07c2b17f7b519b45eb47f65d9628e887eac45a8c5c8f939d4'
const M2_VOLUME = 'm10q02-m2'

/** Runs a command, returning { status, stdout, stderr }; throws only on spawn failure. */
function run(command, arguments_, options = {}) {
  const result = spawnSync(command, arguments_, { encoding: 'utf8', cwd: REPO_ROOT, ...options })
  if (result.error) throw result.error
  return result
}

function git(workspace, arguments_, options = {}) {
  return run('git', ['-C', workspace, ...arguments_], options)
}

/** Materializes the deterministic baseline into a fresh workspace (single commit == baseline). */
function materialize(workspace) {
  rmSync(workspace, { recursive: true, force: true })
  mkdirSync(resolve(workspace, '..'), { recursive: true })
  const result = run('node', [EVALUATE, 'materialize', '--output', workspace])
  if (result.status !== 0) {
    return { ok: false, outcome: 'MATERIALIZATION_FAILED', logTail: result.stderr.slice(-2000) }
  }
  const parsed = JSON.parse(result.stdout)
  if (parsed.baselineCommit !== BASELINE_COMMIT) {
    return { ok: false, outcome: 'MATERIALIZATION_FAILED', logTail: `baseline ${parsed.baselineCommit} != ${BASELINE_COMMIT}` }
  }
  return { ok: true }
}

/** Warming run: pull the fixture's full dependency tree into the shared .m2 volume with
 *  network; afterwards every judge run is offline-capable (m4 sandbox network=none). */
export function warmJudgeVolume() {
  const warmWorkspace = join(REPO_ROOT, 'var', 'release', 'm10-q02', 'judge', 'warm')
  const materialized = materialize(warmWorkspace)
  if (!materialized.ok) return materialized
  const selector = selectorFor(SUITE.tasks[0])
  cpSync(
    join(JUDGE_ROOT, SUITE.tasks[0].judgeTest),
    join(warmWorkspace, 'src', 'test', 'java', 'io', 'crewscope', 'evaluation', selector + '.java'),
  )
  const started = Date.now()
  const result = run('docker', [
    'run', '--rm',
    '-v', `${warmWorkspace}:/workspace/repository`,
    '-v', `${M2_VOLUME}:/root/.m2`,
    '-w', '/workspace/repository',
    MAVEN_IMAGE,
    'mvn', '--batch-mode', '--no-transfer-progress', `-Dtest=${selector}`, 'test',
  ], { timeout: 600_000 })
  rmSync(warmWorkspace, { recursive: true, force: true })
  return {
    ok: result.status === 0,
    outcome: result.status === 0 ? 'WARMED' : 'WARM_FAILED',
    logTail: (result.stdout + result.stderr).slice(-2000),
    durationMs: Date.now() - started,
  }
}

function selectorFor(task) {
  const file = task.judgeTest.split('/').pop()
  return file.slice(0, -'.java'.length)
}

/**
 * Judges one delivered patch against the frozen baseline.
 *
 * @param {{ id: string, allowedPaths: string[], judgeTest: string }} task suite task
 * @param {string} patch the raw unified diff from the patch artifact (may be empty)
 * @param {string} workspace absolute path for this run's judge workspace (kept for evidence)
 * @returns {{ outcome: string, changedPaths: string[], exitCode: number|null, logTail: string,
 *            workspace: string, durationMs: number }}
 */
export function judgeRun(task, patch, workspace) {
  const started = Date.now()
  const base = { changedPaths: [], exitCode: null, logTail: '', workspace, durationMs: 0 }
  const materialized = materialize(workspace)
  if (!materialized.ok) return { ...base, ...materialized }

  if (!patch || !patch.trim()) return { ...base, outcome: 'NO_PATCH' }
  const applied = git(workspace, ['apply', '--whitespace=nowarn', '-'], { input: patch })
  if (applied.status !== 0) {
    return { ...base, outcome: 'PATCH_UNAPPLICABLE', logTail: applied.stderr.slice(-2000) }
  }

  git(workspace, ['add', '-A'])
  const staged = git(workspace, ['diff', '--cached', '--name-only'])
  const changedPaths = staged.stdout.split('\n').filter(Boolean)
  const allowed = new Set(task.allowedPaths)
  const pathsAllowed = changedPaths.length > 0
    && changedPaths.length <= MAXIMUM_CHANGED_FILES
    && changedPaths.every(path => allowed.has(path))
  if (!pathsAllowed) return { ...base, outcome: 'PATH_VIOLATION', changedPaths }

  const selector = selectorFor(task)
  cpSync(
    join(JUDGE_ROOT, task.judgeTest),
    join(workspace, 'src', 'test', 'java', 'io', 'crewscope', 'evaluation', selector + '.java'),
  )
  const judged = run('docker', [
    'run', '--rm', '--network', 'none',
    '-v', `${workspace}:/workspace/repository`,
    '-v', `${M2_VOLUME}:/root/.m2`,
    '-w', '/workspace/repository',
    MAVEN_IMAGE,
    'mvn', '--batch-mode', '--no-transfer-progress', `-Dtest=${selector}`, 'test',
  ], { timeout: 300_000 })
  return {
    ...base,
    outcome: judged.status === 0 ? 'PASSED' : 'JUDGE_FAILED',
    changedPaths,
    exitCode: judged.status,
    logTail: (judged.stdout + '\n' + judged.stderr).slice(-3000),
    durationMs: Date.now() - started,
  }
}

/** Writes the patch artifact to the run's evidence directory (raw diff, never secrets). */
export function persistPatch(patchFile, patch) {
  mkdirSync(resolve(patchFile, '..'), { recursive: true })
  writeFileSync(patchFile, patch)
}

export function judgeWorkspaceFor(reportDir, arm, task, rep) {
  return join(reportDir, 'judge', `${arm}-${task}-rep${rep}`)
}

/** Ensures the judge evidence root exists; returns whether the warmed volume is usable. */
export function judgeVolumeReady() {
  const inspected = run('docker', ['volume', 'inspect', M2_VOLUME])
  return inspected.status === 0
}

export const SUITE_TASKS = SUITE.tasks
