<script setup lang="ts">
import { Bot, CheckCircle2, Info, RefreshCw, ShieldCheck, TriangleAlert, X } from '@lucide/vue'
import { computed, inject, nextTick, onMounted, reactive, ref, useTemplateRef, watch } from 'vue'
import { AUTH_PRINCIPAL } from '../../app/auth'
import { isTopmostModal } from '../../app/dialog'
import {
  agentBindingSourceLabels,
  agentExecutionScopeLabels,
  agentOwnershipTypeLabels,
  agentRuntimeRoleLabels,
} from '../../domains/agent/labels'
import { useAgentStore } from '../../domains/agent/store'
import { ownerTypeLabels } from '../../domains/settings/labels'
import { enumLabel } from '../../domains/shared/labels'
import type { CodingScope, CodingTargetSelection } from '../../domains/coding/types'
import { readTaskDelegationDraft, writeTaskDelegationDraft } from '../../domains/task/delegationDraft'
import { delegationPreflightKey, useTaskStore } from '../../domains/task/store'
import type {
  CreateTaskInput,
  DelegationAgentCandidate,
  DelegationContext,
  TaskDelegationPreflight,
  TaskDelegationSelection,
} from '../../domains/task/types'
import type { WorkItemSummary } from '../../domains/workitem/types'
import BaseButton from '../base/BaseButton.vue'
import CodingTargetFormSection, { type CodingTargetInitialDefaults } from './CodingTargetFormSection.vue'

type DelegationMode = 'assign-and-start' | 'assign-only' | 'start'

const props = defineProps<{
  workItem: WorkItemSummary
  codingScope: CodingScope
  submitting: boolean
  retryable: boolean
  errorMessage: string | null
  conversationSource?: { conversationId: string, messageId: string } | null
  onSubmit: (input: CreateTaskInput) => Promise<void>
  /** “仅分配”意图：保存 EXECUTOR 责任而不启动任何执行（走既有责任命令端点）。 */
  onAssign?: (target: { agentProfileId: string, agentPrincipalId: string, displayName: string }) => Promise<void>
  onRetry: () => Promise<void>
}>()

const emit = defineEmits<{ close: [] }>()
const agentStore = useAgentStore()
const taskStore = useTaskStore()
const dialog = useTemplateRef<HTMLElement>('dialog')
const principal = inject(AUTH_PRINCIPAL)
const submitted = ref(false)
const codingSelection = ref<CodingTargetSelection | null>(null)
const codingValid = ref(false)
const selectedProfileId = ref('')
const revisionValue = ref('current')
const approvedPreflight = ref<TaskDelegationPreflight | null>(null)
const modeChoice = ref<'assign-and-start' | 'assign-only'>('assign-and-start')
let initialized = false

const form = reactive({
  objective: props.workItem.title,
  acceptanceCriteria: props.workItem.description?.trim() || '完成工作项目标并提供可验证结果',
})

// The delegation context is the form's single source of truth (M9b-A05): chain, candidates,
// conflicts, defaults, the in-flight fact and the caller's authority all arrive in one read.
const contextResource = computed(() =>
  taskStore.state.delegationContexts[`${props.codingScope.projectId}:${props.workItem.id}`] ?? null)
const context = computed<DelegationContext | null>(() => contextResource.value?.value ?? null)
const contextPhase = computed(() => contextResource.value?.phase ?? 'idle')
const contextError = computed(() => contextResource.value?.errorMessage ?? null)
const owner = computed(() => context.value?.responsibilities.find(item => item.role === 'OWNER') ?? null)
const candidates = computed<DelegationAgentCandidate[]>(() => context.value?.candidates ?? [])
const selectable = computed(() => candidates.value.filter(item => item.state === 'ASSIGNED' || item.state === 'AVAILABLE'))
const personalCandidates = computed(() => selectable.value.filter(item => item.ownershipType === 'USER'))
const teamCandidates = computed(() => selectable.value.filter(item => item.ownershipType !== 'USER'))
const blockedCandidates = computed(() => candidates.value.filter(item => item.state !== 'ASSIGNED' && item.state !== 'AVAILABLE'))
const selectedCandidate = computed<DelegationAgentCandidate | null>(() =>
  candidates.value.find(item => item.agentProfileId === selectedProfileId.value) ?? null)

/**
 * The mode is derived from server facts, never kept as a second truth: an existing ACTIVE
 * EXECUTOR pins the form to plain start, otherwise assignment authority offers both intents.
 */
