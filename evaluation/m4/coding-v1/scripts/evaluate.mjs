#!/usr/bin/env node

import { createHash } from "node:crypto";
import {
  appendFileSync,
  cpSync,
  existsSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  realpathSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, isAbsolute, join, relative, resolve, sep } from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const evaluationRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const suitePath = join(evaluationRoot, "suite.json");

function fail(message) {
  throw new Error(message);
}

function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

function sha256(content) {
  return createHash("sha256").update(content).digest("hex");
}

function sha256File(path) {
  return sha256(readFileSync(path));
}

function compareCodePoint(left, right) {
  const leftPoints = Array.from(left, (value) => value.codePointAt(0));
  const rightPoints = Array.from(right, (value) => value.codePointAt(0));
  const length = Math.min(leftPoints.length, rightPoints.length);
  for (let index = 0; index < length; index += 1) {
    if (leftPoints[index] !== rightPoints[index]) {
      return leftPoints[index] - rightPoints[index];
    }
  }
  return leftPoints.length - rightPoints.length;
}

function listFiles(root, current = root) {
  const files = [];
  for (const name of readdirSync(current).sort(compareCodePoint)) {
    const path = join(current, name);
    const entry = lstatSync(path);
    if (entry.isDirectory()) {
      files.push(...listFiles(root, path));
    } else if (entry.isFile()) {
      files.push(relative(root, path).split(sep).join("/"));
    } else {
      fail(`Fixture contains unsupported filesystem entry: ${path}`);
    }
  }
  return files.sort(compareCodePoint);
}

/**
 * The fixture fingerprint closes paths, sizes and bytes without depending on archive metadata.
 */
function fixtureFingerprint(root) {
  const hash = createHash("sha256");
  for (const path of listFiles(root)) {
    const bytes = readFileSync(join(root, path));
    hash.update(path, "utf8");
    hash.update("\0");
    hash.update(String(bytes.length), "utf8");
    hash.update("\0");
    hash.update(sha256(bytes), "ascii");
    hash.update("\0");
  }
  return hash.digest("hex");
}

