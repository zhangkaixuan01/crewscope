<script setup lang="ts">
import { CheckCircle2, Circle, CircleDot, ListChecks, RefreshCw, Save, ShieldCheck, TriangleAlert } from '@lucide/vue'
import { computed } from 'vue'
import type { CodingAttemptSummary, CommandEvidenceSummary, EvidencePage, TestEvidenceSummary } from '../../domains/coding/types'
import type {
  MemberTaskCommandOperation,
  PlanTodo,
  TaskCommandVersionConflict,
  TaskExecution,
  TaskRuntimeFacts,
} from '../../domains/task/types'
import StatusBadge from '../base/StatusBadge.vue'
import TaskControlPanel from './TaskControlPanel.vue'

const props = defineProps<{
  attempt: CodingAttemptSummary
  runtimeFacts: TaskRuntimeFacts | null
  commands: EvidencePage<CommandEvidenceSummary> | null
  tests: EvidencePage<TestEvidenceSummary> | null
  controlAttempt: TaskExecution | null
  canControl: boolean
  online: boolean
  pending: MemberTaskCommandOperation | null
  errorMessage: string | null
  retryable: boolean
  versionConflict: TaskCommandVersionConflict | null
  onCommand: (operation: MemberTaskCommandOperation, reason?: string) => Promise<void>
  onRetryCommand: () => Promise<void>
  onClearCommand: () => void
}>()

type StageState = 'completed' | 'active' | 'upcoming' | 'interrupted'

const currentPlan = computed(() => {
  const facts = props.runtimeFacts
  if (!facts) return null
  return facts.planVersions.find(plan => plan.id === facts.execution.currentPlanVersionId)
    ?? [...facts.planVersions].sort((a, b) => b.revision - a.revision)[0]
    ?? null
})
const orderedTodos = computed(() => [...(currentPlan.value?.todoSummary ?? [])].sort((left, right) => {
  const order: Record<string, number> = { IN_PROGRESS: 0, BLOCKED: 1, TODO: 2, PENDING: 2, COMPLETED: 3 }
  return (order[left.status] ?? 4) - (order[right.status] ?? 4)
}))
const latestCommand = computed(() => [...(props.commands?.items ?? [])].sort((a, b) => b.sequence - a.sequence)[0] ?? null)
const latestCommandIsValidation = computed(() => ['TEST', 'VERIFY', 'ACCEPTANCE']
  .includes(latestCommand.value?.commandKind ?? ''))
const latestTest = computed(() => [...(props.tests?.items ?? [])].sort((a, b) => b.sequence - a.sequence)[0] ?? null)
const latestStepCheckpoint = computed(() => props.runtimeFacts?.steps
  .filter(step => step.checkpoint)
  .sort((a, b) => new Date(b.checkpoint!.recordedAt).getTime() - new Date(a.checkpoint!.recordedAt).getTime())[0] ?? null)
const latestRun = computed(() => [...(props.runtimeFacts?.agentRuns ?? [])]
  .sort((a, b) => b.runSequence - a.runSequence)[0] ?? null)
const latestSnapshot = computed(() => [...(props.runtimeFacts?.snapshots ?? [])]
  .filter(snapshot => !latestRun.value || snapshot.agentRunId === latestRun.value.id)
  .sort((a, b) => b.snapshotSequence - a.snapshotSequence)[0] ?? null)
const currentStep = computed(() => props.runtimeFacts?.steps
  .find(step => ['RUNNING', 'WAITING', 'PAUSED', 'READY'].includes(step.status))
  ?? props.runtimeFacts?.steps.at(-1)
  ?? null)
const testPassed = computed(() => Boolean(latestTest.value
  && latestTest.value.total > 0
  && latestTest.value.failed === 0
  && latestTest.value.errors === 0
  && latestTest.value.failureClassification === null))
