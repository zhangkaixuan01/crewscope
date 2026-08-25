import assert from 'node:assert/strict'
import test from 'node:test'

import { loadAssets, scoreRuns, validateAssets } from './benchmark.mjs'

const { protocol, suite, systemPrompt } = await loadAssets()

test('freezes the reviewer quality protocol and 8/4 corpus', () => {
  validateAssets(protocol, suite, systemPrompt)
})

test('accepts a complete quality matrix at every threshold', () => {
  const runs = suite.cases.map((sample) => ({
    caseId: sample.id,
    repetition: 1,
    structuredOutput: true,
    findingCount: sample.defect ? 1 : 0,
    defectDetected: sample.defect,
    evidenceCount: sample.defect ? 1 : 0,
    validEvidenceCount: sample.defect ? 1 : 0,
    categoryMatched: sample.defect,
    severityMatched: sample.defect,
    gateDecisionViolations: 0,
  }))

  const result = scoreRuns(protocol, suite, runs)

  assert.equal(result.passed, true)
  assert.equal(result.metrics.defectRecall, 1)
  assert.equal(result.metrics.cleanSpecificity, 1)
})

test('rejects a duplicate or missing matrix coordinate', () => {
  const runs = suite.cases.map((sample) => passingRun(sample))
  runs[1] = { ...runs[0] }

  assert.throws(() => scoreRuns(protocol, suite, runs), /Duplicate matrix coordinate/)
})

test('rejects quality below recall, evidence or Gate authority thresholds', () => {
  const runs = suite.cases.map((sample) => passingRun(sample))
  for (const run of runs.filter((value) => value.defectDetected).slice(0, 3)) {
    run.defectDetected = false
    run.findingCount = 0
    run.evidenceCount = 0
    run.validEvidenceCount = 0
    run.categoryMatched = false
    run.severityMatched = false
  }
  runs[3].gateDecisionViolations = 1

  assert.equal(scoreRuns(protocol, suite, runs).passed, false)
})

function passingRun(sample) {
  return {
    caseId: sample.id,
    repetition: 1,
    structuredOutput: true,
    findingCount: sample.defect ? 1 : 0,
    defectDetected: sample.defect,
    evidenceCount: sample.defect ? 1 : 0,
    validEvidenceCount: sample.defect ? 1 : 0,
    categoryMatched: sample.defect,
    severityMatched: sample.defect,
    gateDecisionViolations: 0,
  }
}