function runGit(repository, args, environment = {}, trimOutput = true) {
  const output = execFileSync("git", args, {
    cwd: repository,
    encoding: "utf8",
    env: {
      ...process.env,
      GIT_CONFIG_NOSYSTEM: "1",
      GIT_TERMINAL_PROMPT: "0",
      ...environment,
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  return trimOutput ? output.trim() : output;
}

function ensureEmptyOutputDirectory(output) {
  if (existsSync(output)) {
    if (!statSync(output).isDirectory()) {
      fail(`Materialization output is not a directory: ${output}`);
    }
    if (readdirSync(output).length !== 0) {
      fail(`Materialization output must be empty: ${output}`);
    }
  } else {
    mkdirSync(output, { recursive: true });
  }
}

function materialize(suite, output) {
  ensureEmptyOutputDirectory(output);
  const fixtureSource = resolveAsset(suite.fixture.source);
  cpSync(fixtureSource, output, { recursive: true, errorOnExist: true });
  runGit(output, ["init", "--initial-branch", suite.fixture.defaultBranch]);
  runGit(output, ["config", "core.autocrlf", "false"]);
  runGit(output, ["config", "core.filemode", "true"]);
  runGit(output, ["config", "commit.gpgsign", "false"]);
  runGit(output, ["add", "--all"]);
  const commit = suite.fixture.commit;
  const commitEnvironment = {
    GIT_AUTHOR_NAME: commit.authorName,
    GIT_AUTHOR_EMAIL: commit.authorEmail,
    GIT_AUTHOR_DATE: commit.timestamp,
    GIT_COMMITTER_NAME: commit.authorName,
    GIT_COMMITTER_EMAIL: commit.authorEmail,
    GIT_COMMITTER_DATE: commit.timestamp,
    TZ: "UTC",
    LC_ALL: "C",
  };
  runGit(output, ["commit", "--quiet", "--message", commit.message], commitEnvironment);
  return runGit(output, ["rev-parse", "HEAD"]);
}

function resolveAsset(asset) {
  if (typeof asset !== "string" || asset.length === 0 || isAbsolute(asset)) {
    fail(`Asset path must be repository-relative: ${String(asset)}`);
  }
  const resolved = resolve(evaluationRoot, asset);
  const prefix = evaluationRoot.endsWith(sep) ? evaluationRoot : `${evaluationRoot}${sep}`;
  if (!resolved.startsWith(prefix)) {
    fail(`Asset path escapes the evaluation root: ${asset}`);
  }
  if (!existsSync(resolved)) {
    fail(`Evaluation asset is missing: ${asset}`);
  }
  return resolved;
}

function requireString(value, label) {
  if (typeof value !== "string" || value.trim().length === 0) {
    fail(`${label} must be a non-empty string`);
  }
}

function requireSha256(value, label) {
  if (typeof value !== "string" || !/^[0-9a-f]{64}$/.test(value)) {
    fail(`${label} must be a lowercase SHA-256`);
  }
}

function isSafeRelativePath(path) {
  return (
    typeof path === "string" &&
    path.length > 0 &&
    !isAbsolute(path) &&
    !path.includes("\\") &&
    path.split("/").every((segment) => segment.length > 0 && segment !== "." && segment !== "..")
  );
}

function validateRuntime(suite) {
  const runtime = readJson(resolveAsset(suite.runtime));
  if (runtime.schemaVersion !== "crewscope.coding-runtime/v1") {
    fail("Runtime schema version is not frozen to v1");
  }
  if (runtime.agentScopeVersion !== "2.0.0") {
    fail("M4-S04 must use AgentScope Java 2.0.0");
  }
  if (!/^maven@sha256:[0-9a-f]{64}$/.test(runtime.sandbox?.image ?? "")) {
    fail("Sandbox image must use an immutable Maven image digest");
  }
  if (runtime.sandbox.network !== "none" || runtime.sandbox.javaRelease !== 17) {
    fail("Sandbox must use Java 17 with network disabled");
  }
  if (runtime.model.temperature !== 0 || runtime.model.topP !== 1) {
    fail("Model random parameters must be frozen to temperature=0 and topP=1");
  }
  if (runtime.model.modelId !== "crewscope-primary" || runtime.model.modelRevision !== "RUN_LOCK_REQUIRED") {
    fail("Runtime must use the crewscope-primary slot and require an exact model revision RunLock");
  }
  resolveAsset(runtime.model.runLockSchema);
  if (
    runtime.dependencyCache?.mode !== "READ_ONLY_SNAPSHOT_REQUIRED" ||
    runtime.dependencyCache?.snapshotId !== "RUN_LOCK_REQUIRED" ||
    runtime.dependencyCache?.snapshotSha256 !== "RUN_LOCK_REQUIRED" ||
    runtime.dependencyCache?.mountPath !== "/maven-cache" ||
    runtime.dependencyCache?.mavenLocalRepository !== "/maven-cache/repository"
  ) {
    fail("Dependency cache must use the frozen read-only Maven snapshot contract");
  }
  for (const [name, limit] of Object.entries(runtime.budget)) {
    if (!Number.isInteger(limit) || limit <= 0) {
      fail(`Runtime budget ${name} must be a positive integer`);
    }
  }
  for (const path of [
    runtime.agentProfile.systemPrompt,
    runtime.agentProfile.skillBundle,
    runtime.agentProfile.toolset,
  ]) {
    resolveAsset(path);
  }
  return runtime;
}

function validateTasks(suite) {
  if (!Array.isArray(suite.tasks) || suite.tasks.length < 10 || suite.tasks.length > 20) {
    fail("M4-S04 must contain between 10 and 20 tasks");
  }
  const fixtureRoot = resolveAsset(suite.fixture.source);
  const ids = new Set();
  for (const task of suite.tasks) {
    requireString(task.id, "Task id");
    if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(task.id) || ids.has(task.id)) {
      fail(`Task id must be unique kebab-case: ${task.id}`);
    }
    ids.add(task.id);
    requireString(task.instruction, `${task.id} instruction`);
    if (task.baselineCommit !== suite.fixture.baselineCommit) {
      fail(`${task.id} must pin the suite baseline commit explicitly`);
    }
    if (
      !Number.isInteger(task.taskTimeoutSeconds) ||
      task.taskTimeoutSeconds <= 0 ||
      task.taskTimeoutSeconds > suite.policy.taskTimeoutSeconds
    ) {
      fail(`${task.id} task timeout exceeds the suite policy`);
    }
    if (!Array.isArray(task.allowedPaths) || task.allowedPaths.length === 0) {
      fail(`${task.id} must declare AllowedPaths`);
    }
    for (const path of task.allowedPaths) {
      if (!isSafeRelativePath(path) || !existsSync(join(fixtureRoot, path))) {
        fail(`${task.id} has an invalid or missing AllowedPath: ${path}`);
      }
    }
    const judgeTest = resolveAsset(task.judgeTest);
    const judgeRoot = realpathSync(resolveAsset(suite.judge.tests));
    if (!realpathSync(judgeTest).startsWith(`${judgeRoot}${sep}`)) {
      fail(`${task.id} Judge Pack entry escapes the frozen judge-tests directory`);
    }
    if (!judgeTest.endsWith("JudgeTest.java")) {
      fail(`${task.id} Judge Pack entry must end in JudgeTest.java`);
    }
    if (!Array.isArray(task.acceptanceCommands) || task.acceptanceCommands.length === 0) {
      fail(`${task.id} must declare at least one acceptance command`);
    }
    for (const command of task.acceptanceCommands) {
      requireString(command.id, `${task.id} command id`);
      if (!Array.isArray(command.argv) || command.argv[0] !== "mvn") {
        fail(`${task.id} acceptance must use typed Maven argv`);
      }
      if (
        !Number.isInteger(command.timeoutSeconds) ||
        command.timeoutSeconds <= 0 ||
        command.timeoutSeconds > suite.policy.defaultAcceptanceTimeoutSeconds
      ) {
        fail(`${task.id} acceptance timeout exceeds the suite policy`);
      }
      if (command.argv.some((argument) => typeof argument !== "string" || /[\n\r\0]/.test(argument))) {
        fail(`${task.id} acceptance argv contains an invalid argument`);
      }
      const selector = command.argv.find((argument) => argument.startsWith("-Dtest="));
      const expectedSelector = `-Dtest=${judgeTest.slice(judgeTest.lastIndexOf(sep) + 1, -".java".length)}`;
      if (selector !== expectedSelector) {
        fail(`${task.id} acceptance selector does not match its Judge Test`);
      }
    }
    if (!Array.isArray(task.expectedBehavior) || task.expectedBehavior.length < 3) {
      fail(`${task.id} must declare at least three expected behaviors`);
    }
  }

  const realTrackIds = suite.tracks?.["real-model-benchmark"]?.taskIds;
  if (!Array.isArray(realTrackIds) || realTrackIds.length !== ids.size) {
    fail("Real-model track must include every frozen task exactly once");
  }
  if (new Set(realTrackIds).size !== ids.size || realTrackIds.some((id) => !ids.has(id))) {
    fail("Real-model track contains missing or duplicate task ids");
  }
  const realTrack = suite.tracks["real-model-benchmark"];
  if (
    realTrack.repetitions !== 3 ||
    !Array.isArray(realTrack.seeds) ||
    realTrack.seeds.length !== realTrack.repetitions ||
    new Set(realTrack.seeds).size !== realTrack.seeds.length
  ) {
    fail("Real-model track must use three distinct frozen seeds");
  }
  return ids;
}

function classifyFacts(facts) {
  const precedence = [
    ["suiteMatch", "SUITE_MISMATCH"],
    ["runLockMatch", "RUN_LOCK_MISMATCH"],
    ["baselineMatch", "BASELINE_MISMATCH"],
    ["changedPathsAllowed", "PATH_VIOLATION"],
    ["sandboxPolicyObserved", "SANDBOX_POLICY_VIOLATION"],
    ["budgetWithinLimit", "BUDGET_EXHAUSTED"],
    ["acceptanceComplete", "MISSING_EVIDENCE"],
  ];
  for (const [field, failure] of precedence) {
    if (facts[field] !== true) {
      return failure;
    }
  }
  if (facts.acceptanceTimedOut === true) {
    return "ACCEPTANCE_TIMEOUT";
  }
  if (facts.acceptancePassed !== true) {
    return "ACCEPTANCE_FAILED";
  }
  if (facts.evidenceHashesMatch !== true) {
    return "EVIDENCE_HASH_MISMATCH";
  }
  if (facts.structuredResultValid !== true) {
    return "INVALID_STRUCTURED_RESULT";
  }
  if (facts.finalHashMatch !== true) {
    return "RESULT_HASH_MISMATCH";
  }
  return "PASSED";
}

function validateFailureSamples(suite) {
  const failureAsset = "failure-samples/v1.json";
  const document = readJson(resolveAsset(failureAsset));
  const ids = new Set();
  for (const sample of document.samples ?? []) {
    if (ids.has(sample.id)) {
      fail(`Duplicate failure sample: ${sample.id}`);
    }
    ids.add(sample.id);
    const actual = classifyFacts(sample.facts ?? {});
    if (actual !== sample.expected) {
      fail(`Failure sample ${sample.id} expected ${sample.expected} but classified as ${actual}`);
    }
  }
  const deterministicCases = suite.tracks?.["deterministic-ci"]?.cases ?? [];
  for (const id of deterministicCases) {
    if (!ids.has(id)) {
      fail(`Deterministic CI case has no failure sample: ${id}`);
    }
  }
  if (new Set(deterministicCases).size !== ids.size || deterministicCases.length !== ids.size) {
    fail("Deterministic CI must cover every frozen success and failure sample exactly once");
  }
  if (!ids.has("valid-success-report")) {
    fail("Failure corpus must include one valid control sample");
  }
}

function validateAssetLocks(suite) {
  for (const [asset, expected] of Object.entries(suite.assetLocks ?? {})) {
    requireSha256(expected, `Asset lock ${asset}`);
    const actual = sha256File(resolveAsset(asset));
    if (actual !== expected) {
      fail(`Asset lock mismatch for ${asset}: expected ${expected}, got ${actual}`);
    }
  }
}

function calculateFingerprints(suite) {
  const locks = {};
  for (const asset of Object.keys(suite.assetLocks ?? {})) {
    locks[asset] = sha256File(resolveAsset(asset));
  }
  const fixtureRoot = resolveAsset(suite.fixture.source);
  const judgeTestsRoot = resolveAsset(suite.judge.tests);
  const temporaryRoot = mkdtempSync(join(tmpdir(), "crewscope-m4-s04-"));
  const repository = join(temporaryRoot, "repository");
  mkdirSync(repository);
  try {
    return {
      fixtureContentSha256: fixtureFingerprint(fixtureRoot),
      judgeTestsContentSha256: fixtureFingerprint(judgeTestsRoot),
      baselineCommit: materialize(suite, repository),
      assetLocks: locks,
    };
  } finally {
    rmSync(temporaryRoot, { recursive: true, force: true });
  }
}

function validateSuite() {
  const suite = readJson(suitePath);
  if (suite.schemaVersion !== "crewscope.coding-evaluation/v1") {
    fail("Evaluation suite schema version is not v1");
  }
  requireString(suite.suiteId, "Suite id");
  requireString(suite.suiteVersion, "Suite version");
  if (!/^[0-9a-f]{40}$/.test(suite.fixture?.baselineCommit ?? "")) {
    fail("Fixture baselineCommit must be a full Git commit id");
  }
  requireSha256(suite.fixture?.contentSha256, "Fixture contentSha256");
  requireString(suite.judge?.version, "Judge version");
  resolveAsset(suite.judge?.script);
  resolveAsset(suite.judge?.tests);
  requireSha256(suite.judge?.contentSha256, "Judge contentSha256");
  const runtime = validateRuntime(suite);
  validateTasks(suite);
  validateFailureSamples(suite);
  validateAssetLocks(suite);
  validateJudgeProtocol(suite, runtime);

  const fingerprints = calculateFingerprints(suite);
  if (fingerprints.fixtureContentSha256 !== suite.fixture.contentSha256) {
    fail("Fixture content fingerprint does not match suite.json");
  }
  if (fingerprints.judgeTestsContentSha256 !== suite.judge.contentSha256) {
    fail("Judge Pack content fingerprint does not match suite.json");
  }
  if (fingerprints.baselineCommit !== suite.fixture.baselineCommit) {
    fail(
      `Fixture baseline commit does not match: expected ${suite.fixture.baselineCommit}, got ${fingerprints.baselineCommit}`,
    );
  }
  return { suite, fingerprints };
}

function parseArguments(values, allowedNames) {
  const result = {};
  for (let index = 0; index < values.length; index += 2) {
    const key = values[index];
    const value = values[index + 1];
    if (!key?.startsWith("--") || value === undefined) {
      fail(`Invalid command argument near ${String(key)}`);
    }
    const name = key.slice(2);
    if (!allowedNames.has(name)) {
      fail(`Unknown command argument: ${key}`);
    }
    if (Object.hasOwn(result, name)) {
      fail(`Duplicate command argument: ${key}`);
    }
    result[name] = value;
  }
  return result;
}

function gitChangedPaths(workspace, baseline) {
  const tracked = runGit(workspace, ["diff", "--name-only", "-z", baseline, "--"], {}, false);
  const untracked = runGit(workspace, ["ls-files", "--others", "--exclude-standard", "-z"], {}, false);
  return new Set(`${tracked}${untracked}`.split("\0").filter((path) => path.length > 0));
}

function commandEvidenceFacts(task, report) {
  if (!Array.isArray(report.acceptance)) {
    return { complete: false, timedOut: false, passed: false, hashesMatch: false };
  }
  const evidence = new Map();
  let complete = report.acceptance.length === task.acceptanceCommands.length;
  for (const item of report.acceptance) {
    if (item === null || typeof item !== "object" || typeof item.id !== "string" || evidence.has(item.id)) {
      complete = false;
      continue;
    }
    evidence.set(item.id, item);
  }
  const expectedIds = new Set(task.acceptanceCommands.map((command) => command.id));
  if ([...evidence.keys()].some((id) => !expectedIds.has(id))) {
    complete = false;
  }
  let timedOut = false;
  let passed = true;
  let hashesMatch = true;
  for (const command of task.acceptanceCommands) {
    const item = evidence.get(command.id);
    if (!item || JSON.stringify(item.argv) !== JSON.stringify(command.argv)) {
      complete = false;
      passed = false;
      continue;
    }
    if (item.timeoutSeconds !== command.timeoutSeconds) {
      complete = false;
    }
    timedOut ||= item.timedOut === true;
    passed &&= item.timedOut !== true && item.exitCode === 0 && item.status === "PASSED";
    hashesMatch &&=
      /^[0-9a-f]{64}$/.test(item.evidenceSha256 ?? "") &&
      item.evidenceSha256 === item.verifiedSha256;
  }
  return { complete, timedOut, passed, hashesMatch };
}

function budgetWithinLimit(actual, limits) {
  if (actual === null || typeof actual !== "object" || Array.isArray(actual)) {
    return false;
  }
  const actualNames = Object.keys(actual).sort(compareCodePoint);
  const limitNames = Object.keys(limits).sort(compareCodePoint);
  if (JSON.stringify(actualNames) !== JSON.stringify(limitNames)) {
    return false;
  }
  return Object.entries(limits).every(
    ([name, maximum]) => Number.isInteger(actual[name]) && actual[name] >= 0 && actual[name] <= maximum,
  );
}

function hasExactFields(value, fields) {
  return (
    value !== null &&
    typeof value === "object" &&
    !Array.isArray(value) &&
    JSON.stringify(Object.keys(value).sort(compareCodePoint)) === JSON.stringify([...fields].sort(compareCodePoint))
  );
}

function hasNoUnknownFields(value, fields) {
  return (
    value !== null &&
    typeof value === "object" &&
    !Array.isArray(value) &&
    Object.keys(value).every((field) => fields.has(field))
  );
}

function isBoundedString(value, maximumLength) {
  return typeof value === "string" && value.trim().length > 0 && value.length <= maximumLength;
}

function realModelRunLockMatches(suite, runtime, report) {
  if (report.track === "deterministic-ci") {
    return report.runLock === undefined;
  }
  if (report.track !== "real-model-benchmark") {
    return false;
  }
  const runLock = report.runLock;
  const fields = [
    "schemaVersion",
    "suiteId",
    "suiteVersion",
    "track",
    "runId",
    "startedAt",
    "provider",
    "modelId",
    "modelRevision",
    "seed",
    "temperature",
    "topP",
    "agentProfileVersion",
    "sandboxImage",
    "runtimeAssetSha256",
    "dependencyCacheSnapshotId",
    "dependencyCacheSha256",
  ];
  if (!hasExactFields(runLock, fields)) {
    return false;
  }
  const realTrack = suite.tracks["real-model-benchmark"];
  return (
    runLock.schemaVersion === "crewscope.coding-real-model-run-lock/v1" &&
    runLock.suiteId === suite.suiteId &&
    runLock.suiteVersion === suite.suiteVersion &&
    runLock.track === report.track &&
    typeof runLock.runId === "string" &&
    /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$/.test(runLock.runId) &&
    typeof runLock.startedAt === "string" &&
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(runLock.startedAt) &&
    !Number.isNaN(Date.parse(runLock.startedAt)) &&
    isBoundedString(runLock.provider, 100) &&
    isBoundedString(runLock.modelId, 200) &&
    isBoundedString(runLock.modelRevision, 200) &&
    runLock.modelRevision !== "RUN_LOCK_REQUIRED" &&
    realTrack.seeds.includes(runLock.seed) &&
    runLock.temperature === runtime.model.temperature &&
    runLock.topP === runtime.model.topP &&
    runLock.agentProfileVersion === runtime.agentProfile.version &&
    runLock.sandboxImage === runtime.sandbox.image &&
    runLock.runtimeAssetSha256 === suite.assetLocks[suite.runtime] &&
    typeof runLock.dependencyCacheSnapshotId === "string" &&
    /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$/.test(runLock.dependencyCacheSnapshotId) &&
    /^[0-9a-f]{64}$/.test(runLock.dependencyCacheSha256)
  );
}

function judgeReport(suite, runtime, task, workspace, report) {
  const actualWorkspace = realpathSync(workspace);
  if (!existsSync(join(actualWorkspace, ".git"))) {
    fail(`Judge workspace is not a Git repository: ${actualWorkspace}`);
  }
  let baselineExists = true;
  try {
    baselineExists = runGit(actualWorkspace, ["rev-parse", `${suite.fixture.baselineCommit}^{commit}`]) === suite.fixture.baselineCommit;
    runGit(actualWorkspace, ["merge-base", "--is-ancestor", suite.fixture.baselineCommit, "HEAD"]);
  } catch {
    baselineExists = false;
  }
  const changedPaths = [...gitChangedPaths(actualWorkspace, suite.fixture.baselineCommit)].sort(compareCodePoint);
  const allowed = new Set(task.allowedPaths);
  const evidence = commandEvidenceFacts(task, report);
  const reportFields = new Set([
    "suiteId",
    "suiteVersion",
    "taskId",
    "track",
    "runLock",
    "baselineCommit",
    "sandbox",
    "budget",
    "acceptance",
    "structuredResult",
    "final",
  ]);
  const facts = {
    suiteMatch:
      hasNoUnknownFields(report, reportFields) &&
      report.suiteId === suite.suiteId &&
      report.suiteVersion === suite.suiteVersion &&
      report.taskId === task.id &&
      (report.track === "deterministic-ci" || report.track === "real-model-benchmark"),
    runLockMatch: realModelRunLockMatches(suite, runtime, report),
    baselineMatch: baselineExists && report.baselineCommit === suite.fixture.baselineCommit,
    changedPathsAllowed:
      changedPaths.length > 0 &&
      changedPaths.length <= suite.policy.maximumChangedFiles &&
      changedPaths.every((path) => allowed.has(path)),
    sandboxPolicyObserved:
      report.sandbox?.image === runtime.sandbox.image &&
      report.sandbox?.network === runtime.sandbox.network &&
      report.sandbox?.nonRoot === true,
    budgetWithinLimit: budgetWithinLimit(report.budget, runtime.budget),
    acceptanceComplete: evidence.complete,
    acceptanceTimedOut: evidence.timedOut,
    acceptancePassed: evidence.passed,
    evidenceHashesMatch: evidence.hashesMatch,
    structuredResultValid:
      report.structuredResult?.schema === "CodeChangeResultV1" &&
      report.structuredResult?.valid === true,
    finalHashMatch:
      /^[0-9a-f]{64}$/.test(report.final?.manifestSha256 ?? "") &&
      report.final?.manifestSha256 === report.final?.verifiedManifestSha256,
  };
  return {
    schemaVersion: "crewscope.coding-evaluation-result/v1",
    suiteId: suite.suiteId,
    suiteVersion: suite.suiteVersion,
    taskId: task.id,
    outcome: classifyFacts(facts),
    changedPaths,
    facts,
  };
}

function createValidJudgeReport(suite, runtime, task) {
  const evidenceSha256 = "a".repeat(64);
  return {
    suiteId: suite.suiteId,
    suiteVersion: suite.suiteVersion,
    taskId: task.id,
    track: "real-model-benchmark",
    runLock: {
      schemaVersion: "crewscope.coding-real-model-run-lock/v1",
      suiteId: suite.suiteId,
      suiteVersion: suite.suiteVersion,
      track: "real-model-benchmark",
      runId: "m4-s04-protocol-self-test",
      startedAt: "2026-08-16T00:00:00Z",
      provider: "self-test-provider",
      modelId: "self-test-model",
      modelRevision: "self-test-revision-1",
      seed: suite.tracks["real-model-benchmark"].seeds[0],
      temperature: runtime.model.temperature,
      topP: runtime.model.topP,
      agentProfileVersion: runtime.agentProfile.version,
      sandboxImage: runtime.sandbox.image,
      runtimeAssetSha256: suite.assetLocks[suite.runtime],
      dependencyCacheSnapshotId: "m4-s04-self-test-cache",
      dependencyCacheSha256: "b".repeat(64),
    },
    baselineCommit: suite.fixture.baselineCommit,
    sandbox: {
      image: runtime.sandbox.image,
      network: runtime.sandbox.network,
      nonRoot: true,
    },
    budget: Object.fromEntries(Object.keys(runtime.budget).map((name) => [name, 0])),
    acceptance: task.acceptanceCommands.map((command) => ({
      id: command.id,
      argv: command.argv,
      timeoutSeconds: command.timeoutSeconds,
      timedOut: false,
      exitCode: 0,
      status: "PASSED",
      evidenceSha256,
      verifiedSha256: evidenceSha256,
    })),
    structuredResult: { schema: "CodeChangeResultV1", valid: true },
    final: { manifestSha256: "c".repeat(64), verifiedManifestSha256: "c".repeat(64) },
  };
}

function validateJudgeProtocol(suite, runtime) {
  const temporaryRoot = mkdtempSync(join(tmpdir(), "crewscope-m4-s04-judge-"));
  const repository = join(temporaryRoot, "repository");
  mkdirSync(repository);
  try {
    materialize(suite, repository);
    const task = suite.tasks[0];
    appendFileSync(join(repository, task.allowedPaths[0]), "\n", "utf8");
    const report = createValidJudgeReport(suite, runtime, task);
    if (judgeReport(suite, runtime, task, repository, report).outcome !== "PASSED") {
      fail("Judge protocol self-test did not accept a valid real-model report");
    }

    const runLockMismatch = structuredClone(report);
    runLockMismatch.runLock.runtimeAssetSha256 = "d".repeat(64);
    if (judgeReport(suite, runtime, task, repository, runLockMismatch).outcome !== "RUN_LOCK_MISMATCH") {
      fail("Judge protocol self-test did not reject a mismatched RunLock");
    }

    const duplicateCommand = structuredClone(report);
    duplicateCommand.acceptance.push(structuredClone(duplicateCommand.acceptance[0]));
    if (judgeReport(suite, runtime, task, repository, duplicateCommand).outcome !== "MISSING_EVIDENCE") {
      fail("Judge protocol self-test did not reject duplicate command evidence");
    }

    const extraBudget = structuredClone(report);
    extraBudget.budget.untrackedCounter = 0;
    if (judgeReport(suite, runtime, task, repository, extraBudget).outcome !== "BUDGET_EXHAUSTED") {
      fail("Judge protocol self-test did not reject an extra budget field");
    }

    const unknownReportField = structuredClone(report);
    unknownReportField.agentSummary = "untrusted";
    if (judgeReport(suite, runtime, task, repository, unknownReportField).outcome !== "SUITE_MISMATCH") {
      fail("Judge protocol self-test did not reject an unknown report field");
    }

    writeFileSync(join(repository, " leading-space.txt"), "path bytes must not be trimmed\n", "utf8");
    const pathViolation = judgeReport(suite, runtime, task, repository, report);
    if (
      pathViolation.outcome !== "PATH_VIOLATION" ||
      !pathViolation.changedPaths.includes(" leading-space.txt")
    ) {
      fail("Judge protocol self-test did not preserve the raw NUL-delimited Git path");
    }
  } finally {
    rmSync(temporaryRoot, { recursive: true, force: true });
  }
}

function main() {
  const [command = "validate", ...rawArguments] = process.argv.slice(2);
  if (command === "fingerprints") {
    const suite = readJson(suitePath);
    process.stdout.write(`${JSON.stringify(calculateFingerprints(suite), null, 2)}\n`);
    return;
  }

  const { suite } = validateSuite();
  if (command === "validate") {
    process.stdout.write(
      `M4-S04 evaluation suite valid: ${suite.tasks.length} tasks, 2 tracks, ${suite.fixture.baselineCommit}.\n`,
    );
    return;
  }

  if (command === "materialize") {
    const argumentsByName = parseArguments(rawArguments, new Set(["output"]));
    requireString(argumentsByName.output, "--output");
    const output = resolve(argumentsByName.output);
    const commit = materialize(suite, output);
    process.stdout.write(`${JSON.stringify({ output, baselineCommit: commit }, null, 2)}\n`);
    return;
  }

  if (command === "judge-report") {
    const argumentsByName = parseArguments(rawArguments, new Set(["task", "workspace", "report"]));
    requireString(argumentsByName.task, "--task");
    requireString(argumentsByName.workspace, "--workspace");
    requireString(argumentsByName.report, "--report");
    const task = suite.tasks.find((candidate) => candidate.id === argumentsByName.task);
    if (!task) {
      fail(`Unknown task: ${argumentsByName.task}`);
    }
    const runtime = readJson(resolveAsset(suite.runtime));
    const result = judgeReport(
      suite,
      runtime,
      task,
      resolve(argumentsByName.workspace),
      readJson(resolve(argumentsByName.report)),
    );
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
    if (result.outcome !== "PASSED") {
      process.exitCode = 2;
    }
    return;
  }

  fail(`Unknown command: ${command}`);
}

try {
  main();
} catch (error) {
  process.stderr.write(`M4-S04 evaluation error: ${error.message}\n`);
  process.exitCode = 1;
}
