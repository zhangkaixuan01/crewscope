<script setup lang="ts">
import {
  Activity,
  Bot,
  CheckCircle2,
  CircleUserRound,
  Clock3,
  Cpu,
  GitBranch,
  History,
  Layers3,
  MessageSquare,
  RefreshCw,
  ServerCog,
  ShieldCheck,
  TriangleAlert,
  X,
} from '@lucide/vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useTemplateRef, watch } from 'vue'
import { isTopmostModal } from '../../app/dialog'
import type { CodingPhase, CodingResource } from '../../domains/coding/store'
import type { ArtifactTextDocument, CodingAttemptSummary, CodingPatchDocument, CommandEvidenceSummary, EvidencePage, TestEvidenceSummary } from '../../domains/coding/types'
import type { SemanticTone } from '../base/types'
import type { TaskLiveState, TaskPhase } from '../../domains/task/store'
import type { ReviewCommandState, ReviewPhase } from '../../domains/review/store'
import type { EtaggedReview, ReviewDecisionInput, ReviewFindingEvidence, ReviewSummary } from '../../domains/review/types'
import type {
  MemberTaskCommandOperation,
  PlanVersion,
  RuntimeFleetSummary,
  TaskAssociations,
  TaskDetails,
  TaskEventPage,
  TaskExecution,
  TaskRuntimeFacts,
  TaskStatus,
  TaskCommandVersionConflict,
} from '../../domains/task/types'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'
import CodingExecutionStudio from './CodingExecutionStudio.vue'
import CodingDiffExplorer from './CodingDiffExplorer.vue'
import CodingEvidencePanel from './CodingEvidencePanel.vue'
import TaskControlPanel from './TaskControlPanel.vue'
import TaskTimelinePanel from './TaskTimelinePanel.vue'
import ReviewWorkbench from './ReviewWorkbench.vue'
import ActionDeliveryWorkbench from './ActionDeliveryWorkbench.vue'

const props = defineProps<{
  phase: TaskPhase
  details: TaskDetails | null
  attempts: TaskExecution[]
  selectedExecutionId: string | null
  errorMessage: string | null
  runtimePhase: TaskPhase
  runtimeFacts: TaskRuntimeFacts | null
  runtimeErrorMessage: string | null
  codingPhase: CodingPhase
  codingAttempt: CodingAttemptSummary | null
  codingErrorMessage: string | null
  codingCommandsPhase: CodingPhase
  codingCommands: EvidencePage<CommandEvidenceSummary> | null
  codingCommandsErrorMessage: string | null
  codingTestsPhase: CodingPhase
  codingTests: EvidencePage<TestEvidenceSummary> | null
  codingTestsErrorMessage: string | null
  codingCommandLog: (evidenceId: string) => CodingResource<ArtifactTextDocument> | null
  codingTestReport: (evidenceId: string) => CodingResource<ArtifactTextDocument> | null
  codingPatchPhase: CodingPhase
  codingPatch: CodingPatchDocument | null
  codingPatchErrorMessage: string | null
  fleetPhase: TaskPhase
  fleet: RuntimeFleetSummary | null
  fleetErrorMessage: string | null
  associationPhase: TaskPhase
  associations: TaskAssociations | null
  associationErrorMessage: string | null
  eventPhase: TaskPhase
  eventPage: TaskEventPage | null
  eventErrorMessage: string | null
  liveState: TaskLiveState | null
  reviewListPhase: ReviewPhase
  reviews: ReviewSummary[] | null
  selectedReviewRequestId: string | null
  reviewDetailPhase: ReviewPhase
  review: EtaggedReview | null
  reviewListErrorMessage: string | null
  reviewDetailErrorMessage: string | null
  reviewCommand: ReviewCommandState
  canGateReview: boolean
  canConfirmDelivery: boolean
  principals: Array<{ principalId: string, displayName: string }>
  canControl: boolean
  online: boolean
  commandPending: MemberTaskCommandOperation | null
  commandErrorMessage: string | null
  commandRetryable: boolean
  commandVersionConflict: TaskCommandVersionConflict | null
  onSelectAttempt: (executionId: string) => void
  onRetry: () => void
  onRetryRuntime: () => void
  onRetryCoding: () => void
  onLoadCodingPatch: () => void
  onLoadCodingCommandsMore: () => void
  onLoadCodingTestsMore: () => void
  onLoadCodingCommandLog: (evidenceId: string, more?: boolean) => void
  onLoadCodingTestReport: (evidenceId: string, more?: boolean) => void
  onRetryFleet: () => void
  onRetryAssociations: () => void
  onLoadEventsMore: () => void
  onRetryEvents: () => void
  onSelectReview: (reviewRequestId: string) => void
  onRetryReviews: () => void
  onRetryReviewDetail: () => void
  onExecuteReviewer: () => Promise<boolean>
  onDecideReview: (input: ReviewDecisionInput) => Promise<boolean>
  onRequestReviewChanges: (rationale: string) => Promise<boolean>
  onRetryReviewCommand: () => Promise<boolean>
  onClearReviewCommand: () => void
  onCommand: (operation: MemberTaskCommandOperation, reason?: string, agentConfigurationRevision?: number) => Promise<void>
  onRetryCommand: () => Promise<void>
  onClearCommand: () => void
}>()

