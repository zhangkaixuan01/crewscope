<script setup lang="ts">
import {
  Check,
  CheckCircle2,
  CircleDashed,
  CloudOff,
  GitBranch,
  GitPullRequestDraft,
  RefreshCw,
  RotateCw,
  ShieldAlert,
  ShieldCheck,
  TriangleAlert,
  X,
} from '@lucide/vue'
import { computed, nextTick, ref, watch } from 'vue'
import { isTopmostModal } from '../../app/dialog'
import type { EtaggedReview } from '../../domains/review/types'
import { deliveryAttemptKey, deliveryBundleKey, useDeliveryStore } from '../../domains/delivery/store'
import type { ActionBundle, PlannedAction } from '../../domains/delivery/types'
import { enumLabel } from '../../domains/shared/labels'
import {
  actionBundleValidityLabels,
  actionDispatchStatusLabels,
  actionInvalidationReasonLabels,
  actionReceiptResultLabels,
  actionRiskLevelLabels,
  confirmationStatusLabels,
  externalObjectStatusLabels,
  externalResultSourceLabels,
  githubAuthenticationTypeLabels,
  githubConnectionOwnerTypeLabels,
  githubExecutionIdentityLabels,
  providerConnectionStatusLabels,
  repositoryVisibilityLabels,
} from '../../domains/delivery/labels'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'
import type { SemanticTone } from '../base/types'

const props = defineProps<{
  taskId: string
  executionId: string
  objective: string
  review: EtaggedReview | null
  online: boolean
  canConfirm: boolean
}>()

const store = useDeliveryStore()
const title = ref('')
const body = ref('')
const expectedRemoteHead = ref('')
const confirmDialog = ref(false)
const digestAcknowledged = ref(false)
const confirmationDialog = ref<HTMLElement | null>(null)
const confirmationTrigger = ref<HTMLElement | null>(null)
const manualAction = ref<PlannedAction | null>(null)
const manualExplanation = ref('')
const manualDialog = ref<HTMLElement | null>(null)
const manualTrigger = ref<HTMLElement | null>(null)

const coordinates = computed(() => ({ taskId: props.taskId, executionId: props.executionId }))
const connections = computed(() => store.state.connections.value ?? [])
const selectedConnection = computed(() => connections.value.find(item => item.id === store.state.selectedConnectionId) ?? null)
const bindingResource = computed(() => selectedConnection.value ? store.state.bindings[selectedConnection.value.id] : null)
const repositoryResource = computed(() => selectedConnection.value ? store.state.repositories[selectedConnection.value.id] : null)
const healthResource = computed(() => selectedConnection.value ? store.state.health[selectedConnection.value.id] : null)
const bindings = computed(() => bindingResource.value?.value?.filter(item => item.status === 'ACTIVE') ?? [])
const repositories = computed(() => repositoryResource.value?.value ?? [])
const selectedRepository = computed(() => repositories.value.find(item => item.externalRepositoryId === store.state.selectedRepositoryId) ?? null)
const bundleList = computed(() => store.state.bundles[deliveryAttemptKey(coordinates.value)] ?? null)
const selectedBundleResource = computed(() => store.state.selectedBundleId
  ? store.state.bundleDetails[deliveryBundleKey(coordinates.value, store.state.selectedBundleId)] ?? null
  : null)
const bundle = computed(() => selectedBundleResource.value?.value?.value ?? null)
const approvedDecision = computed(() => [...(props.review?.value.decisions ?? [])]
  .filter(item => item.type === 'APPROVED')
  .sort((left, right) => right.revision - left.revision)[0] ?? null)
const reviewEligible = computed(() => Boolean(
  props.review?.value.status === 'COMPLETED'
  && !props.review.value.invalidationReason
  && approvedDecision.value,
))
const selectionReady = computed(() => Boolean(
  selectedConnection.value?.status === 'ACTIVE'
  && bindings.value.some(item => item.id === store.state.selectedBindingId)
  && selectedRepository.value
  && healthResource.value?.value?.authorizationStatus === 'HEALTHY'
  && store.state.preflight.phase === 'ready'
  && store.state.preflight.value?.externalRepositoryId === store.state.selectedRepositoryId,
))
const planReady = computed(() => reviewEligible.value && selectionReady.value
  && title.value.trim().length > 0 && title.value.trim().length <= 256
  && body.value.trim().length > 0 && body.value.trim().length <= 65_536)
const commandPending = computed(() => store.state.command.phase === 'pending')
// 禁用态必须可解释：控件一旦禁用就没有交互线索能说明原因，因此每个控件算出一条原因句并由
// aria-describedby 关联。纯离线与命令在途这两类成因在全页面已有线索，共用同一条原因。
const busyReason = computed(() => (props.online
  ? (commandPending.value ? '命令正在提交，完成前无法发起新的操作。' : null)
  : '当前离线，联网后才能提交。'))
