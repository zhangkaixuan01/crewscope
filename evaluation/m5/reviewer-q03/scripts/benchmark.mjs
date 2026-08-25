#!/usr/bin/env node

import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))
const assetDirectory = path.resolve(scriptDirectory, '..')

export async function loadAssets() {
  const [protocol, suite, systemPrompt] = await Promise.all([
    readJson(path.join(assetDirectory, 'protocol.json')),
    readJson(path.join(assetDirectory, 'suite.json')),
    readFile(path.join(assetDirectory, 'system-prompt.md'), 'utf8'),
  ])
  return { protocol, suite, systemPrompt }
}

export function validateAssets(protocol, suite, systemPrompt) {
  assert.equal(protocol.schemaVersion, 'crewscope.reviewer-benchmark-protocol/v1')
  assert.equal(protocol.protocolId, 'm5-q03-reviewer-quality-baseline')
  assert.equal(protocol.template, 'reviewer@1')
  assert.equal(protocol.systemPrompt, 'system-prompt.md')
  assert.equal(protocol.promptPolicy, 'context-package-v1')
  assert.deepEqual(protocol.skillKeys, [])
  assert.deepEqual(protocol.toolNames, [])
  assert.equal(protocol.modelRunsPerCase, 1)
  assert.deepEqual(protocol.realModel, {
    provider: 'deepseek',
    modelId: 'deepseek-v4-flash',
    modelRevision: 'DeepSeek-V4-Flash-0731',
  })
  assert.match(systemPrompt, /ReviewFindingListV1 advisory findings/)
  assert.match(systemPrompt, /Never approve, reject/)

  const gate = protocol.qualityGate
  for (const field of [
    'minimumStructuredOutputRate',
    'minimumDefectRecall',
    'minimumCleanSpecificity',
    'minimumEvidenceValidity',
    'minimumCategoryAccuracy',
    'minimumSeverityAccuracy',
  ]) {
    assert.equal(typeof gate[field], 'number')
    assert.ok(gate[field] >= 0 && gate[field] <= 1, `${field} must be a ratio`)
  }
  assert.equal(gate.maximumGateDecisionViolations, 0)

  assert.equal(suite.schemaVersion, 'crewscope.reviewer-quality-suite/v1')
  assert.equal(suite.suiteId, 'crewscope-java-reviewer')
  assert.equal(suite.cases.length, 12)
  assert.equal(suite.cases.filter((sample) => sample.defect).length, 8)
  assert.equal(suite.cases.filter((sample) => !sample.defect).length, 4)
  assert.equal(new Set(suite.cases.map((sample) => sample.id)).size, suite.cases.length)

  for (const sample of suite.cases) {
    assert.match(sample.id, /^[a-z0-9-]+$/)
    assert.ok(sample.canonicalPath.startsWith('src/main/java/'))
    assert.ok(!sample.canonicalPath.includes('..'))
    assert.ok(Number.isInteger(sample.startLine) && sample.startLine > 0)
    assert.ok(Number.isInteger(sample.endLine) && sample.endLine >= sample.startLine)
    assert.ok(typeof sample.patch === 'string' && sample.patch.length > 0)
    assert.ok(typeof sample.acceptanceCriterion === 'string'
      && sample.acceptanceCriterion.length > 0)
    assert.ok(['PASSED', 'FAILED'].includes(sample.acceptanceStatus))
    assert.equal(sample.defect, sample.acceptanceStatus === 'FAILED')
    assert.ok(Array.isArray(sample.expectedCategories))
    assert.ok(Array.isArray(sample.expectedSeverities))
    assert.equal(sample.expectedCategories.length > 0, sample.defect)
    assert.equal(sample.expectedSeverities.length > 0, sample.defect)
  }
}

export function scoreRuns(protocol, suite, runs) {
  assert.equal(runs.length, suite.cases.length * protocol.modelRunsPerCase)
  const samples = new Map(suite.cases.map((sample) => [sample.id, sample]))
  const expectedKeys = new Set()
  for (const sample of suite.cases) {
    for (let repetition = 1; repetition <= protocol.modelRunsPerCase; repetition += 1) {
      expectedKeys.add(`${sample.id}:${repetition}`)
    }
  }
  const observedKeys = new Set()
  for (const run of runs) {
    assert.ok(samples.has(run.caseId), `Unknown case ${run.caseId}`)
    const key = `${run.caseId}:${run.repetition}`
    assert.ok(expectedKeys.has(key), `Unexpected matrix coordinate ${key}`)
    assert.ok(!observedKeys.has(key), `Duplicate matrix coordinate ${key}`)
    observedKeys.add(key)
  }
  assert.deepEqual(observedKeys, expectedKeys)

  const defectRuns = runs.filter((run) => samples.get(run.caseId).defect)
  const cleanRuns = runs.filter((run) => !samples.get(run.caseId).defect)
  const findingRuns = runs.filter((run) => run.findingCount > 0)
  const ratio = (value, total) => total === 0 ? 1 : value / total
  const metrics = {
    structuredOutputRate: ratio(runs.filter((run) => run.structuredOutput).length, runs.length),
    defectRecall: ratio(defectRuns.filter((run) => run.defectDetected).length, defectRuns.length),
    cleanSpecificity: ratio(cleanRuns.filter((run) => run.findingCount === 0).length, cleanRuns.length),
    evidenceValidity: ratio(
      runs.reduce((sum, run) => sum + run.validEvidenceCount, 0),
      runs.reduce((sum, run) => sum + run.evidenceCount, 0),
    ),
    categoryAccuracy: ratio(
      findingRuns.filter((run) => run.categoryMatched).length,
      findingRuns.length,
    ),
    severityAccuracy: ratio(
      findingRuns.filter((run) => run.severityMatched).length,
      findingRuns.length,
    ),
    gateDecisionViolations: runs.reduce(
      (sum, run) => sum + run.gateDecisionViolations,
      0,
    ),
  }
  const gate = protocol.qualityGate
  const passed = metrics.structuredOutputRate >= gate.minimumStructuredOutputRate
    && metrics.defectRecall >= gate.minimumDefectRecall
    && metrics.cleanSpecificity >= gate.minimumCleanSpecificity
    && metrics.evidenceValidity >= gate.minimumEvidenceValidity
    && metrics.categoryAccuracy >= gate.minimumCategoryAccuracy
    && metrics.severityAccuracy >= gate.minimumSeverityAccuracy
    && metrics.gateDecisionViolations <= gate.maximumGateDecisionViolations
  return { metrics, passed }
}

async function readJson(file) {
  return JSON.parse(await readFile(file, 'utf8'))
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const command = process.argv[2] ?? 'validate'
  if (command !== 'validate') {
    throw new Error(`Unknown command: ${command}`)
  }
  const { protocol, suite, systemPrompt } = await loadAssets()
  validateAssets(protocol, suite, systemPrompt)
  process.stdout.write(
    `M5-Q03 assets valid: ${suite.cases.length} cases, `
      + `${suite.cases.filter((sample) => sample.defect).length} defects, `
      + `${suite.cases.filter((sample) => !sample.defect).length} clean.\n`,
  )
}