const emit = defineEmits<{ close: [], openWorkItem: [], openConversation: [conversationId: string] }>()
const drawer = useTemplateRef<HTMLElement>('drawer')
const closeButton = useTemplateRef<HTMLButtonElement>('closeButton')
const selectedPlanVersionId = ref<string | null>(null)
const reviewLocation = ref<ReviewFindingEvidence | null>(null)
let previousBodyOverflow = ''

const selectedAttempt = computed(() => props.attempts.find(item => item.id === props.selectedExecutionId) ?? null)
const currentAttempt = computed(() => props.attempts.find(item => item.id === props.details?.currentExecutionId) ?? null)
const orderedAttempts = computed(() => [...props.attempts].sort((left, right) => right.attempt - left.attempt))
const orderedPlans = computed(() => [...(props.runtimeFacts?.planVersions ?? [])].sort((left, right) => right.revision - left.revision))
const selectedPlan = computed<PlanVersion | null>(() => {
  const plans = orderedPlans.value
  return plans.find(item => item.id === selectedPlanVersionId.value)
    ?? plans.find(item => item.id === props.runtimeFacts?.execution.currentPlanVersionId)
    ?? plans[0]
    ?? null
})
const selectedPlanSteps = computed(() => {
  const planId = selectedPlan.value?.id
  return [...(props.runtimeFacts?.steps ?? [])]
    .filter(step => !planId || step.planVersionId === planId)
    .sort((left, right) => left.sequence - right.sequence)
})
const completedSteps = computed(() => selectedPlanSteps.value.filter(step => step.status === 'COMPLETED').length)
const selectedAttemptHasContinuityGap = computed(() => props.runtimeFacts?.agentRuns.some(run => Boolean(run.continuityGap)) ?? false)

watch(
  () => [props.selectedExecutionId, props.runtimeFacts?.execution.currentPlanVersionId, orderedPlans.value[0]?.id] as const,
  ([, currentPlanVersionId, newestPlanVersionId]) => {
    selectedPlanVersionId.value = currentPlanVersionId ?? newestPlanVersionId ?? null
  },
  { immediate: true },
)

onMounted(() => {
  document.addEventListener('keydown', handleKeydown)
  previousBodyOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  void nextTick(() => closeButton.value?.focus())
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', handleKeydown)
  document.body.style.overflow = previousBodyOverflow
})