const mode = computed<DelegationMode>(() => {
  const value = context.value
  if (!value) return 'start'
  const hasExecutor = value.responsibilities.some(item => item.role === 'EXECUTOR')
  if (hasExecutor) return 'start'
  return value.permissions.canAssignResponsibility ? 'assign-and-start' : 'start'
})
const effectiveMode = computed<DelegationMode>(() =>
  mode.value === 'assign-and-start' ? modeChoice.value : mode.value)
const assignsResponsibility = computed(() =>
  effectiveMode.value === 'assign-and-start' || effectiveMode.value === 'assign-only')
const ownerName = computed(() => owner.value?.actorDisplayName ?? '未配置')
const selectedRevision = computed(() => revisionValue.value === 'current' ? null : Number(revisionValue.value))
const historyResource = computed(() => selectedProfileId.value
  ? agentStore.state.configurationHistory[selectedProfileId.value] ?? null
  : null)
const revisionOptions = computed(() => historyResource.value?.value ?? [])
const selection = computed<TaskDelegationSelection | null>(() => selectedProfileId.value ? {
  executorAgentProfileId: selectedProfileId.value,
  agentConfigurationRevision: selectedRevision.value,
} : null)
const preflightKey = computed(() => selection.value
  ? delegationPreflightKey(props.codingScope.projectId, props.workItem.id, selection.value)
  : '')
const preflightResource = computed(() => preflightKey.value
  ? taskStore.state.delegationPreflights[preflightKey.value] ?? null
  : null)
const preflightCurrent = computed(() => Boolean(
  approvedPreflight.value
  && approvedPreflight.value.agentProfileId === selectedProfileId.value
  && (selectedRevision.value === null
    || approvedPreflight.value.configurationRevision === selectedRevision.value),
))
const criteria = computed(() => form.acceptanceCriteria.split('\n').map(value => value.trim()).filter(Boolean))

/** Project defaults the coding section starts from; unavailable ones fall back inside the section. */
const codingInitial = computed<CodingTargetInitialDefaults>(() => ({
  repositoryBindingId: context.value?.defaults.repositoryBindingId.availability === 'AVAILABLE'
    ? context.value.defaults.repositoryBindingId.value
    : null,
  branch: context.value?.defaults.branch.availability === 'AVAILABLE'
    ? context.value.defaults.branch.value
    : null,
  buildProfile: context.value?.defaults.buildProfile.availability === 'AVAILABLE'
    ? context.value.defaults.buildProfile.value
    : null,
}))
const defaultGaps = computed<string[]>(() => {
  const value = context.value
  if (!value) return []
  const gaps: string[] = []
  if (value.defaults.repositoryBindingId.availability !== 'AVAILABLE') {
    gaps.push('项目尚未设置默认仓库——下方已回退到可用仓库，可稍后在项目执行默认值中补配。')
  }
  if (value.defaults.buildProfile.availability !== 'AVAILABLE') {
    gaps.push('项目尚未设置默认构建方案——下方已回退到可用 BuildProfile。')
  }
  return gaps
})
const codingIntentLine = computed(() => codingSelection.value
  ? `${codingSelection.value.baselineRef} · ${codingSelection.value.buildProfile.key}`
  : '通用 Agent Task（不固化仓库目标）')

const assignTargetValid = computed(() => Boolean(selectedCandidate.value
  && (selectedCandidate.value.state === 'ASSIGNED' || selectedCandidate.value.state === 'AVAILABLE')))
const valid = computed(() => {
  if (effectiveMode.value === 'assign-only') {
    return assignTargetValid.value
  }
  return form.objective.trim().length > 0
    && criteria.value.length > 0
    && assignTargetValid.value
    && codingValid.value
    && preflightCurrent.value
})

onMounted(async () => {
  restoreDraft()
  taskStore.activateScope(props.codingScope)
  await taskStore.loadDelegationContext(props.codingScope.projectId, props.workItem.id, true)
  applyPreferredCandidate()
  initialized = true
  if (selectedProfileId.value) {
    await agentStore.loadConfigurationHistory(selectedProfileId.value)
    await runPreflight()
  }
  // Focus the dialog container so narrow screens retain the Agent/preflight context at the top.
  await nextTick(() => dialog.value?.focus())
})

/**
 * The frozen defaults precedence, resolved once the context lands: a kept draft choice, then the
 * current EXECUTOR (both count as explicit), then the project default, then the first available.
 */
