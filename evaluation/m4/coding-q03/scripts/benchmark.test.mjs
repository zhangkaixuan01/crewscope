import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { calculateCostUsd, computeAggregate } from "./benchmark.mjs";

const protocol = JSON.parse(readFileSync(
  resolve(dirname(fileURLToPath(import.meta.url)), "../protocol.json"),
  "utf8",
));

function records(successes) {
  let index = 0;
  const result = [];
  for (let task = 0; task < 12; task += 1) {
    for (const seed of protocol.seeds) {
      const passed = index < successes;
      result.push({
        runId: `run-${index}`,
        taskId: `task-${task}`,
        seed,
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
      });
      index += 1;
    }
  }
  return result;
}

test("accepts 26 of 36 complete successful runs", () => {
  const result = computeAggregate(records(26), protocol, true);

  assert.equal(result.qualityGatePassed, true);
  assert.equal(result.metrics.successfulRuns, 26);
  assert.equal(result.metrics.endToEndSuccessRate, 26 / 36);
  assert.equal(result.metrics.inputTokens, 3_600);
  assert.equal(result.metrics.outputTokens, 720);
  assert.equal(result.metrics.cachedTokens, 360);
  assert.equal(result.metrics.wallClockMillis, 36_000);
});

test("rejects a result below the seventy percent gate", () => {
  assert.equal(computeAggregate(records(25), protocol, true).qualityGatePassed, false);
});

test("rejects a nominal success with an incomplete quality invariant", () => {
  const sample = records(26);
  sample[0].securityCompliant = false;

  const result = computeAggregate(sample, protocol, true);

  assert.equal(result.qualityGatePassed, false);
  assert.deepEqual(result.successfulInvariantViolations, ["run-0"]);
});

test("rejects a missing CrewScope delivery closure", () => {
  assert.equal(computeAggregate(records(26), protocol, false).qualityGatePassed, false);
});

test("prices cached and uncached input tokens separately", () => {
  const cost = calculateCostUsd(
    { inputTokens: 1_000_000, cachedTokens: 250_000, outputTokens: 100_000 },
    {
      inputCacheHitTokenPrice: 0.007,
      inputCacheMissTokenPrice: 0.22,
      outputTokenPrice: 0.66,
    },
  );

  assert.equal(cost, 0.23275);
});

test("rejects cached tokens greater than total input tokens", () => {
  assert.throws(
    () => calculateCostUsd(
      { inputTokens: 99, cachedTokens: 100, outputTokens: 0 },
      {
        inputCacheHitTokenPrice: 0.007,
        inputCacheMissTokenPrice: 0.22,
        outputTokenPrice: 0.66,
      },
    ),
    /cachedTokens must not exceed inputTokens/,
  );
});
