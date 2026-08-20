import { mount } from '@vue/test-utils'
import { execution, runtimeFacts, taskIds } from '../../test/taskFixtures'
import type { CodingAttemptSummary, TestEvidenceSummary } from '../../domains/coding/types'
import CodingProgressControl from './CodingProgressControl.vue'

describe('CodingProgressControl', () => {
  it('projects stages, Todo, public checkpoints and the repair policy without inventing used rounds', () => {
    const wrapper = mount(CodingProgressControl, { props: props() })

    expect(wrapper.get('[aria-label="Coding 阶段"]').text()).toContain('代码变更')
    expect(wrapper.get('[aria-current="step"]').text()).toContain('测试与修复')
    expect(wrapper.text()).toContain('实现详情视图')
    expect(wrapper.text()).toContain('#2 · CONTRACT_READY')
    expect(wrapper.text()).toContain('#2 · checkpoint 2')
    expect(wrapper.text()).toContain('3 轮')
    expect(wrapper.text()).toContain('当前公开事实未单独披露已用修复轮次')
    expect(wrapper.text()).not.toContain('2 / 3')
    expect(wrapper.text()).toContain('修复后仍有一个失败')
    expect(wrapper.text()).not.toContain('stateReference')
    expect(wrapper.text()).not.toContain('checkpointHash')
  })

  it('integrates current-attempt controls and keeps them disabled offline', () => {
    const wrapper = mount(CodingProgressControl, { props: props({ online: false }) })

    expect(wrapper.find('[aria-label="暂停当前 Task"]').exists()).toBe(true)
    expect(wrapper.get('[aria-label="暂停当前 Task"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('当前离线')
  })

  it('makes historical attempts read-only instead of controlling the current attempt', () => {
    const historicalAttempt = codingAttempt()
    historicalAttempt.current = false
    const current = execution()
    current.id = crypto.randomUUID()
    const wrapper = mount(CodingProgressControl, { props: props({ attempt: historicalAttempt, controlAttempt: current }) })

    expect(wrapper.text()).toContain('历史 Attempt 保持只读')
    expect(wrapper.find('.task-control-panel').exists()).toBe(false)
    expect(wrapper.find('[aria-label="暂停当前 Task"]').exists()).toBe(false)
  })
})

function props(overrides: Record<string, unknown> = {}) {
  const controlAttempt = execution()
  controlAttempt.id = taskIds.execution
  controlAttempt.status = 'RUNNING'
  return {
    attempt: codingAttempt(), runtimeFacts: runtimeFacts(), commands: { items: [], nextCursor: null },
    tests: { items: [testEvidence()], nextCursor: null }, controlAttempt, canControl: true, online: true,
    pending: null, errorMessage: null, retryable: false, versionConflict: null,
    onCommand: vi.fn().mockResolvedValue(undefined), onRetryCommand: vi.fn().mockResolvedValue(undefined),
    onClearCommand: vi.fn(), ...overrides,
  }
}

function codingAttempt(): CodingAttemptSummary {
  return {
    executionId: taskIds.execution, attempt: 2, executionStatus: 'RUNNING', current: true, coding: true,
    details: {
      executionId: taskIds.execution, attempt: 2,
      workspace: {
        id: 'workspace-1', repositoryKey: 'crewscope-java', baselineCommit: '1'.repeat(40),
        managedBranch: 'crewscope/task/attempt-2', status: 'ACTIVE', recoveryGeneration: 0,
        completionReason: null, failureCode: null, fingerprint: '2'.repeat(64), version: 1,
        retainUntil: '2026-09-20T01:00:00Z', createdAt: '2026-08-20T01:00:00Z', updatedAt: '2026-08-20T01:01:00Z',
      },
      sandbox: {
        networkMode: 'NONE', cpuCount: 2, memoryMiB: 2048, pids: 256, maxCommandDurationSeconds: 300,
        maxCommandOutputBytes: 1_048_576, readOnlyRootFilesystem: true, maxCommandCalls: 20,
        maxChangedFiles: 100, maxSingleFileBytes: 1_048_576, maxWriteOperations: 200,
        maxWrittenBytes: 5_242_880, maxDiffBytes: 10_485_760, maxTestRepairRounds: 3,
        buildProfileKey: 'maven-java-21', buildProfileVersion: 2,
      },
      diffManifest: {
        artifactId: 'diff-1', generation: 3, manifestHash: '3'.repeat(64), fileCount: 4,
        additions: 20, deletions: 4, baselineCommit: '1'.repeat(40), deliveryCommit: null,
        finalHash: '4'.repeat(64), patch: { artifactId: 'patch-1', kind: 'PATCH', contentType: 'text/x-diff', sizeBytes: 10, contentHash: '5'.repeat(64) },
        files: [], createdAt: '2026-08-20T01:01:00Z',
      },
      codingResult: null, commandEvidenceCount: 3, testEvidenceCount: 3,
    },
  }
}

function testEvidence(): TestEvidenceSummary {
  return {
    id: 'test-3', sequence: 3, diffGeneration: 3, diffManifestHash: '3'.repeat(64), total: 20,
    passed: 19, failed: 1, errors: 0, skipped: 0, summary: '修复后仍有一个失败',
    failureClassification: 'TEST_FAILED', evidenceHash: '6'.repeat(64), commandEvidenceIds: [],
    acceptance: [], testReport: null, createdAt: '2026-08-20T01:02:00Z',
  }
}