function applyPreferredCandidate(): void {
  const value = context.value
  if (!value) return
  if (selectedProfileId.value && selectable.value.some(item => item.agentProfileId === selectedProfileId.value)) return
  const assigned = value.candidates.find(item => item.state === 'ASSIGNED')
  if (assigned) {
    selectedProfileId.value = assigned.agentProfileId
    return
  }
  const defaulted = value.defaults.agentProfileId.availability === 'AVAILABLE' && value.defaults.agentProfileId.value
    ? value.candidates.find(item =>
      item.agentProfileId === value.defaults.agentProfileId.value && item.state === 'AVAILABLE')
    : undefined
  selectedProfileId.value = defaulted?.agentProfileId
    ?? selectable.value[0]?.agentProfileId
    ?? ''
  if (selectedProfileId.value) revisionValue.value = 'current'
}

watch([selectedProfileId, revisionValue], async ([profileId], previous) => {
  if (!initialized) return
  if (previous?.[0] !== profileId && revisionValue.value !== 'current') {
    revisionValue.value = 'current'
    return
  }
  invalidatePreflight()
  persistDraft()
  if (!profileId) return
  await agentStore.loadConfigurationHistory(profileId)
  await runPreflight()
})

watch(() => [form.objective, form.acceptanceCriteria], persistDraft)

function requestClose(): void {
  if (!props.submitting) emit('close')
}

