<script setup lang="ts">
import '../design/tokens.css'
import '../design/base.css'
import OperationsWorkspace from './domain/OperationsWorkspace.vue'
import type { AdministratorDiagnostics, OperationsHealthSummary, ProjectionDiagnostic, RecoveryCandidate } from '../domains/teamops/types'

const health: OperationsHealthSummary = {
  observedAt: '2026-08-27T08:30:00Z', health: 'DEGRADED',
  components: ['PROJECTION', 'OUTBOX', 'DEAD_LETTER', 'CURSOR', 'NOTIFICATION'].map((component, index) => ({
    component: component as OperationsHealthSummary['components'][number]['component'], health: index === 0 ? 'DEGRADED' : 'HEALTHY',
    backlog: index === 0 ? 4 : 0, inFlight: index === 1 ? 1 : 0, failures: 0, affected: index === 0 ? 2 : 0,
    oldestOutstandingAgeSeconds: index === 0 ? 28 : 0, stale: false,
  })),
}
const projection: ProjectionDiagnostic = {
  projectionName: 'team-activity', definitionVersion: 2, activeGeneration: 4, pointerVersion: 7, activeGenerationVersion: 9,
  shadowGeneration: 5, shadowStatus: 'VALIDATING', shadowGenerationVersion: 3,
  rebuildJobId: '00000000-0000-4000-8000-000000000001', rebuildJobVersion: 2,
  lagSeconds: 4, gapCount: 0, deadLetterCount: 1, latestFailureCode: null,
  startConfirmation: 'START REBUILD team-activity', validateConfirmation: 'VALIDATE team-activity 5', switchConfirmation: 'SWITCH team-activity 5',
  cancelConfirmation: 'CANCEL team-activity 5', failConfirmation: 'FAIL team-activity 5',
}
const candidate: RecoveryCandidate = {
  type: 'OUTBOX_DEAD_LETTER', action: 'REPLAY_OUTBOX_DEAD_LETTER', outboxEventId: '00000000-0000-4000-8000-000000000002',
  domainEventId: '00000000-0000-4000-8000-000000000003', expectedVersion: 6, referenceHash: 'a'.repeat(64),
  confirmation: 'REPLAY OUTBOX 00000000-0000-4000-8000-000000000002:6',
}
const diagnostics: AdministratorDiagnostics = { summary: health, projections: [projection], recoveryCandidates: [candidate] }
const idleCommand = { phase: 'idle', operation: null, targetId: null, receipt: null, error: null } as const
const serviceError = { kind: 'unavailable', message: '运行诊断暂不可用', status: 503, retryable: true, currentVersion: null } as const
const conflictCommand = { phase: 'conflict', operation: 'projection-switch', targetId: 'team-activity', receipt: null, error: { kind: 'conflict', message: 'Projection 指针版本已变化', status: 409, retryable: false, currentVersion: 8 } } as const
</script>

<template>
  <Story title="M6/Operations" :layout="{ type: 'grid', width: 1180 }">
    <Variant title="Member health"><OperationsWorkspace phase="ready" :error="null" :health="health" diagnostics-phase="idle" :diagnostics-error="null" :diagnostics="null" :command="idleCommand" :can-manage="false" :online="true" /></Variant>
    <Variant title="Administrator"><OperationsWorkspace phase="ready" :error="null" :health="health" diagnostics-phase="ready" :diagnostics-error="null" :diagnostics="diagnostics" :command="idleCommand" :can-manage="true" :online="true" /></Variant>
    <Variant title="Offline cached"><OperationsWorkspace phase="ready" :error="null" :health="health" diagnostics-phase="ready" :diagnostics-error="null" :diagnostics="diagnostics" :command="idleCommand" :can-manage="true" :online="false" /></Variant>
    <Variant title="Loading"><OperationsWorkspace phase="loading" :error="null" :health="null" diagnostics-phase="idle" :diagnostics-error="null" :diagnostics="null" :command="idleCommand" :can-manage="false" :online="true" /></Variant>
    <Variant title="Initial error"><OperationsWorkspace phase="error" :error="serviceError" :health="null" diagnostics-phase="idle" :diagnostics-error="null" :diagnostics="null" :command="idleCommand" :can-manage="false" :online="true" /></Variant>
    <Variant title="Diagnostics error"><OperationsWorkspace phase="ready" :error="null" :health="health" diagnostics-phase="error" :diagnostics-error="serviceError" :diagnostics="null" :command="idleCommand" :can-manage="true" :online="true" /></Variant>
    <Variant title="Empty projections"><OperationsWorkspace phase="ready" :error="null" :health="health" diagnostics-phase="ready" :diagnostics-error="null" :diagnostics="{ ...diagnostics, projections: [] }" :command="idleCommand" :can-manage="true" :online="true" /></Variant>
    <Variant title="Empty recovery"><OperationsWorkspace phase="ready" :error="null" :health="health" diagnostics-phase="ready" :diagnostics-error="null" :diagnostics="{ ...diagnostics, recoveryCandidates: [] }" :command="idleCommand" :can-manage="true" :online="true" /></Variant>
    <Variant title="Command conflict"><OperationsWorkspace phase="ready" :error="null" :health="health" diagnostics-phase="ready" :diagnostics-error="null" :diagnostics="diagnostics" :command="conflictCommand" :can-manage="true" :online="true" /></Variant>
  </Story>
</template>