// Evidence sequence is publication order. The public DTO currently exposes only the authoritative repair budget ceiling.
const maximumRepairRounds = computed(() => props.attempt.details?.sandbox?.maxTestRepairRounds ?? 0)
const historical = computed(() => !props.attempt.current)
const controlAligned = computed(() => props.controlAttempt?.id === props.attempt.executionId)
const interrupted = computed(() => ['FAILED', 'CANCELLED'].includes(props.attempt.executionStatus))
// The rail is a deterministic reading projection; TaskExecution and Workspace remain the execution state machines.
const progressIndex = computed(() => {
  if (props.attempt.details?.codingResult) return 4
  if (latestTest.value || latestCommandIsValidation.value) return 3
  if (props.attempt.details?.diffManifest?.fileCount) return 2
  if (currentPlan.value) return 1
  return 0
})
const stages = computed(() => [
  { key: 'workspace', label: '准备', detail: props.attempt.details?.workspace.status ?? '等待 Workspace' },
  { key: 'plan', label: '分析与计划', detail: currentPlan.value ? `Plan r${currentPlan.value.revision}` : '等待 Plan' },
  { key: 'change', label: '代码变更', detail: props.attempt.details?.diffManifest ? `Diff g${props.attempt.details.diffManifest.generation}` : '等待 Diff' },
  { key: 'test', label: '测试与修复', detail: latestTest.value
    ? `Test #${latestTest.value.sequence}`
    : latestCommandIsValidation.value ? `${latestCommand.value!.commandKind} command #${latestCommand.value!.sequence}` : '等待 TestEvidence' },
  { key: 'deliver', label: '交付', detail: props.attempt.details?.codingResult ? 'Result 已固化' : '等待权威结果' },
].map((stage, index) => ({ ...stage, state: stageState(index) })))

function stageState(index: number): StageState {
  if (index < progressIndex.value || (index === 4 && props.attempt.details?.codingResult)) return 'completed'
  if (index === progressIndex.value) return interrupted.value ? 'interrupted' : 'active'
  return 'upcoming'
}

function todoTone(todo: PlanTodo): 'success' | 'warning' | 'danger' | 'info' | 'neutral' {
  if (todo.status === 'COMPLETED') return 'success'
  if (todo.status === 'IN_PROGRESS') return 'info'
  if (todo.status === 'BLOCKED') return 'danger'
  return 'neutral'
}
</script>

