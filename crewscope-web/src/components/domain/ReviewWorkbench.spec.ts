import { mount } from '@vue/test-utils'
import type { CodingAttemptSummary, EvidencePage, TestEvidenceSummary } from '../../domains/coding/types'
import type { ReviewCommandState } from '../../domains/review/store'
import { etaggedReview, reviewIds, reviewSummary } from '../../test/reviewFixtures'
import ReviewWorkbench from './ReviewWorkbench.vue'

describe('ReviewWorkbench', () => {
  it('keeps SELF_REVIEW Agent Findings advisory and locates exact evidence in Diff', async () => {
    const wrapper = mount(ReviewWorkbench, { props: props() })

    expect(wrapper.text()).toContain('SELF_REVIEW · Advisory only')
    expect(wrapper.text()).toContain('Agent Findings')
    expect(wrapper.text()).toContain('ADVISORY')
    expect(wrapper.text()).toContain('Gate Decision')
    expect(wrapper.text()).not.toContain('Agent 已批准')

    await wrapper.get('.finding-locations button').trigger('click')
    expect(wrapper.emitted('locate')?.[0]?.[0]).toEqual(expect.objectContaining({
      path: 'src/main/java/io/crewscope/Review.java', startLine: 42, endLine: 47,
    }))
  })

  it('submits CHANGES_REQUESTED through the modification command and records a required rationale', async () => {
    const onRequestChanges = vi.fn().mockResolvedValue(true)
    const onDecide = vi.fn().mockResolvedValue(true)
    const wrapper = mount(ReviewWorkbench, { attachTo: document.body, props: props({ onRequestChanges, onDecide }) })

    await wrapper.get('.gate-actions button').trigger('click')
    await wrapper.get<HTMLSelectElement>('.gate-dialog select').setValue('CHANGES_REQUESTED')
    await wrapper.get<HTMLTextAreaElement>('.gate-dialog textarea').setValue('补充配置为空时的回归测试')
    await wrapper.get('.gate-dialog form').trigger('submit')

    expect(onRequestChanges).toHaveBeenCalledWith('补充配置为空时的回归测试')
    expect(onDecide).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('moves focus into the Gate dialog, traps it and restores the opener on Escape', async () => {
    const wrapper = mount(ReviewWorkbench, { attachTo: document.body, props: props() })
    const opener = wrapper.get('.gate-actions button')
    await opener.trigger('click')

    expect(document.activeElement).toBe(wrapper.get<HTMLSelectElement>('.gate-dialog select').element)
    const close = wrapper.get<HTMLButtonElement>('[aria-label="关闭 Gate Decision"]')
    close.element.focus()
    await close.trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(wrapper.get<HTMLButtonElement>('.gate-dialog footer button:last-child').element)
    await wrapper.get('.gate-dialog').trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('.gate-dialog').exists()).toBe(false)
    expect(document.activeElement).toBe(opener.element)
    wrapper.unmount()
  })

  it('disables Gate for ineligible members and closes invalidated Review facts to new commands', () => {
    const invalidated = etaggedReview({
      status: 'INVALIDATED', invalidationReason: 'DIFF_CHANGED',
      decisions: [{
        id: crypto.randomUUID(), revision: 1, type: 'APPROVED', rationale: '旧版本通过',
        reviewerMemberId: crypto.randomUUID(), eligibilityMode: 'STRICT', decidedAt: '2026-08-25T09:00:00Z',
      }],
    })
    const wrapper = mount(ReviewWorkbench, { props: props({ review: invalidated, canGate: false }) })

    expect(wrapper.text()).toContain('旧 Review 已失效')
    expect(wrapper.text()).toContain('DIFF_CHANGED')
    expect(wrapper.text()).toContain('当前成员不持有')
    expect(wrapper.find('.gate-actions button').exists()).toBe(false)
  })

  it('runs or resumes Reviewer only for an active ReviewRequest and keeps empty Review server-owned', async () => {
    const onExecute = vi.fn().mockResolvedValue(true)
    const open = etaggedReview({ status: 'OPEN', version: 1, findings: [], modificationRounds: [] })
    const wrapper = mount(ReviewWorkbench, { props: props({ review: open, onExecute }) })
    await wrapper.get('.gate-actions button').trigger('click')
    expect(onExecute).toHaveBeenCalled()

    const empty = mount(ReviewWorkbench, { props: props({ listPhase: 'empty', reviews: [] }) })
    expect(empty.text()).toContain('浏览器不接受原始 PolicySnapshot ID')
  })
})

function props(overrides: Record<string, unknown> = {}) {
  return {
    listPhase: 'ready' as const,
    reviews: [reviewSummary()],
    selectedReviewRequestId: reviewIds.request,
    detailPhase: 'ready' as const,
    review: etaggedReview(),
    listErrorMessage: null,
    detailErrorMessage: null,
    codingAttempt: codingAttempt(),
    tests: testPage(),
    canGate: true,
    online: true,
    command: idleCommand(),
    onSelect: vi.fn(),
    onRetryList: vi.fn(),
    onRetryDetail: vi.fn(),
    onExecute: vi.fn().mockResolvedValue(true),
    onDecide: vi.fn().mockResolvedValue(true),
    onRequestChanges: vi.fn().mockResolvedValue(true),
    onRetryCommand: vi.fn().mockResolvedValue(true),
    onClearCommand: vi.fn(),
    ...overrides,
  }
}

function idleCommand(): ReviewCommandState {
  return {
    phase: 'idle', operation: null, reviewRequestId: null, receiptCorrelationId: null,
    execution: null, errorMessage: null, errorStatus: null, errorCode: null,
    errorDetails: {}, retryable: false,
  }
}

function codingAttempt(): CodingAttemptSummary {
  return {
    executionId: 'execution', attempt: 2, executionStatus: 'COMPLETED', current: true, coding: true,
    details: {
      executionId: 'execution', attempt: 2,
      workspace: {
        id: 'workspace', repositoryKey: 'crewscope-java', baselineCommit: 'c'.repeat(40),
        managedBranch: 'crewscope/task/review', status: 'COMPLETED', recoveryGeneration: 0,
        completionReason: 'DELIVERED', failureCode: null, fingerprint: 'a'.repeat(64), version: 2,
        retainUntil: '2026-09-25T00:00:00Z', createdAt: '2026-08-25T08:00:00Z', updatedAt: '2026-08-25T09:00:00Z',
      },
      sandbox: null,
      diffManifest: {
        artifactId: reviewIds.diff, generation: 2, manifestHash: '1'.repeat(64), fileCount: 2,
        additions: 24, deletions: 5, baselineCommit: 'c'.repeat(40), deliveryCommit: 'd'.repeat(40),
        finalHash: 'b'.repeat(64), patch: { artifactId: 'patch', kind: 'PATCH', contentType: 'text/x-diff', sizeBytes: 200, contentHash: '2'.repeat(64) },
        files: [], createdAt: '2026-08-25T09:00:00Z',
      },
      codingResult: null, commandEvidenceCount: 2, testEvidenceCount: 1,
    },
  }
}

function testPage(): EvidencePage<TestEvidenceSummary> {
  return {
    items: [{
      id: reviewIds.test, sequence: 1, diffGeneration: 2, diffManifestHash: '1'.repeat(64),
      total: 18, passed: 18, failed: 0, errors: 0, skipped: 0, summary: '全部通过',
      failureClassification: null, evidenceHash: 'e'.repeat(64), commandEvidenceIds: [],
      acceptance: [{ criterionIndex: 0, criterion: '空值分支有回归测试', status: 'PASSED', summary: '验证完成', commandEvidenceIds: [] }],
      testReport: null, createdAt: '2026-08-25T09:00:00Z',
    }],
    nextCursor: null,
  }
}
