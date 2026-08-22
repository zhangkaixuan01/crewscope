#!/usr/bin/env node

import { createHash } from "node:crypto";
import {
  existsSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  realpathSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from "node:path";
import { execFileSync, spawnSync } from "node:child_process";
import { fileURLToPath, pathToFileURL } from "node:url";

const scriptPath = fileURLToPath(import.meta.url);
const evaluationRoot = resolve(dirname(scriptPath), "..");
const protocolPath = join(evaluationRoot, "protocol.json");
const repositoryRoot = resolve(evaluationRoot, "../../..");
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function fail(message) {
  throw new Error(message);
}

function readJson(path) {
  try {
    return JSON.parse(readFileSync(path, "utf8"));
  } catch (error) {
    fail(`Unable to read JSON ${path}: ${error.message}`);
  }
}

function writeJsonExclusive(path, value) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`, { encoding: "utf8", flag: "wx" });
}

function exactObject(value, fields, label) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    fail(`${label} must be an object`);
  }
  const actual = Object.keys(value).sort();
  const expected = [...fields].sort();
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    fail(`${label} fields do not match the frozen contract`);
  }
  return value;
}

function requireText(value, label, maximum = 200) {
  if (typeof value !== "string" || value.trim().length === 0 || value.length > maximum) {
    fail(`${label} must be a non-empty string of at most ${maximum} characters`);
  }
  return value;
}

function requirePattern(value, pattern, label) {
  requireText(value, label, 512);
  if (!pattern.test(value)) {
    fail(`${label} has an invalid format`);
  }
  return value;
}

function requireBoolean(value, label) {
  if (typeof value !== "boolean") {
    fail(`${label} must be a boolean`);
  }
  return value;
}

function requireInteger(value, label) {
  if (!Number.isSafeInteger(value) || value < 0) {
    fail(`${label} must be a non-negative safe integer`);
  }
  return value;
}

function requirePrice(value, label) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 0 || parsed > 1_000_000) {
    fail(`${label} must be a finite non-negative USD price`);
  }
  return parsed;
}

function requireIsoDate(value, label) {
  requireText(value, label, 64);
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/.test(value)
      || Number.isNaN(Date.parse(value))) {
    fail(`${label} must be a UTC ISO-8601 timestamp`);
  }
  return value;
}

function requireHttpUrl(value, label) {
  requireText(value, label, 1_024);
  let parsed;
  try {
    parsed = new URL(value);
  } catch {
    fail(`${label} must be an absolute HTTP(S) URL`);
  }
  if (!["http:", "https:"].includes(parsed.protocol) || parsed.username || parsed.password) {
    fail(`${label} must be an absolute HTTP(S) URL without credentials`);
  }
  return value;
}

function requireSafeRelativePath(value, label) {
  requireText(value, label, 512);
  if (isAbsolute(value)
      || value.includes("\\")
      || value.split("/").some((segment) => segment.length === 0 || segment === "." || segment === "..")) {
    fail(`${label} must be a normalized repository-relative path`);
  }
  return value;
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function cacheFingerprint(root) {
  const hash = createHash("sha256");
  let fileCount = 0;
  let totalBytes = 0;
  const visit = (current) => {
    for (const name of readdirSync(current).sort()) {
      const path = join(current, name);
      const entry = lstatSync(path);
      if (entry.isSymbolicLink()) {
        fail(`Dependency cache contains a symbolic link: ${relative(root, path)}`);
      }
      if ((entry.mode & 0o222) !== 0) {
        fail(`Dependency cache entry is writable: ${relative(root, path) || "."}`);
      }
      if (entry.isDirectory()) {
        visit(path);
        continue;
      }
      if (!entry.isFile()) {
        fail(`Dependency cache contains an unsupported entry: ${relative(root, path)}`);
      }
      const bytes = readFileSync(path);
      const nameInSnapshot = relative(root, path).split(sep).join("/");
      hash.update(nameInSnapshot, "utf8");
      hash.update("\0");
      hash.update(String(bytes.length), "ascii");
      hash.update("\0");
      hash.update(sha256(bytes), "ascii");
      hash.update("\0");
      fileCount += 1;
      totalBytes += bytes.length;
    }
  };
  const rootEntry = lstatSync(root);
  if (!rootEntry.isDirectory() || (rootEntry.mode & 0o222) !== 0) {
    fail("Dependency cache root must be a read-only directory");
  }
  visit(root);
  return { fileCount, totalBytes, contentSha256: hash.digest("hex") };
}

function resolveProtocolAsset(asset, label) {
  requireText(asset, label, 512);
  if (isAbsolute(asset)) {
    fail(`${label} must be relative to the Q03 evaluation root`);
  }
  const resolved = resolve(evaluationRoot, asset);
  const allowedRoot = `${resolve(repositoryRoot, "evaluation/m4")}${sep}`;
  if (!resolved.startsWith(allowedRoot) || !existsSync(resolved)) {
    fail(`${label} escapes or is missing: ${asset}`);
  }
  return resolved;
}

function loadContracts() {
  const protocol = exactObject(readJson(protocolPath), [
    "schemaVersion",
    "protocolId",
    "protocolVersion",
    "suite",
    "suiteValidator",
    "track",
    "expectedTaskCount",
    "repetitions",
    "seeds",
    "qualityGate",
    "pricing",
    "archive",
  ], "Q03 protocol");
  if (protocol.schemaVersion !== "crewscope.coding-benchmark-protocol/v1"
      || protocol.track !== "real-model-benchmark") {
    fail("Q03 protocol identity is invalid");
  }
  const suitePath = resolveProtocolAsset(protocol.suite, "protocol.suite");
  const validatorPath = resolveProtocolAsset(protocol.suiteValidator, "protocol.suiteValidator");
  const suite = readJson(suitePath);
  const runtimePath = resolve(dirname(suitePath), suite.runtime);
  const runtime = readJson(runtimePath);
  if (protocol.expectedTaskCount !== suite.tasks.length
      || protocol.repetitions !== suite.tracks[protocol.track].repetitions
      || JSON.stringify(protocol.seeds) !== JSON.stringify(suite.tracks[protocol.track].seeds)) {
    fail("Q03 task matrix differs from the frozen S04 real-model track");
  }
  const gate = exactObject(protocol.qualityGate, [
    "minimumEndToEndSuccessRate",
    "successfulRunRequiresCompilation",
    "successfulRunRequiresTests",
    "successfulRunRequiresAcceptance",
    "successfulRunRequiresPathCompliance",
    "successfulRunRequiresSecurityCompliance",
    "successfulRunRequiresHumanApproval",
    "minimumCrewScopeClosures",
  ], "qualityGate");
  if (gate.minimumEndToEndSuccessRate !== 0.7
      || gate.minimumCrewScopeClosures < 1
      || Object.entries(gate)
        .filter(([name]) => name.startsWith("successfulRunRequires"))
        .some(([, enabled]) => enabled !== true)) {
    fail("Q03 quality gate has been weakened");
  }
  const pricing = exactObject(protocol.pricing, [
    "currency",
    "unit",
    "inputCacheHitTokenPrice",
    "inputCacheMissTokenPrice",
    "outputTokenPrice",
    "pricingWindow",
    "pricingSource",
    "pricingEffectiveAt",
  ], "pricing protocol");
  if (pricing.currency !== "USD"
      || pricing.unit !== "PER_MILLION_TOKENS"
      || Object.entries(pricing)
        .filter(([name]) => !["currency", "unit"].includes(name))
        .some(([, value]) => value !== "RUN_LOCK_REQUIRED")) {
    fail("Q03 pricing protocol is invalid");
  }
  return { protocol, suite, runtime, validatorPath };
}

function validateFrozenSuite(validatorPath) {
  execFileSync(process.execPath, [validatorPath, "validate"], {
    cwd: repositoryRoot,
    stdio: ["ignore", "pipe", "pipe"],
  });
}

function parseArguments(values, allowed) {
  const parsed = {};
  for (let index = 0; index < values.length; index += 2) {
    const key = values[index];
    const value = values[index + 1];
    if (!key?.startsWith("--") || value === undefined) {
      fail(`Invalid command argument near ${String(key)}`);
    }
    const name = key.slice(2);
    if (!allowed.has(name) || Object.hasOwn(parsed, name)) {
      fail(`Unknown or duplicate command argument: ${key}`);
    }
    parsed[name] = value;
  }
  return parsed;
}

function ensureEmptyOutput(output) {
  if (!existsSync(output)) {
    mkdirSync(output, { recursive: true });
    return;
  }
  if (!statSync(output).isDirectory() || readdirSync(output).length !== 0) {
    fail(`Benchmark output must be an empty directory: ${output}`);
  }
}

function runIdFor(batchRunId, taskId, seed) {
  return `${batchRunId}--${taskId}--${seed}`;
}

function prepare(rawArguments, contracts) {
  const args = parseArguments(rawArguments, new Set([
    "output",
    "run-id",
    "provider",
    "model-id",
    "model-revision",
    "dependency-cache-id",
    "dependency-cache-sha256",
    "input-cache-hit-price",
    "input-cache-miss-price",
    "output-price",
    "pricing-window",
    "pricing-source",
    "pricing-effective-at",
  ]));
  const output = resolve(requireText(args.output, "--output", 1_024));
  const batchRunId = requirePattern(args["run-id"], /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/, "--run-id");
  const provider = requireText(args.provider, "--provider", 100);
  const modelId = requireText(args["model-id"], "--model-id", 200);
  const modelRevision = requireText(args["model-revision"], "--model-revision", 200);
  if (modelRevision === "RUN_LOCK_REQUIRED" || /^(latest|unknown)$/i.test(modelRevision)) {
    fail("--model-revision must identify an exact immutable model revision");
  }
  const dependencyCacheSnapshotId = requirePattern(
    args["dependency-cache-id"], /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/,
    "--dependency-cache-id");
  const dependencyCacheSha256 = requirePattern(
    args["dependency-cache-sha256"], /^[0-9a-f]{64}$/,
    "--dependency-cache-sha256");
  const inputCacheHitTokenPrice = requirePrice(
    args["input-cache-hit-price"], "--input-cache-hit-price");
  const inputCacheMissTokenPrice = requirePrice(
    args["input-cache-miss-price"], "--input-cache-miss-price");
  const outputTokenPrice = requirePrice(args["output-price"], "--output-price");
  const pricingWindow = requirePattern(
    args["pricing-window"], /^(OFF_PEAK|PEAK)$/, "--pricing-window");
  const pricingSource = requireHttpUrl(args["pricing-source"], "--pricing-source");
  const pricingEffectiveAt = requireIsoDate(
    args["pricing-effective-at"], "--pricing-effective-at");
  ensureEmptyOutput(output);

  const { protocol, suite, runtime } = contracts;
  const startedAt = new Date().toISOString();
  const matrix = [];
  for (const taskId of suite.tracks[protocol.track].taskIds) {
    for (const seed of protocol.seeds) {
      const runId = runIdFor(batchRunId, taskId, seed);
      const relativeDirectory = `runs/${taskId}/${seed}`;
      const runLock = {
        schemaVersion: "crewscope.coding-real-model-run-lock/v1",
        suiteId: suite.suiteId,
        suiteVersion: suite.suiteVersion,
        track: protocol.track,
        runId,
        startedAt,
        provider,
        modelId,
        modelRevision,
        seed,
        temperature: runtime.model.temperature,
        topP: runtime.model.topP,
        agentProfileVersion: runtime.agentProfile.version,
        sandboxImage: runtime.sandbox.image,
        runtimeAssetSha256: suite.assetLocks[suite.runtime],
        dependencyCacheSnapshotId,
        dependencyCacheSha256,
      };
      writeJsonExclusive(join(output, relativeDirectory, "run-lock.json"), runLock);
      matrix.push({ runId, taskId, seed, directory: relativeDirectory });
    }
  }
  const lock = {
    schemaVersion: "crewscope.coding-benchmark-lock/v1",
    protocolId: protocol.protocolId,
    protocolVersion: protocol.protocolVersion,
    suiteId: suite.suiteId,
    suiteVersion: suite.suiteVersion,
    track: protocol.track,
    batchRunId,
    startedAt,
    provider,
    modelId,
    modelRevision,
    sandboxImage: runtime.sandbox.image,
    runtimeAssetSha256: suite.assetLocks[suite.runtime],
    dependencyCacheSnapshotId,
    dependencyCacheSha256,
    inputCacheHitTokenPrice,
    inputCacheMissTokenPrice,
    outputTokenPrice,
    pricingWindow,
    pricingSource,
    pricingEffectiveAt,
    matrix,
  };
  writeJsonExclusive(join(output, "benchmark-lock.json"), lock);
  return { output, batchRunId, runs: matrix.length };
}

function validateBenchmarkLock(lock, contracts) {
  exactObject(lock, [
    "schemaVersion", "protocolId", "protocolVersion", "suiteId", "suiteVersion", "track",
    "batchRunId", "startedAt", "provider", "modelId", "modelRevision", "sandboxImage",
    "runtimeAssetSha256", "dependencyCacheSnapshotId", "dependencyCacheSha256",
    "inputCacheHitTokenPrice", "inputCacheMissTokenPrice", "outputTokenPrice",
    "pricingWindow", "pricingSource", "pricingEffectiveAt", "matrix",
  ], "benchmark-lock.json");
  const { protocol, suite, runtime } = contracts;
  if (lock.schemaVersion !== "crewscope.coding-benchmark-lock/v1"
      || lock.protocolId !== protocol.protocolId
      || lock.protocolVersion !== protocol.protocolVersion
      || lock.suiteId !== suite.suiteId
      || lock.suiteVersion !== suite.suiteVersion
      || lock.track !== protocol.track
      || lock.sandboxImage !== runtime.sandbox.image
      || lock.runtimeAssetSha256 !== suite.assetLocks[suite.runtime]) {
    fail("Benchmark lock differs from the frozen Q03/S04 assets");
  }
  requireIsoDate(lock.startedAt, "benchmark startedAt");
  requirePattern(lock.batchRunId, /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/, "benchmark batchRunId");
  requireText(lock.provider, "benchmark provider", 100);
  requireText(lock.modelId, "benchmark modelId", 200);
  requireText(lock.modelRevision, "benchmark modelRevision", 200);
  if (lock.modelRevision === "RUN_LOCK_REQUIRED" || /^(latest|unknown)$/i.test(lock.modelRevision)) {
    fail("Benchmark lock does not contain an exact model revision");
  }
  requirePattern(
    lock.dependencyCacheSnapshotId,
    /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/,
    "dependency cache snapshot id");
  requirePattern(lock.dependencyCacheSha256, /^[0-9a-f]{64}$/, "dependency cache hash");
  requirePrice(lock.inputCacheHitTokenPrice, "cache-hit input token price");
  requirePrice(lock.inputCacheMissTokenPrice, "cache-miss input token price");
  requirePrice(lock.outputTokenPrice, "output token price");
  requirePattern(lock.pricingWindow, /^(OFF_PEAK|PEAK)$/, "pricing window");
  requireHttpUrl(lock.pricingSource, "pricing source");
  requireIsoDate(lock.pricingEffectiveAt, "pricing effectiveAt");

  const expected = [];
  for (const taskId of suite.tracks[protocol.track].taskIds) {
    for (const seed of protocol.seeds) {
      expected.push(`${taskId}:${seed}`);
    }
  }
  if (!Array.isArray(lock.matrix)) {
    fail("Benchmark matrix must be an array");
  }
  const actual = lock.matrix.map((entry) => {
    exactObject(entry, ["runId", "taskId", "seed", "directory"], "benchmark matrix entry");
    if (entry.runId !== runIdFor(lock.batchRunId, entry.taskId, entry.seed)
        || entry.directory !== `runs/${entry.taskId}/${entry.seed}`) {
      fail(`Benchmark matrix entry is not canonical: ${entry.taskId}:${entry.seed}`);
    }
    return `${entry.taskId}:${entry.seed}`;
  });
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    fail("Benchmark matrix is incomplete, duplicated or reordered");
  }
}

function runJudge(validatorPath, taskId, workspace, platformReport) {
  const result = spawnSync(process.execPath, [
    validatorPath,
    "judge-report",
    "--task", taskId,
    "--workspace", workspace,
    "--report", platformReport,
  ], { cwd: repositoryRoot, encoding: "utf8" });
  if (result.status !== 0 && result.status !== 2) {
    fail(`S04 judge failed for ${taskId}: ${result.stderr.trim()}`);
  }
  try {
    return JSON.parse(result.stdout);
  } catch {
    fail(`S04 judge returned invalid JSON for ${taskId}`);
  }
}

const telemetryFields = [
  "schemaVersion", "runId", "taskId", "seed", "compilationPassed", "testsPassed",
  "acceptanceCriteriaPassed", "pathPolicyPassed", "securityPolicyPassed", "inputTokens",
  "outputTokens", "cachedTokens", "modelCalls", "toolCalls", "commandCalls",
  "writeOperations", "writtenBytes", "diffBytes", "testRepairRounds", "wallClockMillis",
];

function validateTelemetry(telemetry, entry, report, judge) {
  exactObject(telemetry, telemetryFields, `${entry.runId} telemetry`);
  if (telemetry.schemaVersion !== "crewscope.coding-benchmark-telemetry/v1"
      || telemetry.runId !== entry.runId
      || telemetry.taskId !== entry.taskId
      || telemetry.seed !== entry.seed) {
    fail(`${entry.runId} telemetry crossed its run boundary`);
  }
  for (const field of [
    "compilationPassed", "testsPassed", "acceptanceCriteriaPassed", "pathPolicyPassed",
    "securityPolicyPassed",
  ]) {
    requireBoolean(telemetry[field], `${entry.runId}.${field}`);
  }
  for (const field of telemetryFields.slice(9)) {
    requireInteger(telemetry[field], `${entry.runId}.${field}`);
  }
  if (telemetry.cachedTokens > telemetry.inputTokens) {
    fail(`${entry.runId}.cachedTokens must not exceed inputTokens`);
  }
  const budgetMapping = {
    modelCalls: telemetry.modelCalls,
    inputTokens: telemetry.inputTokens,
    outputTokens: telemetry.outputTokens,
    toolCalls: telemetry.toolCalls,
    commandCalls: telemetry.commandCalls,
    writeOperations: telemetry.writeOperations,
    writtenBytes: telemetry.writtenBytes,
    diffBytes: telemetry.diffBytes,
    testRepairRounds: telemetry.testRepairRounds,
    wallClockSeconds: Math.ceil(telemetry.wallClockMillis / 1_000),
  };
  for (const [field, expected] of Object.entries(budgetMapping)) {
    if (report.budget?.[field] !== expected) {
      fail(`${entry.runId} telemetry does not match platform budget ${field}`);
    }
  }
  if (telemetry.pathPolicyPassed !== judge.facts.changedPathsAllowed
      || telemetry.securityPolicyPassed !== judge.facts.sandboxPolicyObserved
      || telemetry.acceptanceCriteriaPassed !== judge.facts.acceptancePassed) {
    fail(`${entry.runId} telemetry does not match judge authority`);
  }
}

function validateHumanReview(review, entry) {
  exactObject(review, [
    "schemaVersion", "runId", "reviewerId", "reviewedAt", "verdict", "criteria",
    "notesSha256",
  ], `${entry.runId} human review`);
  if (review.schemaVersion !== "crewscope.coding-human-review/v1" || review.runId !== entry.runId) {
    fail(`${entry.runId} human review crossed its run boundary`);
  }
  requireText(review.reviewerId, `${entry.runId}.reviewerId`, 128);
  requireIsoDate(review.reviewedAt, `${entry.runId}.reviewedAt`);
  if (!["APPROVED", "APPROVED_WITH_NOTES", "REJECTED"].includes(review.verdict)) {
    fail(`${entry.runId} has an invalid human verdict`);
  }
  exactObject(review.criteria, ["correctness", "maintainability", "scope", "safety"],
    `${entry.runId} human criteria`);
  for (const [name, value] of Object.entries(review.criteria)) {
    requireBoolean(value, `${entry.runId}.criteria.${name}`);
  }
  requirePattern(review.notesSha256, /^[0-9a-f]{64}$/, `${entry.runId}.notesSha256`);
}

function humanApproved(review) {
  return review.verdict !== "REJECTED" && Object.values(review.criteria).every(Boolean);
}

export function calculateCostUsd(telemetry, pricing) {
  const inputTokens = requireInteger(telemetry.inputTokens, "cost inputTokens");
  const cachedTokens = requireInteger(telemetry.cachedTokens, "cost cachedTokens");
  const outputTokens = requireInteger(telemetry.outputTokens, "cost outputTokens");
  if (cachedTokens > inputTokens) {
    fail("cost cachedTokens must not exceed inputTokens");
  }
  const uncachedInputTokens = inputTokens - cachedTokens;
  return (cachedTokens * requirePrice(
    pricing.inputCacheHitTokenPrice, "cost cache-hit input token price")
      + uncachedInputTokens * requirePrice(
        pricing.inputCacheMissTokenPrice, "cost cache-miss input token price")
      + outputTokens * requirePrice(pricing.outputTokenPrice, "cost output token price"))
    / 1_000_000;
}

function validateRunLock(expected, actual, label) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    fail(`${label} differs from its prepared immutable run lock`);
  }
}

function correctedEvidencePaths(runDirectory, entry, originalReportPath, originalTelemetryPath) {
  const correctionPath = join(runDirectory, "evidence-correction.json");
  if (!existsSync(correctionPath)) {
    return { platformReportPath: originalReportPath, telemetryPath: originalTelemetryPath };
  }
  const correction = readJson(correctionPath);
  exactObject(correction, [
    "schemaVersion", "runId", "reason", "originalPlatformReportSha256",
    "originalTelemetrySha256", "correctedPlatformReportSha256",
    "correctedTelemetrySha256", "sourceCommandEvidenceIds", "correctedAt",
  ], `${entry.runId} evidence correction`);
  if (correction.schemaVersion !== "crewscope.coding-evidence-correction/v1"
      || correction.runId !== entry.runId
      || correction.reason !== "VERIFY_ACCEPTANCE_EXPORT_OMISSION") {
    fail(`${entry.runId} has an invalid evidence correction identity`);
  }
  const correctedReportPath = join(runDirectory, "platform-report.corrected.json");
  const correctedTelemetryPath = join(runDirectory, "telemetry.corrected.json");
  for (const path of [correctedReportPath, correctedTelemetryPath]) {
    if (!existsSync(path) || lstatSync(path).isSymbolicLink()) {
      fail(`${entry.runId} has an incomplete evidence correction`);
    }
  }
  const hashes = {
    originalPlatformReportSha256: sha256(readFileSync(originalReportPath)),
    originalTelemetrySha256: sha256(readFileSync(originalTelemetryPath)),
    correctedPlatformReportSha256: sha256(readFileSync(correctedReportPath)),
    correctedTelemetrySha256: sha256(readFileSync(correctedTelemetryPath)),
  };
  for (const [field, actual] of Object.entries(hashes)) {
    requirePattern(correction[field], /^[0-9a-f]{64}$/, `${entry.runId}.${field}`);
    if (correction[field] !== actual) {
      fail(`${entry.runId} evidence correction hash mismatch for ${field}`);
    }
  }
  if (!Array.isArray(correction.sourceCommandEvidenceIds)
      || correction.sourceCommandEvidenceIds.length === 0) {
    fail(`${entry.runId} evidence correction requires source CommandEvidence`);
  }
  correction.sourceCommandEvidenceIds.forEach((id, index) => requirePattern(
    id, UUID_PATTERN,
    `${entry.runId}.sourceCommandEvidenceIds[${index}]`));
  requireIsoDate(correction.correctedAt, `${entry.runId}.correctedAt`);
  const originalReport = readJson(originalReportPath);
  const originalTelemetry = readJson(originalTelemetryPath);
  const correctedReport = readJson(correctedReportPath);
  const correctedTelemetry = readJson(correctedTelemetryPath);
  if ((originalReport.acceptance?.length ?? 0) !== 0
      || originalTelemetry.acceptanceCriteriaPassed !== false
      || correctedReport.acceptance?.length === 0
      || correctedTelemetry.acceptanceCriteriaPassed !== true) {
    fail(`${entry.runId} evidence correction is outside the VERIFY omission boundary`);
  }
  return {
    platformReportPath: correctedReportPath,
    telemetryPath: correctedTelemetryPath,
  };
}

function collectRun(input, entry, contracts, lock) {
  const runDirectory = resolve(input, entry.directory);
  const allowedPrefix = `${realpathSync(input)}${sep}`;
  const physicalRunDirectory = realpathSync(runDirectory);
  if (!physicalRunDirectory.startsWith(allowedPrefix)) {
    fail(`${entry.runId} directory escaped the benchmark archive`);
  }
  const runLock = readJson(join(runDirectory, "run-lock.json"));
  const originalPlatformReportPath = join(runDirectory, "platform-report.json");
  const originalTelemetryPath = join(runDirectory, "telemetry.json");
  const humanReviewPath = join(runDirectory, "human-review.json");
  const workspace = join(runDirectory, "workspace");
  for (const path of [originalPlatformReportPath, originalTelemetryPath, humanReviewPath, workspace]) {
    if (!existsSync(path)) {
      fail(`${entry.runId} is missing required evidence: ${relative(input, path)}`);
    }
    // Evidence is append-only only when its physical object remains below the prepared Run.
    // Reject symlink indirection so a later host-file change cannot rewrite archived facts.
    if (lstatSync(path).isSymbolicLink()
        || !(realpathSync(path) === physicalRunDirectory
          || realpathSync(path).startsWith(`${physicalRunDirectory}${sep}`))) {
      fail(`${entry.runId} evidence escaped its physical run directory`);
    }
  }
  const { platformReportPath, telemetryPath } = correctedEvidencePaths(
    runDirectory, entry, originalPlatformReportPath, originalTelemetryPath);
  for (const path of [platformReportPath, telemetryPath]) {
    if (!(realpathSync(path) === physicalRunDirectory
        || realpathSync(path).startsWith(`${physicalRunDirectory}${sep}`))) {
      fail(`${entry.runId} corrected evidence escaped its physical run directory`);
    }
  }
  const platformReport = readJson(platformReportPath);
  validateRunLock(runLock, platformReport.runLock, `${entry.runId} platform RunLock`);
  if (runLock.provider !== lock.provider
      || runLock.modelId !== lock.modelId
      || runLock.modelRevision !== lock.modelRevision) {
    fail(`${entry.runId} model identity differs from the batch lock`);
  }
  const judge = runJudge(contracts.validatorPath, entry.taskId, workspace, platformReportPath);
  const telemetry = readJson(telemetryPath);
  validateTelemetry(telemetry, entry, platformReport, judge);
  const review = readJson(humanReviewPath);
  validateHumanReview(review, entry);
  const costUsd = calculateCostUsd(telemetry, lock);
  return {
    runId: entry.runId,
    taskId: entry.taskId,
    seed: entry.seed,
    outcome: judge.outcome,
    compilationPassed: telemetry.compilationPassed,
    testsPassed: telemetry.testsPassed,
    acceptancePassed: telemetry.acceptanceCriteriaPassed,
    pathCompliant: telemetry.pathPolicyPassed,
    securityCompliant: telemetry.securityPolicyPassed,
    humanVerdict: review.verdict,
    humanApproved: humanApproved(review),
    inputTokens: telemetry.inputTokens,
    outputTokens: telemetry.outputTokens,
    cachedTokens: telemetry.cachedTokens,
    costUsd,
    wallClockMillis: telemetry.wallClockMillis,
  };
}

function validateCrewScopeClosure(closure, lock) {
  exactObject(closure, [
    "schemaVersion", "closureId", "batchRunId", "provider", "modelId", "modelRevision",
    "repositoryKey", "taskId", "baselineCommit", "deliveryCommit", "executionWorkspaceId",
    "agentRunId", "commandEvidenceIds", "testEvidenceId", "diffArtifactId",
    "codingResultSha256", "changedPaths", "compilationPassed", "testsPassed",
    "acceptancePassed", "pathCompliant", "securityCompliant", "humanVerdict", "completedAt",
  ], "crewscope-closure.json");
  if (closure.schemaVersion !== "crewscope.coding-closure-evidence/v1"
      || closure.repositoryKey !== "crewscope-java"
      || closure.batchRunId !== lock.batchRunId
      || closure.provider !== lock.provider
      || closure.modelId !== lock.modelId
      || closure.modelRevision !== lock.modelRevision) {
    fail("CrewScope closure identity is invalid");
  }
  requireText(closure.closureId, "closureId", 128);
  requireText(closure.taskId, "closure taskId", 128);
  requirePattern(closure.baselineCommit, /^[0-9a-f]{40}$/, "closure baselineCommit");
  requirePattern(closure.deliveryCommit, /^[0-9a-f]{40}$/, "closure deliveryCommit");
  if (closure.baselineCommit === closure.deliveryCommit) {
    fail("CrewScope closure must contain a non-empty Delivery Commit");
  }
  for (const field of ["executionWorkspaceId", "agentRunId", "testEvidenceId", "diffArtifactId"]) {
    requirePattern(closure[field], UUID_PATTERN, `closure ${field}`);
  }
  if (!Array.isArray(closure.commandEvidenceIds) || closure.commandEvidenceIds.length === 0) {
    fail("CrewScope closure requires CommandEvidence");
  }
  closure.commandEvidenceIds.forEach((id, index) =>
    requirePattern(id, UUID_PATTERN, `closure commandEvidenceIds[${index}]`));
  requirePattern(closure.codingResultSha256, /^[0-9a-f]{64}$/, "closure codingResultSha256");
  if (!Array.isArray(closure.changedPaths) || closure.changedPaths.length === 0) {
    fail("CrewScope closure requires changed paths");
  }
  closure.changedPaths.forEach((path, index) =>
    requireSafeRelativePath(path, `closure changedPaths[${index}]`));
  for (const field of [
    "compilationPassed", "testsPassed", "acceptancePassed", "pathCompliant",
    "securityCompliant",
  ]) {
    if (requireBoolean(closure[field], `closure ${field}`) !== true) {
      fail(`CrewScope closure requires ${field}`);
    }
  }
  if (!["APPROVED", "APPROVED_WITH_NOTES"].includes(closure.humanVerdict)) {
    fail("CrewScope closure requires human approval");
  }
  requireIsoDate(closure.completedAt, "closure completedAt");
  return true;
}

function ratio(count, total) {
  return total === 0 ? 0 : count / total;
}

export function computeAggregate(records, protocol, closurePassed) {
  const total = records.length;
  const passed = records.filter((record) => record.outcome === "PASSED");
  const taskIds = [...new Set(records.map((record) => record.taskId))];
  const firstSeed = protocol.seeds[0];
  const successfulInvariantViolations = passed.filter((record) => !(
    record.compilationPassed
    && record.testsPassed
    && record.acceptancePassed
    && record.pathCompliant
    && record.securityCompliant
    && record.humanApproved
  ));
  const metrics = {
    totalRuns: total,
    successfulRuns: passed.length,
    endToEndSuccessRate: ratio(passed.length, total),
    passAt1: ratio(
      records.filter((record) => record.seed === firstSeed && record.outcome === "PASSED").length,
      taskIds.length),
    taskSuccessRate: ratio(
      taskIds.filter((taskId) => records.some(
        (record) => record.taskId === taskId && record.outcome === "PASSED")).length,
      taskIds.length),
    compileRate: ratio(records.filter((record) => record.compilationPassed).length, total),
    testRate: ratio(records.filter((record) => record.testsPassed).length, total),
    acceptanceRate: ratio(records.filter((record) => record.acceptancePassed).length, total),
    pathComplianceRate: ratio(records.filter((record) => record.pathCompliant).length, total),
    securityComplianceRate: ratio(records.filter((record) => record.securityCompliant).length, total),
    humanApprovalRate: ratio(records.filter((record) => record.humanApproved).length, total),
    inputTokens: records.reduce((sum, record) => sum + record.inputTokens, 0),
    outputTokens: records.reduce((sum, record) => sum + record.outputTokens, 0),
    cachedTokens: records.reduce((sum, record) => sum + record.cachedTokens, 0),
    costUsd: records.reduce((sum, record) => sum + record.costUsd, 0),
    wallClockMillis: records.reduce((sum, record) => sum + record.wallClockMillis, 0),
  };
  const gate = protocol.qualityGate;
  return {
    metrics,
    successfulInvariantViolations: successfulInvariantViolations.map((record) => record.runId),
    qualityGatePassed: metrics.endToEndSuccessRate >= gate.minimumEndToEndSuccessRate
      && successfulInvariantViolations.length === 0
      && closurePassed,
  };
}

function aggregate(rawArguments, contracts) {
  const args = parseArguments(rawArguments, new Set(["input", "output"]));
  const input = realpathSync(resolve(requireText(args.input, "--input", 1_024)));
  const output = resolve(requireText(args.output, "--output", 1_024));
  if (realpathSync(dirname(output)) !== input
      || !/^aggregate(?:-v[2-9][0-9]*)?\.json$/.test(basename(output))) {
    fail("Aggregate output must be <input>/aggregate.json or an append-only aggregate-vN.json");
  }
  if (existsSync(output)) {
    fail(`Aggregate report is append-only and already exists: ${output}`);
  }
  const lock = readJson(join(input, "benchmark-lock.json"));
  validateBenchmarkLock(lock, contracts);
  const records = lock.matrix.map((entry) => collectRun(input, entry, contracts, lock));
  const closurePath = join(input, "crewscope-closure.json");
  if (!existsSync(closurePath)) {
    fail("Benchmark archive is missing crewscope-closure.json");
  }
  if (lstatSync(closurePath).isSymbolicLink()) {
    fail("crewscope-closure.json must be a physical archive file");
  }
  const closurePassed = validateCrewScopeClosure(readJson(closurePath), lock);
  const computed = computeAggregate(records, contracts.protocol, closurePassed);
  const report = {
    schemaVersion: "crewscope.coding-benchmark-aggregate/v1",
    protocolId: contracts.protocol.protocolId,
    protocolVersion: contracts.protocol.protocolVersion,
    suiteId: contracts.suite.suiteId,
    suiteVersion: contracts.suite.suiteVersion,
    batchRunId: lock.batchRunId,
    generatedAt: new Date().toISOString(),
    provider: lock.provider,
    modelId: lock.modelId,
    modelRevision: lock.modelRevision,
    sandboxImage: lock.sandboxImage,
    dependencyCacheSnapshotId: lock.dependencyCacheSnapshotId,
    dependencyCacheSha256: lock.dependencyCacheSha256,
    pricing: {
      currency: "USD",
      unit: "PER_MILLION_TOKENS",
      inputCacheHitTokenPricePerMillion: lock.inputCacheHitTokenPrice,
      inputCacheMissTokenPricePerMillion: lock.inputCacheMissTokenPrice,
      outputTokenPricePerMillion: lock.outputTokenPrice,
      pricingWindow: lock.pricingWindow,
      source: lock.pricingSource,
      effectiveAt: lock.pricingEffectiveAt,
    },
    metrics: computed.metrics,
    successfulInvariantViolations: computed.successfulInvariantViolations,
    crewScopeClosurePassed: closurePassed,
    qualityGatePassed: computed.qualityGatePassed,
    outcomes: records.map((record) => ({
      runId: record.runId,
      taskId: record.taskId,
      seed: record.seed,
      outcome: record.outcome,
      humanVerdict: record.humanVerdict,
    })),
    evidenceDigest: sha256(JSON.stringify(records)),
  };
  writeJsonExclusive(output, report);
  if (!report.qualityGatePassed) {
    process.exitCode = 2;
  }
  return report;
}

function snapshotCache(rawArguments) {
  const args = parseArguments(rawArguments, new Set(["source", "output", "snapshot-id"]));
  const source = realpathSync(resolve(requireText(args.source, "--source", 1_024)));
  const output = resolve(requireText(args.output, "--output", 1_024));
  const snapshotId = requirePattern(
    args["snapshot-id"], /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/, "--snapshot-id");
  if (existsSync(output)) {
    fail(`Dependency cache manifest already exists: ${output}`);
  }
  const fingerprint = cacheFingerprint(source);
  const manifest = {
    schemaVersion: "crewscope.maven-cache-snapshot/v1",
    snapshotId,
    createdAt: new Date().toISOString(),
    mountPath: "/maven-cache",
    repositoryPath: "/maven-cache/repository",
    fileCount: fingerprint.fileCount,
    totalBytes: fingerprint.totalBytes,
    contentSha256: fingerprint.contentSha256,
  };
  writeJsonExclusive(output, manifest);
  return manifest;
}

function verifyCache(rawArguments) {
  const args = parseArguments(rawArguments, new Set(["source", "manifest"]));
  const source = realpathSync(resolve(requireText(args.source, "--source", 1_024)));
  const manifest = exactObject(readJson(resolve(requireText(args.manifest, "--manifest", 1_024))), [
    "schemaVersion", "snapshotId", "createdAt", "mountPath", "repositoryPath", "fileCount",
    "totalBytes", "contentSha256",
  ], "dependency cache manifest");
  if (manifest.schemaVersion !== "crewscope.maven-cache-snapshot/v1"
      || manifest.mountPath !== "/maven-cache"
      || manifest.repositoryPath !== "/maven-cache/repository") {
    fail("Dependency cache manifest identity is invalid");
  }
  requireIsoDate(manifest.createdAt, "cache manifest createdAt");
  requirePattern(manifest.contentSha256, /^[0-9a-f]{64}$/, "cache manifest hash");
  const actual = cacheFingerprint(source);
  if (actual.fileCount !== manifest.fileCount
      || actual.totalBytes !== manifest.totalBytes
      || actual.contentSha256 !== manifest.contentSha256) {
    fail("Dependency cache differs from its immutable manifest");
  }
  return { snapshotId: manifest.snapshotId, ...actual };
}

function syntheticRecord(index, passed) {
  return {
    runId: `self-test-${index}`,
    taskId: `task-${index % 12}`,
    seed: [20260816, 20260817, 20260818][index % 3],
    outcome: passed ? "PASSED" : "ACCEPTANCE_FAILED",
    compilationPassed: true,
    testsPassed: passed,
    acceptancePassed: passed,
    pathCompliant: true,
    securityCompliant: true,
    humanApproved: true,
    inputTokens: 100,
    outputTokens: 20,
    cachedTokens: 10,
    costUsd: 0.001,
    wallClockMillis: 1_000,
  };
}

function validateSelfTest(protocol) {
  const passing = Array.from({ length: 36 }, (_, index) => syntheticRecord(index, index < 26));
  const result = computeAggregate(passing, protocol, true);
  if (!result.qualityGatePassed || result.metrics.endToEndSuccessRate < 0.7) {
    fail("Q03 self-test rejected the minimum passing sample");
  }
  const belowThreshold = computeAggregate(
    Array.from({ length: 36 }, (_, index) => syntheticRecord(index, index < 25)),
    protocol,
    true);
  if (belowThreshold.qualityGatePassed) {
    fail("Q03 self-test accepted a result below 70%");
  }
  const invalidSuccess = structuredClone(passing);
  invalidSuccess[0].compilationPassed = false;
  if (computeAggregate(invalidSuccess, protocol, true).qualityGatePassed) {
    fail("Q03 self-test accepted a successful run without compilation evidence");
  }
  if (computeAggregate(passing, protocol, false).qualityGatePassed) {
    fail("Q03 self-test accepted a missing CrewScope closure");
  }
}

function prerequisites(contracts) {
  const commandAvailable = (command, args) => {
    const result = spawnSync(command, args, { stdio: "ignore" });
    return result.status === 0;
  };
  return {
    schemaVersion: "crewscope.coding-benchmark-prerequisites/v1",
    node24OrNewer: Number(process.versions.node.split(".")[0]) >= 24,
    dockerDaemon: commandAvailable("docker", ["info"]),
    sandboxImagePresent: commandAvailable("docker", [
      "image", "inspect", contracts.runtime.sandbox.image,
    ]),
    java17OrNewer: commandAvailable("java", ["-version"]),
    openAiApiKeyConfigured: Boolean(process.env.OPENAI_API_KEY),
    modelProvider: process.env.AGENTSCOPE_MODEL_PROVIDER ?? "",
    exactModelRevision: process.env.CREWSCOPE_Q03_MODEL_REVISION ?? "",
  };
}

function main() {
  const [command = "validate", ...rawArguments] = process.argv.slice(2);
  const contracts = loadContracts();
  if (command === "validate") {
    validateFrozenSuite(contracts.validatorPath);
    validateSelfTest(contracts.protocol);
    process.stdout.write(
      `M4-Q03 benchmark protocol valid: ${contracts.suite.tasks.length} tasks, `
      + `${contracts.protocol.repetitions} repetitions, 70% gate.\n`);
    return;
  }
  if (command === "prerequisites") {
    process.stdout.write(`${JSON.stringify(prerequisites(contracts), null, 2)}\n`);
    return;
  }
  if (command === "prepare") {
    validateFrozenSuite(contracts.validatorPath);
    process.stdout.write(`${JSON.stringify(prepare(rawArguments, contracts), null, 2)}\n`);
    return;
  }
  if (command === "snapshot-cache") {
    process.stdout.write(`${JSON.stringify(snapshotCache(rawArguments), null, 2)}\n`);
    return;
  }
  if (command === "verify-cache") {
    process.stdout.write(`${JSON.stringify(verifyCache(rawArguments), null, 2)}\n`);
    return;
  }
  if (command === "aggregate") {
    process.stdout.write(`${JSON.stringify(aggregate(rawArguments, contracts), null, 2)}\n`);
    return;
  }
  fail(`Unknown command: ${command}`);
}

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : "";
if (import.meta.url === invokedPath) {
  try {
    main();
  } catch (error) {
    process.stderr.write(`M4-Q03 benchmark error: ${error.message}\n`);
    process.exitCode = 1;
  }
}