<template>
  <section class="coding-progress" aria-labelledby="coding-progress-title" data-testid="coding-progress-control">
    <header class="progress-heading">
      <div><p>Authoritative progress · Durable control</p><h4 id="coding-progress-title">Coding 进度与执行控制</h4></div>
      <StatusBadge :tone="historical ? 'neutral' : 'agent'" dot>{{ historical ? '历史 Attempt' : '当前 Attempt' }}</StatusBadge>
    </header>

    <ol class="stage-rail" aria-label="Coding 阶段">
      <li v-for="stage in stages" :key="stage.key" :class="`stage-${stage.state}`" :aria-current="stage.state === 'active' ? 'step' : undefined">
        <CheckCircle2 v-if="stage.state === 'completed'" :size="15" aria-hidden="true" />
        <TriangleAlert v-else-if="stage.state === 'interrupted'" :size="15" aria-hidden="true" />
        <CircleDot v-else-if="stage.state === 'active'" :size="15" aria-hidden="true" />
        <Circle v-else :size="15" aria-hidden="true" />
        <span><strong>{{ stage.label }}</strong><small>{{ stage.detail }}</small></span>
      </li>
    </ol>
    <p class="stage-note">阶段按最新已发布事实定位，TaskExecution 与 Workspace 状态继续作为执行事实。</p>

    <div class="progress-grid">
      <section class="progress-facts" aria-labelledby="coding-todo-title">
        <header><ListChecks :size="14" aria-hidden="true" /><strong id="coding-todo-title">当前 Todo</strong><small>{{ orderedTodos.length }}</small></header>
        <ul v-if="orderedTodos.length" class="coding-todos">
          <li v-for="todo in orderedTodos" :key="`${todo.planStepKey}:${todo.content}`">
            <StatusBadge :tone="todoTone(todo)">{{ todo.status }}</StatusBadge>
            <span><strong>{{ todo.content }}</strong><small>{{ todo.planStepKey ?? '未绑定 Step' }}<template v-if="todo.priority"> · {{ todo.priority }}</template></small></span>
          </li>
        </ul>
        <p v-else>当前公开 PlanVersion 没有 Todo 摘要。</p>
      </section>

      <section class="progress-facts" aria-labelledby="coding-checkpoint-title">
        <header><Save :size="14" aria-hidden="true" /><strong id="coding-checkpoint-title">Checkpoint 与恢复</strong></header>
        <dl class="checkpoint-grid">
          <div><dt>Step Checkpoint</dt><dd>{{ latestStepCheckpoint?.checkpoint ? `#${latestStepCheckpoint.checkpoint.sequence} · ${latestStepCheckpoint.checkpoint.code}` : '尚未提交' }}</dd></div>
          <div><dt>State Snapshot</dt><dd>{{ latestSnapshot ? `#${latestSnapshot.snapshotSequence} · checkpoint ${latestSnapshot.checkpointSequence}` : '尚未发布' }}</dd></div>
          <div><dt>Agent Run</dt><dd>{{ latestRun ? `Run ${latestRun.runSequence} · ${latestRun.status}` : '尚未创建' }}</dd></div>
          <div><dt>当前 Step</dt><dd>{{ currentStep ? `${currentStep.planStepKey} · ${currentStep.status}` : '尚未创建' }}</dd></div>
        </dl>
        <p v-if="latestRun?.continuityGap" class="checkpoint-warning"><TriangleAlert :size="12" />存在 Checkpoint 连续性缺口，控制前以服务端恢复结果为准。</p>
      </section>

      <section class="progress-facts progress-facts--test" aria-labelledby="coding-repair-title">
        <header><RefreshCw :size="14" aria-hidden="true" /><strong id="coding-repair-title">测试与修复预算</strong><StatusBadge :tone="testPassed ? 'success' : latestTest ? 'warning' : 'neutral'">{{ testPassed ? '验证通过' : latestTest ? '等待修复' : '尚未测试' }}</StatusBadge></header>
        <div class="repair-meter">
          <div><span>最新 TestEvidence</span><strong>{{ latestTest ? `#${latestTest.sequence}` : '—' }}</strong></div>
          <div><span>修复预算上限</span><strong>{{ maximumRepairRounds }} 轮</strong></div>
        </div>
        <p>{{ latestTest?.summary ?? '测试命令完成后由平台发布 TestEvidence，并据此进入修复或交付阶段。' }}</p>
        <p class="repair-disclosure">TestEvidence 序号表示证据发布顺序；当前公开事实未单独披露已用修复轮次。</p>
      </section>

      <section class="control-slot" aria-label="Coding Task 执行控制">
        <TaskControlPanel
          v-if="controlAligned"
          :attempt="controlAttempt"
          :can-control="canControl"
          :online="online"
          :pending="pending"
          :error-message="errorMessage"
          :retryable="retryable"
          :version-conflict="versionConflict"
          :on-command="onCommand"
          :on-retry="onRetryCommand"
          :on-clear-feedback="onClearCommand"
        />
        <div v-else-if="historical" class="history-control-note"><ShieldCheck :size="16" /><span><strong>历史 Attempt 保持只读</strong>切回当前 Attempt 后才能提交 Pause、Resume、Cancel 或 Retry。</span></div>
        <div v-else class="history-control-note" role="status" aria-live="polite" aria-atomic="true"><RefreshCw :size="16" /><span><strong>正在同步当前 Attempt 控制事实</strong>TaskExecution 强版本返回后才会开放控制操作。</span></div>
      </section>
    </div>
  </section>
</template>

