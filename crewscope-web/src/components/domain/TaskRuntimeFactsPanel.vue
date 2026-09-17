<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Activity, Bot, Cpu, GitBranch, Layers3, RefreshCw, ShieldCheck, TriangleAlert } from '@lucide/vue'
import type { TaskPhase } from '../../domains/task/store'
import type { PlanVersion, TaskRuntimeFacts } from '../../domains/task/types'
import type { SemanticTone } from '../base/types'
import { enumLabel } from '../../domains/shared/labels'
import {
  agentInterruptKindLabels, agentInterruptStatusLabels, agentRunContinuityGapReasonLabels,
  agentRunStatusLabels, agentRuntimeSessionStatusLabels, agentStateSnapshotStatusLabels,
  executionLeasePhaseLabels, executionLeaseReleaseReasonLabels, executionLeaseStatusLabels,
  planChangeReasonLabels, runtimeFleetHealthLabels, runtimeWaitCauseLabels,
  stepExecutionStatusLabels, stepWaitReasonLabels, taskExecutionStatusLabels, todoStatusLabels,
} from '../../domains/task/labels'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'

const props = defineProps<{
  phase: TaskPhase
  facts: TaskRuntimeFacts | null
  errorMessage: string | null
  displayDate: (value: string) => string
  factTone: (value: string) => SemanticTone
  onRetry: () => void
}>()

const selectedPlanVersionId = ref<string | null>(null)
const orderedPlans = computed(() => [...(props.facts?.planVersions ?? [])].sort((left, right) => right.revision - left.revision))
const selectedPlan = computed<PlanVersion | null>(() => {
  const plans = orderedPlans.value
  return plans.find(item => item.id === selectedPlanVersionId.value)
    ?? plans.find(item => item.id === props.facts?.execution.currentPlanVersionId)
    ?? plans[0]
    ?? null
})
const selectedPlanSteps = computed(() => {
  const planId = selectedPlan.value?.id
  return [...(props.facts?.steps ?? [])].filter(step => !planId || step.planVersionId === planId).sort((left, right) => left.sequence - right.sequence)
})
const completedSteps = computed(() => selectedPlanSteps.value.filter(step => step.status === 'COMPLETED').length)

watch(
  () => [props.facts?.execution.currentPlanVersionId, orderedPlans.value[0]?.id] as const,
  ([currentPlanVersionId, newestPlanVersionId]) => { selectedPlanVersionId.value = currentPlanVersionId ?? newestPlanVersionId ?? null },
  { immediate: true },
)

function label(value: string | null | undefined, labels: Record<string, string>): string { return enumLabel(value, labels) }
</script>

