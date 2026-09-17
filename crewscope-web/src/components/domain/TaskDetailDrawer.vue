<script setup lang="ts">
import {
  Bot,
  CheckCircle2,
  CircleUserRound,
  Clock3,
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
import type { EtaggedReview, ReviewDecisionInput, ReviewFindingEvidence, ReviewLineComment, ReviewSummary } from '../../domains/review/types'
import type {
  MemberTaskCommandOperation,
  RuntimeFleetSummary,
  TaskAssociations,
  TaskDetails,
  TaskEventPage,
  TaskExecution,
  TaskRuntimeFacts,
  TaskStatus,
  TaskCommandVersionConflict,
} from '../../domains/task/types'
import { enumLabel } from '../../domains/shared/labels'
import { principalTypeLabels } from '../../domains/principal/labels'
import {
  runtimeFleetHealthLabels,
  runtimeWaitCauseLabels,
  taskSourceTypeLabels,
  taskStatusLabels,
} from '../../domains/task/labels'
import { conversationVisibilityLabels } from '../../domains/conversation/labels'
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
import TaskAttemptHistoryPanel from './TaskAttemptHistoryPanel.vue'
import TaskRuntimeFactsPanel from './TaskRuntimeFactsPanel.vue'

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
  reviewComments?: ReviewLineComment[]
  onAddReviewComment?: (input: { filePath: string; side: 'OLD' | 'NEW'; lineNumber: number; hunkHeader: string; lineContentHash: string; diffGeneration: number; content: string }) => Promise<ReviewLineComment | null>
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
const reviewLocation = ref<ReviewFindingEvidence | null>(null)
let previousBodyOverflow = ''

const selectedAttempt = computed(() => props.attempts.find(item => item.id === props.selectedExecutionId) ?? null)
const currentAttempt = computed(() => props.attempts.find(item => item.id === props.details?.currentExecutionId) ?? null)
const selectedAttemptHasContinuityGap = computed(() => props.runtimeFacts?.agentRuns.some(run => Boolean(run.continuityGap)) ?? false)

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

/**
 * Runtime facts arrive as `{Enum}.name()` strings over a pass-through gateway, so every one of
 * them is read through the domain's own label map. `enumLabel` keeps an unrecognised future value
 * visible instead of blanking the field.
 */
function label(value: string | null | undefined, labels: Record<string, string>): string {
  return enumLabel(value, labels)
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
    <aside ref="drawer" class="task-detail-drawer" role="dialog" aria-modal="true" tabindex="-1" :aria-label="details ? `${details.objective} Task 详情` : 'Task 详情'">
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
          :review-comments="reviewComments"
          :on-add-comment="onAddReviewComment"
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
                <StatusBadge :tone="taskTone(details.status)" dot>{{ taskStatusLabels[details.status] }}</StatusBadge>
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
                <div><dt>来源</dt><dd>{{ label(details.source.type, taskSourceTypeLabels) }}</dd></div>
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
                    <span><strong>{{ conversation.title }}</strong><small>{{ label(conversation.visibility, conversationVisibilityLabels) }} · {{ displayDate(conversation.associatedAt) }}</small></span>
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
                  <div><strong>{{ principalName(entry.principalId) }}</strong><span>{{ label(entry.principalType, principalTypeLabels) }} · {{ displayDate(entry.acceptedAt) }}</span></div>
                  <StatusBadge>{{ roleLabel(entry.role) }}</StatusBadge>
                </article>
              </div>
              <p v-else class="empty-note">这个 Task 没有责任快照。</p>
            </section>

            <TaskAttemptHistoryPanel
              :attempts="attempts"
              :selected-execution-id="selectedExecutionId"
              :current-execution-id="details.currentExecutionId"
              :principal-name="principalName"
              :display-date="displayDate"
              :fact-tone="factTone"
              @select-attempt="onSelectAttempt"
            />

            <section class="detail-card fleet-card">
              <div class="section-heading"><div><p>Member-safe fleet</p><h3>Runtime 状态</h3></div><ServerCog :size="17" /></div>
              <StatePanel v-if="fleetPhase === 'loading' || fleetPhase === 'idle'" state="loading" title="正在读取 Runtime 健康" />
              <StatePanel v-else-if="fleetPhase === 'error' && !fleet" state="error" :description="fleetErrorMessage ?? undefined" @retry="onRetryFleet" />
              <template v-else-if="fleet">
                <div class="fleet-overview">
                  <div><StatusBadge :tone="factTone(fleet.health)" dot>{{ label(fleet.health, runtimeFleetHealthLabels) }}</StatusBadge><small>{{ fleet.environment }}</small></div>
                  <strong>{{ fleet.capacity.available }}<span>/ {{ fleet.capacity.maximum }} 可用</span></strong>
                </div>
                <div v-if="fleet.staleWorkerCount > 0" class="runtime-alert" role="status"><TriangleAlert :size="15" /><span><strong>{{ fleet.staleWorkerCount }} 个 Worker 失联</strong>Fleet 已降级，当前 attempt 可能进入恢复或等待。</span></div>
                <dl class="compact-facts">
                  <div><dt>活跃 Worker</dt><dd>{{ fleet.activeWorkerCount }} / {{ fleet.workerCount }}</dd></div>
                  <div><dt>执行中容量</dt><dd>{{ fleet.capacity.active }}</dd></div>
                  <div><dt>等待 Runtime</dt><dd>{{ fleet.waitingRuntimeExecutions }}</dd></div>
                  <div><dt>观测时间</dt><dd>{{ displayDate(fleet.observedAt) }}</dd></div>
                </dl>
                <div v-if="fleet.waitingCauses.length" class="wait-causes"><span v-for="cause in fleet.waitingCauses" :key="cause.cause">{{ label(cause.cause, runtimeWaitCauseLabels) }} · {{ cause.count }}</span></div>
                <p v-if="fleetErrorMessage" class="inline-error">{{ fleetErrorMessage }} <button type="button" @click="onRetryFleet"><RefreshCw :size="11" />刷新</button></p>
              </template>
            </section>
          </div>

          <div class="task-detail-column task-detail-column--runtime">
            <TaskRuntimeFactsPanel
              :phase="runtimePhase"
              :facts="runtimeFacts"
              :error-message="runtimeErrorMessage"
              :display-date="displayDate"
              :fact-tone="factTone"
              :on-retry="onRetryRuntime"
            />
            <!-- Runtime facts are rendered by TaskRuntimeFactsPanel; the drawer keeps selection and orchestration. -->
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
.execution-studio, .diff-explorer, .evidence-panel { margin-bottom: var(--cs-space-12); }
.task-detail-backdrop { position: fixed; inset: 0; z-index: var(--cs-z-drawer); background: var(--cs-scrim); backdrop-filter: blur(2px); }.task-detail-drawer { position: absolute; inset: 0 0 0 auto; display: grid; width: min(1040px, 96vw); grid-template-rows: auto minmax(0, 1fr) auto; border-left: 1px solid var(--cs-border-strong); background: var(--cs-canvas); box-shadow: var(--cs-shadow-drawer); }.task-detail-header { display: flex; min-height: 64px; align-items: center; justify-content: space-between; gap: var(--cs-space-16); padding: var(--cs-space-12) var(--cs-space-16) var(--cs-space-12) var(--cs-space-20); border-bottom: 1px solid var(--cs-border); background: var(--cs-surface); }.task-detail-header p, .task-detail-header strong { display: block; margin: 0; }.task-detail-header p { color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); letter-spacing: .08em; text-transform: uppercase; }.task-detail-header strong { margin-top: var(--cs-space-2); color: var(--cs-text-brand); font: var(--cs-text-base) var(--cs-font-mono); }.task-detail-header button { display: grid; width: 34px; height: 34px; place-items: center; border-radius: 9px; background: var(--cs-surface-subtle); cursor: pointer; }.task-detail-content { min-height: 0; overflow-y: auto; padding: var(--cs-space-12); }.detail-sync-state { margin-bottom: var(--cs-space-12); }.task-detail-columns { display: grid; grid-template-columns: minmax(310px, .78fr) minmax(430px, 1.22fr); align-items: start; gap: var(--cs-space-12); }.task-detail-column { display: grid; gap: var(--cs-space-12); }.detail-card { min-width: 0; padding: var(--cs-space-16); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }.task-hero { padding: var(--cs-space-20); }.task-hero__status { display: flex; align-items: center; justify-content: space-between; }.task-hero__status > span { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.task-hero h2 { margin: var(--cs-space-12) 0 var(--cs-space-16); font-size: var(--cs-text-lg); line-height: var(--cs-leading-tight); }.task-lifecycle-state { margin: calc(var(--cs-space-4) * -1) 0 var(--cs-space-12); }.acceptance { padding: var(--cs-space-12); border-radius: 9px; background: var(--cs-surface-accent); }.acceptance p { margin: 0 0 var(--cs-space-8); color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.acceptance ul { display: grid; gap: var(--cs-space-8); margin: 0; padding: 0; list-style: none; }.acceptance li { display: flex; align-items: flex-start; gap: var(--cs-space-8); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); line-height: var(--cs-leading-normal); }.acceptance li svg { flex: 0 0 auto; margin-top: var(--cs-space-2); color: var(--cs-success); }.section-heading { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-12); margin-bottom: var(--cs-space-12); }.section-heading p { margin: 0 0 var(--cs-space-2); color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); letter-spacing: .08em; text-transform: uppercase; }.section-heading h3 { margin: 0; font-size: var(--cs-text-base); }.section-heading h3 span { color: var(--cs-text-muted); font-weight: var(--cs-weight-medium); }.section-heading > svg { color: var(--cs-text-muted); }.compact-facts { display: grid; grid-template-columns: 1fr 1fr; margin: var(--cs-space-12) 0 0; }.compact-facts > div { min-width: 0; padding: var(--cs-space-8) 0; border-top: 1px solid var(--cs-border); }.compact-facts dt { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.compact-facts dd { min-width: 0; margin: var(--cs-space-4) 0 0; overflow: hidden; color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); text-overflow: ellipsis; white-space: nowrap; }.responsibility-list, .step-list, .run-list, .lease-list { display: grid; gap: var(--cs-space-8); }.responsibility-list article { display: grid; grid-template-columns: 30px minmax(0, 1fr) auto; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-8); border-radius: 9px; background: var(--cs-surface-subtle); }.responsibility-list i { display: grid; width: 30px; height: 30px; place-items: center; border-radius: 9px; background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); }.responsibility-list strong, .responsibility-list span { display: block; }.responsibility-list strong { overflow: hidden; font-size: var(--cs-text-sm); text-overflow: ellipsis; white-space: nowrap; }.responsibility-list span { margin-top: var(--cs-space-2); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.attempt-list { display: grid; gap: var(--cs-space-8); padding: 0; margin: 0; list-style: none; }.attempt-list button { display: grid; width: 100%; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: var(--cs-space-4) var(--cs-space-8); padding: var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: 9px; background: var(--cs-surface-subtle); text-align: left; cursor: pointer; }.attempt-list button.selected { border-color: var(--cs-border-accent-strong); background: var(--cs-surface-accent); box-shadow: 0 0 0 2px var(--cs-ring-brand); }.attempt-list button > span { display: flex; align-items: baseline; gap: var(--cs-space-8); }.attempt-list strong { font-size: var(--cs-text-sm); }.attempt-list small, .attempt-list em { color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-style: normal; }.attempt-list em { grid-column: 1 / -1; }.fleet-overview { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-12); padding: var(--cs-space-12); border-radius: 9px; background: var(--cs-surface-subtle); }.fleet-overview > div { display: flex; align-items: center; gap: var(--cs-space-8); }.fleet-overview small { color: var(--cs-text-muted); font: var(--cs-text-xs) var(--cs-font-mono); }.fleet-overview > strong { font-size: var(--cs-text-lg); }.fleet-overview > strong span { margin-left: var(--cs-space-4); color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.runtime-alert { display: flex; align-items: flex-start; gap: var(--cs-space-8); margin-top: var(--cs-space-8); padding: var(--cs-space-8); border: 1px solid var(--cs-warning-border); border-radius: 9px; background: var(--cs-warning-soft); color: var(--cs-warning); }.runtime-alert svg { flex: 0 0 auto; }.runtime-alert span, .runtime-alert strong { display: block; }.runtime-alert span { color: var(--cs-text-muted); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }.runtime-alert strong { margin-bottom: var(--cs-space-2); color: var(--cs-warning); font-size: var(--cs-text-xs); }.wait-causes { display: flex; flex-wrap: wrap; gap: var(--cs-space-4); margin-top: var(--cs-space-8); }.wait-causes span, .lease-reason { padding: var(--cs-space-4) var(--cs-space-8); border-radius: 6px; background: var(--cs-warning-soft); color: var(--cs-warning); font: var(--cs-text-xs) var(--cs-font-mono); }.plan-selector { display: grid; gap: var(--cs-space-4); margin-bottom: var(--cs-space-12); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.plan-selector select { min-height: 34px; padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-text); font: var(--cs-text-base) var(--cs-font-sans); }.plan-meta { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-12); }.plan-meta > span { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.plan-markdown { margin: var(--cs-space-12) 0 0; color: var(--cs-text-secondary); font-size: var(--cs-text-sm); line-height: var(--cs-leading-normal); white-space: pre-wrap; }.todo-summary { display: grid; gap: var(--cs-space-8); margin: var(--cs-space-12) 0 0; padding: var(--cs-space-12); border-radius: 9px; background: var(--cs-surface-subtle); list-style: none; }.todo-summary li { display: grid; grid-template-columns: auto minmax(0, 1fr); align-items: center; gap: var(--cs-space-8); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); }.step-list article { display: grid; grid-template-columns: 28px minmax(0, 1fr) auto; align-items: start; gap: var(--cs-space-8); padding: var(--cs-space-8); border-radius: 9px; background: var(--cs-surface-subtle); }.step-list article > i { display: grid; width: 28px; height: 28px; place-items: center; border-radius: 8px; background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-style: normal; font-weight: var(--cs-weight-semibold); }.step-list strong, .step-list span, .step-list em { display: block; }.step-list strong { font-size: var(--cs-text-sm); }.step-list span { margin-top: var(--cs-space-2); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.step-list em { width: fit-content; margin-top: var(--cs-space-4); padding: var(--cs-space-4) var(--cs-space-4); border-radius: 5px; background: var(--cs-warning-soft); color: var(--cs-warning); font-size: var(--cs-text-xs); font-style: normal; }.run-list > article, .lease-list > article { padding: var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: 9px; }.run-list header, .lease-list header { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--cs-space-12); }.run-list header strong, .run-list header span, .lease-list header strong, .lease-list header span { display: block; }.run-list header strong, .lease-list header strong { font-size: var(--cs-text-sm); }.run-list header span, .lease-list header span { margin-top: var(--cs-space-2); color: var(--cs-text-muted); font: var(--cs-text-xs) var(--cs-font-mono); }.run-list dl { display: grid; grid-template-columns: 1fr 1fr; gap: var(--cs-space-8); margin: var(--cs-space-8) 0 0; }.run-list dt { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.run-list dd { margin: var(--cs-space-2) 0 0; font-size: var(--cs-text-xs); }.session-strip { display: flex; align-items: center; gap: var(--cs-space-8); margin-top: var(--cs-space-8); padding: var(--cs-space-8); border-radius: 8px; background: var(--cs-agent-soft); color: var(--cs-agent); font-size: var(--cs-text-xs); }.recovery-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--cs-space-8); margin-top: var(--cs-space-8); }.recovery-grid article { display: grid; gap: var(--cs-space-4); padding: var(--cs-space-8); border-radius: 9px; background: var(--cs-surface-subtle); }.recovery-grid span, .recovery-grid small { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.recovery-grid strong { font-size: var(--cs-text-md); }.security-note { display: flex; align-items: flex-start; gap: var(--cs-space-8); margin: var(--cs-space-12) 0 0; padding: var(--cs-space-8); border-radius: 8px; background: var(--cs-surface-accent); color: var(--cs-text-brand); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }.security-note svg { flex: 0 0 auto; }.empty-note { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.inline-error { margin: var(--cs-space-8) 0 0; color: var(--cs-danger); font-size: var(--cs-text-xs); }.inline-error button { display: inline-flex; align-items: center; gap: var(--cs-space-4); color: inherit; text-decoration: underline; cursor: pointer; }.task-detail-footer { display: flex; min-height: 52px; align-items: center; justify-content: space-between; gap: var(--cs-space-12); padding: var(--cs-space-8) var(--cs-space-16); border-top: 1px solid var(--cs-border); background: var(--cs-surface); }.task-detail-footer > span { display: flex; align-items: center; gap: var(--cs-space-4); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.task-conversation-links { display: grid; gap: var(--cs-space-8); }.task-conversation-links > button { display: flex; width: 100%; align-items: center; justify-content: space-between; gap: var(--cs-space-8); padding: var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: 9px; background: var(--cs-surface-subtle); color: var(--cs-text); text-align: left; cursor: pointer; }.task-conversation-links > button:hover { border-color: var(--cs-border-accent); background: var(--cs-surface-accent); }.task-conversation-links span, .task-conversation-links strong, .task-conversation-links small { display: block; }.task-conversation-links strong { font-size: var(--cs-text-sm); }.task-conversation-links small { margin-top: var(--cs-space-4); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.task-conversation-links svg { flex: 0 0 auto; color: var(--cs-text-brand); }.associations-card :deep(.state-panel) { min-height: 100px; border: 0; }
@media (max-width: 767px) { .task-detail-drawer { width: 100%; }.task-detail-content { padding: var(--cs-space-8); }.task-detail-columns { grid-template-columns: 1fr; }.task-detail-column { display: contents; }.task-hero { order: 1; }.control-card { order: 2; }.timeline-card { order: 3; }.associations-card { order: 4; }.responsibility-card { order: 5; }.attempt-card { order: 6; }.fleet-card { order: 7; }.task-hero h2 { font-size: var(--cs-text-lg); }.task-detail-footer > span { display: none; }.task-detail-footer > :deep(button) { width: 100%; } }
</style>