const selectionIncomplete = computed(() => !selectedConnection.value || !store.state.selectedBindingId || !selectedRepository.value)
const connectionMissingReason = computed(() => (connections.value.length ? null : '还没有可选的 GitHub 授权连接，请先在 GitHub 设置中创建。'))
const bindingMissingReason = computed(() => (bindings.value.length ? null : '当前 Team 没有可用的执行身份 Binding。'))
const repositoryMissingReason = computed(() => (repositories.value.length ? null : 'Catalog 里还没有可访问的仓库，请先同步 Catalog。'))
const syncCatalogReason = computed(() => (!selectedConnection.value ? '请先选择 GitHub Connection。' : busyReason.value))
const preflightReason = computed(() => (selectionIncomplete.value ? '需要先选定 Connection、Team Binding 与 Repository。' : busyReason.value))
const planReason = computed(() => (!planReady.value ? '需要 Review 资格、Provider 健康与 Preflight 就绪，并填写 PR 标题与正文。' : busyReason.value))
const confirmReason = computed(() => (props.canConfirm ? busyReason.value : '需要这个任务的 Owner 或 Reviewer 权限才能确认交付。'))
const reviewConfirmReason = computed(() => (props.canConfirm
  ? (bundle.value && bundle.value.validity !== 'CURRENT' ? 'ActionBundle 已过期，需要重新生成后再确认。' : busyReason.value)
  : '需要这个任务的 Owner 或 Reviewer 权限才能确认交付。'))
const hasUnknown = computed(() => bundle.value?.actions.some(item => ['UNKNOWN', 'RECONCILING', 'MANUAL_REVIEW'].includes(item.dispatch?.status ?? '')) ?? false)

watch(() => props.objective, value => {
  if (!title.value) title.value = value.slice(0, 256)
  if (!body.value) body.value = `由 CrewScope 完成 Coding、Review 与成员 Gate 后生成。\n\nTask: ${value}`
}, { immediate: true })

function chooseConnection(event: Event): void {
  const id = (event.target as HTMLSelectElement).value
  void store.selectConnection(id)
}

async function plan(): Promise<void> {
  if (!planReady.value || !approvedDecision.value || !store.state.selectedBindingId || !store.state.selectedRepositoryId) return
  await store.plan({
    reviewDecisionId: approvedDecision.value.id,
    providerBindingId: store.state.selectedBindingId,
    repositoryId: store.state.selectedRepositoryId,
    expectedRemoteHead: expectedRemoteHead.value.trim() || undefined,
    title: title.value.trim(),
    body: body.value.trim(),
  })
}

async function openConfirmation(event?: MouseEvent): Promise<void> {
  if (event?.currentTarget instanceof HTMLElement) confirmationTrigger.value = event.currentTarget
  digestAcknowledged.value = false
  confirmDialog.value = true
  await nextTick()
  confirmationDialog.value?.querySelector<HTMLElement>('button:not(:disabled)')?.focus()
}

async function confirm(): Promise<void> {
  if (!digestAcknowledged.value || !props.canConfirm || !props.online) return
  if (await store.confirm()) await closeConfirmation(true)
}

async function closeConfirmation(force = false): Promise<void> {
  if (commandPending.value && !force) return
  confirmDialog.value = false
  await nextTick()
  if (confirmationTrigger.value?.isConnected) confirmationTrigger.value.focus()
}

function openManual(action: PlannedAction, event?: MouseEvent): void {
  if (event?.currentTarget instanceof HTMLElement) manualTrigger.value = event.currentTarget
  manualAction.value = action
  manualExplanation.value = ''
  void nextTick(() => manualDialog.value?.querySelector<HTMLElement>('textarea')?.focus())
}

async function resolveFailure(): Promise<void> {
  if (!manualAction.value) return
  if (await store.resolveFailure(manualAction.value, manualExplanation.value)) await closeManual(true)
}

async function closeManual(force = false): Promise<void> {
  if (commandPending.value && !force) return
  manualAction.value = null
  manualExplanation.value = ''
  await nextTick()
  if (manualTrigger.value?.isConnected) manualTrigger.value.focus()
}

