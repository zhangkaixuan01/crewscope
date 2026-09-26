import { mount } from '@vue/test-utils'
import type { CodingAttemptSummary, TestEvidenceSummary } from '../../domains/coding/types'
import TaskOutcomeBlock from './TaskOutcomeBlock.vue'

/**
 * M9b-F03 Task-level outcome block: diff facts and the test verdict are stated separately (never
 * one merged green), a coding attempt without a manifest stays honestly empty, and a pinned
 * reference that disagrees with the loaded manifest is said out loud.
 */
function attempt(overrides: Partial<CodingAttemptSummary> = {}): CodingAttemptSummary {
  return {
    executionId: 'exec-1', attempt: 1, executionStatus: 'RUNNING', current: true, coding: true,
    details: {
      executionId: 'exec-1', attempt: 1,
      workspace: {
        id: 'w', repositoryKey: 'org/repo', baselineCommit: 'b', managedBranch: 'refs/heads/crew', status: 'READY',
        recoveryGeneration: 1, completionReason: null, failureCode: null, fingerprint: 'f', version: 1,
        retainUntil: '2026-10-01T00:00:00Z', createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z',
      },
      sandbox: null,
      diffManifest: {
        artifactId: 'a1', generation: 2, manifestHash: 'm'.repeat(64), fileCount: 8, additions: 120, deletions: 30,
        baselineCommit: 'b', deliveryCommit: 'd', finalHash: 'deadbeefcafe',
        patch: { artifactId: 'a1', kind: 'DIFF_PATCH', contentType: 'text/x-diff', sizeBytes: 100, contentHash: 'c'.repeat(64) },
        files: [], createdAt: '2026-09-01T00:00:00Z',
      },
      codingResult: null, commandEvidenceCount: 1, testEvidenceCount: 1,
    },
    ...overrides,
  }
}

function test(overrides: Partial<TestEvidenceSummary> = {}): TestEvidenceSummary {
  return {
    id: 't1', sequence: 1, diffGeneration: 2, diffManifestHash: 'm'.repeat(64),
    total: 24, passed: 24, failed: 0, errors: 0, skipped: 0, summary: 'all green',
    failureClassification: null, evidenceHash: 'e', commandEvidenceIds: [], acceptance: [],
    testReport: null, createdAt: '2026-09-01T00:00:00Z',
    ...overrides,
  }
}

describe('TaskOutcomeBlock', () => {
  it('states the delivered diff facts and the green test verdict separately', () => {
    const wrapper = mount(TaskOutcomeBlock, { props: { attempt: attempt(), latestTest: test() } })
    expect(wrapper.get('[data-testid="task-outcome"]').text()).toContain('本次产出 8 个文件变更（+120/−30）')
    expect(wrapper.get('.outcome-evidence').text()).toContain('deadbeefcafe')
    expect(wrapper.get('.outcome-test').text()).toBe('测试通过 24/24')
    expect(wrapper.get('.outcome-test').classes()).not.toContain('failed')
  })

  it('keeps a failing test verdict red next to the delivery facts', () => {
    const wrapper = mount(TaskOutcomeBlock, { props: { attempt: attempt(), latestTest: test({ passed: 22, failed: 2 }) } })
    expect(wrapper.get('.outcome-test').text()).toContain('测试未通过 22/24')
    expect(wrapper.get('.outcome-test').classes()).toContain('failed')
  })

  it('stays honestly empty for a coding attempt without a manifest, and names non-coding work', () => {
    const noManifest = mount(TaskOutcomeBlock, {
      props: { attempt: attempt({ details: null }), latestTest: null },
    })
    expect(noManifest.get('[data-testid="task-outcome"]').text()).toContain('尚无变更产出')
    expect(noManifest.text()).toContain('测试证据尚未生成')

    const nonCoding = mount(TaskOutcomeBlock, {
      props: { attempt: attempt({ coding: false, details: null, executionStatus: 'COMPLETED' }), latestTest: null },
    })
    expect(nonCoding.get('[data-testid="task-outcome"]').text()).toContain('非编码执行 · 产物以运行时工件为准')
  })

  it('reports a pinned reference that disagrees with the loaded manifest, and stays silent otherwise', () => {
    const mismatch = mount(TaskOutcomeBlock, {
      props: { attempt: attempt(), latestTest: null, pinnedReference: { kind: 'coding-attempt', executionId: 'exec-1', finalHash: 'cafebabe' } },
    })
    expect(mismatch.get('.outcome-mismatch').text()).toContain('证据版本不一致')

    const matching = mount(TaskOutcomeBlock, {
      props: { attempt: attempt(), latestTest: null, pinnedReference: { kind: 'coding-attempt', executionId: 'exec-1', finalHash: 'deadbeefcafe' } },
    })
    expect(matching.find('.outcome-mismatch').exists()).toBe(false)
  })

  it('renders nothing while the coding attempt has not loaded', () => {
    const wrapper = mount(TaskOutcomeBlock, { props: { attempt: null, latestTest: null } })
    expect(wrapper.find('[data-testid="task-outcome"]').exists()).toBe(false)
  })

  it('keeps awaiting decisions and external-delivery attention as their own lines (R11/R13)', () => {
    const wrapper = mount(TaskOutcomeBlock, {
      props: {
        attempt: attempt(), latestTest: test(),
        reviewAwaitingCount: 2, modificationRound: 3,
        deliveryDispatchStatuses: ['UNKNOWN', 'MANUAL_REVIEW', 'SUCCEEDED', ''],
      },
    })
    const lines = wrapper.get('.outcome-awaiting').text()
    expect(lines).toContain('2 项评审待人审（已到第 3 轮要求修改）')
    expect(lines).toContain('外部交付 1 项待对账 · 1 项待人工判定')
  })

  it('stays quiet about delivery and review facts it does not have', () => {
    const wrapper = mount(TaskOutcomeBlock, {
      props: { attempt: attempt(), latestTest: test(), deliveryDispatchStatuses: ['SUCCEEDED'] },
    })
    expect(wrapper.find('.outcome-awaiting').exists()).toBe(false)
  })

  it('turns the evidence anchor into a link to the changes section when a locator is given', async () => {
    const onLocateChanges = vi.fn()
    const wrapper = mount(TaskOutcomeBlock, {
      props: { attempt: attempt(), latestTest: test(), onLocateChanges },
    })
    const anchor = wrapper.get('.evidence-anchor')
    expect(anchor.text()).toContain('deadbeefcafe')
    await anchor.trigger('click')
    expect(onLocateChanges).toHaveBeenCalledOnce()

    const plain = mount(TaskOutcomeBlock, { props: { attempt: attempt(), latestTest: test() } })
    expect(plain.find('.evidence-anchor').exists()).toBe(false)
    expect(plain.get('.outcome-evidence').text()).toContain('deadbeefcafe')
  })
})
