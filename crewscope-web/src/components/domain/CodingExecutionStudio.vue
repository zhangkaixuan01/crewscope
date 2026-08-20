<script setup lang="ts">
import {
  Bot,
  Box,
  CheckCircle2,
  CircleGauge,
  Code2,
  Cpu,
  GitBranch,
  History,
  Network,
  RefreshCw,
  ShieldCheck,
  SquareTerminal,
  TriangleAlert,
} from '@lucide/vue'
import { computed } from 'vue'
import type { CodingPhase } from '../../domains/coding/store'
import type {
  CodingAttemptSummary,
  CommandEvidenceSummary,
  EvidencePage,
  TestEvidenceSummary,
} from '../../domains/coding/types'
import type { TaskPhase } from '../../domains/task/store'
import type { MemberTaskCommandOperation, TaskCommandVersionConflict, TaskExecution, TaskRuntimeFacts } from '../../domains/task/types'
import type { SemanticTone } from '../base/types'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'
import CodingProgressControl from './CodingProgressControl.vue'

const props = defineProps<{
  phase: CodingPhase
  attempt: CodingAttemptSummary | null
  errorMessage: string | null
  commandsPhase: CodingPhase
  commands: EvidencePage<CommandEvidenceSummary> | null
  commandsErrorMessage: string | null
  tests: EvidencePage<TestEvidenceSummary> | null
  runtimePhase: TaskPhase
  runtimeFacts: TaskRuntimeFacts | null
  runtimeErrorMessage: string | null
  controlAttempt: TaskExecution | null
  canControl: boolean
  online: boolean
  commandPending: MemberTaskCommandOperation | null
  commandErrorMessage: string | null
  commandRetryable: boolean
  commandVersionConflict: TaskCommandVersionConflict | null
  onCommand: (operation: MemberTaskCommandOperation, reason?: string) => Promise<void>
  onRetryCommand: () => Promise<void>
  onClearCommand: () => void
  onRetry: () => void
}>()

const details = computed(() => props.attempt?.details ?? null)
const workspace = computed(() => details.value?.workspace ?? null)
const sandbox = computed(() => details.value?.sandbox ?? null)
const latestCommand = computed(() => [...(props.commands?.items ?? [])]
  .sort((left, right) => right.sequence - left.sequence)[0] ?? null)
const currentPlan = computed(() => {
  const facts = props.runtimeFacts
  if (!facts) return null
  return facts.planVersions.find(plan => plan.id === facts.execution.currentPlanVersionId)
    ?? [...facts.planVersions].sort((left, right) => right.revision - left.revision)[0]
    ?? null
})
const currentStep = computed(() => {
  const facts = props.runtimeFacts
  if (!facts) return null
  const planId = currentPlan.value?.id
  const steps = facts.steps
    .filter(step => !planId || step.planVersionId === planId)
    .sort((left, right) => left.sequence - right.sequence)
  return steps.find(step => ['RUNNING', 'WAITING', 'PAUSED', 'READY'].includes(step.status))
    ?? steps.at(-1)
    ?? null
})
const currentStepTitle = computed(() => currentPlan.value?.steps
  .find(step => step.key === currentStep.value?.planStepKey)?.title
  ?? currentStep.value?.planStepKey
  ?? null)
const latestRun = computed(() => [...(props.runtimeFacts?.agentRuns ?? [])]
  .sort((left, right) => right.runSequence - left.runSequence)[0] ?? null)
const terminal = computed(() => ['COMPLETED', 'FAILED', 'CANCELLED']
  .includes(props.attempt?.executionStatus ?? ''))
const recovering = computed(() => workspace.value?.status === 'RECOVERING')
const commandUsage = computed(() => ({
  used: details.value?.commandEvidenceCount ?? 0,
  maximum: sandbox.value?.maxCommandCalls ?? 0,
}))
const fileUsage = computed(() => ({
  used: details.value?.diffManifest?.fileCount ?? 0,
  maximum: sandbox.value?.maxChangedFiles ?? 0,
}))