/** External-write dialogs retain focus inside the current topmost modal. */
function trapDialogFocus(event: KeyboardEvent, dialog: HTMLElement | null, close: () => void): void {
  if (!isTopmostModal(dialog)) return
  event.stopPropagation()
  if (event.key === 'Escape') {
    event.preventDefault()
    close()
    return
  }
  if (event.key !== 'Tab' || !dialog) return
  const controls = [...dialog.querySelectorAll<HTMLElement>(
    'button:not(:disabled), input:not(:disabled), textarea:not(:disabled)',
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

function short(value: string | null | undefined, length = 10): string {
  if (!value) return '—'
  return value.length > length + 4 ? `${value.slice(0, length)}…${value.slice(-4)}` : value
}

function statusTone(status: string | null | undefined): SemanticTone {
  if (['SUCCEEDED', 'MANUALLY_SUCCEEDED', 'OPEN', 'HEALTHY', 'CURRENT'].includes(status ?? '')) return 'success'
  if (['FAILED', 'MANUALLY_FAILED', 'STALE'].includes(status ?? '')) return 'danger'
  if (['UNKNOWN', 'RECONCILING', 'MANUAL_REVIEW', 'CANCELLED'].includes(status ?? '')) return 'warning'
  if (['READY', 'RUNNING', 'ACTIVE'].includes(status ?? '')) return 'info'
  return 'neutral'
}

/** Delivery enums arrive as `{Enum}.name()` strings, so each is read through its own label map. */
function label(value: string | null | undefined, labels: Record<string, string>): string {
  return enumLabel(value, labels)
}

/**
 * A planned action that has not been dispatched yet has no `ActionDispatchStatus` at all, so the
 * stage badge distinguishes "confirmed, queued" from "planned, not yet confirmed" itself rather
 * than borrowing a backend constant for a state the backend has not recorded.
 */
function stageStatusLabel(action: PlannedAction, bundle: ActionBundle): string {
  if (action.dispatch) return label(action.dispatch.status, actionDispatchStatusLabels)
  return bundle.confirmation ? '待执行' : '待确认'
}

function stageStatusTone(action: PlannedAction, bundle: ActionBundle): SemanticTone {
  return statusTone(action.dispatch?.status ?? (bundle.confirmation ? 'READY' : 'PLANNED'))
}

/**
 * The result strip prefers the authoritative receipt and falls back to the observed external
 * object status, so the two enums are read through their own maps rather than one shared map.
 */
function deliveryOutcomeLabel(action: PlannedAction): string {
  if (action.receipt) return label(action.receipt.result, actionReceiptResultLabels)
  return label(action.externalResult?.status, externalObjectStatusLabels)
}

function actionTitle(action: PlannedAction): string {
  return action.kind === 'PUSH_BRANCH' ? 'Push 受管分支' : 'Create Draft PR'
}

function stageDescription(action: PlannedAction): string {
  if (action.kind === 'PUSH_BRANCH') return `${action.parameters.branch} · ${short(action.parameters.deliveryHead)}`
  return `${action.parameters.pullRequestHead} → ${action.parameters.pullRequestBase}`
}
</script>

<template>
  <section class="action-workbench detail-card" aria-labelledby="action-workbench-title" data-testid="action-delivery-workbench">
    <header class="action-heading">
      <div><p>Exact confirmation · External delivery</p><h3 id="action-workbench-title">GitHub Delivery</h3></div>
      <div class="heading-actions">
        <StatusBadge v-if="bundle" :tone="statusTone(bundle.validity)" dot>{{ actionBundleValidityLabels[bundle.validity] }}</StatusBadge>
        <BaseButton variant="secondary" size="small" :disabled="!online || commandPending" @click="store.refresh"><RefreshCw :size="14" />刷新结果</BaseButton>
      </div>
    </header>

    <div class="sr-only">
      <p id="delivery-connection-reason">{{ connectionMissingReason ?? busyReason }}</p>
      <p id="delivery-binding-reason">{{ bindingMissingReason ?? busyReason }}</p>
      <p id="delivery-repository-reason">{{ repositoryMissingReason ?? busyReason }}</p>
      <p id="delivery-sync-reason">{{ syncCatalogReason }}</p>
      <p id="delivery-preflight-reason">{{ preflightReason }}</p>
      <p id="delivery-plan-reason">{{ planReason }}</p>
      <p id="delivery-manual-reason">{{ confirmReason }}</p>
      <p id="delivery-review-reason">{{ reviewConfirmReason }}</p>
    </div>

    <StatePanel v-if="!online" compact state="offline" title="交付写操作已暂停" description="已加载的 Review、ActionBundle 与回执保持可读；联网后可 Preflight、确认或刷新 Webhook 结果。" />
    <StatePanel
      v-if="!reviewEligible"
      compact
      state="conflict"
      title="需要当前成员 Gate Approval"
      description="只有当前未失效 ReviewRequest 的 APPROVED 决策才能规划 GitHub 写动作；Reviewer Agent Finding 不能替代成员 Gate。"
    />

    <section class="github-selection" aria-labelledby="github-selection-title">
      <div class="section-title"><div><p>Provider authority</p><h4 id="github-selection-title">Connection 与 Repository</h4></div><GitBranch :size="16" /></div>
      <div class="selection-grid">
        <label>GitHub Connection
          <select :value="store.state.selectedConnectionId ?? ''" :disabled="!connections.length || commandPending" aria-describedby="delivery-connection-reason" @change="chooseConnection">
            <option value="" disabled>选择授权连接</option>
            <option v-for="item in connections" :key="item.id" :value="item.id">
              {{ githubConnectionOwnerTypeLabels[item.ownerType] }} · {{ item.externalAccountLogin ?? githubAuthenticationTypeLabels[item.authenticationType] }} · {{ label(item.status, providerConnectionStatusLabels) }}
            </option>
          </select>
        </label>
        <label>Team Binding
          <select :value="store.state.selectedBindingId ?? ''" :disabled="!bindings.length || commandPending" aria-describedby="delivery-binding-reason" @change="store.selectBinding(($event.target as HTMLSelectElement).value)">
            <option value="" disabled>选择执行身份</option>
            <option v-for="item in bindings" :key="item.id" :value="item.id">{{ githubExecutionIdentityLabels[item.executionIdentity] }} · {{ item.defaultUsage ? '默认绑定' : short(item.id) }}</option>
          </select>
        </label>
        <label>Repository
          <select :value="store.state.selectedRepositoryId ?? ''" :disabled="!repositories.length || commandPending" aria-describedby="delivery-repository-reason" @change="store.selectRepository(($event.target as HTMLSelectElement).value)">
            <option value="" disabled>选择 Catalog 仓库</option>
            <option v-for="item in repositories" :key="item.externalRepositoryId" :value="item.externalRepositoryId">{{ item.fullName }} · {{ label(item.visibility, repositoryVisibilityLabels) }}</option>
          </select>
        </label>
        <div class="selection-actions">
          <BaseButton variant="secondary" size="small" :loading="store.state.command.operation === 'catalog-sync' && commandPending" :disabled="!selectedConnection || !online || commandPending" aria-describedby="delivery-sync-reason" @click="store.synchronizeCatalog"><RotateCw :size="14" />同步 Catalog</BaseButton>
          <BaseButton size="small" :loading="store.state.preflight.phase === 'loading'" :disabled="!selectedConnection || !store.state.selectedBindingId || !selectedRepository || !online || commandPending" aria-describedby="delivery-preflight-reason" @click="store.preflightSelected"><ShieldCheck :size="14" />Remote Preflight</BaseButton>
        </div>
      </div>
      <div class="provider-facts">
        <span><small>Authorization</small><StatusBadge :tone="statusTone(healthResource?.value?.authorizationStatus)">{{ healthResource?.value?.authorizationStatus ?? 'UNKNOWN' }}</StatusBadge></span>
        <span><small>Webhook</small><strong>{{ healthResource?.value?.webhookStatus ?? 'UNKNOWN' }}</strong></span>
        <span><small>Catalog</small><strong>{{ repositories.length }} deliverable</strong></span>
        <span><small>Rate Limit</small><strong>{{ healthResource?.value?.rateLimit ? `${healthResource.value.rateLimit.remaining}/${healthResource.value.rateLimit.limit}` : '—' }}</strong></span>
      </div>
      <p v-if="store.state.preflight.phase === 'ready'" class="inline-result success" role="status"><CheckCircle2 :size="14" />{{ store.state.preflight.value?.fullName }} / {{ store.state.preflight.value?.defaultBranch }} · 权限 {{ short(store.state.preflight.value?.permissionsHash) }}</p>
      <p v-else-if="store.state.preflight.phase === 'error'" class="inline-result error" role="alert"><TriangleAlert :size="14" />{{ store.state.preflight.errorMessage }}</p>
    </section>

    <section v-if="reviewEligible && (!bundle || bundle.validity === 'STALE')" class="action-plan" aria-labelledby="action-plan-title">
        <div class="section-title"><div><p>Server-derived graph</p><h4 id="action-plan-title">{{ bundle?.validity === 'STALE' ? '重新规划 Push 与 Draft PR' : '规划 Push 与 Draft PR' }}</h4></div><GitPullRequestDraft :size="16" /></div>
      <div class="plan-form">
        <label>PR Title<input v-model="title" maxlength="256" :disabled="commandPending" /></label>
        <label>Expected Remote Head（可选）<input v-model="expectedRemoteHead" maxlength="40" class="mono" placeholder="仅在已知远端 Head 时填写" :disabled="commandPending" /></label>
        <label class="wide">PR Body<textarea v-model="body" rows="4" maxlength="65536" :disabled="commandPending" /></label>
        <BaseButton :loading="store.state.command.operation === 'plan' && commandPending" :disabled="!planReady || !online || commandPending" aria-describedby="delivery-plan-reason" @click="plan"><GitPullRequestDraft :size="15" />生成 ActionBundle</BaseButton>
      </div>
    </section>

    <StatePanel v-if="bundleList?.phase === 'loading' && !bundle" compact state="loading" title="正在读取 ActionBundle" />
    <StatePanel v-else-if="bundleList?.phase === 'error' && !bundle" compact state="error" :description="bundleList.errorMessage ?? undefined" @retry="store.refresh" />

    <template v-if="bundle">
      <section class="bundle-review" aria-labelledby="bundle-review-title">
        <div class="section-title"><div><p>Immutable preview · v{{ bundle.version }}</p><h4 id="bundle-review-title">ActionBundle 风险与参数</h4></div><ShieldAlert :size="16" /></div>
        <div v-if="bundle.validity === 'STALE'" class="stale-alert" role="alert"><TriangleAlert :size="16" /><div><strong>ActionBundle 已失效</strong><span>{{ bundle.staleReason ? label(bundle.staleReason, actionInvalidationReasonLabels) : '当前 Review、责任、授权或目标事实已变化' }}。请重新规划后再确认。</span></div></div>
        <dl class="bundle-facts">
          <div><dt>Repository</dt><dd>{{ bundle.repositoryKey }}</dd></div>
          <div><dt>Review Decision</dt><dd class="mono">{{ short(bundle.reviewDecisionId) }}</dd></div>
          <div><dt>Baseline</dt><dd class="mono">{{ short(bundle.baselineCommit) }}</dd></div>
          <div><dt>Delivery</dt><dd class="mono">{{ short(bundle.deliveryCommit) }}</dd></div>
          <div class="digest"><dt>Bundle Digest</dt><dd class="mono">{{ bundle.digest }}</dd></div>
        </dl>

        <div class="action-stages" role="list" aria-label="ActionBundle 分步状态">
          <article v-for="(action, index) in bundle.actions" :key="action.id" class="action-stage" role="listitem">
            <div class="stage-rail"><span>{{ index + 1 }}</span><i v-if="index < bundle.actions.length - 1"></i></div>
            <div class="stage-card">
              <header><div><strong>{{ actionTitle(action) }}</strong><span>{{ stageDescription(action) }}</span></div><StatusBadge :tone="stageStatusTone(action, bundle)" dot>{{ stageStatusLabel(action, bundle) }}</StatusBadge></header>
              <div class="stage-meta"><span>Risk <b>{{ label(action.risk, actionRiskLevelLabels) }}</b></span><span>Digest <b class="mono">{{ short(action.digest) }}</b></span><span>Depends <b>{{ action.dependencyActionIds.length ? action.dependencyActionIds.map(item => short(item, 6)).join(', ') : 'none' }}</b></span></div>
              <dl class="action-parameters">
                <template v-if="action.kind === 'PUSH_BRANCH'">
                  <div><dt>Branch</dt><dd class="mono">{{ action.parameters.branch }}</dd></div><div><dt>Delivery Head</dt><dd class="mono">{{ action.parameters.deliveryHead }}</dd></div><div><dt>Expected Remote</dt><dd class="mono">{{ action.parameters.expectedRemoteHead ?? 'absent' }}</dd></div>
                </template>
                <template v-else>
                  <div><dt>Head → Base</dt><dd class="mono">{{ action.parameters.pullRequestHead }} → {{ action.parameters.pullRequestBase }}</dd></div><div><dt>Head SHA</dt><dd class="mono">{{ action.parameters.pullRequestHeadSha }}</dd></div><div><dt>Draft</dt><dd>{{ action.parameters.draft === true ? 'true' : 'invalid' }}</dd></div><div class="wide"><dt>Title</dt><dd>{{ action.parameters.title }}</dd></div><div class="wide"><dt>Body</dt><dd class="pre-wrap">{{ action.parameters.body }}</dd></div>
                </template>
              </dl>
              <div v-if="action.receipt || action.externalResult" class="result-strip" aria-live="polite">
                <CheckCircle2 v-if="action.receipt?.result.includes('SUCCEEDED')" :size="15" /><TriangleAlert v-else :size="15" />
                <div><strong>{{ deliveryOutcomeLabel(action) }}</strong><span>{{ action.externalResult ? `${label(action.externalResult.source, externalResultSourceLabels)} · ${action.receipt?.evidenceCode ?? label(action.externalResult.status, externalObjectStatusLabels)}` : action.receipt?.evidenceCode }} · {{ short(action.receipt?.externalIdentityHash ?? action.externalResult?.externalIdentityHash) }}</span></div>
                <StatusBadge v-if="action.externalResult" :tone="statusTone(action.externalResult.status)">{{ label(action.externalResult.status, externalObjectStatusLabels) }}</StatusBadge>
              </div>
              <div v-if="['UNKNOWN', 'RECONCILING'].includes(action.dispatch?.status ?? '')" class="reconcile-note" role="status"><CircleDashed :size="15" /><div><strong>{{ label(action.dispatch?.status, actionDispatchStatusLabels) }}</strong><span>外部副作用结果尚不确定；系统只查询 GitHub 并对账，不会盲目重放写操作。第 {{ action.dispatch?.reconciliationAttempts }} 次对账。</span></div></div>
              <div v-if="action.dispatch?.status === 'MANUAL_REVIEW'" class="manual-note"><CloudOff :size="15" /><div><strong>已进入人工队列</strong><span>自动对账已达到边界，Owner 可在核对 Provider 审计后终结为失败。</span></div><BaseButton size="small" variant="secondary" :disabled="!canConfirm || !online" aria-describedby="delivery-manual-reason" @click="openManual(action, $event)">人工终结</BaseButton></div>
            </div>
          </article>
        </div>

        <div class="confirmation-bar">
          <div v-if="bundle.confirmation"><Check :size="16" /><span><strong>确认{{ label(bundle.confirmation.status, confirmationStatusLabels) }}</strong>确认于 {{ new Date(bundle.confirmation.confirmedAt).toLocaleString('zh-CN') }} · 有效至 {{ new Date(bundle.confirmation.validUntil).toLocaleString('zh-CN') }}</span></div>
          <div v-else><ShieldCheck :size="16" /><span><strong>等待精确确认</strong>确认只覆盖当前 Version、Digest、Review、Binding、Repository 与动作参数。</span></div>
          <BaseButton v-if="!bundle.confirmation" :disabled="!canConfirm || !online || bundle.validity !== 'CURRENT' || commandPending" aria-describedby="delivery-review-reason" @click="openConfirmation">审查并确认</BaseButton>
          <BaseButton v-else-if="bundle.confirmation.status === 'ACTIVE' && !bundle.actions.some(item => item.dispatch && item.dispatch.status !== 'READY')" variant="secondary" :disabled="!canConfirm || !online || commandPending" aria-describedby="delivery-manual-reason" @click="store.cancel">撤回未执行确认</BaseButton>
        </div>
        <p v-if="hasUnknown" class="webhook-note"><RefreshCw :size="13" />页面刷新会回读 Webhook/主动查询合并后的权威 ExternalResult；浏览器不推导外部成功。</p>
      </section>
    </template>

    <StatePanel
      v-if="store.state.command.phase === 'error' || store.state.command.phase === 'conflict'"
      compact
      :state="store.state.command.phase === 'conflict' ? 'conflict' : 'error'"
      :title="store.state.command.phase === 'conflict' ? '交付事实已变化' : '交付命令未完成'"
      :description="store.state.command.errorMessage ?? undefined"
      @retry="store.state.command.retryable ? store.retryCommand() : store.refresh()"
    />
    <p v-else-if="store.state.command.phase === 'success'" class="command-success" role="status" aria-live="polite"><CheckCircle2 :size="14" />命令已提交并回读权威状态 · Correlation {{ short(store.state.command.correlationId) }}</p>

    <div v-if="confirmDialog && bundle" class="dialog-backdrop" @click.self="closeConfirmation()">
      <section ref="confirmationDialog" class="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="delivery-confirm-title" tabindex="-1" @keydown="trapDialogFocus($event, confirmationDialog, () => { void closeConfirmation() })">
        <header><div><p>Human confirmation boundary</p><h3 id="delivery-confirm-title">确认执行 GitHub 写操作</h3></div><button type="button" aria-label="关闭确认对话框" :disabled="commandPending" @click="closeConfirmation()"><X :size="17" /></button></header>
        <div class="confirm-content">
          <p>本次确认将授权平台先 Push 受管分支，再在 Push 成功后创建 Draft PR。两个动作分别保留 Dispatch、Receipt 与 ExternalResult。</p>
          <dl><div><dt>Repository</dt><dd>{{ bundle.repositoryKey }}</dd></div><div><dt>Version</dt><dd>v{{ bundle.version }}</dd></div><div><dt>Digest</dt><dd class="mono">{{ bundle.digest }}</dd></div></dl>
          <label class="acknowledge"><input v-model="digestAcknowledged" type="checkbox" />我已逐项审查上方风险、依赖与参数，并确认当前完整 Digest。</label>
        </div>
        <footer><BaseButton variant="secondary" :disabled="commandPending" @click="closeConfirmation()">取消</BaseButton><BaseButton :loading="store.state.command.operation === 'confirm' && commandPending" :disabled="!digestAcknowledged || !online || !canConfirm" @click="confirm"><ShieldCheck :size="15" />精确确认</BaseButton></footer>
      </section>
    </div>

    <div v-if="manualAction" class="dialog-backdrop" @click.self="closeManual()">
      <section ref="manualDialog" class="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="manual-resolution-title" tabindex="-1" @keydown="trapDialogFocus($event, manualDialog, () => { void closeManual() })">
        <header><div><p>Irreversible owner decision</p><h3 id="manual-resolution-title">人工终结为失败</h3></div><button type="button" aria-label="关闭人工终结对话框" :disabled="commandPending" @click="closeManual()"><X :size="17" /></button></header>
        <div class="confirm-content"><p>仅在 Provider 审计已经证明没有外部对象时使用。该结论不可逆，不会触发 GitHub 写操作。</p><label>核验证据说明<textarea v-model="manualExplanation" rows="4" maxlength="2000" placeholder="至少 10 个字符，记录已核对的 Provider 审计证据。" /></label></div>
        <footer><BaseButton variant="secondary" :disabled="commandPending" @click="closeManual()">取消</BaseButton><BaseButton variant="danger" :loading="store.state.command.operation === 'manual-resolution' && commandPending" :disabled="manualExplanation.trim().length < 10 || !online || !canConfirm" @click="resolveFailure">确认无外部对象</BaseButton></footer>
      </section>
    </div>
  </section>
</template>

<style scoped>
.action-workbench { overflow: hidden; }.action-heading { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-16); padding: var(--cs-space-16) var(--cs-space-20); border-bottom: 1px solid var(--cs-border); background: linear-gradient(135deg, var(--cs-surface-accent), var(--cs-surface)); }.action-heading p, .section-title p, .confirm-dialog header p { margin: 0 0 var(--cs-space-2); color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); letter-spacing: .08em; text-transform: uppercase; }.action-heading h3, .section-title h4, .confirm-dialog h3 { margin: 0; }.action-heading h3 { font-size: var(--cs-text-md); }.heading-actions { display: flex; align-items: center; gap: var(--cs-space-8); }
.github-selection, .action-plan, .bundle-review { padding: var(--cs-space-16) var(--cs-space-20); border-bottom: 1px solid var(--cs-border); }.section-title { display: flex; align-items: center; justify-content: space-between; margin-bottom: var(--cs-space-12); }.section-title h4 { font-size: var(--cs-text-base); }.section-title > svg { color: var(--cs-text-brand); }
.selection-grid { display: grid; grid-template-columns: minmax(150px, .8fr) minmax(150px, .8fr) minmax(210px, 1.2fr) auto; align-items: end; gap: var(--cs-space-8); }.selection-grid label, .plan-form label, .confirm-content > label { display: grid; gap: var(--cs-space-4); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.selection-grid select, .plan-form input, .plan-form textarea, .confirm-content textarea { min-width: 0; min-height: 36px; padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); font: var(--cs-text-base) var(--cs-font-sans); }.plan-form textarea, .confirm-content textarea { padding-block: var(--cs-space-8); resize: vertical; }.selection-actions { display: flex; gap: var(--cs-space-8); }.provider-facts { display: grid; grid-template-columns: repeat(4, minmax(110px, 1fr)); gap: var(--cs-space-8); margin-top: var(--cs-space-12); padding: var(--cs-space-12) var(--cs-space-12); border-radius: 9px; background: var(--cs-surface-subtle); }.provider-facts span { min-width: 0; }.provider-facts small, .provider-facts strong { display: block; }.provider-facts small { margin-bottom: var(--cs-space-4); color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); text-transform: uppercase; }.provider-facts strong { overflow: hidden; font-size: var(--cs-text-xs); text-overflow: ellipsis; white-space: nowrap; }.inline-result, .command-success, .webhook-note { display: flex; align-items: center; gap: var(--cs-space-8); margin: var(--cs-space-12) 0 0; font-size: var(--cs-text-xs); }.success, .command-success { color: var(--cs-success); }.error { color: var(--cs-danger); }
.plan-form { display: grid; grid-template-columns: 1fr 1fr auto; align-items: end; gap: var(--cs-space-12); }.plan-form .wide { grid-column: 1 / -1; }.plan-form textarea { min-height: 80px; }
.stale-alert, .reconcile-note, .manual-note { display: flex; align-items: flex-start; gap: var(--cs-space-8); padding: var(--cs-space-12) var(--cs-space-12); border-radius: 9px; }.stale-alert { margin-bottom: var(--cs-space-12); background: var(--cs-danger-soft); color: var(--cs-danger); }.stale-alert strong, .stale-alert span, .reconcile-note strong, .reconcile-note span, .manual-note strong, .manual-note span { display: block; }.stale-alert span, .reconcile-note span, .manual-note span { margin-top: var(--cs-space-2); font-size: var(--cs-text-xs); }.bundle-facts { display: grid; grid-template-columns: 1.3fr 1fr 1fr 1fr; gap: var(--cs-space-8); margin: 0 0 var(--cs-space-16); }.bundle-facts > div { min-width: 0; padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: 8px; background: var(--cs-surface-subtle); }.bundle-facts .digest { grid-column: 1 / -1; }.bundle-facts dt, .action-parameters dt { color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); text-transform: uppercase; }.bundle-facts dd, .action-parameters dd { margin: var(--cs-space-4) 0 0; overflow-wrap: anywhere; font-size: var(--cs-text-xs); }
.action-stages { display: grid; }.action-stage { display: grid; grid-template-columns: 28px 1fr; gap: var(--cs-space-8); }.stage-rail { display: grid; grid-template-rows: 27px 1fr; justify-items: center; }.stage-rail span { display: grid; width: 25px; height: 25px; place-items: center; border-radius: 50%; background: var(--cs-surface-accent-strong); color: var(--cs-text-brand-strong); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.stage-rail i { width: 1px; min-height: 20px; background: var(--cs-border-strong); }.stage-card { margin-bottom: var(--cs-space-12); padding: var(--cs-space-12) var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: 10px; background: var(--cs-surface); }.stage-card > header { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--cs-space-12); }.stage-card > header strong, .stage-card > header span { display: block; }.stage-card > header strong { font-size: var(--cs-text-sm); }.stage-card > header span { margin-top: var(--cs-space-2); color: var(--cs-text-muted); font-size: var(--cs-text-xs); overflow-wrap: anywhere; }.stage-meta { display: flex; flex-wrap: wrap; gap: var(--cs-space-8) var(--cs-space-16); margin-top: var(--cs-space-8); padding-top: var(--cs-space-8); border-top: 1px dashed var(--cs-border); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.stage-meta b { color: var(--cs-text-secondary); }.action-parameters { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--cs-space-8); margin: var(--cs-space-12) 0 0; }.action-parameters > div { min-width: 0; }.action-parameters .wide { grid-column: 1 / -1; }.pre-wrap { white-space: pre-wrap; }.result-strip { display: flex; align-items: center; gap: var(--cs-space-8); margin-top: var(--cs-space-12); padding: var(--cs-space-8) var(--cs-space-12); border-radius: 8px; background: var(--cs-success-soft); color: var(--cs-success); }.result-strip > div { flex: 1; }.result-strip strong, .result-strip span { display: block; }.result-strip strong { font-size: var(--cs-text-xs); }.result-strip span { margin-top: var(--cs-space-2); font-size: var(--cs-text-xs); }.reconcile-note { margin-top: var(--cs-space-12); background: var(--cs-warning-soft); color: var(--cs-warning); }.manual-note { align-items: center; margin-top: var(--cs-space-12); background: var(--cs-surface-subtle); }.manual-note > div { flex: 1; }
.confirmation-bar { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-16); margin-top: var(--cs-space-4); padding: var(--cs-space-12) var(--cs-space-12); border: 1px solid var(--cs-border-accent); border-radius: 10px; background: var(--cs-surface-accent); }.confirmation-bar > div { display: flex; align-items: flex-start; gap: var(--cs-space-8); color: var(--cs-text-brand-strong); }.confirmation-bar strong, .confirmation-bar span { display: block; }.confirmation-bar strong { font-size: var(--cs-text-sm); }.confirmation-bar span { font-size: var(--cs-text-xs); }.webhook-note { color: var(--cs-text-muted); }
.command-success { padding: 0 var(--cs-space-20) var(--cs-space-16); }.dialog-backdrop { position: fixed; inset: 0; z-index: var(--cs-z-dialog); display: grid; place-items: center; padding: var(--cs-space-20); background: var(--cs-scrim); backdrop-filter: blur(3px); }.confirm-dialog { width: min(590px, 100%); overflow: hidden; border-radius: var(--cs-radius-lg); background: var(--cs-surface); box-shadow: var(--cs-shadow-float); }.confirm-dialog > header { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--cs-space-16); padding: var(--cs-space-20) var(--cs-space-20); border-bottom: 1px solid var(--cs-border); }.confirm-dialog h3 { font-size: var(--cs-text-md); }.confirm-dialog header button { display: grid; width: 30px; height: 30px; place-items: center; border-radius: 8px; background: var(--cs-surface-subtle); cursor: pointer; }.confirm-content { display: grid; gap: var(--cs-space-12); padding: var(--cs-space-20) var(--cs-space-20); }.confirm-content > p { margin: 0; color: var(--cs-text-secondary); font-size: var(--cs-text-sm); line-height: var(--cs-leading-relaxed); }.confirm-content dl { display: grid; gap: var(--cs-space-8); margin: 0; }.confirm-content dl div { display: grid; grid-template-columns: 85px 1fr; gap: var(--cs-space-8); }.confirm-content dt { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.confirm-content dd { margin: 0; overflow-wrap: anywhere; font-size: var(--cs-text-xs); }.acknowledge { grid-template-columns: 16px 1fr !important; align-items: flex-start; padding: var(--cs-space-12); border-radius: 8px; background: var(--cs-warning-soft); line-height: var(--cs-leading-normal); }.acknowledge input { margin: var(--cs-space-2) 0 0; }.confirm-dialog > footer { display: flex; justify-content: flex-end; gap: var(--cs-space-8); padding: 0 var(--cs-space-20) var(--cs-space-20); }
@media (max-width: 1000px) { .selection-grid { grid-template-columns: 1fr 1fr; }.selection-actions { align-self: end; }.bundle-facts { grid-template-columns: 1fr 1fr; }.action-parameters { grid-template-columns: 1fr 1fr; } }
@media (max-width: 767px) { .action-heading, .github-selection, .action-plan, .bundle-review { padding-inline: var(--cs-space-16); }.action-heading { align-items: flex-start; }.heading-actions { flex-direction: column; align-items: flex-end; }.selection-grid, .plan-form { grid-template-columns: 1fr; }.selection-actions { display: grid; grid-template-columns: 1fr 1fr; }.provider-facts { grid-template-columns: 1fr 1fr; }.plan-form .wide { grid-column: 1; }.bundle-facts { grid-template-columns: 1fr 1fr; }.action-parameters { grid-template-columns: 1fr; }.action-parameters .wide { grid-column: 1; }.confirmation-bar { align-items: stretch; flex-direction: column; }.dialog-backdrop { align-items: end; padding: 0; }.confirm-dialog { border-radius: 18px 18px 0 0; }.confirm-content dl div { grid-template-columns: 68px 1fr; } }
</style>
