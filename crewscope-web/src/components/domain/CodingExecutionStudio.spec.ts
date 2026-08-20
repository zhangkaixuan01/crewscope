import { mount } from '@vue/test-utils'
import type { CodingAttemptSummary, CommandEvidenceSummary, EvidencePage } from '../../domains/coding/types'
import type { TaskRuntimeFacts } from '../../domains/task/types'
import { execution } from '../../test/taskFixtures'
import CodingExecutionStudio from './CodingExecutionStudio.vue'

describe('CodingExecutionStudio', () => {
  it('shows the immutable baseline, workspace, Coding Agent, current command and bounded resources', () => {
    const wrapper = mount(CodingExecutionStudio, { props: readyProps() })

    expect(wrapper.get('[data-testid="execution-studio"]').text()).toContain('Execution Studio')
    expect(wrapper.text()).toContain('crewscope-java')
    expect(wrapper.text()).toContain('111111111111…')
    expect(wrapper.text()).toContain('Profile v3')
    expect(wrapper.text()).toContain('修改受控文件')
    expect(wrapper.text()).toContain('coding.maven.test')
    expect(wrapper.text()).toContain('2 / 20')
    expect(wrapper.text()).toContain('3 / 100')
    expect(wrapper.text()).toContain('网络 NONE')
    expect(wrapper.text()).not.toContain('/private/worktree')
    expect(wrapper.text()).not.toContain('container-secret')
    expect(wrapper.text()).not.toContain('task-token')
  })

  it('makes recovery generation and terminal retention explicit', async () => {
    const recovering = attempt()
    recovering.details!.workspace.status = 'RECOVERING'
    recovering.details!.workspace.recoveryGeneration = 2
    const wrapper = mount(CodingExecutionStudio, {
      props: { ...readyProps(), attempt: recovering },
    })

    expect(wrapper.get('.studio-state').text()).toContain('Workspace 正在恢复')
    expect(wrapper.get('.studio-state').text()).toContain('恢复代次 2')

    const completed = attempt()
    completed.executionStatus = 'COMPLETED'
    completed.details!.workspace.status = 'COMPLETED'
    completed.details!.workspace.completionReason = 'DELIVERED'
    await wrapper.setProps({ attempt: completed })
    expect(wrapper.get('.studio-state').text()).toContain('Attempt 已进入终态')
    expect(wrapper.get('.studio-state').text()).toContain('DELIVERED')
  })

  it('renders loading, non-Coding and isolated failure states with retry', async () => {
    const onRetry = vi.fn()
    const wrapper = mount(CodingExecutionStudio, {
      props: {
        ...readyProps(), phase: 'loading', attempt: null, commands: null,
        commandsPhase: 'idle', runtimeFacts: null, runtimePhase: 'idle', onRetry,
      },
    })
    expect(wrapper.text()).toContain('正在加载 Coding execution')

    await wrapper.setProps({ phase: 'empty' })
    expect(wrapper.text()).toContain('这是通用 Agent Task')

    await wrapper.setProps({ phase: 'error', errorMessage: 'Coding attempt 读取失败' })
    expect(wrapper.text()).toContain('Coding attempt 读取失败')
    await wrapper.get('button').trigger('click')
    expect(onRetry).toHaveBeenCalledOnce()
  })
})

function readyProps() {
  const controlAttempt = execution()
  controlAttempt.id = '00000000-0000-0000-0000-000000004301'
  controlAttempt.status = 'RUNNING'
  return {
    phase: 'ready' as const,
    attempt: attempt(),
    errorMessage: null,
    commandsPhase: 'ready' as const,
    commands: commands(),
    commandsErrorMessage: null,
    tests: { items: [], nextCursor: null },
    runtimePhase: 'ready' as const,
    runtimeFacts: runtimeFacts(),
    runtimeErrorMessage: null,
    controlAttempt,
    canControl: true,
    online: true,
    commandPending: null,
    commandErrorMessage: null,
    commandRetryable: false,
    commandVersionConflict: null,
    onCommand: vi.fn().mockResolvedValue(undefined),
    onRetryCommand: vi.fn().mockResolvedValue(undefined),
    onClearCommand: vi.fn(),
    onRetry: vi.fn(),
  }
}