function handleDialogKeydown(event: KeyboardEvent): void {
  if (!isTopmostModal(dialog.value)) return
  event.stopPropagation()
  if (event.key === 'Escape') {
    event.preventDefault()
    requestClose()
    return
  }
  if (event.key !== 'Tab' || !dialog.value) return
  const controls = [...dialog.value.querySelectorAll<HTMLElement>(
    'button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled)',
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

async function runPreflight(): Promise<void> {
  const current = selection.value
  if (!current || props.retryable) return
  approvedPreflight.value = await taskStore.preflightDelegation(
    props.codingScope.projectId,
    props.workItem.id,
    current,
  )
}

function invalidatePreflight(): void {
  approvedPreflight.value = null
  taskStore.clearDelegationPreflight(props.codingScope.projectId, props.workItem.id)
}

async function submit(): Promise<void> {
  submitted.value = true
  const candidate = selectedCandidate.value
  if (!valid.value || !candidate) return
  if (effectiveMode.value === 'assign-only') {
    // “仅分配”是另一个意图：它复用既有责任命令端点，不创建 Task、不启动执行。
    await props.onAssign?.({
      agentProfileId: candidate.agentProfileId,
      agentPrincipalId: candidate.agentPrincipalId,
      displayName: candidate.displayName,
    })
    return
  }
  const preflight = approvedPreflight.value
  if (!preflight) return
  await props.onSubmit({
    objective: form.objective.trim(),
    acceptanceCriteria: criteria.value,
    executorAgentProfileId: preflight.agentProfileId,
    // Creation pins the exact revision returned by preflight, even when “current” was selected.
    agentConfigurationRevision: preflight.configurationRevision,
    // “分配并启动”：同一条命令先落 EXECUTOR 责任再启动；已存在执行者时表单处于 start 模式不带此字段。
    executorAssignment: effectiveMode.value === 'assign-and-start'
      ? { agentProfileId: candidate.agentProfileId }
      : null,
    conversationSource: props.conversationSource ?? null,
    providerBindingIds: [],
    codingTarget: codingSelection.value ? plainCodingTarget(codingSelection.value) : null,
  })
}

function codingChanged(value: CodingTargetSelection | null, validSelection: boolean): void {
  codingSelection.value = value
  codingValid.value = validSelection
}

function plainCodingTarget(value: CodingTargetSelection): CodingTargetSelection {
  // The Task Store snapshots commands with structuredClone; Vue Proxies must not cross that boundary.
  return {
    repositoryBindingId: value.repositoryBindingId,
    baselineRef: value.baselineRef,
    allowedPaths: [...value.allowedPaths],
    buildProfile: { ...value.buildProfile },
  }
}

function restoreDraft(): void {
  const draft = readTaskDelegationDraft(props.codingScope, props.codingScope.projectId, props.workItem.id, principal)
  if (!draft) return
  form.objective = draft.objective
  form.acceptanceCriteria = draft.acceptanceCriteria
  selectedProfileId.value = draft.executorAgentProfileId
  revisionValue.value = draft.agentConfigurationRevision === null
    ? 'current'
    : String(draft.agentConfigurationRevision)
}

function persistDraft(): void {
  if (!initialized && !selectedProfileId.value) return
  writeTaskDelegationDraft(props.codingScope, props.codingScope.projectId, props.workItem.id, {
    objective: form.objective,
    acceptanceCriteria: form.acceptanceCriteria,
    executorAgentProfileId: selectedProfileId.value,
    agentConfigurationRevision: selectedRevision.value,
  }, principal)
}

/** Candidate states carry their server-side explanation; the form only relabels the state code. */
const candidateStateLabels: Record<string, string> = {
  ASSIGNED: '当前执行者',
  AVAILABLE: '可分配',
  EXECUTOR_CONFLICT: '责任冲突',
  AGENT_DISABLED: '已停用',
  PRINCIPAL_INACTIVE: '主体不可用',
}

function stateLabel(candidate: DelegationAgentCandidate): string {
  return candidateStateLabels[candidate.state] ?? candidate.state
}

function ownershipLabel(candidate: DelegationAgentCandidate): string {
  return enumLabel(candidate.ownershipType, agentOwnershipTypeLabels)
}

function candidateLabel(candidate: DelegationAgentCandidate): string {
  return `${candidate.displayName} · ${enumLabel(candidate.runtimeRole, agentRuntimeRoleLabels)}`
}

function sourceLabel(source: string): string {
  return enumLabel(source, agentBindingSourceLabels)
}

const modeHints: Record<'assign-and-start' | 'assign-only', string> = {
  'assign-and-start': '创建 EXECUTOR 责任并按下方目标立即启动一次可审计执行；同一条命令内完成，失败不留下半提交。',
  'assign-only': '只保存 EXECUTOR 责任，不启动任何执行；之后可从责任面板管理，或再次打开本表单启动。',
}

const submitLabel = computed(() => effectiveMode.value === 'assign-only'
  ? '仅分配'
  : effectiveMode.value === 'assign-and-start' ? '分配并启动' : '启动执行')

function preflightError(): string {
  const reason = String(preflightResource.value?.errorDetails?.reason ?? '')
  const labels: Record<string, string> = {
    MODEL_BINDING_MISSING: '这个执行范围没有可用模型 Binding。',
    DEFAULT_MISSING: '没有可继承的 Team 或 Organization 默认模型。',
    DEFAULT_AMBIGUOUS: '默认模型配置存在歧义，请由管理员收敛后重试。',
    CONNECTION_FORBIDDEN: '当前执行范围不能使用所选 Connection；TEAM 执行不会使用 USER Key。',
    TEAM_PARTICIPATION_REQUIRED: 'Agent Owner 已离队或不再具备团队参与资格。',
    RESPONSIBILITY_REQUIRED: '当前责任链不再允许这个 Agent 执行。',
    AGENT_UNAVAILABLE: 'Agent 已停用、归档或不在当前 Workspace。',
    PRINCIPAL_INACTIVE: 'Agent 或其 Owner 当前不可执行。',
  }
  return labels[reason] ?? preflightResource.value?.errorMessage ?? 'Task 模型预检未通过。'
}
</script>

<template>
  <div class="delegate-backdrop" @click.self="requestClose">
    <div ref="dialog" class="delegate-dialog panel" role="dialog" aria-modal="true" aria-labelledby="delegate-title" tabindex="-1" @keydown="handleDialogKeydown">
      <form class="delegate-form" @submit.prevent="submit">
      <div class="dialog-header"><span class="delegate-icon"><Bot :size="19" /></span><div><p class="eyebrow">Durable Task · {{ workItem.key }}</p><h2 id="delegate-title">交给 Agent 处理</h2><span>一次读取责任、候选与项目默认值；选定后预检并形成可恢复命令。</span></div><button type="button" aria-label="关闭交给 Agent 对话框" :disabled="submitting" @click="requestClose"><X :size="18" /></button></div>

      <div v-if="contextPhase === 'loading'" class="context-state" role="status" aria-live="polite"><RefreshCw class="spin" :size="15" />正在读取责任链、候选 Agent 与项目默认值…</div>
      <div v-else-if="contextPhase === 'error'" class="context-state error" role="alert"><TriangleAlert :size="15" /><span>{{ contextError ?? '暂时无法读取委托上下文' }}</span><BaseButton type="button" size="small" variant="secondary" :disabled="submitting || retryable" @click="taskStore.loadDelegationContext(codingScope.projectId, workItem.id, true)">重试</BaseButton></div>

      <template v-if="context">
      <p v-if="context.activeExecution" class="active-execution-note" role="note"><Info :size="14" />当前执行仍按原说明继续；补充要求请发布评论，或等本轮完成后开启新一轮。</p>

      <section class="responsibility-preview" aria-label="责任预览"><ShieldCheck :size="17" /><div><strong>Owner · {{ ownerName }}</strong><span>Executor · {{ selectedCandidate?.displayName ?? '请选择执行 Agent' }}；服务端会再次授权。</span></div></section>

      <fieldset v-if="mode === 'assign-and-start'" class="mode-choice">
        <legend>发送意图</legend>
        <label><input v-model="modeChoice" type="radio" value="assign-and-start" :disabled="submitting || retryable">分配并启动</label>
        <label><input v-model="modeChoice" type="radio" value="assign-only" :disabled="submitting || retryable">仅分配</label>
        <p>{{ modeHints[modeChoice] }}</p>
      </fieldset>

      <p v-if="conversationSource" class="conversation-source-note">来源保留为当前 Conversation 消息；仅传入下方确认的目标与验收标准，不复制对话原文。</p>

      <section v-if="selectable.length" class="agent-selection" aria-labelledby="agent-selection-title">
        <div class="section-heading"><div><p>Execution identity</p><h3 id="agent-selection-title">Agent 与配置</h3></div><span>{{ selectable.length }} 个可分配候选</span></div>
        <div class="selection-grid">
          <label><span>执行 Agent</span><select v-model="selectedProfileId" :disabled="submitting || retryable">
            <optgroup v-if="personalCandidates.length" label="个人 Agent"><option v-for="candidate in personalCandidates" :key="candidate.agentProfileId" :value="candidate.agentProfileId">{{ candidateLabel(candidate) }}{{ candidate.state === 'ASSIGNED' ? ' · 当前执行者' : '' }}</option></optgroup>
            <optgroup v-if="teamCandidates.length" label="团队 Agent"><option v-for="candidate in teamCandidates" :key="candidate.agentProfileId" :value="candidate.agentProfileId">{{ candidateLabel(candidate) }}{{ candidate.state === 'ASSIGNED' ? ' · 当前执行者' : '' }}</option></optgroup>
          </select></label>
          <label><span>Configuration Revision</span><select v-model="revisionValue" :disabled="submitting || retryable || !selectedProfileId || effectiveMode === 'assign-only'">
            <option value="current">当前配置</option>
            <option v-for="item in revisionOptions" :key="item.revision" :value="String(item.revision)">固定历史 r{{ item.revision }}</option>
          </select></label>
        </div>
        <div class="agent-meta"><span>{{ selectedCandidate ? ownershipLabel(selectedCandidate) : '—' }}</span><span>{{ selectedCandidate ? stateLabel(selectedCandidate) : '由服务端标注状态' }}</span></div>
        <ul v-if="blockedCandidates.length" class="blocked-candidates" aria-label="暂不可分配的候选">
          <li v-for="candidate in blockedCandidates" :key="candidate.agentProfileId"><TriangleAlert :size="13" /><span>{{ candidate.displayName }} · {{ stateLabel(candidate) }}{{ candidate.reason ? `——${candidate.reason}` : '' }}</span></li>
        </ul>

        <div v-if="effectiveMode !== 'assign-only'" class="preflight-shell" aria-live="polite" aria-atomic="true">
          <div v-if="preflightResource?.phase === 'loading'" class="preflight-state"><RefreshCw class="spin" :size="15" />正在解析 ExecutionScope、Binding、模型与 PolicySnapshot…</div>
          <div v-else-if="preflightCurrent && approvedPreflight" class="preflight-card">
            <div class="preflight-header"><div><CheckCircle2 :size="16" /><strong>PolicySnapshot Preflight 通过</strong></div><span :class="approvedPreflight.executionScope.toLowerCase()">{{ enumLabel(approvedPreflight.executionScope, agentExecutionScopeLabels) }}</span></div>
            <dl>
              <div><dt>配置</dt><dd>r{{ approvedPreflight.configurationRevision }} · {{ sourceLabel(approvedPreflight.bindingSource) }}</dd></div>
              <div><dt>Primary</dt><dd>{{ approvedPreflight.primary.providerKey }} / {{ approvedPreflight.primary.modelId }}</dd></div>
              <div><dt>模型来源</dt><dd>{{ enumLabel(approvedPreflight.primary.connectionOwnerType, ownerTypeLabels) }}连接 · Catalog r{{ approvedPreflight.primary.catalogRevision }} · Price r{{ approvedPreflight.primary.priceRevision }}</dd></div>
              <div><dt>Fallback</dt><dd>{{ approvedPreflight.fallback ? `${approvedPreflight.fallback.providerKey} / ${approvedPreflight.fallback.modelId}` : '未配置' }}</dd></div>
              <div><dt>PolicySnapshot</dt><dd>PolicyPack v{{ approvedPreflight.policyPackVersion }} · {{ approvedPreflight.resolutionHash.slice(0, 12) }}</dd></div>
              <div><dt>成本主体</dt><dd>服务端已固定；当前 Preflight API 不披露 Billing Subject</dd></div>
            </dl>
            <p v-if="approvedPreflight.executionScope === 'TEAM'">TEAM 执行只允许 TEAM / ORGANIZATION Connection，USER Key 已在服务端禁用。</p>
          </div>
          <div v-else-if="preflightResource?.phase === 'error'" class="preflight-state error" role="alert"><TriangleAlert :size="15" /><span>{{ preflightError() }}</span><BaseButton type="button" size="small" variant="secondary" :disabled="submitting || retryable" @click="runPreflight">重新预检</BaseButton></div>
          <div v-else class="preflight-state"><ShieldCheck :size="15" />选择 Agent 与配置后执行服务端预检。</div>
        </div>
      </section>

      <template v-if="effectiveMode !== 'assign-only'">
        <CodingTargetFormSection v-if="selectable.length" :scope="codingScope" :work-item-id="workItem.id" :initial="codingInitial" :disabled="submitting || retryable" @change="codingChanged" />
        <div v-if="selectable.length" class="delegate-fields">
          <label><span>执行目标</span><input v-model="form.objective" maxlength="2000" :disabled="submitting || retryable" :aria-invalid="submitted && !form.objective.trim()"></label>
          <label><span>验收标准 <small>每行一项</small></span><textarea v-model="form.acceptanceCriteria" rows="5" maxlength="8000" :disabled="submitting || retryable" :aria-invalid="submitted && criteria.length === 0" /></label>
          <p>草稿按 Organization、Team、Project 和 WorkItem 隔离保存在当前浏览器会话；进入可重试状态后冻结，并只使用原请求与原幂等键重试。</p>
        </div>
      </template>

      <section v-if="selectable.length" class="intent-confirm" aria-labelledby="intent-title">
        <h3 id="intent-title">影响确认</h3>
        <ul>
          <li>原工作：{{ workItem.key }} · {{ workItem.title }}（Owner {{ ownerName }}）</li>
          <li>{{ assignsResponsibility ? `责任：把 ${selectedCandidate?.displayName ?? '所选 Agent'} 记为 EXECUTOR` : '责任：沿用当前责任链，不新增分配' }}</li>
          <li v-if="effectiveMode !== 'assign-only'">启动：一次可审计执行（{{ codingIntentLine }}）</li>
          <li v-else>启动：本次不启动任何执行</li>
          <li v-for="gap in defaultGaps" :key="gap" class="gap"><TriangleAlert :size="13" />{{ gap }}</li>
        </ul>
      </section>
      <p v-if="!selectable.length" class="delegate-unavailable">当前没有可分配的 Agent 候选。请先由 Agent Owner 启用 Agent，或让团队成员创建个人 Agent。</p>
      </template>
      <p v-if="errorMessage" class="delegate-error" role="alert">{{ errorMessage }}</p>
      <div class="dialog-footer"><BaseButton type="button" variant="ghost" :disabled="submitting" @click="requestClose">取消</BaseButton><BaseButton v-if="retryable" type="button" :loading="submitting" @click="onRetry">使用原请求重试</BaseButton><BaseButton v-else type="submit" :loading="submitting" :disabled="!valid">{{ submitLabel }}</BaseButton></div>
      </form>
    </div>
  </div>
</template>

<style scoped>
.delegate-backdrop { position: fixed; inset: 0; z-index: var(--cs-z-dialog); display: grid; place-items: center; padding: var(--cs-space-20); background: var(--cs-scrim); backdrop-filter: blur(3px); }.delegate-dialog { width: min(780px, 100%); max-height: calc(100vh - 36px); overflow-y: auto; box-shadow: var(--cs-shadow-float); }.delegate-dialog > header { display: grid; grid-template-columns: 42px minmax(0, 1fr) 32px; align-items: start; gap: var(--cs-space-12); padding: var(--cs-space-20); border-bottom: 1px solid var(--cs-border); }.delegate-icon { display: grid; width: 42px; height: 42px; place-items: center; border-radius: 12px; background: var(--cs-agent-soft); color: var(--cs-agent); }.delegate-dialog h2 { margin: 0 0 var(--cs-space-4); font-size: var(--cs-text-lg); }.delegate-dialog header div > span { color: var(--cs-text-muted); font-size: var(--cs-text-sm); }.delegate-dialog header button { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 8px; background: var(--cs-surface-subtle); cursor: pointer; }.responsibility-preview { display: flex; align-items: center; gap: var(--cs-space-12); margin: var(--cs-space-16) var(--cs-space-20) 0; padding: var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface-accent); color: var(--cs-text-brand); }.responsibility-preview strong, .responsibility-preview span { display: block; }.responsibility-preview strong { font-size: var(--cs-text-sm); }.responsibility-preview span { margin-top: var(--cs-space-2); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.conversation-source-note { margin: var(--cs-space-8) var(--cs-space-20) 0; padding: var(--cs-space-8) var(--cs-space-12); border-radius: 8px; background: var(--cs-agent-soft); color: var(--cs-agent); font-size: var(--cs-text-xs); }.agent-selection { margin: var(--cs-space-16) var(--cs-space-20) 0; overflow: hidden; border: 1px solid var(--cs-border-accent); border-radius: var(--cs-radius-md); background: linear-gradient(145deg, var(--cs-surface), var(--cs-surface-accent)); }.section-heading { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-12); padding: var(--cs-space-12) var(--cs-space-12) var(--cs-space-8); }.section-heading p, .section-heading h3 { margin: 0; }.section-heading p { color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); text-transform: uppercase; }.section-heading h3 { margin-top: var(--cs-space-2); font-size: var(--cs-text-base); }.section-heading > span { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.selection-grid { display: grid; grid-template-columns: 1.25fr .75fr; gap: var(--cs-space-12); padding: 0 var(--cs-space-12) var(--cs-space-12); }.selection-grid label { display: grid; gap: var(--cs-space-4); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.selection-grid select { width: 100%; min-height: 36px; padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border-strong); border-radius: 8px; background: var(--cs-surface); color: var(--cs-text); font: var(--cs-text-base) var(--cs-font-sans); }.agent-meta { display: flex; flex-wrap: wrap; gap: var(--cs-space-4); padding: 0 var(--cs-space-12) var(--cs-space-12); }.agent-meta span { padding: var(--cs-space-4) var(--cs-space-8); border-radius: 999px; background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.preflight-shell { border-top: 1px solid var(--cs-border-accent); }.preflight-state { display: flex; min-height: 56px; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-12) var(--cs-space-12); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.preflight-state.error { color: var(--cs-danger); }.preflight-state.error span { flex: 1; }.preflight-card { padding: var(--cs-space-12) var(--cs-space-12) var(--cs-space-12); background: var(--cs-surface-glass); }.preflight-card > header { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-12); }.preflight-card > header div { display: flex; align-items: center; gap: var(--cs-space-8); color: var(--cs-success); font-size: var(--cs-text-sm); }.preflight-card > header span { padding: var(--cs-space-4) var(--cs-space-8); border-radius: 999px; font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.preflight-card > header span.personal { background: var(--cs-agent-soft); color: var(--cs-agent); }.preflight-card > header span.team { background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); }.preflight-card dl { display: grid; grid-template-columns: 1fr 1fr; gap: var(--cs-space-8) var(--cs-space-16); margin: var(--cs-space-12) 0 0; }.preflight-card dl div { min-width: 0; }.preflight-card dt { color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.preflight-card dd { margin: var(--cs-space-2) 0 0; overflow-wrap: anywhere; color: var(--cs-text-secondary); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }.preflight-card > p { margin: var(--cs-space-12) 0 0; padding: var(--cs-space-8) var(--cs-space-8); border-radius: 7px; background: var(--cs-surface-accent); color: var(--cs-text-brand); font-size: var(--cs-text-xs); }.delegate-fields { display: grid; gap: var(--cs-space-12); padding: var(--cs-space-16) var(--cs-space-20) var(--cs-space-4); }.delegate-fields label { display: grid; gap: var(--cs-space-4); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.delegate-fields small { color: var(--cs-text-muted); font-weight: var(--cs-weight-medium); }.delegate-fields input, .delegate-fields textarea { width: 100%; min-height: 36px; padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-text); font: var(--cs-text-base) var(--cs-font-sans); }.delegate-fields textarea { resize: vertical; }.delegate-fields [aria-invalid="true"] { border-color: var(--cs-danger); }.delegate-fields p, .delegate-unavailable { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }.delegate-unavailable { margin: var(--cs-space-16) var(--cs-space-20) 0; padding: var(--cs-space-12); border-radius: var(--cs-radius-md); background: var(--cs-warning-soft); color: var(--cs-warning); }.delegate-error { margin: var(--cs-space-12) var(--cs-space-20) 0; color: var(--cs-danger); font-size: var(--cs-text-sm); }.delegate-dialog > footer { display: flex; justify-content: flex-end; gap: var(--cs-space-8); padding: var(--cs-space-16) var(--cs-space-20) var(--cs-space-20); }.spin { animation: spin var(--cs-motion-spin) linear infinite; }@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 767px) { .delegate-backdrop { align-items: end; padding: 0; }.delegate-dialog { width: 100%; max-height: 94vh; border-radius: 18px 18px 0 0; }.delegate-dialog > header { padding: var(--cs-space-16) var(--cs-space-16); }.responsibility-preview, .conversation-source-note, .agent-selection { margin-inline: var(--cs-space-16); }.selection-grid, .preflight-card dl { grid-template-columns: 1fr; }.delegate-fields { padding-inline: var(--cs-space-16); }.delegate-dialog > footer { display: grid; padding-inline: var(--cs-space-16); }.delegate-dialog > footer > * { width: 100%; } }
.dialog-header { display: grid; grid-template-columns: 42px minmax(0, 1fr) 32px; align-items: start; gap: var(--cs-space-12); padding: var(--cs-space-20); border-bottom: 1px solid var(--cs-border); }.dialog-header div > span { color: var(--cs-text-muted); font-size: var(--cs-text-sm); }.dialog-header button { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 8px; background: var(--cs-surface-subtle); cursor: pointer; }.dialog-footer { display: flex; justify-content: flex-end; gap: var(--cs-space-8); padding: var(--cs-space-16) var(--cs-space-20) var(--cs-space-20); }
.preflight-header { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-12); }.preflight-header > div { display: flex; align-items: center; gap: var(--cs-space-8); color: var(--cs-success); font-size: var(--cs-text-sm); }.preflight-header > span { padding: var(--cs-space-4) var(--cs-space-8); border-radius: 999px; font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.preflight-header > span.personal { background: var(--cs-agent-soft); color: var(--cs-agent); }.preflight-header > span.team { background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); }
@media (max-width: 767px) { .dialog-header { padding: var(--cs-space-16) var(--cs-space-16); }.dialog-footer { display: grid; padding-inline: var(--cs-space-16); }.dialog-footer > * { width: 100%; } }
.context-state { display: flex; align-items: center; gap: var(--cs-space-8); margin: var(--cs-space-16) var(--cs-space-20) 0; padding: var(--cs-space-12); border-radius: var(--cs-radius-md); background: var(--cs-surface-accent); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.context-state.error { color: var(--cs-danger); }.context-state.error span { flex: 1; }
.active-execution-note { display: flex; align-items: center; gap: var(--cs-space-8); margin: var(--cs-space-16) var(--cs-space-20) 0; padding: var(--cs-space-8) var(--cs-space-12); border-radius: 8px; background: var(--cs-warning-soft); color: var(--cs-warning); font-size: var(--cs-text-xs); }
.mode-choice { display: grid; gap: var(--cs-space-8); margin: var(--cs-space-16) var(--cs-space-20) 0; padding: var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); }.mode-choice legend { padding: 0 var(--cs-space-4); color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.mode-choice > label { display: flex; align-items: center; gap: var(--cs-space-8); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); }.mode-choice > label input { accent-color: var(--cs-focus); }.mode-choice > p { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }
.blocked-candidates { display: grid; gap: var(--cs-space-4); margin: 0; padding: 0 var(--cs-space-12) var(--cs-space-12); list-style: none; }.blocked-candidates li { display: flex; align-items: baseline; gap: var(--cs-space-4); color: var(--cs-warning); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }
.intent-confirm { margin: var(--cs-space-16) var(--cs-space-20) 0; padding: var(--cs-space-12); border: 1px solid var(--cs-border-accent); border-radius: var(--cs-radius-md); background: var(--cs-surface-accent); }.intent-confirm h3 { margin: 0 0 var(--cs-space-8); font-size: var(--cs-text-sm); color: var(--cs-text-brand); }.intent-confirm ul { display: grid; gap: var(--cs-space-4); margin: 0; padding: 0; list-style: none; }.intent-confirm li { color: var(--cs-text-secondary); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }.intent-confirm li.gap { display: flex; align-items: baseline; gap: var(--cs-space-4); color: var(--cs-warning); }
</style>