function shortHash(value: string): string {
  return value.length > 14 ? `${value.slice(0, 12)}…` : value
}

function shortId(value: string): string {
  return value.length > 12 ? `${value.slice(0, 8)}…` : value
}

function displayDate(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

function bytes(value: number): string {
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${Math.round(value / 1024)} KiB`
  return `${Math.round(value / 1024 / 1024)} MiB`
}

function statusTone(status: string): SemanticTone {
  if (['COMPLETED', 'PASSED', 'ACTIVE', 'EXITED', 'VALID'].includes(status)) return 'success'
  if (['RECOVERING', 'RUNNING', 'PREPARING', 'CLAIMED'].includes(status)) return 'info'
  if (['WAITING', 'PAUSED', 'INTERRUPTED'].includes(status)) return 'warning'
  if (['FAILED', 'CANCELLED', 'TIMED_OUT', 'INVALID'].includes(status)) return 'danger'
  return 'neutral'
}

function progress(value: { used: number, maximum: number }): number {
  if (value.maximum <= 0) return 0
  return Math.min(100, Math.round(value.used / value.maximum * 100))
}
</script>

<template>
  <section class="execution-studio detail-card" aria-labelledby="execution-studio-title" data-testid="execution-studio">
    <header class="studio-heading">
      <div class="studio-heading__icon"><Code2 :size="18" aria-hidden="true" /></div>
      <div>
        <p>Coding workspace · Member-safe projection</p>
        <h3 id="execution-studio-title">Execution Studio</h3>
      </div>
      <StatusBadge v-if="attempt" :tone="statusTone(attempt.executionStatus)" dot>
        Attempt {{ attempt.attempt }} · {{ attempt.executionStatus }}
      </StatusBadge>
    </header>

    <StatePanel
      v-if="(phase === 'idle' || phase === 'loading') && !attempt"
      compact
      state="loading"
      title="正在加载 Coding execution"
      description="读取基线、Workspace、Sandbox 与 Agent 公开事实。"
    />
    <StatePanel
      v-else-if="phase === 'error' && !attempt"
      compact
      state="error"
      title="Execution Studio 暂时不可用"
      :description="errorMessage ?? undefined"
      @retry="onRetry"
    />
    <StatePanel
      v-else-if="phase === 'empty' || !attempt?.coding || !details"
      compact
      state="empty"
      title="这是通用 Agent Task"
      description="当前 attempt 没有 CodingTargetSnapshot，不创建代码 Workspace 与 Sandbox。"
    />

    <template v-else>
      <div
        v-if="recovering || terminal"
        class="studio-state"
        :class="{ 'studio-state--recovering': recovering, 'studio-state--terminal': terminal && !recovering }"
        role="status"
        aria-live="polite"
      >
        <RefreshCw v-if="recovering" :size="16" aria-hidden="true" />
        <CheckCircle2 v-else-if="attempt.executionStatus === 'COMPLETED'" :size="16" aria-hidden="true" />
        <TriangleAlert v-else :size="16" aria-hidden="true" />
        <span v-if="recovering"><strong>Workspace 正在恢复</strong>恢复代次 {{ workspace?.recoveryGeneration }}，事实对账完成后继续执行。</span>
        <span v-else><strong>Attempt 已进入终态</strong>{{ workspace?.completionReason ?? workspace?.failureCode ?? attempt.executionStatus }}，Workspace 证据按保留期继续可读。</span>
      </div>

      <div class="studio-grid">
        <article class="studio-card studio-card--baseline">
          <div class="studio-card__title"><GitBranch :size="15" aria-hidden="true" /><span>不可变基线</span></div>
          <strong>{{ workspace?.repositoryKey }}</strong>
          <code :title="workspace?.baselineCommit">{{ shortHash(workspace?.baselineCommit ?? '') }}</code>
          <small>{{ workspace?.managedBranch }}</small>
        </article>

        <article class="studio-card studio-card--workspace">
          <div class="studio-card__title"><Box :size="15" aria-hidden="true" /><span>Workspace / Sandbox</span></div>
          <div class="studio-status"><StatusBadge :tone="statusTone(workspace?.status ?? '')" dot>{{ workspace?.status }}</StatusBadge><span>恢复代次 {{ workspace?.recoveryGeneration }}</span></div>
          <strong class="mono">{{ shortId(workspace?.id ?? '') }}</strong>
          <small>更新于 {{ displayDate(workspace?.updatedAt ?? '') }}</small>
        </article>

        <article class="studio-card studio-card--agent">
          <div class="studio-card__title"><Bot :size="15" aria-hidden="true" /><span>Coding Agent</span></div>
          <template v-if="latestRun">
            <div class="studio-status"><StatusBadge :tone="statusTone(latestRun.status)" dot>{{ latestRun.status }}</StatusBadge><span>Run {{ latestRun.runSequence }}</span></div>
            <strong>Profile v{{ latestRun.agentProfileVersion }}</strong>
            <small>Session {{ shortId(latestRun.runtimeSessionId) }}</small>
          </template>
          <p v-else-if="runtimePhase === 'loading' || runtimePhase === 'idle'">正在读取 Agent Runtime…</p>
          <p v-else>尚未创建 Coding Agent Run。</p>
        </article>

        <article class="studio-card studio-card--plan">
          <div class="studio-card__title"><History :size="15" aria-hidden="true" /><span>计划与当前步骤</span></div>
          <template v-if="currentPlan">
            <div class="studio-status"><StatusBadge tone="info">Revision {{ currentPlan.revision }}</StatusBadge><span>{{ currentPlan.steps.length }} steps</span></div>
            <strong>{{ currentStepTitle ?? '计划已发布' }}</strong>
            <small v-if="currentStep">{{ currentStep.status }} · Run {{ currentStep.runAttempt }}/{{ currentStep.maxRunAttempts }}</small>
            <small v-else>{{ currentPlan.changeReason }}</small>
          </template>
          <p v-else-if="runtimePhase === 'error'">{{ runtimeErrorMessage ?? 'Runtime 事实暂时不可用' }}</p>
          <p v-else>当前 attempt 尚未发布计划。</p>
        </article>

        <article class="studio-card studio-card--command">
          <div class="studio-card__title"><SquareTerminal :size="15" aria-hidden="true" /><span>当前命令</span></div>
          <template v-if="latestCommand">
            <div class="studio-status"><StatusBadge :tone="statusTone(latestCommand.termination)">{{ latestCommand.commandKind }}</StatusBadge><span>#{{ latestCommand.sequence }}</span></div>
            <strong>{{ latestCommand.toolKey }}</strong>
            <small>{{ latestCommand.summary }} · Exit {{ latestCommand.exitCode ?? '—' }}</small>
          </template>
          <p v-else-if="commandsPhase === 'loading' || commandsPhase === 'idle'">正在读取结构化命令事实…</p>
          <p v-else-if="commandsPhase === 'error'">{{ commandsErrorMessage ?? 'CommandEvidence 暂时不可用' }}</p>
          <p v-else>尚未产生结构化 CommandEvidence。</p>
        </article>

        <article class="studio-card studio-card--sandbox">
          <div class="studio-card__title"><ShieldCheck :size="15" aria-hidden="true" /><span>Sandbox 边界</span></div>
          <template v-if="sandbox">
            <div class="sandbox-facts">
              <span><Network :size="12" />网络 {{ sandbox.networkMode }}</span>
              <span><Cpu :size="12" />{{ sandbox.cpuCount }} CPU · {{ sandbox.memoryMiB }} MiB</span>
              <span><ShieldCheck :size="12" />只读根层 {{ sandbox.readOnlyRootFilesystem ? '开启' : '关闭' }}</span>
            </div>
            <small>{{ sandbox.buildProfileKey }} · v{{ sandbox.buildProfileVersion }}</small>
          </template>
          <p v-else>Sandbox 尚未创建。</p>
        </article>

        <article class="studio-card studio-card--budget">
          <div class="studio-card__title"><CircleGauge :size="15" aria-hidden="true" /><span>资源预算</span></div>
          <template v-if="sandbox">
            <div class="budget-grid">
              <div><span>耐久命令</span><strong>{{ commandUsage.used }} / {{ commandUsage.maximum }}</strong><i><b :style="{ width: `${progress(commandUsage)}%` }" /></i></div>
              <div><span>变更文件</span><strong>{{ fileUsage.used }} / {{ fileUsage.maximum }}</strong><i><b :style="{ width: `${progress(fileUsage)}%` }" /></i></div>
              <div><span>写操作上限</span><strong>{{ sandbox.maxWriteOperations }}</strong><small>{{ bytes(sandbox.maxWrittenBytes) }} 累计写入</small></div>
              <div><span>单次命令</span><strong>{{ sandbox.maxCommandDurationSeconds }}s</strong><small>{{ bytes(sandbox.maxCommandOutputBytes) }} 输出</small></div>
              <div><span>单文件上限</span><strong>{{ bytes(sandbox.maxSingleFileBytes) }}</strong><small>{{ sandbox.pids }} PIDs</small></div>
              <div><span>Diff 上限</span><strong>{{ bytes(sandbox.maxDiffBytes) }}</strong><small>{{ sandbox.maxTestRepairRounds }} 次测试修复</small></div>
            </div>
          </template>
          <p v-else>预算在 Sandbox 创建后固化。</p>
        </article>
      </div>

      <CodingProgressControl
        :attempt="attempt"
        :runtime-facts="runtimeFacts"
        :commands="commands"
        :tests="tests"
        :control-attempt="controlAttempt"
        :can-control="canControl"
        :online="online"
        :pending="commandPending"
        :error-message="commandErrorMessage"
        :retryable="commandRetryable"
        :version-conflict="commandVersionConflict"
        :on-command="onCommand"
        :on-retry-command="onRetryCommand"
        :on-clear-command="onClearCommand"
      />

      <p class="studio-security"><ShieldCheck :size="13" aria-hidden="true" />页面只持有公开坐标与摘要，不保存宿主路径、容器标识、命令参数、环境变量、Token 或 Agent 内部状态。</p>
      <p v-if="errorMessage || commandsErrorMessage || runtimeErrorMessage" class="studio-warning" role="status" aria-live="polite" aria-atomic="true">
        部分事实刷新失败，已加载的耐久事实继续可见。
        <button type="button" @click="onRetry"><RefreshCw :size="11" />重新读取</button>
      </p>
    </template>
  </section>
</template>

<style scoped>
.execution-studio { padding: 0; overflow: hidden; border-color: var(--cs-brand-200); background: rgb(255 255 255 / 96%); box-shadow: 0 8px 26px rgb(21 35 29 / 5%); }.studio-heading { display: grid; min-height: 64px; grid-template-columns: 36px minmax(0, 1fr) auto; align-items: center; gap: 10px; padding: 12px 15px; border-bottom: 1px solid var(--cs-border); background: linear-gradient(110deg, var(--cs-brand-50), var(--cs-surface) 62%); }.studio-heading__icon { display: grid; width: 36px; height: 36px; place-items: center; border: 1px solid var(--cs-brand-200); border-radius: 10px; background: var(--cs-surface); color: var(--cs-brand-700); }.studio-heading p, .studio-heading h3 { margin: 0; }.studio-heading p { color: var(--cs-brand-600); font-size: 8px; font-weight: 800; letter-spacing: .08em; text-transform: uppercase; }.studio-heading h3 { margin-top: 2px; font-size: 14px; }.execution-studio > :deep(.state-panel) { min-height: 126px; border: 0; border-radius: 0; }.studio-state { display: flex; align-items: flex-start; gap: 8px; margin: 10px 12px 0; padding: 9px 10px; border: 1px solid var(--cs-border); border-radius: 9px; color: var(--cs-text-muted); font-size: 9px; line-height: 1.45; }.studio-state svg { flex: 0 0 auto; }.studio-state span, .studio-state strong { display: block; }.studio-state strong { color: var(--cs-text); font-size: 10px; }.studio-state--recovering { border-color: var(--cs-brand-200); background: var(--cs-brand-50); color: var(--cs-brand-700); }.studio-state--terminal { background: var(--cs-surface-subtle); }.studio-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; padding: 12px; }.studio-card { min-width: 0; min-height: 120px; padding: 11px; border: 1px solid var(--cs-border); border-radius: 10px; background: var(--cs-surface-subtle); }.studio-card__title { display: flex; align-items: center; gap: 6px; margin-bottom: 10px; color: var(--cs-text-muted); font-size: 8px; font-weight: 800; letter-spacing: .04em; text-transform: uppercase; }.studio-card__title svg { color: var(--cs-brand-600); }.studio-card > strong, .studio-card > code, .studio-card > small { display: block; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.studio-card > strong { color: var(--cs-text); font-size: 11px; }.studio-card > code { margin-top: 5px; color: var(--cs-brand-700); font: 9px var(--cs-font-mono); }.studio-card > small { margin-top: 6px; color: var(--cs-text-muted); font: 8px var(--cs-font-mono); }.studio-card > p { margin: 0; color: var(--cs-text-muted); font-size: 9px; line-height: 1.5; }.studio-status { display: flex; align-items: center; justify-content: space-between; gap: 7px; margin-bottom: 8px; }.studio-status > span { color: var(--cs-text-muted); font-size: 8px; }.studio-card--budget { grid-column: span 2; }.sandbox-facts { display: grid; gap: 6px; }.sandbox-facts span { display: flex; align-items: center; gap: 5px; color: var(--cs-text-secondary); font-size: 9px; }.sandbox-facts svg { color: var(--cs-brand-600); }.budget-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }.budget-grid > div { display: grid; align-content: start; gap: 3px; }.budget-grid span, .budget-grid small { color: var(--cs-text-muted); font-size: 8px; }.budget-grid strong { font-size: 11px; }.budget-grid i { display: block; height: 3px; margin-top: 3px; overflow: hidden; border-radius: 999px; background: var(--cs-brand-100); }.budget-grid b { display: block; height: 100%; border-radius: inherit; background: var(--cs-brand-400); }.studio-security, .studio-warning { display: flex; align-items: flex-start; gap: 6px; margin: 0 12px 12px; padding: 8px 9px; border-radius: 8px; font-size: 8px; line-height: 1.45; }.studio-security { background: var(--cs-brand-50); color: var(--cs-brand-700); }.studio-security svg, .studio-warning svg { flex: 0 0 auto; }.studio-warning { align-items: center; background: var(--cs-warning-soft); color: #7c4a12; }.studio-warning button { display: inline-flex; align-items: center; gap: 3px; margin-left: auto; color: inherit; font-size: inherit; font-weight: 800; text-decoration: underline; cursor: pointer; }
@media (max-width: 820px) { .studio-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }.studio-card--budget { grid-column: span 2; } }
@media (max-width: 560px) { .studio-heading { grid-template-columns: 34px minmax(0, 1fr); padding: 11px 12px; }.studio-heading > :deep(.status-badge) { grid-column: 1 / -1; width: fit-content; }.studio-grid { grid-template-columns: 1fr; padding: 9px; }.studio-card, .studio-card--budget { grid-column: 1; min-height: 0; }.budget-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }.studio-security, .studio-warning { margin-inline: 9px; }.studio-warning { align-items: flex-start; flex-wrap: wrap; }.studio-warning button { margin-left: 0; } }
</style>
