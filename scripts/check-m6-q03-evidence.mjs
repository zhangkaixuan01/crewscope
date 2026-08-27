#!/usr/bin/env node

import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { isAbsolute } from 'node:path'

const [protocolPath, productionPath, restorePath, larkPath] = process.argv.slice(2)
if (!protocolPath || !productionPath) {
  fail('Usage: check-m6-q03-evidence.mjs protocol-load.json production-load.json [restore.json] [lark.json]')
}
const expectedProductionLane = process.env.CREWSCOPE_M6_Q03_EXPECTED_PRODUCTION_LOAD_LANE ?? 'FIXTURE'
if (!['FIXTURE', 'CANONICAL'].includes(expectedProductionLane)) {
  fail('CREWSCOPE_M6_Q03_EXPECTED_PRODUCTION_LOAD_LANE must be FIXTURE or CANONICAL')
}

try {
  const protocol = readEvidence(protocolPath, 'protocol load')
  const production = readEvidence(productionPath, 'production load')
  let restore = null
  let lark = null
  validateProtocolLoad(protocol)
  validateProductionLoad(production, expectedProductionLane)

  if (restorePath) {
    restore = readEvidence(restorePath, 'restore')
    assert.equal(restore.formatVersion, 1)
    assert.ok(restore.actualRpoSeconds >= 0 && restore.actualRpoSeconds <= 86_400)
    assert.ok(restore.actualRtoSeconds >= 0 && restore.actualRtoSeconds <= 14_400)
    assert.equal(restore.restoredSchemaVersion, 30)
    assert.equal(restore.smoke?.apiReadiness, 'UP')
    assert.equal(restore.smoke?.systemInfo, 'AgentScope Java')
    assert.equal(restore.smoke?.activeExecutions, 0)
  }

  if (larkPath) {
    lark = readEvidence(larkPath, 'real Lark')
    assert.equal(lark.formatVersion, 1)
    assert.equal(lark.lane, 'RELEASE_CANDIDATE')
    assert.equal(lark.templateKey, 'release-candidate-smoke@1')
    assert.ok(['feishu', 'lark'].includes(lark.provider))
    assert.equal(lark.dataset, 'm6-team-beta-v1')
    assert.equal(lark.seed, 20260825)
    assert.equal(lark.recipientLabel, 'dedicated-lark-test-recipient')
    assert.equal(lark.outcome, 'SUCCEEDED')
    for (const key of [
      'appIdentityHash',
      'recipientIdentityHash',
      'providerMessageIdentityHash',
      'idempotencyKeyHash',
    ]) assert.match(lark[key], /^[0-9a-f]{64}$/)
    for (const forbidden of ['appId', 'appSecret', 'receiveId', 'messageId', 'body', 'content']) {
      assert.equal(Object.hasOwn(lark, forbidden), false)
    }
    assert.ok(Date.parse(lark.startedAt) <= Date.parse(lark.finishedAt))
  }

  const digest = createHash('sha256')
    .update(JSON.stringify({ protocol, production, restore, lark }))
    .digest('hex')
  console.log(`M6-Q03 evidence passed; aggregate evidence hash ${digest}`)
} catch (error) {
  fail(error instanceof Error ? error.message : 'evidence validation failed')
}

function validateProtocolLoad(load) {
  assert.equal(load.formatVersion, 1)
  assert.equal(load.loadLane, 'FIXTURE')
  assert.equal(load.implementationPath, 'POSTGRESQL_PROTOCOL_LOAD')
  validateCommonLoad(load, false, [
    ['claimSamples', 'claimP95Millis', 'claimHistogram'],
    ['projectionSamples', 'projectionP95Millis', 'projectionHistogram'],
  ])
}

function validateProductionLoad(load, expectedLane) {
  assert.equal(load.formatVersion, 1)
  assert.equal(load.loadLane, expectedLane)
  assert.equal(load.implementationPath, 'PRODUCTION_QUEUE_ACTIVITY_INBOX')
  validateCommonLoad(load, expectedLane === 'CANONICAL', [
    ['readyClaimSamples', 'readyClaimP95Millis', 'readyClaimHistogram'],
    ['activitySamples', 'activityP95Millis', 'activityHistogram'],
    ['inboxSamples', 'inboxP95Millis', 'inboxHistogram'],
  ])
}

function validateCommonLoad(load, canonical, metrics) {
  assert.equal(load.dataset, 'm6-team-beta-v1')
  assert.equal(load.seed, 20260825)
  assert.deepEqual(load.canonicalProfile, {
    webConcurrency: 10,
    taskConcurrency: 2,
    warmupSeconds: 120,
    measurementSeconds: 600,
    repetitions: 3,
    minimumSamplesPerMetric: 500,
    latencyTargetMillisExclusive: 2000,
    maximumErrorRate: 0.001,
  })
  assert.equal(load.measurementRuns.length, 3)
  assert.ok(load.warmup.requests >= 120)
  validateMetrics(load.warmup, load.warmup.requests, metrics)
  if (canonical) {
    assert.equal(load.executionEnvironment.canonicalLinuxAmd64, true)
    assert.ok(load.warmup.elapsedMillis >= load.canonicalProfile.warmupSeconds * 1000)
  }
  for (const [index, run] of load.measurementRuns.entries()) {
    assert.equal(run.repetition, index + 1)
    assert.ok(run.requests >= 500)
    assert.equal(run.errors, 0)
    assert.ok(run.errorRate <= 0.001)
    validateMetrics(run, 500, metrics)
    if (canonical) {
      assert.ok(run.elapsedMillis >= load.canonicalProfile.measurementSeconds * 1000)
    }
  }
}

function validateMetrics(run, minimumSamples, metrics) {
  for (const [samplesKey, p95Key, histogramKey] of metrics) {
    assert.ok(run[samplesKey] >= minimumSamples)
    assert.ok(run[p95Key] < 2000)
    assert.equal(histogramCount(run[histogramKey]), run[samplesKey])
  }
}

function readEvidence(path, label) {
  if (!isAbsolute(path)) throw new Error(`${label} evidence path must be absolute`)
  return JSON.parse(readFileSync(path, 'utf8'))
}

function histogramCount(histogram) {
  return Object.values(histogram).reduce((sum, value) => sum + value, 0)
}

function fail(message) {
  console.error(`M6-Q03 evidence failed: ${message}`)
  process.exit(2)
}