<style scoped>
.coding-progress{border-top:1px solid var(--cs-border);background:var(--cs-surface)}.progress-heading{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:11px 14px}.progress-heading p,.progress-heading h4{margin:0}.progress-heading p{color:var(--cs-brand-600);font-size:8px;font-weight:800;letter-spacing:.08em;text-transform:uppercase}.progress-heading h4{margin-top:2px;font-size:12px}.stage-rail{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));margin:0;padding:0 14px 6px;list-style:none}.stage-rail li{position:relative;display:grid;min-width:0;grid-template-columns:auto minmax(0,1fr);align-items:center;gap:6px;padding:8px 7px;border-block:1px solid var(--cs-border);background:var(--cs-surface-subtle);color:var(--cs-text-muted)}.stage-rail li:first-child{border-left:1px solid var(--cs-border);border-radius:8px 0 0 8px}.stage-rail li:last-child{border-right:1px solid var(--cs-border);border-radius:0 8px 8px 0}.stage-rail li+li:before{position:absolute;left:0;width:1px;height:64%;background:var(--cs-border);content:""}.stage-rail svg{flex:0 0 auto}.stage-rail span,.stage-rail strong,.stage-rail small{display:block;min-width:0}.stage-rail strong,.stage-rail small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.stage-rail strong{font-size:8px}.stage-rail small{margin-top:2px;font:7px var(--cs-font-mono)}.stage-completed{color:#237a50!important;background:#f1f8f3!important}.stage-active{color:var(--cs-brand-700)!important;background:var(--cs-brand-50)!important}.stage-interrupted{color:var(--cs-danger)!important;background:var(--cs-danger-soft)!important}.stage-note{margin:0;padding:0 14px 8px;color:var(--cs-text-muted);font-size:7px;line-height:1.5}.progress-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;padding:0 14px 14px}.progress-facts,.control-slot{min-width:0;border:1px solid var(--cs-border);border-radius:9px;background:var(--cs-surface-subtle)}.progress-facts{padding:10px}.progress-facts>header{display:flex;align-items:center;gap:6px;min-height:20px;color:var(--cs-text-secondary);font-size:8px}.progress-facts>header svg{color:var(--cs-brand-600)}.progress-facts>header small,.progress-facts>header :deep(.status-badge){margin-left:auto}.progress-facts>p{margin:8px 0 0;color:var(--cs-text-muted);font-size:8px;line-height:1.5}.coding-todos{display:grid;gap:5px;margin:8px 0 0;padding:0;list-style:none}.coding-todos li{display:grid;grid-template-columns:auto minmax(0,1fr);align-items:start;gap:6px;padding:6px;border-radius:7px;background:var(--cs-surface)}.coding-todos span,.coding-todos strong,.coding-todos small{display:block;min-width:0}.coding-todos strong{font-size:8px}.coding-todos small{margin-top:2px;color:var(--cs-text-muted);font:7px var(--cs-font-mono)}.checkpoint-grid{display:grid;grid-template-columns:1fr 1fr;gap:6px;margin:8px 0 0}.checkpoint-grid>div{min-width:0;padding:6px;border-radius:7px;background:var(--cs-surface)}.checkpoint-grid dt{color:var(--cs-text-muted);font-size:7px}.checkpoint-grid dd{margin:3px 0 0;overflow:hidden;font:8px var(--cs-font-mono);text-overflow:ellipsis;white-space:nowrap}.checkpoint-warning{display:flex;align-items:flex-start;gap:4px;color:#7c4a12!important}.repair-meter{display:grid;grid-template-columns:1fr 1fr;gap:6px;margin-top:8px}.repair-meter>div{padding:6px;border-radius:7px;background:var(--cs-surface)}.repair-meter span,.repair-meter strong{display:block}.repair-meter span{color:var(--cs-text-muted);font-size:7px}.repair-meter strong{margin-top:2px;font:10px var(--cs-font-mono)}.repair-disclosure{padding-top:6px;border-top:1px solid var(--cs-border)}.control-slot{background:linear-gradient(145deg,var(--cs-brand-50),var(--cs-surface) 72%)}.control-slot :deep(.task-control-panel){height:100%;border:0;background:transparent}.history-control-note{display:flex;min-height:100%;align-items:flex-start;gap:8px;padding:14px;color:var(--cs-text-muted);font-size:8px;line-height:1.5}.history-control-note svg{flex:0 0 auto;color:var(--cs-brand-600)}.history-control-note strong{display:block;margin-bottom:2px;color:var(--cs-text-secondary);font-size:9px}@media(max-width:720px){.stage-rail{grid-template-columns:1fr}.stage-rail li{border:1px solid var(--cs-border);border-radius:0!important}.stage-rail li:first-child{border-radius:8px 8px 0 0!important}.stage-rail li:last-child{border-radius:0 0 8px 8px!important}.stage-rail li+li{border-top:0}.stage-rail li+li:before{display:none}.progress-grid{grid-template-columns:1fr}.progress-facts--test{order:3}.control-slot{order:4}.checkpoint-grid{grid-template-columns:1fr 1fr}}
</style>
