<script setup lang="ts">
import '../design/tokens.css'
import '../design/base.css'
import type { CodingAttemptSummary, CommandEvidenceSummary, EvidencePage } from '../domains/coding/types'
import { execution, runtimeFacts, taskIds } from '../test/taskFixtures'
import CodingExecutionStudio from './domain/CodingExecutionStudio.vue'

const currentExecution = execution()
currentExecution.status = 'RUNNING'

const currentAttempt = codingAttempt()
const recoveringAttempt = structuredClone(currentAttempt)
recoveringAttempt.details!.workspace.status = 'RECOVERING'
recoveringAttempt.details!.workspace.recoveryGeneration = 2

const historicalAttempt = structuredClone(currentAttempt)
historicalAttempt.current = false
historicalAttempt.executionStatus = 'COMPLETED'
historicalAttempt.details!.workspace.status = 'COMPLETED'
historicalAttempt.details!.workspace.completionReason = 'DELIVERED'

const noop = (): void => {}
const asyncNoop = async (): Promise<void> => {}
const readyProps = {
  phase: 'ready' as const,
  attempt: currentAttempt,
  errorMessage: null,
  commandsPhase: 'ready' as const,
  commands: commandEvidence(),
  commandsErrorMessage: null,
  tests: { items: [], nextCursor: null },
  runtimePhase: 'ready' as const,
  runtimeFacts: runtimeFacts(),
  runtimeErrorMessage: null,
  controlAttempt: currentExecution,
  canControl: true,
  online: true,
  commandPending: null,
  commandErrorMessage: null,
  commandRetryable: false,
  commandVersionConflict: null,
  onCommand: asyncNoop,
  onRetryCommand: asyncNoop,
  onClearCommand: noop,
  onRetry: noop,
}
const loadingProps = { ...readyProps, phase: 'loading' as const, attempt: null, commands: null, commandsPhase: 'idle' as const, runtimeFacts: null, runtimePhase: 'idle' as const, controlAttempt: null }
const emptyProps = { ...loadingProps, phase: 'empty' as const }
const errorProps = { ...loadingProps, phase: 'error' as const, errorMessage: 'Coding attempt 暂时不可用，请刷新服务端事实。' }
const recoveringProps = { ...readyProps, attempt: recoveringAttempt }
const historicalProps = { ...readyProps, attempt: historicalAttempt, controlAttempt: null }
const offlineProps = { ...readyProps, online: false }

function codingAttempt(): CodingAttemptSummary {
  return {
    executionId: taskIds.execution,
    attempt: 2,
    executionStatus: 'RUNNING',
    current: true,
    coding: true,
    details: {
      executionId: taskIds.execution,
      attempt: 2,
      workspace: {
        id: '00000000-0000-0000-0000-000000004401',
        repositoryKey: 'crewscope-java',
        baselineCommit: '1'.repeat(40),
        managedBranch: 'crewscope/tasks/crw-18/attempt-2',
        status: 'ACTIVE',
        recoveryGeneration: 0,
        completionReason: null,
        failureCode: null,
        fingerprint: '2'.repeat(64),
        version: 2,
        retainUntil: '2026-09-20T01:00:00Z',
        createdAt: '2026-08-20T01:00:00Z',
        updatedAt: '2026-08-20T01:02:00Z',
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
        artifactId: 'diff-artifact', generation: 3, manifestHash: '3'.repeat(64), fileCount: 4,
        additions: 28, deletions: 6, baselineCommit: '1'.repeat(40), deliveryCommit: null,
        finalHash: '4'.repeat(64), patch: artifact('PATCH'), files: [], createdAt: '2026-08-20T01:02:00Z',
      },
      codingResult: null,
      commandEvidenceCount: 5,
      testEvidenceCount: 2,
    },
  }
}

function commandEvidence(): EvidencePage<CommandEvidenceSummary> {
  return {
    items: [{
      id: 'command-5', sequence: 5, commandKind: 'TEST', toolKey: 'coding.maven.test',
      timeoutSeconds: 300, startedAt: '2026-08-20T01:01:00Z', finishedAt: '2026-08-20T01:02:00Z',
      termination: 'EXITED', exitCode: 0, summary: '230 项测试通过', failureClassification: null,
      evidenceHash: '5'.repeat(64), commandLog: artifact('COMMAND_LOG'),
    }],
    nextCursor: null,
  }
}

function artifact(kind: string) {
  return { artifactId: `${kind.toLowerCase()}-artifact`, kind, contentType: 'text/plain', sizeBytes: 10, contentHash: '6'.repeat(64) }
}
</script>

<template>
  <Story title="Coding/Execution Studio" :layout="{ type: 'grid', width: 1040 }">
    <Variant title="Ready · current control"><div class="studio-story"><CodingExecutionStudio v-bind="readyProps" /></div></Variant>
    <Variant title="Recovering"><div class="studio-story"><CodingExecutionStudio v-bind="recoveringProps" /></div></Variant>
    <Variant title="Terminal · historical read-only"><div class="studio-story"><CodingExecutionStudio v-bind="historicalProps" /></div></Variant>
    <Variant title="Offline control"><div class="studio-story"><CodingExecutionStudio v-bind="offlineProps" /></div></Variant>
    <Variant title="Loading"><div class="studio-story"><CodingExecutionStudio v-bind="loadingProps" /></div></Variant>
    <Variant title="Non-Coding empty"><div class="studio-story"><CodingExecutionStudio v-bind="emptyProps" /></div></Variant>
    <Variant title="Error"><div class="studio-story"><CodingExecutionStudio v-bind="errorProps" /></div></Variant>
  </Story>
</template>

<style scoped>
.studio-story { min-height: 760px; padding: 24px; background: var(--cs-canvas); font-family: var(--cs-font-sans); }
</style>
