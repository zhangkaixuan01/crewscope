<script setup lang="ts">
import {
  Bot,
  CheckCircle2,
  ChevronRight,
  CircleUserRound,
  FileDiff,
  GitCompareArrows,
  History,
  ListChecks,
  RefreshCw,
  ShieldAlert,
  ShieldCheck,
  TriangleAlert,
  X,
} from '@lucide/vue'
import { computed, nextTick, ref, useTemplateRef } from 'vue'
import { isTopmostModal } from '../../app/dialog'
import type { CodingAttemptSummary, EvidencePage, TestEvidenceSummary } from '../../domains/coding/types'
import type { ReviewCommandState, ReviewPhase } from '../../domains/review/store'
import type {
  EtaggedReview,
  ReviewDecisionInput,
  ReviewDecisionType,
  ReviewFindingEvidence,
  ReviewSummary,
} from '../../domains/review/types'
import type { SemanticTone } from '../base/types'
import { enumLabel } from '../../domains/shared/labels'
import {
  acceptanceStatusLabels,
  reviewDecisionTypeLabels,
  reviewFindingCategoryLabels,
  reviewFindingSeverityLabels,
  reviewInvalidationReasonLabels,
  reviewRequestStatusLabels,
  reviewerEligibilityModeLabels,
  reviewerRelationshipLabels,
} from '../../domains/review/labels'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'

const props = defineProps<{
  listPhase: ReviewPhase
  reviews: ReviewSummary[] | null
  selectedReviewRequestId: string | null
  detailPhase: ReviewPhase
  review: EtaggedReview | null
  listErrorMessage: string | null
  detailErrorMessage: string | null
  codingAttempt: CodingAttemptSummary | null
  tests: EvidencePage<TestEvidenceSummary> | null
  canGate: boolean
  online: boolean
  command: ReviewCommandState
  onSelect: (reviewRequestId: string) => void
  onRetryList: () => void
  onRetryDetail: () => void
  onExecute: () => Promise<boolean>
  onDecide: (input: ReviewDecisionInput) => Promise<boolean>
  onRequestChanges: (rationale: string) => Promise<boolean>
  onRetryCommand: () => Promise<boolean>
  onClearCommand: () => void
}>()

const emit = defineEmits<{
  locate: [location: ReviewFindingEvidence]
}>()

const decisionDialog = ref(false)
const decisionType = ref<ReviewDecisionType>('COMMENTED')
const confirmRejected = ref(false)
const rationale = ref('')
const submitted = ref(false)
const decisionContainer = useTemplateRef<HTMLElement>('decisionContainer')
const decisionTrigger = ref<HTMLElement | null>(null)

const detail = computed(() => props.review?.value ?? null)
const orderedReviews = computed(() => [...(props.reviews ?? [])].sort((left, right) => right.revision - left.revision))
const orderedFindings = computed(() => [...(detail.value?.findings ?? [])].sort((left, right) => {
  const rank = { BLOCKER: 0, HIGH: 1, MEDIUM: 2, LOW: 3 }
  return rank[left.severity] - rank[right.severity]
}))
const matchedTest = computed(() => props.tests?.items.find(item => item.id === detail.value?.testEvidenceId) ?? null)
const terminalDecision = computed(() => [...(detail.value?.decisions ?? [])]
  .reverse()
  .find(item => ['APPROVED', 'CHANGES_REQUESTED', 'REJECTED'].includes(item.type)) ?? null)
const canExecute = computed(() => Boolean(
  detail.value
  && ['OPEN', 'IN_PROGRESS'].includes(detail.value.status)
  && props.online
  && props.command.phase !== 'pending',
))
const canSubmitGate = computed(() => Boolean(
  detail.value?.status === 'COMPLETED'
  && !terminalDecision.value
  && props.canGate
  && props.online
  && props.command.phase !== 'pending',
))
const rationaleValid = computed(() => rationale.value.trim().length > 0 && rationale.value.trim().length <= 4_000)

/**
 * `canExecute` / `canSubmitGate` 里的成因有两类：离线或命令在途（这里），以及不持有 Gate 责任
 * （页面上本来就有可见说明，直接复用同一段文案作为描述目标，避免读屏重复播报）。
 */
const busyGateReason = computed(() => (props.online
  ? '正在提交上一条 Review 命令，完成前不能再发起新的命令。'
  : '当前离线，联网后才能发起 Review 命令。'))