<template>
  <div class="task-runtime-panel">
    <StatePanel v-if="phase === 'loading' || phase === 'idle'" state="loading" title="正在加载 Attempt Runtime 事实" />
    <StatePanel v-else-if="phase === 'error' && !facts" state="error" :description="errorMessage ?? undefined" @retry="onRetry" />
    <template v-else-if="facts">
      <section class="detail-card plan-card">
        <div class="section-heading"><div><p>Durable plan</p><h3>执行计划</h3></div><GitBranch :size="17" /></div>
        <label v-if="orderedPlans.length > 1" class="plan-selector"><span>计划版本</span><select v-model="selectedPlanVersionId"><option v-for="plan in orderedPlans" :key="plan.id" :value="plan.id">Revision {{ plan.revision }} · {{ label(plan.changeReason, planChangeReasonLabels) }}</option></select></label>
        <template v-if="selectedPlan">
          <div class="plan-meta"><StatusBadge tone="info">Revision {{ selectedPlan.revision }}</StatusBadge><span>{{ displayDate(selectedPlan.publishedAt) }}</span></div>
          <p class="plan-markdown">{{ selectedPlan.markdown }}</p>
          <ul v-if="selectedPlan.todoSummary.length" class="todo-summary"><li v-for="todo in selectedPlan.todoSummary" :key="`${todo.planStepKey}:${todo.content}`"><StatusBadge :tone="factTone(todo.status)">{{ label(todo.status, todoStatusLabels) }}</StatusBadge><span>{{ todo.content }}</span></li></ul>
        </template>
        <p v-else class="empty-note">这个 attempt 尚未发布 PlanVersion。</p>
      </section>

      <section class="detail-card steps-card">
        <div class="section-heading"><div><p>Step progress</p><h3>步骤进度 <span>{{ completedSteps }}/{{ selectedPlanSteps.length }}</span></h3></div><Layers3 :size="17" /></div>
        <div v-if="selectedPlanSteps.length" class="step-list"><article v-for="step in selectedPlanSteps" :key="step.id"><i>{{ step.sequence }}</i><div><strong>{{ selectedPlan?.steps.find(item => item.key === step.planStepKey)?.title ?? step.planStepKey }}</strong><span>Run {{ step.runAttempt }}/{{ step.maxRunAttempts }}<template v-if="step.checkpoint"> · Checkpoint {{ step.checkpoint.sequence }}</template></span><em v-if="step.waitReason">{{ label(step.waitReason, stepWaitReasonLabels) }}</em></div><StatusBadge :tone="factTone(step.status)" dot>{{ label(step.status, stepExecutionStatusLabels) }}</StatusBadge></article></div>
        <p v-else class="empty-note">当前计划还没有 StepExecution。</p>
      </section>

      <section class="detail-card runs-card">
        <div class="section-heading"><div><p>AgentScope execution</p><h3>Agent Runs <span>{{ facts.agentRuns.length }}</span></h3></div><Bot :size="17" /></div>
        <div v-if="facts.agentRuns.length" class="run-list"><article v-for="run in facts.agentRuns" :key="run.id"><header><div><strong>Run {{ run.runSequence }}</strong><span>{{ run.id.slice(0, 8) }}… · Profile v{{ run.agentProfileVersion }}</span></div><StatusBadge :tone="factTone(run.status)" dot>{{ label(run.status, agentRunStatusLabels) }}</StatusBadge></header><dl><div><dt>Session</dt><dd class="mono">{{ run.runtimeSessionId.slice(0, 8) }}…</dd></div><div><dt>Segments</dt><dd>{{ run.segments.length }}</dd></div></dl><div v-if="run.continuityGap" class="runtime-alert"><TriangleAlert :size="15" /><span><strong>执行连续性缺口</strong>{{ label(run.continuityGap.reason, agentRunContinuityGapReasonLabels) }} · Checkpoint {{ run.continuityGap.firstMissingCheckpoint }}–{{ run.continuityGap.lastMissingCheckpoint }}</span></div></article></div>
        <p v-else class="empty-note">这个 attempt 尚未创建 AgentRun。</p>
        <div v-if="facts.sessions.length" class="session-strip"><Activity :size="14" /><span>{{ facts.sessions.length }} 个 Session · {{ facts.sessions.map(item => label(item.status, agentRuntimeSessionStatusLabels)).join(' / ') }}</span></div>
      </section>

      <section class="detail-card lease-card">
        <div class="section-heading"><div><p>Safe runtime projection</p><h3>Lease 与恢复事实</h3></div><Cpu :size="17" /></div>
        <div v-if="facts.leases.length" class="lease-list"><article v-for="lease in facts.leases" :key="lease.id"><header><div><strong>{{ lease.environment }} · {{ label(lease.phase, executionLeasePhaseLabels) }}</strong><span>Lease {{ lease.id.slice(0, 8) }}…</span></div><StatusBadge :tone="factTone(lease.status)" dot>{{ label(lease.status, executionLeaseStatusLabels) }}</StatusBadge></header><dl class="compact-facts"><div><dt>Runtime</dt><dd class="mono">{{ lease.runtimeId.slice(0, 8) }}…</dd></div><div><dt>Worker</dt><dd class="mono">{{ lease.workerId.slice(0, 8) }}…</dd></div><div><dt>最近心跳</dt><dd>{{ displayDate(lease.lastHeartbeatAt) }}</dd></div><div><dt>失效时间</dt><dd>{{ displayDate(lease.expiresAt) }}</dd></div></dl><p v-if="lease.releaseReason" class="lease-reason">{{ label(lease.releaseReason, executionLeaseReleaseReasonLabels) }}</p></article></div>
        <p v-else class="empty-note">这个 attempt 没有公开 Lease 事实。</p>
        <div class="recovery-grid"><article><span>State Snapshot</span><strong>{{ facts.snapshots.length }}</strong><small>{{ facts.snapshots.map(item => `${label(item.status, agentStateSnapshotStatusLabels)} · checkpoint ${item.checkpointSequence}`).join(' / ') || '无快照' }}</small></article><article><span>Interrupt</span><strong>{{ facts.interrupts.length }}</strong><small>{{ facts.interrupts.map(item => `${label(item.kind, agentInterruptKindLabels)} · ${label(item.status, agentInterruptStatusLabels)}`).join(' / ') || '无中断' }}</small></article></div>
        <p class="security-note"><ShieldCheck :size="13" />这里只展示成员安全的公开投影，执行凭证与内部运行载荷不会进入页面状态。</p>
        <p v-if="errorMessage" class="inline-error">{{ errorMessage }} <button type="button" @click="onRetry"><RefreshCw :size="11" />刷新</button></p>
      </section>
    </template>
  </div>
</template>

