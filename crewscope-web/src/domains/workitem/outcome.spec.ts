import { parseResultReference, referenceMatchesManifest, resolveReferenceTask, resolveWorkItemOutcome } from './outcome'
import type { DiffManifestSummary } from '../coding/types'
import type { WorkItemExecutionSummary } from './types'
import type { ResultReference } from './outcome'

const codingRef = (finalHash: string): ResultReference => ({ kind: 'coding-attempt', executionId: 'e', finalHash })

/**
 * M9b-F03 outcome judgement (contract §4.1 / R11): the states are shown separately and never
 * merged into one green completion, the judgement order is contract-fixed, and a reference the
 * caller cannot safely resolve stays raw instead of becoming a guessed navigation.
 */

function summary(overrides: Partial<WorkItemExecutionSummary> = {}): WorkItemExecutionSummary {
  return {
    workItemId: '00000000-0000-0000-0000-000000000011',
    workItemVersion: 3,
    workStatus: 'IN_PROGRESS',
    taskCount: 1,
    activeTaskCount: 1,
    pendingReviewCount: 0,
    currentTaskId: '00000000-0000-0000-0000-000000000021',
    currentExecutionId: '00000000-0000-0000-0000-000000000031',
    executionStatus: 'RUNNING',
    selectionRequired: false,
    blockedReasons: [],
    resultSummary: null,
    resultSourceReference: null,
    projectionVersion: 1,
    observedAt: '2026-09-01T00:00:00Z',
    ...overrides,
  }
}

describe('parseResultReference', () => {
  it('parses both server shapes and keeps malformed text unparsed', () => {
    expect(parseResultReference('coding-attempt:00000000-0000-0000-0000-000000000031@ab12')).toEqual({
      kind: 'coding-attempt',
      executionId: '00000000-0000-0000-0000-000000000031',
      finalHash: 'ab12',
    })
    expect(parseResultReference('runtime-artifact:00000000-0000-0000-0000-000000000041')).toEqual({
      kind: 'runtime-artifact',
      artifactId: '00000000-0000-0000-0000-000000000041',
    })
    expect(parseResultReference(null)).toBeNull()
    expect(parseResultReference('')).toBeNull()
    expect(parseResultReference('coding-attempt:missing-hash')).toBeNull()
    expect(parseResultReference('runtime-artifact:')).toBeNull()
    expect(parseResultReference('an external PR link')).toBeNull()
  })
})

describe('resolveWorkItemOutcome', () => {
  const delivered = summary({
    resultSummary: '已交付 8 个文件变更（+120/−30），测试通过',
    resultSourceReference: 'coding-attempt:00000000-0000-0000-0000-000000000031@final',
  })

  it.each([
    ['DELIVERED wins over a pending decision and running work', delivered, 'DELIVERED'],
    ['a pending review decision outranks running work', summary({ pendingReviewCount: 2 }), 'AWAITING_DECISION'],
    ['running work reports WAITING with its honest fact', summary(), 'WAITING'],
    ['no tasks at all is NOT_STARTED', summary({ taskCount: 0, activeTaskCount: 0, currentTaskId: null, currentExecutionId: null, executionStatus: null }), 'NOT_STARTED'],
    ['finished tasks without a result stay honestly unsummarised', summary({ activeTaskCount: 0, executionStatus: 'COMPLETED' }), 'NO_SUMMARY'],
  ])('%s', (_name, input, expected) => {
    expect(resolveWorkItemOutcome(input)?.state).toBe(expected)
  })

  it('marks a delivered-but-failing test verdict independently of the delivery', () => {
    const outcome = resolveWorkItemOutcome(summary({
      resultSummary: '已交付 3 个文件变更（+9/−1），测试未通过',
      resultSourceReference: 'coding-attempt:00000000-0000-0000-0000-000000000031@final',
    }))
    expect(outcome?.state).toBe('DELIVERED')
    expect(outcome?.testsFailed).toBe(true)
    expect(resolveWorkItemOutcome(delivered)?.testsFailed).toBe(false)
  })

  it('keeps a delivered result even when its reference text is malformed', () => {
    const outcome = resolveWorkItemOutcome(summary({ resultSummary: '已交付 1 个文件变更（+2/−0）', resultSourceReference: '外部系统引用' }))
    expect(outcome?.state).toBe('DELIVERED')
    expect(outcome?.reference).toBeNull()
  })

  it('returns null while the summary read is still in flight', () => {
    expect(resolveWorkItemOutcome(null)).toBeNull()
  })

  it('carries the waiting facts through for the panel to phrase', () => {
    const blocked = { code: 'REVIEW', taskId: '00000000-0000-0000-0000-000000000021', executionId: '00000000-0000-0000-0000-000000000031', since: '2026-09-01T00:00:00Z', waitingOnPrincipalId: '00000000-0000-0000-0000-000000000001' }
    const outcome = resolveWorkItemOutcome(summary({ blockedReasons: [blocked], executionStatus: 'WAITING' }))
    expect(outcome?.blockedReasons).toEqual([blocked])
    expect(outcome?.executionStatus).toBe('WAITING')
  })
})

describe('resolveReferenceTask', () => {
  const reference = { kind: 'coding-attempt', executionId: '00000000-0000-0000-0000-000000000031', finalHash: 'f' } as const

  it('opens the current task for the current execution', () => {
    expect(resolveReferenceTask(reference, summary())).toBe('00000000-0000-0000-0000-000000000021')
  })

  it('opens the only task for a historical execution of a single-task WorkItem', () => {
    const single = summary({ currentExecutionId: '00000000-0000-0000-0000-000000000099' })
    expect(resolveReferenceTask(reference, single)).toBe('00000000-0000-0000-0000-000000000021')
  })

  it('refuses to guess across parallel tasks or without a current task', () => {
    const parallel = summary({ taskCount: 2, currentExecutionId: '00000000-0000-0000-0000-000000000099' })
    expect(resolveReferenceTask(reference, parallel)).toBeNull()
    expect(resolveReferenceTask(reference, summary({ currentTaskId: null }))).toBeNull()
  })

  it('never maps a runtime-artifact reference onto a task', () => {
    expect(resolveReferenceTask({ kind: 'runtime-artifact', artifactId: 'a' }, summary())).toBeNull()
  })
})

describe('referenceMatchesManifest', () => {
  const manifest = { finalHash: 'deadbeef' } as DiffManifestSummary

  it('compares only a coding reference against a loaded manifest', () => {
    expect(referenceMatchesManifest(codingRef('deadbeef'), manifest)).toBe(true)
    expect(referenceMatchesManifest(codingRef('cafebabe'), manifest)).toBe(false)
  })

  it('stays silent when the comparison is not possible', () => {
    expect(referenceMatchesManifest(null, manifest)).toBeNull()
    expect(referenceMatchesManifest(codingRef('x'), null)).toBeNull()
    expect(referenceMatchesManifest({ kind: 'runtime-artifact', artifactId: 'a' }, manifest)).toBeNull()
  })
})