function short(value: string | null): string {
  if (!value) return '—'
  return value.length > 16 ? `${value.slice(0, 12)}…` : value
}

function statusTone(status: string): SemanticTone {
  if (status === 'COMPLETED' || status === 'APPROVED' || status === 'PASSED') return 'success'
  if (status === 'OPEN' || status === 'IN_PROGRESS' || status === 'COMMENTED') return 'info'
  if (status === 'INVALIDATED' || status === 'CHANGES_REQUESTED') return 'warning'
  if (status === 'REJECTED' || status === 'FAILED') return 'danger'
  return 'neutral'
}

function severityTone(severity: string): SemanticTone {
  if (severity === 'BLOCKER' || severity === 'HIGH') return 'danger'
  if (severity === 'MEDIUM') return 'warning'
  return 'neutral'
}

function displayDate(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(value))
}

function openDecision(event?: MouseEvent): void {
  if (event?.currentTarget instanceof HTMLElement) decisionTrigger.value = event.currentTarget
  props.onClearCommand()
  decisionType.value = 'COMMENTED'
  confirmRejected.value = false
  rationale.value = ''
  submitted.value = false
  decisionDialog.value = true
  void nextTick(() => decisionContainer.value?.querySelector<HTMLElement>('[aria-pressed="true"]')?.focus())
}

function closeDecision(): void {
  if (props.command.phase === 'pending') return
  decisionDialog.value = false
  void nextTick(() => {
    if (decisionTrigger.value?.isConnected) decisionTrigger.value.focus()
  })
}

async function submitDecision(): Promise<void> {
  submitted.value = true
  if (!rationaleValid.value || !canSubmitGate.value) return
  if (decisionType.value === 'REJECTED' && !confirmRejected.value) {
    confirmRejected.value = true
    return
  }
  const text = rationale.value.trim()
  const succeeded = decisionType.value === 'CHANGES_REQUESTED'
    ? await props.onRequestChanges(text)
    : await props.onDecide({ type: decisionType.value, rationale: text })
  if (succeeded) closeDecision()
}

function chooseDecision(type: ReviewDecisionType): void {
  decisionType.value = type
  confirmRejected.value = false
}

function decisionLabel(type: ReviewDecisionType): string {
  return reviewDecisionTypeLabels[type]
}

/** Review facts arrive as `{Enum}.name()` strings, so each one is read through its own label map. */
function label(value: string | null | undefined, labels: Record<string, string>): string {
  return enumLabel(value, labels)
}