function handleKeydown(event: KeyboardEvent): void {
  if (!isTopmostModal(drawer.value)) return
  if (event.key === 'Escape') {
    emit('close')
    return
  }
  if (event.key !== 'Tab' || !drawer.value) return
  const controls = [...drawer.value.querySelectorAll<HTMLElement>('button:not(:disabled), select:not(:disabled), a[href]')]
    .filter(element => element.offsetParent !== null)
  const first = controls[0]
  const last = controls.at(-1)
  if (!first || !last) return
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

function displayDate(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

function principalName(principalId: string): string {
  return props.principals.find(item => item.principalId === principalId)?.displayName
    ?? `${principalId.slice(0, 8)}…`
}

function shortIdentifier(value: string | null): string {
  if (!value) return '—'
  return value.length > 12 ? `${value.slice(0, 8)}…` : value
}

function taskTone(status: TaskStatus): SemanticTone {
  if (status === 'COMPLETED') return 'success'
  if (status === 'WAITING') return 'warning'
  if (status === 'FAILED' || status === 'CANCELLED') return 'danger'
  return status === 'ACTIVE' ? 'agent' : 'neutral'
}

function factTone(status: string): SemanticTone {
  if (['COMPLETED', 'HEALTHY', 'ACTIVE', 'VALID', 'RELEASED'].includes(status)) return 'success'
  if (['WAITING', 'WAITING_RUNTIME', 'DEGRADED', 'PAUSED', 'INTERRUPTED'].includes(status)) return 'warning'
  if (['FAILED', 'CANCELLED', 'EXPIRED', 'UNAVAILABLE', 'INVALID', 'OPEN'].includes(status)) return 'danger'
  if (['RUNNING', 'CLAIMED', 'PREPARING', 'RECOVERING'].includes(status)) return 'info'
  return 'neutral'
}

function roleLabel(role: string): string {
  return ({ OWNER: 'Owner', EXECUTOR: 'Executor', REVIEWER: 'Reviewer' } as Record<string, string>)[role] ?? role
}

function locateReviewFinding(location: ReviewFindingEvidence): void {
  reviewLocation.value = { ...location }
  if (props.codingPatchPhase === 'idle') props.onLoadCodingPatch()
  void nextTick(() => {
    const explorer = drawer.value?.querySelector<HTMLElement>('#coding-diff-explorer')
    explorer?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    explorer?.focus({ preventScroll: true })
  })
}
</script>

<template>
  <div class="task-detail-backdrop" @mousedown.self="$emit('close')">
    <aside ref="drawer" class="task-detail-drawer" role="dialog" aria-modal="true" :aria-label="details ? `${details.objective} Task 详情` : 'Task 详情'">
      <header class="task-detail-header">
        <div>
          <p>Control Mode · Runtime facts</p>
          <strong>{{ details ? `Task ${shortIdentifier(details.id)}` : '加载 Task' }}</strong>
        </div>
        <button ref="closeButton" type="button" aria-label="关闭 Task 详情" @click="$emit('close')"><X :size="18" /></button>
      </header>

      <StatePanel v-if="phase === 'loading' && !details" state="loading" title="正在加载 Task 详情" />
      <StatePanel v-else-if="phase === 'error' && !details" state="error" :description="errorMessage ?? undefined" @retry="onRetry" />

      <div v-else-if="details" class="task-detail-content">
        <StatePanel
          v-if="phase === 'loading'"
          class="detail-sync-state"
          compact
          state="loading"
          title="正在刷新 Task 事实"
          description="当前已加载内容继续可见，服务端最新版本返回后会自动替换。"
        />
        <StatePanel
          v-else-if="phase === 'error'"
          class="detail-sync-state"
          compact
          state="error"
          title="最新 Task 事实暂时不可用"
          :description="errorMessage ?? undefined"
          @retry="onRetry"
        />
        <CodingExecutionStudio
          :phase="codingPhase"
          :attempt="codingAttempt"
          :error-message="codingErrorMessage"
          :commands-phase="codingCommandsPhase"
          :commands="codingCommands"
          :commands-error-message="codingCommandsErrorMessage"
          :tests="codingTests"
          :runtime-phase="runtimePhase"
          :runtime-facts="runtimeFacts"
          :runtime-error-message="runtimeErrorMessage"
          :control-attempt="codingAttempt?.executionId === currentAttempt?.id ? currentAttempt : null"
          :can-control="canControl"
          :online="online"
          :command-pending="commandPending"
          :command-error-message="commandErrorMessage"
          :command-retryable="commandRetryable"
          :command-version-conflict="commandVersionConflict"
          :on-command="onCommand"
          :on-retry-command="onRetryCommand"
          :on-clear-command="onClearCommand"
          :on-retry="onRetryCoding"
        />
        <CodingDiffExplorer
          v-if="codingAttempt?.coding && codingAttempt.details"
          :attempt="codingAttempt"
          :event-page="eventPage"
          :live-state="liveState"
          :patch-phase="codingPatchPhase"
          :patch="codingPatch"
          :patch-error-message="codingPatchErrorMessage"
          :review-location="reviewLocation"
          :on-load-patch="onLoadCodingPatch"
          :on-reconcile="onRetryCoding"
        />
        <CodingEvidencePanel
          v-if="codingAttempt?.coding && codingAttempt.details"
          :task-id="details.id"
          :execution-id="codingAttempt.executionId"
          :commands-phase="codingCommandsPhase"
          :commands="codingCommands"
          :commands-error-message="codingCommandsErrorMessage"
          :tests-phase="codingTestsPhase"
          :tests="codingTests"
          :tests-error-message="codingTestsErrorMessage"
          :command-log="codingCommandLog"
          :test-report="codingTestReport"
          :on-load-commands-more="onLoadCodingCommandsMore"
          :on-load-tests-more="onLoadCodingTestsMore"
          :on-load-command-log="onLoadCodingCommandLog"
          :on-load-test-report="onLoadCodingTestReport"
        />
        <ReviewWorkbench
          v-if="codingAttempt?.coding && codingAttempt.details"
          :list-phase="reviewListPhase"
          :reviews="reviews"
          :selected-review-request-id="selectedReviewRequestId"
          :detail-phase="reviewDetailPhase"
          :review="review"
          :list-error-message="reviewListErrorMessage"
          :detail-error-message="reviewDetailErrorMessage"
          :coding-attempt="codingAttempt"
          :tests="codingTests"
          :can-gate="canGateReview"
          :online="online"
          :command="reviewCommand"
          :on-select="onSelectReview"
          :on-retry-list="onRetryReviews"
          :on-retry-detail="onRetryReviewDetail"
          :on-execute="onExecuteReviewer"
          :on-decide="onDecideReview"
          :on-request-changes="onRequestReviewChanges"
          :on-retry-command="onRetryReviewCommand"
          :on-clear-command="onClearReviewCommand"
          @locate="locateReviewFinding"
        />
        <ActionDeliveryWorkbench
          v-if="codingAttempt?.coding && codingAttempt.details"
          :task-id="details.id"
          :execution-id="codingAttempt.executionId"
          :objective="details.objective"
          :review="review"
          :online="online"
          :can-confirm="canConfirmDelivery"
        />
        <div class="task-detail-columns">
          <div class="task-detail-column task-detail-column--context">
            <section class="task-hero detail-card">
              <div class="task-hero__status">
                <StatusBadge :tone="taskTone(details.status)" dot>{{ details.status }}</StatusBadge>
                <span class="mono">v{{ details.version }}</span>
              </div>
              <h2>{{ details.objective }}</h2>
              <StatePanel
                v-if="details.status === 'CANCELLED' || currentAttempt?.status === 'CANCELLED'"
                class="task-lifecycle-state"
                compact
                state="cancelled"
                title="Task 已取消"
                description="耐久历史、已产生结果与审计证据继续保留，当前 Task 不再执行。"
              />
              <div class="acceptance">
                <p>验收标准</p>
                <ul><li v-for="criterion in details.acceptanceCriteria" :key="criterion"><CheckCircle2 :size="13" />{{ criterion }}</li></ul>
              </div>
              <dl class="compact-facts">
                <div><dt>来源</dt><dd>{{ details.source.type }}</dd></div>
                <div><dt>创建时间</dt><dd>{{ displayDate(details.audit.createdAt) }}</dd></div>
                <div><dt>WorkItem</dt><dd class="mono">{{ shortIdentifier(details.workItemId) }}</dd></div>
                <div><dt>快照时间</dt><dd>{{ displayDate(details.responsibilityCapturedAt) }}</dd></div>
              </dl>
            </section>

            <TaskControlPanel
              v-if="!codingAttempt?.coding"
              class="control-card"
              :attempt="currentAttempt"
              :can-control="canControl"
              :online="online"
              :pending="commandPending"
              :error-message="commandErrorMessage"
              :retryable="commandRetryable"
              :version-conflict="commandVersionConflict"
              :on-command="onCommand"
              :on-retry="onRetryCommand"
              :on-clear-feedback="onClearCommand"
            />

            <TaskTimelinePanel
              class="timeline-card"
              :phase="eventPhase"
              :page="eventPage"
              :error-message="eventErrorMessage"
              :live="liveState"
              :execution-id="selectedExecutionId"
              :execution-status="selectedAttempt?.status ?? null"
              :continuity-gap="selectedAttemptHasContinuityGap"
              :on-load-more="onLoadEventsMore"
              :on-retry="onRetryEvents"
            />

            <section v-if="associationPhase !== 'empty'" class="detail-card associations-card">
              <div class="section-heading"><div><p>Linked context</p><h3>关联对话</h3></div><MessageSquare :size="17" /></div>
              <StatePanel v-if="associationPhase === 'loading' || associationPhase === 'idle'" state="loading" title="正在读取关联对象" />
              <StatePanel v-else-if="associationPhase === 'error' && !associations" state="error" :description="associationErrorMessage ?? undefined" @retry="onRetryAssociations" />
              <template v-else-if="associations">
                <div v-if="associations.conversations.items.length" class="task-conversation-links">
                  <button
                    v-for="conversation in associations.conversations.items"
                    :key="conversation.id"
                    type="button"
                    @click="$emit('openConversation', conversation.id)"
                  >
                    <span><strong>{{ conversation.title }}</strong><small>{{ conversation.visibility }} · {{ displayDate(conversation.associatedAt) }}</small></span>
                    <MessageSquare :size="14" aria-hidden="true" />
                  </button>
                </div>
                <p v-else class="empty-note">这个 Task 没有当前成员可见的关联 Conversation。</p>
                <p v-if="associationErrorMessage" class="inline-error">{{ associationErrorMessage }} <button type="button" @click="onRetryAssociations"><RefreshCw :size="11" />刷新</button></p>
              </template>
            </section>

            <section class="detail-card responsibility-card">
              <div class="section-heading"><div><p>Accountability snapshot</p><h3>责任快照 <span>{{ details.responsibilitySnapshot.length }}</span></h3></div><ShieldCheck :size="17" /></div>
              <div v-if="details.responsibilitySnapshot.length" class="responsibility-list">
                <article v-for="entry in details.responsibilitySnapshot" :key="entry.assignmentId">
                  <i><CircleUserRound :size="15" /></i>
                  <div><strong>{{ principalName(entry.principalId) }}</strong><span>{{ entry.principalType }} · {{ displayDate(entry.acceptedAt) }}</span></div>
                  <StatusBadge>{{ roleLabel(entry.role) }}</StatusBadge>
                </article>
              </div>
              <p v-else class="empty-note">这个 Task 没有责任快照。</p>
            </section>

            <section class="detail-card attempt-card">
              <div class="section-heading"><div><p>Execution history</p><h3>Attempt 历史 <span>{{ attempts.length }}</span></h3></div><History :size="17" /></div>
              <ul class="attempt-list" aria-label="Task attempts">
                <li
                  v-for="attempt in orderedAttempts"
                  :key="attempt.id"
                >
                  <button
                    type="button"
                    :class="{ selected: attempt.id === selectedExecutionId }"
                    :aria-pressed="attempt.id === selectedExecutionId"
                    @click="onSelectAttempt(attempt.id)"
                  >
                    <span><strong>Attempt {{ attempt.attempt }}</strong><small>{{ attempt.id === details.currentExecutionId ? '当前' : '历史' }}</small></span>
                    <StatusBadge :tone="factTone(attempt.status)" dot>{{ attempt.status }}</StatusBadge>
                    <em v-if="attempt.waiting">{{ attempt.waiting.reason }}</em>
                    <em v-else-if="attempt.terminal?.failureCode">{{ attempt.terminal.failureCode }}</em>
                  </button>
                </li>
              </ul>
              <dl v-if="selectedAttempt" class="compact-facts attempt-facts">
                <div><dt>执行者</dt><dd>{{ selectedAttempt.executorPrincipalId ? principalName(selectedAttempt.executorPrincipalId) : '等待认领' }}</dd></div>
                <div><dt>优先级</dt><dd>{{ selectedAttempt.priority }}</dd></div>
                <div><dt>可运行时间</dt><dd>{{ displayDate(selectedAttempt.notBefore) }}</dd></div>
                <div><dt>最大尝试</dt><dd>{{ selectedAttempt.maxAttempts }}</dd></div>
              </dl>
            </section>

            <section class="detail-card fleet-card">
              <div class="section-heading"><div><p>Member-safe fleet</p><h3>Runtime 状态</h3></div><ServerCog :size="17" /></div>
              <StatePanel v-if="fleetPhase === 'loading' || fleetPhase === 'idle'" state="loading" title="正在读取 Runtime 健康" />
              <StatePanel v-else-if="fleetPhase === 'error' && !fleet" state="error" :description="fleetErrorMessage ?? undefined" @retry="onRetryFleet" />
              <template v-else-if="fleet">
                <div class="fleet-overview">
                  <div><StatusBadge :tone="factTone(fleet.health)" dot>{{ fleet.health }}</StatusBadge><small>{{ fleet.environment }}</small></div>
                  <strong>{{ fleet.capacity.available }}<span>/ {{ fleet.capacity.maximum }} 可用</span></strong>
                </div>
                <div v-if="fleet.staleWorkerCount > 0" class="runtime-alert" role="status"><TriangleAlert :size="15" /><span><strong>{{ fleet.staleWorkerCount }} 个 Worker 失联</strong>Fleet 已降级，当前 attempt 可能进入恢复或等待。</span></div>
                <dl class="compact-facts">
                  <div><dt>活跃 Worker</dt><dd>{{ fleet.activeWorkerCount }} / {{ fleet.workerCount }}</dd></div>
                  <div><dt>执行中容量</dt><dd>{{ fleet.capacity.active }}</dd></div>
                  <div><dt>等待 Runtime</dt><dd>{{ fleet.waitingRuntimeExecutions }}</dd></div>
                  <div><dt>观测时间</dt><dd>{{ displayDate(fleet.observedAt) }}</dd></div>
                </dl>
                <div v-if="fleet.waitingCauses.length" class="wait-causes"><span v-for="cause in fleet.waitingCauses" :key="cause.cause">{{ cause.cause }} · {{ cause.count }}</span></div>
                <p v-if="fleetErrorMessage" class="inline-error">{{ fleetErrorMessage }} <button type="button" @click="onRetryFleet"><RefreshCw :size="11" />刷新</button></p>
              </template>
            </section>
          </div>

          <div class="task-detail-column task-detail-column--runtime">
            <StatePanel v-if="runtimePhase === 'loading' || runtimePhase === 'idle'" state="loading" title="正在加载 Attempt Runtime 事实" />
            <StatePanel v-else-if="runtimePhase === 'error' && !runtimeFacts" state="error" :description="runtimeErrorMessage ?? undefined" @retry="onRetryRuntime" />
            <template v-else-if="runtimeFacts">
              <section class="detail-card plan-card">
                <div class="section-heading"><div><p>Durable plan</p><h3>执行计划</h3></div><GitBranch :size="17" /></div>
                <label v-if="orderedPlans.length > 1" class="plan-selector"><span>计划版本</span><select v-model="selectedPlanVersionId"><option v-for="plan in orderedPlans" :key="plan.id" :value="plan.id">Revision {{ plan.revision }} · {{ plan.changeReason }}</option></select></label>
                <template v-if="selectedPlan">
                  <div class="plan-meta"><StatusBadge tone="info">Revision {{ selectedPlan.revision }}</StatusBadge><span>{{ displayDate(selectedPlan.publishedAt) }}</span></div>
                  <p class="plan-markdown">{{ selectedPlan.markdown }}</p>
                  <ul v-if="selectedPlan.todoSummary.length" class="todo-summary"><li v-for="todo in selectedPlan.todoSummary" :key="`${todo.planStepKey}:${todo.content}`"><StatusBadge :tone="factTone(todo.status)">{{ todo.status }}</StatusBadge><span>{{ todo.content }}</span></li></ul>
                </template>
                <p v-else class="empty-note">这个 attempt 尚未发布 PlanVersion。</p>
              </section>

              <section class="detail-card steps-card">
                <div class="section-heading"><div><p>Step progress</p><h3>步骤进度 <span>{{ completedSteps }}/{{ selectedPlanSteps.length }}</span></h3></div><Layers3 :size="17" /></div>
                <div v-if="selectedPlanSteps.length" class="step-list">
                  <article v-for="step in selectedPlanSteps" :key="step.id">
                    <i>{{ step.sequence }}</i>
                    <div><strong>{{ selectedPlan?.steps.find(item => item.key === step.planStepKey)?.title ?? step.planStepKey }}</strong><span>Run {{ step.runAttempt }}/{{ step.maxRunAttempts }}<template v-if="step.checkpoint"> · Checkpoint {{ step.checkpoint.sequence }}</template></span><em v-if="step.waitReason">{{ step.waitReason }}</em></div>
                    <StatusBadge :tone="factTone(step.status)" dot>{{ step.status }}</StatusBadge>
                  </article>
                </div>
                <p v-else class="empty-note">当前计划还没有 StepExecution。</p>
              </section>

              <section class="detail-card runs-card">
                <div class="section-heading"><div><p>AgentScope execution</p><h3>Agent Runs <span>{{ runtimeFacts.agentRuns.length }}</span></h3></div><Bot :size="17" /></div>
                <div v-if="runtimeFacts.agentRuns.length" class="run-list">
                  <article v-for="run in runtimeFacts.agentRuns" :key="run.id">
                    <header><div><strong>Run {{ run.runSequence }}</strong><span>{{ shortIdentifier(run.id) }} · Profile v{{ run.agentProfileVersion }}</span></div><StatusBadge :tone="factTone(run.status)" dot>{{ run.status }}</StatusBadge></header>
                    <dl><div><dt>Session</dt><dd class="mono">{{ shortIdentifier(run.runtimeSessionId) }}</dd></div><div><dt>Segments</dt><dd>{{ run.segments.length }}</dd></div></dl>
                    <div v-if="run.continuityGap" class="runtime-alert"><TriangleAlert :size="15" /><span><strong>执行连续性缺口</strong>{{ run.continuityGap.reason }} · Checkpoint {{ run.continuityGap.firstMissingCheckpoint }}–{{ run.continuityGap.lastMissingCheckpoint }}</span></div>
                  </article>
                </div>
                <p v-else class="empty-note">这个 attempt 尚未创建 AgentRun。</p>
                <div v-if="runtimeFacts.sessions.length" class="session-strip"><Activity :size="14" /><span>{{ runtimeFacts.sessions.length }} 个 Session · {{ runtimeFacts.sessions.map(item => item.status).join(' / ') }}</span></div>
              </section>

              <section class="detail-card lease-card">
                <div class="section-heading"><div><p>Safe runtime projection</p><h3>Lease 与恢复事实</h3></div><Cpu :size="17" /></div>
                <div v-if="runtimeFacts.leases.length" class="lease-list">
                  <article v-for="lease in runtimeFacts.leases" :key="lease.id">
                    <header><div><strong>{{ lease.environment }} · {{ lease.phase }}</strong><span>Lease {{ shortIdentifier(lease.id) }}</span></div><StatusBadge :tone="factTone(lease.status)" dot>{{ lease.status }}</StatusBadge></header>
                    <dl class="compact-facts"><div><dt>Runtime</dt><dd class="mono">{{ shortIdentifier(lease.runtimeId) }}</dd></div><div><dt>Worker</dt><dd class="mono">{{ shortIdentifier(lease.workerId) }}</dd></div><div><dt>最近心跳</dt><dd>{{ displayDate(lease.lastHeartbeatAt) }}</dd></div><div><dt>失效时间</dt><dd>{{ displayDate(lease.expiresAt) }}</dd></div></dl>
                    <p v-if="lease.releaseReason" class="lease-reason">{{ lease.releaseReason }}</p>
                  </article>
                </div>
                <p v-else class="empty-note">这个 attempt 没有公开 Lease 事实。</p>
                <div class="recovery-grid">
                  <article><span>State Snapshot</span><strong>{{ runtimeFacts.snapshots.length }}</strong><small>{{ runtimeFacts.snapshots.map(item => `${item.status} · checkpoint ${item.checkpointSequence}`).join(' / ') || '无快照' }}</small></article>
                  <article><span>Interrupt</span><strong>{{ runtimeFacts.interrupts.length }}</strong><small>{{ runtimeFacts.interrupts.map(item => `${item.kind} · ${item.status}`).join(' / ') || '无中断' }}</small></article>
                </div>
                <p class="security-note"><ShieldCheck :size="13" />这里只展示成员安全的公开投影，执行凭证与内部运行载荷不会进入页面状态。</p>
                <p v-if="runtimeErrorMessage" class="inline-error">{{ runtimeErrorMessage }} <button type="button" @click="onRetryRuntime"><RefreshCw :size="11" />刷新</button></p>
              </section>
            </template>
          </div>
        </div>
      </div>

      <footer class="task-detail-footer">
        <span><Clock3 :size="13" />详情来自耐久 Task Runtime 公开投影</span>
        <BaseButton variant="secondary" size="small" @click="$emit('openWorkItem')">查看工作项</BaseButton>
      </footer>
    </aside>
  </div>
</template>

<style scoped>
.execution-studio, .diff-explorer, .evidence-panel { margin-bottom: 10px; }
.task-detail-backdrop { position: fixed; inset: 0; z-index: 70; background: rgb(21 35 29 / 24%); backdrop-filter: blur(2px); }.task-detail-drawer { position: absolute; inset: 0 0 0 auto; display: grid; width: min(1040px, 96vw); grid-template-rows: auto minmax(0, 1fr) auto; border-left: 1px solid var(--cs-border-strong); background: var(--cs-canvas); box-shadow: -24px 0 64px rgb(21 35 29 / 14%); }.task-detail-header { display: flex; min-height: 64px; align-items: center; justify-content: space-between; gap: 16px; padding: 11px 16px 11px 20px; border-bottom: 1px solid var(--cs-border); background: var(--cs-surface); }.task-detail-header p, .task-detail-header strong { display: block; margin: 0; }.task-detail-header p { color: var(--cs-text-muted); font-size: 9px; font-weight: 750; letter-spacing: .08em; text-transform: uppercase; }.task-detail-header strong { margin-top: 2px; color: var(--cs-brand-700); font: 12px var(--cs-font-mono); }.task-detail-header button { display: grid; width: 34px; height: 34px; place-items: center; border-radius: 9px; background: var(--cs-surface-subtle); cursor: pointer; }.task-detail-content { min-height: 0; overflow-y: auto; padding: 12px; }.detail-sync-state { margin-bottom: 10px; }.task-detail-columns { display: grid; grid-template-columns: minmax(310px, .78fr) minmax(430px, 1.22fr); align-items: start; gap: 10px; }.task-detail-column { display: grid; gap: 10px; }.detail-card { min-width: 0; padding: 16px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }.task-hero { padding: 19px; }.task-hero__status { display: flex; align-items: center; justify-content: space-between; }.task-hero__status > span { color: var(--cs-text-muted); font-size: 9px; }.task-hero h2 { margin: 13px 0 15px; font-size: 19px; line-height: 1.32; }.task-lifecycle-state { margin: -4px 0 13px; }.acceptance { padding: 11px; border-radius: 9px; background: var(--cs-brand-50); }.acceptance p { margin: 0 0 7px; color: var(--cs-brand-700); font-size: 9px; font-weight: 800; }.acceptance ul { display: grid; gap: 6px; margin: 0; padding: 0; list-style: none; }.acceptance li { display: flex; align-items: flex-start; gap: 6px; color: var(--cs-text-secondary); font-size: 10px; line-height: 1.45; }.acceptance li svg { flex: 0 0 auto; margin-top: 1px; color: var(--cs-success); }.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }.section-heading p { margin: 0 0 2px; color: var(--cs-brand-600); font-size: 8px; font-weight: 750; letter-spacing: .08em; text-transform: uppercase; }.section-heading h3 { margin: 0; font-size: 12px; }.section-heading h3 span { color: var(--cs-text-muted); font-weight: 500; }.section-heading > svg { color: var(--cs-text-muted); }.compact-facts { display: grid; grid-template-columns: 1fr 1fr; margin: 12px 0 0; }.compact-facts > div { min-width: 0; padding: 7px 0; border-top: 1px solid var(--cs-border); }.compact-facts dt { color: var(--cs-text-muted); font-size: 8px; }.compact-facts dd { min-width: 0; margin: 3px 0 0; overflow: hidden; color: var(--cs-text-secondary); font-size: 9px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }.responsibility-list, .step-list, .run-list, .lease-list { display: grid; gap: 7px; }.responsibility-list article { display: grid; grid-template-columns: 30px minmax(0, 1fr) auto; align-items: center; gap: 8px; padding: 8px; border-radius: 9px; background: var(--cs-surface-subtle); }.responsibility-list i { display: grid; width: 30px; height: 30px; place-items: center; border-radius: 9px; background: var(--cs-brand-100); color: var(--cs-brand-700); }.responsibility-list strong, .responsibility-list span { display: block; }.responsibility-list strong { overflow: hidden; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }.responsibility-list span { margin-top: 2px; color: var(--cs-text-muted); font-size: 8px; }.attempt-list { display: grid; gap: 6px; padding: 0; margin: 0; list-style: none; }.attempt-list button { display: grid; width: 100%; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 4px 8px; padding: 9px; border: 1px solid var(--cs-border); border-radius: 9px; background: var(--cs-surface-subtle); text-align: left; cursor: pointer; }.attempt-list button.selected { border-color: var(--cs-brand-300); background: var(--cs-brand-50); box-shadow: 0 0 0 2px var(--cs-brand-50); }.attempt-list button > span { display: flex; align-items: baseline; gap: 6px; }.attempt-list strong { font-size: 10px; }.attempt-list small, .attempt-list em { color: var(--cs-text-muted); font-size: 8px; font-style: normal; }.attempt-list em { grid-column: 1 / -1; }.fleet-overview { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 11px; border-radius: 9px; background: var(--cs-surface-subtle); }.fleet-overview > div { display: flex; align-items: center; gap: 7px; }.fleet-overview small { color: var(--cs-text-muted); font: 8px var(--cs-font-mono); }.fleet-overview > strong { font-size: 19px; }.fleet-overview > strong span { margin-left: 3px; color: var(--cs-text-muted); font-size: 8px; font-weight: 600; }.runtime-alert { display: flex; align-items: flex-start; gap: 8px; margin-top: 8px; padding: 9px; border: 1px solid #efd4aa; border-radius: 9px; background: var(--cs-warning-soft); color: var(--cs-warning); }.runtime-alert svg { flex: 0 0 auto; }.runtime-alert span, .runtime-alert strong { display: block; }.runtime-alert span { color: var(--cs-text-muted); font-size: 8px; line-height: 1.45; }.runtime-alert strong { margin-bottom: 2px; color: #7c4a12; font-size: 9px; }.wait-causes { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 8px; }.wait-causes span, .lease-reason { padding: 4px 7px; border-radius: 6px; background: var(--cs-warning-soft); color: #7c4a12; font: 8px var(--cs-font-mono); }.plan-selector { display: grid; gap: 5px; margin-bottom: 10px; color: var(--cs-text-secondary); font-size: 8px; font-weight: 750; }.plan-selector select { min-height: 34px; padding: 0 9px; border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-text); font: 9px var(--cs-font-sans); }.plan-meta { display: flex; align-items: center; justify-content: space-between; gap: 10px; }.plan-meta > span { color: var(--cs-text-muted); font-size: 8px; }.plan-markdown { margin: 11px 0 0; color: var(--cs-text-secondary); font-size: 10px; line-height: 1.6; white-space: pre-wrap; }.todo-summary { display: grid; gap: 6px; margin: 12px 0 0; padding: 10px; border-radius: 9px; background: var(--cs-surface-subtle); list-style: none; }.todo-summary li { display: grid; grid-template-columns: auto minmax(0, 1fr); align-items: center; gap: 7px; color: var(--cs-text-secondary); font-size: 9px; }.step-list article { display: grid; grid-template-columns: 28px minmax(0, 1fr) auto; align-items: start; gap: 8px; padding: 9px; border-radius: 9px; background: var(--cs-surface-subtle); }.step-list article > i { display: grid; width: 28px; height: 28px; place-items: center; border-radius: 8px; background: var(--cs-brand-100); color: var(--cs-brand-700); font-size: 9px; font-style: normal; font-weight: 800; }.step-list strong, .step-list span, .step-list em { display: block; }.step-list strong { font-size: 10px; }.step-list span { margin-top: 2px; color: var(--cs-text-muted); font-size: 8px; }.step-list em { width: fit-content; margin-top: 5px; padding: 3px 5px; border-radius: 5px; background: var(--cs-warning-soft); color: #7c4a12; font-size: 8px; font-style: normal; }.run-list > article, .lease-list > article { padding: 10px; border: 1px solid var(--cs-border); border-radius: 9px; }.run-list header, .lease-list header { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }.run-list header strong, .run-list header span, .lease-list header strong, .lease-list header span { display: block; }.run-list header strong, .lease-list header strong { font-size: 10px; }.run-list header span, .lease-list header span { margin-top: 2px; color: var(--cs-text-muted); font: 8px var(--cs-font-mono); }.run-list dl { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin: 9px 0 0; }.run-list dt { color: var(--cs-text-muted); font-size: 8px; }.run-list dd { margin: 2px 0 0; font-size: 8px; }.session-strip { display: flex; align-items: center; gap: 6px; margin-top: 9px; padding: 8px; border-radius: 8px; background: var(--cs-agent-soft); color: var(--cs-agent); font-size: 8px; }.recovery-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 7px; margin-top: 9px; }.recovery-grid article { display: grid; gap: 3px; padding: 9px; border-radius: 9px; background: var(--cs-surface-subtle); }.recovery-grid span, .recovery-grid small { color: var(--cs-text-muted); font-size: 8px; }.recovery-grid strong { font-size: 16px; }.security-note { display: flex; align-items: flex-start; gap: 6px; margin: 10px 0 0; padding: 8px; border-radius: 8px; background: var(--cs-brand-50); color: var(--cs-brand-700); font-size: 8px; line-height: 1.45; }.security-note svg { flex: 0 0 auto; }.empty-note { margin: 0; color: var(--cs-text-muted); font-size: 9px; }.inline-error { margin: 8px 0 0; color: var(--cs-danger); font-size: 8px; }.inline-error button { display: inline-flex; align-items: center; gap: 3px; color: inherit; text-decoration: underline; cursor: pointer; }.task-detail-footer { display: flex; min-height: 52px; align-items: center; justify-content: space-between; gap: 12px; padding: 9px 14px; border-top: 1px solid var(--cs-border); background: var(--cs-surface); }.task-detail-footer > span { display: flex; align-items: center; gap: 5px; color: var(--cs-text-muted); font-size: 8px; }
.task-conversation-links { display: grid; gap: 6px; }.task-conversation-links > button { display: flex; width: 100%; align-items: center; justify-content: space-between; gap: 9px; padding: 9px; border: 1px solid var(--cs-border); border-radius: 9px; background: var(--cs-surface-subtle); color: var(--cs-text); text-align: left; cursor: pointer; }.task-conversation-links > button:hover { border-color: var(--cs-brand-200); background: var(--cs-brand-50); }.task-conversation-links span, .task-conversation-links strong, .task-conversation-links small { display: block; }.task-conversation-links strong { font-size: 10px; }.task-conversation-links small { margin-top: 3px; color: var(--cs-text-muted); font-size: 8px; }.task-conversation-links svg { flex: 0 0 auto; color: var(--cs-brand-600); }.associations-card :deep(.state-panel) { min-height: 100px; border: 0; }
@media (max-width: 767px) { .task-detail-drawer { width: 100%; }.task-detail-content { padding: 9px; }.task-detail-columns { grid-template-columns: 1fr; }.task-detail-column { display: contents; }.task-hero { order: 1; }.control-card { order: 2; }.timeline-card { order: 3; }.associations-card { order: 4; }.responsibility-card { order: 5; }.attempt-card { order: 6; }.fleet-card { order: 7; }.plan-card { order: 8; }.steps-card { order: 9; }.runs-card { order: 10; }.lease-card { order: 11; }.task-hero h2 { font-size: 17px; }.task-detail-footer > span { display: none; }.task-detail-footer > :deep(button) { width: 100%; } }
</style>