function attempt(): CodingAttemptSummary {
  return {
    executionId: '00000000-0000-0000-0000-000000004301',
    attempt: 2,
    executionStatus: 'RUNNING',
    current: true,
    coding: true,
    details: {
      executionId: '00000000-0000-0000-0000-000000004301',
      attempt: 2,
      workspace: {
        id: '00000000-0000-0000-0000-000000004401', repositoryKey: 'crewscope-java',
        baselineCommit: '1'.repeat(40), managedBranch: 'crewscope/tasks/crw-18/attempt-2',
        status: 'ACTIVE', recoveryGeneration: 0, completionReason: null, failureCode: null,
        fingerprint: '2'.repeat(64), version: 2, retainUntil: '2026-09-20T01:00:00Z',
        createdAt: '2026-08-20T01:00:00Z', updatedAt: '2026-08-20T01:02:00Z',
      },
      sandbox: {
        networkMode: 'NONE', cpuCount: 2, memoryMiB: 2048, pids: 256,
        maxCommandDurationSeconds: 300, maxCommandOutputBytes: 1_048_576,
        readOnlyRootFilesystem: true, maxCommandCalls: 20, maxChangedFiles: 100,
        maxSingleFileBytes: 1_048_576, maxWriteOperations: 200, maxWrittenBytes: 5_242_880,
        maxDiffBytes: 10_485_760, maxTestRepairRounds: 3,
        buildProfileKey: 'maven-java-21', buildProfileVersion: 2,
      },
      diffManifest: {
        artifactId: 'diff', generation: 1, manifestHash: '3'.repeat(64), fileCount: 3,
        additions: 14, deletions: 2, baselineCommit: '1'.repeat(40), deliveryCommit: null,
        finalHash: '4'.repeat(64), patch: artifact('PATCH'), files: [], createdAt: '2026-08-20T01:02:00Z',
      },
      codingResult: null,
      commandEvidenceCount: 2,
      testEvidenceCount: 1,
    },
  }
}

function commands(): EvidencePage<CommandEvidenceSummary> {
  return {
    items: [{
      id: 'command-2', sequence: 2, commandKind: 'TEST', toolKey: 'coding.maven.test',
      timeoutSeconds: 120, startedAt: '2026-08-20T01:01:00Z', finishedAt: '2026-08-20T01:02:00Z',
      termination: 'EXITED', exitCode: 0, summary: '全部测试通过', failureClassification: null,
      evidenceHash: '5'.repeat(64), commandLog: artifact('COMMAND_LOG'),
    }],
    nextCursor: null,
  }
}

function runtimeFacts(): TaskRuntimeFacts {
  return {
    execution: { currentPlanVersionId: 'plan-2' },
    planVersions: [{
      id: 'plan-2', revision: 2, parentVersionId: 'plan-1', changeReason: '补充测试',
      markdown: '修改并验证。',
      steps: [{ key: 'edit', title: '修改受控文件', description: '只修改 AllowedPaths', specialistKey: 'coding', requiredCapabilities: [], successCriteria: [], dependsOn: [] }],
      todoSummary: [], publishedByPrincipalId: 'principal', publishedAt: '2026-08-20T01:00:00Z',
    }],
    steps: [{
      id: 'step-1', planVersionId: 'plan-2', planStepKey: 'edit', sequence: 1, critical: true,
      runAttempt: 1, maxRunAttempts: 2, status: 'RUNNING', waitReason: null, checkpoint: null,
      failureClass: null, failureCode: null, version: 1, audit: {},
    }],
    sessions: [],
    agentRuns: [{
      id: 'run-1', stepExecutionId: 'step-1', runtimeSessionId: 'session-public', agentPrincipalId: 'agent',
      agentProfileId: 'profile', agentProfileVersion: 3, runSequence: 1, status: 'RUNNING',
      segments: [], continuityGap: null, version: 1, audit: {},
    }],
    interrupts: [], snapshots: [], leases: [],
  } as unknown as TaskRuntimeFacts
}

function artifact(kind: string) {
  return { artifactId: `${kind.toLowerCase()}-artifact`, kind, contentType: 'text/plain', sizeBytes: 10, contentHash: '6'.repeat(64) }
}