function handleDecisionKeydown(event: KeyboardEvent): void {
  if (!isTopmostModal(decisionContainer.value)) return
  event.stopPropagation()
  if (event.key === 'Escape') {
    event.preventDefault()
    closeDecision()
    return
  }
  if (event.key !== 'Tab' || !decisionContainer.value) return
  const controls = [...decisionContainer.value.querySelectorAll<HTMLElement>(
    'button:not(:disabled), select:not(:disabled), textarea:not(:disabled)',
  )]
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
</script>

<template>
  <section class="review-workbench detail-card" aria-labelledby="review-workbench-title" data-testid="review-workbench">
    <div class="review-heading">
      <div><p>Independent advisory · Human gate</p><h3 id="review-workbench-title">Review Workbench</h3></div>
      <span><ShieldCheck :size="14" aria-hidden="true" />Agent 建议与成员结论分离</span>
    </div>

    <StatePanel
      v-if="listPhase === 'loading' && !reviews"
      compact
      state="loading"
      title="正在加载 Review 历史"
      description="ReviewRequest 按当前 Task attempt 与精确 Diff 读取。"
    />
    <StatePanel
      v-else-if="listPhase === 'error' && !reviews"
      compact
      state="error"
      title="Review 历史暂时不可用"
      :description="listErrorMessage ?? undefined"
      @retry="onRetryList"
    />
    <StatePanel
      v-else-if="listPhase === 'empty' || !orderedReviews.length"
      compact
      state="empty"
      title="当前 Attempt 尚无 ReviewRequest"
      description="ReviewRequest 由服务端绑定 Reviewer PolicySnapshot、最终 Diff 与精确测试证据后进入这里；浏览器不接受原始 PolicySnapshot ID。"
    />

    <template v-else>
      <div class="review-revisions" role="group" aria-label="Review 修订历史">
        <button
          v-for="item in orderedReviews"
          :key="item.id"
          type="button"
          :class="{ selected: item.id === selectedReviewRequestId }"
          :aria-pressed="item.id === selectedReviewRequestId"
          @click="onSelect(item.id)"
        >
          <span>Review r{{ item.revision }}</span>
          <StatusBadge :tone="statusTone(item.status)" dot>{{ reviewRequestStatusLabels[item.status] }}</StatusBadge>
          <small>{{ item.findingCount }} Finding · Round {{ item.modificationRound }}</small>
        </button>
      </div>

      <StatePanel v-if="detailPhase === 'loading' && !review" compact state="loading" title="正在读取 Review Context" />
      <StatePanel v-else-if="detailPhase === 'error' && !review" compact state="error" :description="detailErrorMessage ?? undefined" @retry="onRetryDetail" />

      <div v-else-if="detail" class="review-body">
        <div v-if="detail.status === 'INVALIDATED'" class="review-invalidated" role="alert">
          <TriangleAlert :size="16" /><div><strong>旧 Review 已失效</strong><span>{{ detail.invalidationReason ? label(detail.invalidationReason, reviewInvalidationReasonLabels) : 'Review Context 已变化' }}。Finding 与成员结论只作为历史证据，不能控制当前交付。</span></div>
        </div>
        <div v-if="detail.reviewerRelationship === 'SELF_REVIEW'" class="self-review-note">
          <ShieldAlert :size="16" /><div><strong>SELF_REVIEW · Advisory only</strong><span>Reviewer Agent Owner 与被审对象 Owner 相同；这些 Finding 可辅助修复，但不能形成 Gate Approval。</span></div>
        </div>

        <section class="review-context" aria-labelledby="review-context-title">
          <div class="section-title"><div><p>Immutable context</p><h4 id="review-context-title">ContextPackage 摘要</h4></div><GitCompareArrows :size="16" /></div>
          <dl>
            <div><dt>Request</dt><dd>r{{ detail.revision }} · v{{ detail.version }} · {{ reviewRequestStatusLabels[detail.status] }}</dd></div>
            <div><dt>Reviewer</dt><dd>{{ reviewerRelationshipLabels[detail.reviewerRelationship] }} · {{ short(detail.reviewerAgentProfileId) }}</dd></div>
            <div><dt>Context</dt><dd class="mono">{{ short(detail.contextHash) }}</dd></div>
            <div><dt>DiffArtifact</dt><dd class="mono">{{ short(detail.diffArtifactHash) }}</dd></div>
            <div><dt>Baseline</dt><dd class="mono">{{ short(detail.baselineCommit) }}</dd></div>
            <div><dt>Delivery</dt><dd class="mono">{{ short(detail.deliveryCommit) }}</dd></div>
          </dl>
        </section>

        <div class="review-evidence-grid">
          <section class="review-diff" aria-labelledby="review-diff-title">
            <div class="section-title"><div><p>Exact artifact</p><h4 id="review-diff-title">Diff 范围</h4></div><FileDiff :size="16" /></div>
            <div v-if="codingAttempt?.details?.diffManifest" class="diff-totals">
              <span>{{ codingAttempt.details.diffManifest.fileCount }} files</span>
              <b>+{{ codingAttempt.details.diffManifest.additions }}</b>
              <i>-{{ codingAttempt.details.diffManifest.deletions }}</i>
            </div>
            <div class="changed-paths">
              <button v-for="path in detail.changedPaths" :key="path" type="button" @click="emit('locate', { path, startLine: 1, endLine: 1, acceptanceCriterionIndex: 0 })">
                <span>{{ path }}</span><ChevronRight :size="12" />
              </button>
              <p v-if="!detail.changedPaths.length">Context 未公开变更路径。</p>
            </div>
          </section>

          <section class="review-tests" aria-labelledby="review-tests-title">
            <div class="section-title"><div><p>Evidence-bound</p><h4 id="review-tests-title">Test 与 Acceptance</h4></div><ListChecks :size="16" /></div>
            <template v-if="matchedTest">
              <div class="test-totals"><span>Total <b>{{ matchedTest.total }}</b></span><span class="passed">Passed <b>{{ matchedTest.passed }}</b></span><span class="failed">Failed <b>{{ matchedTest.failed + matchedTest.errors }}</b></span></div>
              <ol class="review-acceptance">
                <li v-for="item in matchedTest.acceptance" :key="item.criterionIndex">
                  <CheckCircle2 v-if="item.status === 'PASSED'" :size="12" /><TriangleAlert v-else :size="12" />
                  <div><strong>{{ item.criterion }}</strong><span>{{ item.summary }}</span></div>
                  <StatusBadge :tone="statusTone(item.status)">{{ label(item.status, acceptanceStatusLabels) }}</StatusBadge>
                </li>
              </ol>
            </template>
            <p v-else class="evidence-unavailable">当前已加载的 M4 测试分页不含 {{ short(detail.testEvidenceId) }}；Context Hash 与 TestEvidence Hash 仍由服务端固定。</p>
          </section>
        </div>

        <section class="review-findings" aria-labelledby="review-findings-title">
          <div class="section-title"><div><p>Agent output · Advisory</p><h4 id="review-findings-title">Agent Findings <span>{{ orderedFindings.length }}</span></h4></div><Bot :size="16" /></div>
          <div v-if="orderedFindings.length" class="finding-list">
            <article v-for="finding in orderedFindings" :key="finding.id">
              <div class="finding-header"><div><StatusBadge :tone="severityTone(finding.severity)" dot>{{ reviewFindingSeverityLabels[finding.severity] }}</StatusBadge><span>{{ reviewFindingCategoryLabels[finding.category] }}</span><em>仅参考</em></div><small>{{ reviewerRelationshipLabels[finding.relationship] }}</small></div>
              <h5>{{ finding.title }}</h5>
              <p>{{ finding.claim }}</p>
              <div class="finding-fix"><strong>建议修复</strong><span>{{ finding.suggestedFix }}</span></div>
              <div class="finding-locations" aria-label="Finding 证据位置">
                <button v-for="evidence in finding.evidence" :key="`${evidence.path}:${evidence.startLine}:${evidence.endLine}`" type="button" @click="emit('locate', evidence)">
                  <FileDiff :size="11" /><span>{{ evidence.path }}</span><b>L{{ evidence.startLine }}–{{ evidence.endLine }}</b><small>验收 #{{ evidence.acceptanceCriterionIndex + 1 }}</small>
                </button>
              </div>
            </article>
          </div>
          <div v-else class="review-clean"><CheckCircle2 :size="18" /><div><strong>Reviewer 未发现有效 Finding</strong><span>空 Finding 是成功 Review 输出；Gate Decision 仍由合格 TeamMember 独立提交。</span></div></div>
        </section>

        <section class="review-gate" aria-labelledby="review-gate-title">
          <div class="section-title"><div><p>Accountable member action</p><h4 id="review-gate-title">Gate Decision</h4></div><CircleUserRound :size="16" /></div>
          <div class="gate-actions">
            <div>
              <StatusBadge :tone="statusTone(terminalDecision?.type ?? detail.status)" dot>{{ terminalDecision ? decisionLabel(terminalDecision.type) : '等待成员结论' }}</StatusBadge>
              <p v-if="!canGate" id="review-gate-eligibility">当前成员不持有可用于 Gate 的 Active USER Reviewer 责任；最终 Eligibility 由服务端复验。</p>
              <p v-else>成员结论与 Agent Advisory 分开记录，提交时重新验证职责分离和 Reviewer Eligibility。</p>
            </div>
            <BaseButton v-if="['OPEN', 'IN_PROGRESS'].includes(detail.status)" size="small" :disabled="!canExecute" :aria-describedby="busyGateReason ? 'review-busy-reason' : undefined" :loading="command.phase === 'pending' && command.operation === 'execute'" @click="onExecute"><Bot :size="13" />{{ detail.status === 'IN_PROGRESS' ? '恢复 Reviewer' : '运行 Reviewer' }}</BaseButton>
            <BaseButton v-if="detail.status === 'COMPLETED' && !terminalDecision" size="small" variant="secondary" :disabled="!canSubmitGate" :aria-describedby="canGate ? (busyGateReason ? 'review-busy-reason' : undefined) : 'review-gate-eligibility'" @click="openDecision"><ShieldCheck :size="13" />提交成员结论</BaseButton>
            <p id="review-busy-reason" class="sr-only">{{ busyGateReason }}</p>
          </div>
          <ol v-if="detail.decisions.length" class="decision-history">
            <li v-for="decision in detail.decisions" :key="decision.id"><History :size="12" /><div><strong>{{ decisionLabel(decision.type) }} · r{{ decision.revision }}</strong><span>{{ decision.rationale }}</span><small>{{ label(decision.eligibilityMode, reviewerEligibilityModeLabels) }} · {{ displayDate(decision.decidedAt) }}</small></div></li>
          </ol>
          <div v-if="detail.modificationRounds.length" class="modification-rounds"><strong>修改轮次</strong><span v-for="round in detail.modificationRounds" :key="round.id">Round {{ round.roundNumber }} · {{ displayDate(round.createdAt) }}</span></div>
        </section>

        <div v-if="command.errorMessage" class="review-command-error" role="alert">
          <TriangleAlert :size="14" /><span>{{ command.errorMessage }}</span>
          <BaseButton v-if="command.retryable" size="small" variant="secondary" @click="onRetryCommand"><RefreshCw :size="12" />使用原命令重试</BaseButton>
          <BaseButton v-else size="small" variant="ghost" @click="onClearCommand">知道了</BaseButton>
        </div>
      </div>
    </template>

    <div v-if="decisionDialog" class="gate-dialog-backdrop" @mousedown.self="closeDecision">
      <div ref="decisionContainer" class="gate-dialog" role="dialog" aria-modal="true" aria-labelledby="gate-dialog-title" tabindex="-1" @keydown="handleDecisionKeydown">
        <form @submit.prevent="submitDecision">
          <div class="gate-dialog-header"><div><p>Human Gate · Review r{{ detail?.revision }}</p><h4 id="gate-dialog-title">提交成员 Review 结论</h4></div><button type="button" aria-label="关闭 Gate Decision" :disabled="command.phase === 'pending'" @click="closeDecision"><X :size="16" /></button></div>
          <p class="gate-impact">结论绑定当前 ReviewRequest ETag 与精确 Context。Agent Finding 只作为建议；服务端会重新校验当前成员、Reviewer Assignment 和职责分离。</p>
          <fieldset class="decision-options" :disabled="command.phase === 'pending'">
            <legend>结论</legend>
            <div role="radiogroup" aria-label="Review 结论">
              <button v-for="type in (['COMMENTED', 'APPROVED', 'CHANGES_REQUESTED', 'REJECTED'] as ReviewDecisionType[])" :key="type" type="button" :class="[{ selected: decisionType === type }, `decision-option--${type.toLowerCase()}`]" :aria-pressed="decisionType === type" @click="chooseDecision(type)">{{ confirmRejected && type === 'REJECTED' ? '再次点击确认拒绝' : decisionLabel(type) }}</button>
            </div>
          </fieldset>
          <label><span>理由</span><textarea v-model="rationale" rows="5" maxlength="4000" :disabled="command.phase === 'pending'" :aria-invalid="submitted && !rationaleValid" placeholder="记录团队可审计的判断依据" /></label>
          <p v-if="submitted && !rationaleValid" class="gate-validation" role="alert">请输入 1–4000 个字符的理由。</p>
          <footer><BaseButton type="button" variant="ghost" :disabled="command.phase === 'pending'" @click="closeDecision">返回</BaseButton><BaseButton type="submit" :variant="decisionType === 'REJECTED' ? 'danger' : 'primary'" :loading="command.phase === 'pending'">确认提交</BaseButton></footer>
        </form>
      </div>
    </div>
  </section>
</template>

<style scoped>
.review-workbench{padding:0;overflow:hidden}.review-heading{display:flex;min-height:60px;align-items:center;justify-content:space-between;gap:var(--cs-space-12);padding:var(--cs-space-12) var(--cs-space-16);border-bottom: 1px solid var(--cs-border);background: linear-gradient(115deg,var(--cs-surface-accent),var(--cs-surface) 65%)}.review-heading p,.review-heading h3{margin:0}.review-heading p{color: var(--cs-text-brand);font-size:var(--cs-text-xs);font-weight:var(--cs-weight-semibold);letter-spacing:.08em;text-transform:uppercase}.review-heading h3{margin-top:var(--cs-space-2);font-size:var(--cs-text-base)}.review-heading>span{display:flex;align-items:center;gap:var(--cs-space-4);color: var(--cs-text-brand);font-size:var(--cs-text-xs)}.review-workbench>:deep(.state-panel){border: 0;border-radius:0}.review-revisions{display:flex;gap:var(--cs-space-4);overflow:auto;padding:var(--cs-space-8) var(--cs-space-12);border-bottom: 1px solid var(--cs-border);background: var(--cs-surface-subtle)}.review-revisions>button{display:grid;min-width:145px;grid-template-columns:1fr auto;gap:var(--cs-space-2) var(--cs-space-8);padding:var(--cs-space-8) var(--cs-space-8);border: 1px solid var(--cs-border);border-radius:8px;background: var(--cs-surface);text-align:left;cursor:pointer}.review-revisions>button.selected{border-color: var(--cs-border-accent-strong);background: var(--cs-surface-accent);box-shadow: 0 0 0 1px var(--cs-ring-brand)}.review-revisions span{font-size:var(--cs-text-xs);font-weight:var(--cs-weight-semibold)}.review-revisions small{grid-column:1/-1;color: var(--cs-text-muted);font-size:var(--cs-text-xs)}.review-body{display:grid;gap:var(--cs-space-12);padding:var(--cs-space-12)}.review-invalidated,.self-review-note{display:flex;align-items:flex-start;gap:var(--cs-space-8);padding:var(--cs-space-8) var(--cs-space-12);border-radius:8px;font-size:var(--cs-text-xs)}.review-invalidated{background: var(--cs-warning-soft);color: var(--cs-warning)}.self-review-note{background: var(--cs-agent-soft);color: var(--cs-agent)}.review-invalidated strong,.review-invalidated span,.self-review-note strong,.self-review-note span{display:block}.review-invalidated span,.self-review-note span{margin-top:var(--cs-space-2);line-height:var(--cs-leading-normal)}.review-context,.review-diff,.review-tests,.review-findings,.review-gate{min-width:0;border: 1px solid var(--cs-border);border-radius:9px;background: var(--cs-surface)}.section-title{display:flex;min-height:42px;align-items:center;justify-content:space-between;gap:var(--cs-space-8);padding:var(--cs-space-8) var(--cs-space-12);border-bottom: 1px solid var(--cs-border)}.section-title p,.section-title h4{margin:0}.section-title p{color: var(--cs-text-muted);font-size:var(--cs-text-xs);font-weight:var(--cs-weight-semibold);text-transform:uppercase}.section-title h4{margin-top:var(--cs-space-2);font-size:var(--cs-text-sm)}.section-title h4 span{color: var(--cs-text-muted);font-weight:var(--cs-weight-medium)}.section-title>svg{color: var(--cs-text-brand)}.review-context dl{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:var(--cs-space-2);margin:0;background: var(--cs-border)}.review-context dl>div{min-width:0;padding:var(--cs-space-8);background: var(--cs-surface)}.review-context dt{color: var(--cs-text-muted);font-size:var(--cs-text-xs)}.review-context dd{margin:var(--cs-space-4) 0 0;overflow:hidden;color: var(--cs-text-secondary);font-size:var(--cs-text-xs);text-overflow:ellipsis;white-space:nowrap}.mono{font-family:var(--cs-font-mono)}.review-evidence-grid{display:grid;grid-template-columns:minmax(0,.85fr) minmax(0,1.15fr);gap:var(--cs-space-12)}.diff-totals,.test-totals{display:flex;gap:var(--cs-space-8);padding:var(--cs-space-8) var(--cs-space-12);color: var(--cs-text-muted);font:var(--cs-text-xs) var(--cs-font-mono)}.diff-totals b,.test-totals .passed b{color: var(--cs-success)}.diff-totals i,.test-totals .failed b{color: var(--cs-danger);font-style:normal}.changed-paths{display:grid;gap:var(--cs-space-4);max-height:160px;overflow:auto;padding:0 var(--cs-space-8) var(--cs-space-8)}.changed-paths button{display:flex;min-width:0;align-items:center;gap:var(--cs-space-4);padding:var(--cs-space-8);border-radius:6px;color: var(--cs-text-brand);font:var(--cs-text-xs) var(--cs-font-mono);text-align:left;cursor:pointer}.changed-paths button:hover{background: var(--cs-surface-accent)}.changed-paths button span{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.changed-paths button svg{margin-left:auto}.changed-paths p,.evidence-unavailable{margin:var(--cs-space-8);color: var(--cs-text-muted);font-size:var(--cs-text-xs);line-height:var(--cs-leading-normal)}.review-acceptance{display:grid;gap:var(--cs-space-4);margin:0;padding:0 var(--cs-space-8) var(--cs-space-8);list-style:none}.review-acceptance li{display:grid;grid-template-columns:auto minmax(0,1fr) auto;align-items:start;gap:var(--cs-space-4);padding:var(--cs-space-8);border-radius:6px;background: var(--cs-surface-subtle)}.review-acceptance li>svg{color: var(--cs-text-brand)}.review-acceptance strong,.review-acceptance span{display:block;font-size:var(--cs-text-xs)}.review-acceptance span{margin-top:var(--cs-space-2);color: var(--cs-text-muted)}.finding-list{display:grid;gap:var(--cs-space-8);padding:var(--cs-space-8)}.finding-list article{padding:var(--cs-space-8);border: 1px solid var(--cs-border);border-radius:8px;background: var(--cs-surface-subtle)}.finding-header{display:flex;align-items:center;justify-content:space-between;gap:var(--cs-space-8)}.finding-header>div{display:flex;align-items:center;gap:var(--cs-space-4)}.finding-header span,.finding-header em,.finding-header small{color: var(--cs-text-muted);font-size:var(--cs-text-xs);font-style:normal}.finding-header em{padding:var(--cs-space-2) var(--cs-space-4);border-radius:99px;background: var(--cs-agent-soft);color: var(--cs-agent);font-weight:var(--cs-weight-semibold)}.finding-list h5{margin:var(--cs-space-8) 0 var(--cs-space-4);font-size:var(--cs-text-sm)}.finding-list article>p{margin:0;color: var(--cs-text-secondary);font-size:var(--cs-text-xs);line-height:var(--cs-leading-normal)}.finding-fix{display:grid;gap:var(--cs-space-2);margin-top:var(--cs-space-8);padding:var(--cs-space-8) var(--cs-space-8);border-left: 2px solid var(--cs-border-accent-strong);background: var(--cs-surface-accent);font-size:var(--cs-text-xs)}.finding-fix span{color: var(--cs-text-secondary)}.finding-locations{display:flex;flex-wrap:wrap;gap:var(--cs-space-4);margin-top:var(--cs-space-8)}.finding-locations button{display:flex;min-width:0;align-items:center;gap:var(--cs-space-4);padding:var(--cs-space-4) var(--cs-space-8);border: 1px solid var(--cs-border);border-radius:6px;background: var(--cs-surface);color: var(--cs-text-brand);font-size:var(--cs-text-xs);cursor:pointer}.finding-locations button span{max-width:240px;overflow:hidden;font-family:var(--cs-font-mono);text-overflow:ellipsis;white-space:nowrap}.finding-locations button b{font-family:var(--cs-font-mono)}.finding-locations button small{color: var(--cs-text-muted)}.review-clean{display:flex;align-items:flex-start;gap:var(--cs-space-8);padding:var(--cs-space-12);color: var(--cs-success)}.review-clean strong,.review-clean span{display:block;font-size:var(--cs-text-xs)}.review-clean span{margin-top:var(--cs-space-2);color: var(--cs-text-muted)}.gate-actions{display:flex;align-items:center;justify-content:space-between;gap:var(--cs-space-12);padding:var(--cs-space-8)}.gate-actions>div{min-width:0}.gate-actions p{margin:var(--cs-space-4) 0 0;color: var(--cs-text-muted);font-size:var(--cs-text-xs);line-height:var(--cs-leading-normal)}.decision-history{display:grid;gap:var(--cs-space-4);margin:0;padding:0 var(--cs-space-8) var(--cs-space-8);list-style:none}.decision-history li{display:flex;align-items:flex-start;gap:var(--cs-space-8);padding:var(--cs-space-8);border-radius:7px;background: var(--cs-surface-subtle)}.decision-history li>svg{color: var(--cs-text-brand)}.decision-history strong,.decision-history span,.decision-history small{display:block;font-size:var(--cs-text-xs)}.decision-history span{margin-top:var(--cs-space-2);color: var(--cs-text-secondary)}.decision-history small{margin-top:var(--cs-space-4);color: var(--cs-text-muted)}.modification-rounds{display:flex;flex-wrap:wrap;align-items:center;gap:var(--cs-space-4);padding:0 var(--cs-space-8) var(--cs-space-8);font-size:var(--cs-text-xs)}.modification-rounds span{padding:var(--cs-space-4) var(--cs-space-8);border-radius:99px;background: var(--cs-warning-soft);color: var(--cs-warning)}.review-command-error{display:flex;align-items:center;gap:var(--cs-space-8);padding:var(--cs-space-8);border-radius:8px;background: var(--cs-danger-soft);color: var(--cs-danger);font-size:var(--cs-text-xs)}.review-command-error span{flex:1}.gate-dialog-backdrop{position:fixed;inset:0;z-index: var(--cs-z-dialog);display:grid;place-items:center;padding:var(--cs-space-20);background: var(--cs-scrim);backdrop-filter:blur(3px)}.gate-dialog{width:min(520px,100%);overflow:hidden;border: 1px solid var(--cs-border);border-radius:14px;background: var(--cs-surface);box-shadow: var(--cs-shadow-float)}.gate-dialog-header{display:flex;align-items:flex-start;justify-content:space-between;gap:var(--cs-space-12);padding:var(--cs-space-16) var(--cs-space-16);border-bottom: 1px solid var(--cs-border)}.gate-dialog-header p,.gate-dialog-header h4{margin:0}.gate-dialog-header p{color: var(--cs-text-muted);font:var(--cs-text-xs) var(--cs-font-mono)}.gate-dialog-header h4{margin-top:var(--cs-space-4);font-size:var(--cs-text-base)}.gate-dialog-header button{display:grid;width:30px;height:30px;place-items:center;border-radius:7px;background: var(--cs-surface-subtle);cursor:pointer}.gate-impact{margin:0;padding:var(--cs-space-12) var(--cs-space-16) var(--cs-space-8);color: var(--cs-text-secondary);font-size:var(--cs-text-xs);line-height:var(--cs-leading-normal)}.gate-dialog label{display:grid;gap:var(--cs-space-4);padding:var(--cs-space-4) var(--cs-space-16);color: var(--cs-text-secondary);font-size:var(--cs-text-xs);font-weight:var(--cs-weight-semibold)}.gate-dialog select,.gate-dialog textarea{width:100%;padding:var(--cs-space-8) var(--cs-space-8);border: 1px solid var(--cs-border-strong);border-radius:8px;background: var(--cs-surface-subtle);color: var(--cs-text);font:var(--cs-text-base) var(--cs-font-sans)}.gate-dialog textarea{resize:vertical}.gate-dialog [aria-invalid=true]{border-color: var(--cs-danger)}.gate-validation{margin:var(--cs-space-2) var(--cs-space-16);color: var(--cs-danger);font-size:var(--cs-text-xs)}.gate-dialog footer{display:flex;justify-content:flex-end;gap:var(--cs-space-8);padding:var(--cs-space-12) var(--cs-space-16) var(--cs-space-16)}@media(max-width:720px){.review-heading{align-items:flex-start;flex-direction:column}.review-context dl{grid-template-columns:repeat(2,minmax(0,1fr))}.review-evidence-grid{grid-template-columns:1fr}.gate-actions{align-items:stretch;flex-direction:column}.gate-actions :deep(.base-button){width:100%}.gate-dialog-backdrop{align-items:end;padding:0}.gate-dialog{border-radius:16px 16px 0 0}.gate-dialog footer{display:grid;grid-template-columns:1fr 1fr}}
.decision-options { display: grid; gap: var(--cs-space-8); padding: var(--cs-space-4) var(--cs-space-16); border: 0; }
.decision-options legend { padding: 0; color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }
.decision-options > div { display: flex; flex-wrap: wrap; gap: var(--cs-space-8); }
.decision-options button { min-height: 32px; padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: 7px; background: var(--cs-surface-subtle); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); cursor: pointer; }
.decision-options button.selected { border-color: var(--cs-border-accent-strong); background: var(--cs-surface-accent-strong); color: var(--cs-text-brand-strong); }
.decision-options button.decision-option--rejected { border-color: var(--cs-danger-border); color: var(--cs-danger); }
.decision-options button:disabled { cursor: not-allowed; opacity: .55; }
</style>