<style scoped>
.task-runtime-panel { display: grid; gap: var(--cs-space-12); }.detail-card { min-width: 0; padding: var(--cs-space-16); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }.section-heading { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-12); margin-bottom: var(--cs-space-12); }.section-heading p { margin: 0 0 var(--cs-space-2); color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); letter-spacing: .08em; text-transform: uppercase; }.section-heading h3 { margin: 0; font-size: var(--cs-text-base); }.section-heading h3 span { color: var(--cs-text-muted); font-weight: var(--cs-weight-medium); }.section-heading > svg { color: var(--cs-text-muted); }.plan-selector { display: grid; gap: var(--cs-space-4); margin-bottom: var(--cs-space-12); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.plan-selector select { min-height: 34px; padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-text); font: var(--cs-text-base) var(--cs-font-sans); }.plan-meta { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-12); }.plan-meta > span { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.plan-markdown { margin: var(--cs-space-12) 0 0; color: var(--cs-text-secondary); font-size: var(--cs-text-sm); line-height: var(--cs-leading-normal); white-space: pre-wrap; }.todo-summary,.step-list,.run-list,.lease-list { display: grid; gap: var(--cs-space-8); margin: var(--cs-space-12) 0 0; padding: 0; list-style: none; }.todo-summary { padding: var(--cs-space-12); border-radius: 9px; background: var(--cs-surface-subtle); }.todo-summary li { display: grid; grid-template-columns: auto minmax(0, 1fr); align-items: center; gap: var(--cs-space-8); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); }.step-list article { display: grid; grid-template-columns: 28px minmax(0, 1fr) auto; align-items: start; gap: var(--cs-space-8); padding: var(--cs-space-8); border-radius: 9px; background: var(--cs-surface-subtle); }.step-list article > i { display: grid; width: 28px; height: 28px; place-items: center; border-radius: 8px; background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-style: normal; font-weight: var(--cs-weight-semibold); }.step-list strong,.step-list span,.step-list em { display: block; }.step-list strong { font-size: var(--cs-text-sm); }.step-list span,.step-list em { margin-top: var(--cs-space-2); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.step-list em { width: fit-content; padding: var(--cs-space-4); border-radius: 5px; background: var(--cs-warning-soft); color: var(--cs-warning); font-style: normal; }.run-list > article,.lease-list > article { padding: var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: 9px; }.run-list header,.lease-list header { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--cs-space-12); }.run-list header strong,.run-list header span,.lease-list header strong,.lease-list header span { display: block; }.run-list header span,.lease-list header span { margin-top: var(--cs-space-2); color: var(--cs-text-muted); font: var(--cs-text-xs) var(--cs-font-mono); }.run-list dl { display: grid; grid-template-columns: 1fr 1fr; gap: var(--cs-space-8); margin: var(--cs-space-8) 0 0; }.run-list dt,.compact-facts dt { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.run-list dd,.compact-facts dd { margin: var(--cs-space-2) 0 0; font-size: var(--cs-text-xs); }.session-strip { display: flex; align-items: center; gap: var(--cs-space-8); margin-top: var(--cs-space-8); padding: var(--cs-space-8); border-radius: 8px; background: var(--cs-agent-soft); color: var(--cs-agent); font-size: var(--cs-text-xs); }.compact-facts { display: grid; grid-template-columns: 1fr 1fr; margin: var(--cs-space-12) 0 0; }.compact-facts > div { min-width: 0; padding: var(--cs-space-8) 0; border-top: 1px solid var(--cs-border); }.recovery-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--cs-space-8); margin-top: var(--cs-space-8); }.recovery-grid article { display: grid; gap: var(--cs-space-4); padding: var(--cs-space-8); border-radius: 9px; background: var(--cs-surface-subtle); }.recovery-grid span,.recovery-grid small { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.security-note { display: flex; align-items: flex-start; gap: var(--cs-space-8); margin: var(--cs-space-12) 0 0; padding: var(--cs-space-8); border-radius: 8px; background: var(--cs-surface-accent); color: var(--cs-text-brand); font-size: var(--cs-text-xs); }.runtime-alert { display: flex; align-items: flex-start; gap: var(--cs-space-8); margin-top: var(--cs-space-8); padding: var(--cs-space-8); border: 1px solid var(--cs-warning-border); border-radius: 9px; background: var(--cs-warning-soft); }.runtime-alert span,.runtime-alert strong { display: block; }.runtime-alert span { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.runtime-alert strong { color: var(--cs-warning); }.lease-reason { padding: var(--cs-space-4) var(--cs-space-8); color: var(--cs-warning); font-size: var(--cs-text-xs); }.empty-note,.inline-error { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.inline-error { color: var(--cs-danger); }.inline-error button { display: inline-flex; align-items: center; gap: var(--cs-space-4); color: inherit; text-decoration: underline; cursor: pointer; }
@media (max-width: 767px) { .task-runtime-panel { display: contents; }.plan-card { order: 8; }.steps-card { order: 9; }.runs-card { order: 10; }.lease-card { order: 11; } }
</style>
