import assert from "node:assert/strict";
import {
  appendFileSync,
  cpSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { execFileSync } from "node:child_process";
import test from "node:test";
import { fileURLToPath } from "node:url";

const scriptsRoot = dirname(fileURLToPath(import.meta.url));
const evaluationRoot = resolve(scriptsRoot, "../..");
const benchmarkScript = join(scriptsRoot, "benchmark.mjs");
const judgeScript = join(evaluationRoot, "coding-v1/scripts/evaluate.mjs");
const suite = JSON.parse(readFileSync(join(evaluationRoot, "coding-v1/suite.json"), "utf8"));
const runtime = JSON.parse(readFileSync(
  join(evaluationRoot, "coding-v1", suite.runtime),
  "utf8",
));

function writeJson(path, value) {
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

function platformReport(task, runLock) {
  const evidenceHash = "a".repeat(64);
  return {
    suiteId: suite.suiteId,
    suiteVersion: suite.suiteVersion,
    taskId: task.id,
    track: "real-model-benchmark",
    runLock,
    baselineCommit: suite.fixture.baselineCommit,
    sandbox: { image: runtime.sandbox.image, network: "none", nonRoot: true },
    budget: Object.fromEntries(Object.keys(runtime.budget).map((name) => [name, 0])),
    acceptance: task.acceptanceCommands.map((command) => ({
      id: command.id,
      argv: command.argv,
      timeoutSeconds: command.timeoutSeconds,
      timedOut: false,
      exitCode: 0,
      status: "PASSED",
      evidenceSha256: evidenceHash,
      verifiedSha256: evidenceHash,
    })),
    structuredResult: { schema: "CodeChangeResultV1", valid: true },
    final: {
      manifestSha256: "b".repeat(64),
      verifiedManifestSha256: "b".repeat(64),
    },
  };
}

function telemetry(entry) {
  return {
    schemaVersion: "crewscope.coding-benchmark-telemetry/v1",
    runId: entry.runId,
    taskId: entry.taskId,
    seed: entry.seed,
    compilationPassed: true,
    testsPassed: true,
    acceptanceCriteriaPassed: true,
    pathPolicyPassed: true,
    securityPolicyPassed: true,
    inputTokens: 0,
    outputTokens: 0,
    cachedTokens: 0,
    modelCalls: 0,
    toolCalls: 0,
    commandCalls: 0,
    writeOperations: 0,
    writtenBytes: 0,
    diffBytes: 0,
    testRepairRounds: 0,
    wallClockMillis: 0,
  };
}

function humanReview(entry) {
  return {
    schemaVersion: "crewscope.coding-human-review/v1",
    runId: entry.runId,
    reviewerId: "m4-q03-protocol-reviewer",
    reviewedAt: "2026-08-21T00:00:00Z",
    verdict: "APPROVED",
    criteria: {
      correctness: true,
      maintainability: true,
      scope: true,
      safety: true,
    },
    notesSha256: "c".repeat(64),
  };
}

test("aggregates the complete frozen matrix and refuses overwrite", { timeout: 180_000 }, () => {
  const temporaryRoot = mkdtempSync(join(tmpdir(), "crewscope-m4-q03-aggregate-"));
  try {
    const template = join(temporaryRoot, "template");
    const archive = join(temporaryRoot, "archive");
    execFileSync(process.execPath, [judgeScript, "materialize", "--output", template]);
    execFileSync(process.execPath, [
      benchmarkScript,
      "prepare",
      "--output", archive,
      "--run-id", "q03-aggregate-self-test",
      "--provider", "protocol-provider",
      "--model-id", "protocol-model",
      "--model-revision", "protocol-model-2026-08-21",
      "--dependency-cache-id", "protocol-cache",
      "--dependency-cache-sha256", "d".repeat(64),
      "--input-cache-hit-price", "0.25",
      "--input-cache-miss-price", "1",
      "--output-price", "4",
      "--pricing-window", "OFF_PEAK",
      "--pricing-source", "https://example.com/pricing",
      "--pricing-effective-at", "2026-08-21T00:00:00Z",
    ]);
    const benchmarkLock = JSON.parse(readFileSync(join(archive, "benchmark-lock.json"), "utf8"));
    for (const entry of benchmarkLock.matrix) {
      const task = suite.tasks.find((candidate) => candidate.id === entry.taskId);
      const runDirectory = join(archive, entry.directory);
      const workspace = join(runDirectory, "workspace");
      cpSync(template, workspace, { recursive: true });
      appendFileSync(join(workspace, task.allowedPaths[0]), "\n", "utf8");
      const runLock = JSON.parse(readFileSync(join(runDirectory, "run-lock.json"), "utf8"));
      writeJson(join(runDirectory, "platform-report.json"), platformReport(task, runLock));
      writeJson(join(runDirectory, "telemetry.json"), telemetry(entry));
      writeJson(join(runDirectory, "human-review.json"), humanReview(entry));
    }
    writeJson(join(archive, "crewscope-closure.json"), {
      schemaVersion: "crewscope.coding-closure-evidence/v1",
      closureId: "q03-protocol-closure",
      batchRunId: benchmarkLock.batchRunId,
      provider: benchmarkLock.provider,
      modelId: benchmarkLock.modelId,
      modelRevision: benchmarkLock.modelRevision,
      repositoryKey: "crewscope-java",
      taskId: "m4-q03-protocol-closure",
      baselineCommit: "a".repeat(40),
      deliveryCommit: "b".repeat(40),
      executionWorkspaceId: "11111111-1111-4111-8111-111111111111",
      agentRunId: "22222222-2222-4222-8222-222222222222",
      commandEvidenceIds: ["33333333-3333-4333-8333-333333333333"],
      testEvidenceId: "44444444-4444-4444-8444-444444444444",
      diffArtifactId: "55555555-5555-4555-8555-555555555555",
      codingResultSha256: "e".repeat(64),
      changedPaths: ["crewscope-server/src/main/java/io/crewscope/Q03Probe.java"],
      compilationPassed: true,
      testsPassed: true,
      acceptancePassed: true,
      pathCompliant: true,
      securityCompliant: true,
      humanVerdict: "APPROVED",
      completedAt: "2026-08-21T00:00:00Z",
    });

    const aggregatePath = join(archive, "aggregate.json");
    execFileSync(process.execPath, [
      benchmarkScript, "aggregate", "--input", archive, "--output", aggregatePath,
    ]);
    const report = JSON.parse(readFileSync(aggregatePath, "utf8"));
    assert.equal(report.qualityGatePassed, true);
    assert.equal(report.metrics.totalRuns, 36);
    assert.equal(report.metrics.successfulRuns, 36);
    assert.equal(report.pricing.inputCacheHitTokenPricePerMillion, 0.25);
    assert.equal(report.pricing.inputCacheMissTokenPricePerMillion, 1);
    assert.equal(report.outcomes.length, 36);

    assert.throws(() => execFileSync(process.execPath, [
      benchmarkScript, "aggregate", "--input", archive, "--output", aggregatePath,
    ], { stdio: "pipe" }));
  } finally {
    rmSync(temporaryRoot, { recursive: true, force: true });
  }
});
