<script setup lang="ts">
import { Bot, CheckCircle2, RefreshCw, ShieldCheck, TriangleAlert, X } from '@lucide/vue'
import { computed, nextTick, onMounted, reactive, ref, useTemplateRef, watch } from 'vue'
import { isTopmostModal } from '../../app/dialog'
import type { AgentSummary } from '../../domains/agent/types'
import { useAgentStore } from '../../domains/agent/store'
import type { CodingScope, CodingTargetSelection } from '../../domains/coding/types'
import { readTaskDelegationDraft, writeTaskDelegationDraft } from '../../domains/task/delegationDraft'
import { delegationPreflightKey, useTaskStore } from '../../domains/task/store'
import type { CreateTaskInput, TaskDelegationPreflight, TaskDelegationSelection } from '../../domains/task/types'
import type { ResponsibilityAssignment, WorkItemSummary } from '../../domains/workitem/types'
import BaseButton from '../base/BaseButton.vue'
import CodingTargetFormSection from './CodingTargetFormSection.vue'

const props = defineProps<{
  workItem: WorkItemSummary
  codingScope: CodingScope
  responsibilities: ResponsibilityAssignment[]
  submitting: boolean
  retryable: boolean
  errorMessage: string | null
  conversationSource?: { conversationId: string, messageId: string } | null
  onSubmit: (input: CreateTaskInput) => Promise<void>
  onRetry: () => Promise<void>
}>()

const emit = defineEmits<{ close: [] }>()
const agentStore = useAgentStore()
const taskStore = useTaskStore()
const dialog = useTemplateRef<HTMLElement>('dialog')
const submitted = ref(false)
const codingSelection = ref<CodingTargetSelection | null>(null)
const codingValid = ref(false)
const selectedProfileId = ref('')
const revisionValue = ref('current')
const approvedPreflight = ref<TaskDelegationPreflight | null>(null)
let initialized = false

const form = reactive({
  objective: props.workItem.title,
  acceptanceCriteria: props.workItem.description?.trim() || '完成工作项目标并提供可验证结果',
})
const owner = computed(() => props.responsibilities.find(item => item.role === 'OWNER') ?? null)
const executorAssignments = computed(() => {
  const seen = new Set<string>()
  return props.responsibilities.filter(item => {
    if (item.role !== 'EXECUTOR'
      || !['PERSONAL_AGENT', 'TEAM_AGENT'].includes(item.actorType)
      || !item.actorAgentProfileId
      || seen.has(item.actorAgentProfileId)) return false
    seen.add(item.actorAgentProfileId)
    return true
  })
})
const agentById = computed(() => new Map((agentStore.state.agents.value ?? []).map(agent => [agent.id, agent])))
const candidates = computed(() => executorAssignments.value.map(assignment => ({
  assignment,
  agent: agentById.value.get(assignment.actorAgentProfileId!) ?? null,
})))
const personalCandidates = computed(() => candidates.value.filter(candidate => ownership(candidate) === 'USER'))
const teamCandidates = computed(() => candidates.value.filter(candidate => ownership(candidate) !== 'USER'))
const selectedCandidate = computed(() => candidates.value.find(candidate =>
  candidate.assignment.actorAgentProfileId === selectedProfileId.value,
) ?? null)
const selectedAgent = computed(() => selectedCandidate.value?.agent ?? null)
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
const valid = computed(() => form.objective.trim().length > 0
  && criteria.value.length > 0
  && Boolean(selectedCandidate.value)
  && codingValid.value
  && preflightCurrent.value)

onMounted(async () => {
  restoreDraft()
  taskStore.activateScope(props.codingScope)
  agentStore.activateScope(props.codingScope)
  await agentStore.loadAgents()
  if (!selectedProfileId.value || !candidates.value.some(candidate =>
    candidate.assignment.actorAgentProfileId === selectedProfileId.value)) {
    selectedProfileId.value = candidates.value[0]?.assignment.actorAgentProfileId ?? ''
    revisionValue.value = 'current'
  }
  initialized = true
  if (selectedProfileId.value) {
    await agentStore.loadConfigurationHistory(selectedProfileId.value)
    await runPreflight()
  }
  // Focus the dialog container so narrow screens retain the Agent/preflight context at the top.
  await nextTick(() => dialog.value?.focus())
})

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
  const preflight = approvedPreflight.value
  if (!valid.value || !preflight) return
  await props.onSubmit({
    objective: form.objective.trim(),
    acceptanceCriteria: criteria.value,
    executorAgentProfileId: preflight.agentProfileId,
    // Creation pins the exact revision returned by preflight, even when “current” was selected.
    agentConfigurationRevision: preflight.configurationRevision,
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
  const draft = readTaskDelegationDraft(props.codingScope, props.codingScope.projectId, props.workItem.id)
  if (!draft) {
    selectedProfileId.value = executorAssignments.value[0]?.actorAgentProfileId ?? ''
    return
  }
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
  })
}

function ownership(candidate: { assignment: ResponsibilityAssignment, agent: AgentSummary | null }): string {
  return candidate.agent?.ownershipType ?? (candidate.assignment.actorType === 'PERSONAL_AGENT' ? 'USER' : 'TEAM')
}

function candidateLabel(candidate: { assignment: ResponsibilityAssignment, agent: AgentSummary | null }): string {
  const role = candidate.agent?.runtimeRole ?? candidate.assignment.actorType.replace('_AGENT', '')
  return `${candidate.assignment.actorDisplayName} · ${role}`
}

function sourceLabel(source: string): string {
  return ({ DIRECT: 'Agent 直接配置', TEAM_DEFAULT: '继承 Team 默认', ORGANIZATION_DEFAULT: '继承 Organization 默认' } as Record<string, string>)[source] ?? source
}

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
      <div class="dialog-header"><span class="delegate-icon"><Bot :size="19" /></span><div><p class="eyebrow">Durable Task · {{ workItem.key }}</p><h2 id="delegate-title">交给 Agent 处理</h2><span>选择责任链 Agent，预检实际模型后创建可审计 Task。</span></div><button type="button" aria-label="关闭交给 Agent 对话框" :disabled="submitting" @click="requestClose"><X :size="18" /></button></div>
      <section class="responsibility-preview" aria-label="责任预览"><ShieldCheck :size="17" /><div><strong>Owner · {{ owner?.actorDisplayName ?? '未配置' }}</strong><span>Executor · {{ selectedCandidate?.assignment.actorDisplayName ?? '请从当前责任链选择' }}；服务端会再次授权。</span></div></section>
      <p v-if="conversationSource" class="conversation-source-note">来源保留为当前 Conversation 消息；创建后可从对话和工作项双向查看 Task。</p>

      <section v-if="candidates.length" class="agent-selection" aria-labelledby="agent-selection-title">
        <div class="section-heading"><div><p>Execution identity</p><h3 id="agent-selection-title">Agent 与配置</h3></div><span>{{ candidates.length }} 个责任链候选</span></div>
        <div class="selection-grid">
          <label><span>执行 Agent</span><select v-model="selectedProfileId" :disabled="submitting || retryable || agentStore.state.agents.phase === 'loading'">
            <optgroup v-if="personalCandidates.length" label="个人 Agent"><option v-for="candidate in personalCandidates" :key="candidate.assignment.actorAgentProfileId!" :value="candidate.assignment.actorAgentProfileId!">{{ candidateLabel(candidate) }}</option></optgroup>
            <optgroup v-if="teamCandidates.length" label="团队 Agent"><option v-for="candidate in teamCandidates" :key="candidate.assignment.actorAgentProfileId!" :value="candidate.assignment.actorAgentProfileId!">{{ candidateLabel(candidate) }}</option></optgroup>
          </select></label>
          <label><span>Configuration Revision</span><select v-model="revisionValue" :disabled="submitting || retryable || !selectedProfileId">
            <option value="current">当前配置{{ selectedAgent?.currentConfigurationRevision ? ` · r${selectedAgent.currentConfigurationRevision}` : '' }}</option>
            <option v-for="item in revisionOptions" :key="item.revision" :value="String(item.revision)">固定历史 r{{ item.revision }}</option>
          </select></label>
        </div>
        <div class="agent-meta"><span>{{ selectedAgent?.ownershipType ?? selectedCandidate?.assignment.actorType }}</span><span>{{ selectedAgent?.runtimeRole ?? '责任链 Agent' }}</span><span>{{ selectedAgent?.status ?? '由 Preflight 校验状态' }}</span></div>

        <div class="preflight-shell" aria-live="polite" aria-atomic="true">
          <div v-if="preflightResource?.phase === 'loading'" class="preflight-state"><RefreshCw class="spin" :size="15" />正在解析 ExecutionScope、Binding、模型与 PolicySnapshot…</div>
          <div v-else-if="preflightCurrent && approvedPreflight" class="preflight-card">
            <div class="preflight-header"><div><CheckCircle2 :size="16" /><strong>PolicySnapshot Preflight 通过</strong></div><span :class="approvedPreflight.executionScope.toLowerCase()">{{ approvedPreflight.executionScope }}</span></div>
            <dl>
              <div><dt>配置</dt><dd>r{{ approvedPreflight.configurationRevision }} · {{ sourceLabel(approvedPreflight.bindingSource) }}</dd></div>
              <div><dt>Primary</dt><dd>{{ approvedPreflight.primary.providerKey }} / {{ approvedPreflight.primary.modelId }}</dd></div>
              <div><dt>模型来源</dt><dd>{{ approvedPreflight.primary.connectionOwnerType }} Connection · Catalog r{{ approvedPreflight.primary.catalogRevision }} · Price r{{ approvedPreflight.primary.priceRevision }}</dd></div>
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

      <CodingTargetFormSection v-if="candidates.length" :scope="codingScope" :work-item-id="workItem.id" :disabled="submitting || retryable" @change="codingChanged" />
      <div v-if="candidates.length" class="delegate-fields">
        <label><span>执行目标</span><input v-model="form.objective" maxlength="2000" :disabled="submitting || retryable" :aria-invalid="submitted && !form.objective.trim()"></label>
        <label><span>验收标准 <small>每行一项</small></span><textarea v-model="form.acceptanceCriteria" rows="5" maxlength="8000" :disabled="submitting || retryable" :aria-invalid="submitted && criteria.length === 0" /></label>
        <p>草稿按 Organization、Team、Project 和 WorkItem 隔离保存在当前浏览器会话；进入可重试状态后冻结，并只使用原请求与原幂等键重试。</p>
      </div>
      <p v-else class="delegate-unavailable">当前责任链没有 Personal Agent 或 Team Agent Executor。请先在责任链中分配 Agent。</p>
      <p v-if="errorMessage" class="delegate-error" role="alert">{{ errorMessage }}</p>
      <div class="dialog-footer"><BaseButton type="button" variant="ghost" :disabled="submitting" @click="requestClose">取消</BaseButton><BaseButton v-if="retryable" type="button" :loading="submitting" @click="onRetry">使用原请求重试</BaseButton><BaseButton v-else type="submit" :loading="submitting" :disabled="!valid">创建 Task</BaseButton></div>
      </form>
    </div>
  </div>
</template>

<style scoped>
.delegate-backdrop { position: fixed; inset: 0; z-index: 80; display: grid; place-items: center; padding: 18px; background: rgb(21 35 29 / 34%); backdrop-filter: blur(3px); }.delegate-dialog { width: min(780px, 100%); max-height: calc(100vh - 36px); overflow-y: auto; box-shadow: var(--cs-shadow-float); }.delegate-dialog > header { display: grid; grid-template-columns: 42px minmax(0, 1fr) 32px; align-items: start; gap: 11px; padding: 20px; border-bottom: 1px solid var(--cs-border); }.delegate-icon { display: grid; width: 42px; height: 42px; place-items: center; border-radius: 12px; background: var(--cs-agent-soft); color: var(--cs-agent); }.delegate-dialog h2 { margin: 0 0 3px; font-size: 18px; }.delegate-dialog header div > span { color: var(--cs-text-muted); font-size: 10px; }.delegate-dialog header button { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 8px; background: var(--cs-surface-subtle); cursor: pointer; }.responsibility-preview { display: flex; align-items: center; gap: 10px; margin: 16px 20px 0; padding: 12px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-brand-50); color: var(--cs-brand-700); }.responsibility-preview strong, .responsibility-preview span { display: block; }.responsibility-preview strong { font-size: 10px; }.responsibility-preview span { margin-top: 2px; color: var(--cs-text-muted); font-size: 9px; }.conversation-source-note { margin: 9px 20px 0; padding: 8px 10px; border-radius: 8px; background: var(--cs-agent-soft); color: var(--cs-agent); font-size: 9px; }.agent-selection { margin: 15px 20px 0; overflow: hidden; border: 1px solid var(--cs-brand-200); border-radius: var(--cs-radius-md); background: linear-gradient(145deg, #fff, var(--cs-brand-50)); }.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 13px 9px; }.section-heading p, .section-heading h3 { margin: 0; }.section-heading p { color: var(--cs-brand-600); font-size: 8px; font-weight: 800; text-transform: uppercase; }.section-heading h3 { margin-top: 2px; font-size: 12px; }.section-heading > span { color: var(--cs-text-muted); font-size: 8px; }.selection-grid { display: grid; grid-template-columns: 1.25fr .75fr; gap: 10px; padding: 0 13px 10px; }.selection-grid label { display: grid; gap: 5px; color: var(--cs-text-secondary); font-size: 9px; font-weight: 750; }.selection-grid select { width: 100%; min-height: 36px; padding: 0 9px; border: 1px solid var(--cs-border-strong); border-radius: 8px; background: #fff; color: var(--cs-text); font: 9px var(--cs-font-sans); }.agent-meta { display: flex; flex-wrap: wrap; gap: 5px; padding: 0 13px 10px; }.agent-meta span { padding: 3px 7px; border-radius: 999px; background: var(--cs-brand-100); color: var(--cs-brand-700); font-size: 8px; font-weight: 750; }.preflight-shell { border-top: 1px solid var(--cs-brand-100); }.preflight-state { display: flex; min-height: 56px; align-items: center; gap: 8px; padding: 12px 13px; color: var(--cs-text-muted); font-size: 9px; }.preflight-state.error { color: var(--cs-danger); }.preflight-state.error span { flex: 1; }.preflight-card { padding: 11px 13px 13px; background: rgb(255 255 255 / 72%); }.preflight-card > header { display: flex; align-items: center; justify-content: space-between; gap: 10px; }.preflight-card > header div { display: flex; align-items: center; gap: 6px; color: var(--cs-success); font-size: 10px; }.preflight-card > header span { padding: 3px 7px; border-radius: 999px; font-size: 8px; font-weight: 850; }.preflight-card > header span.personal { background: var(--cs-agent-soft); color: var(--cs-agent); }.preflight-card > header span.team { background: var(--cs-brand-100); color: var(--cs-brand-700); }.preflight-card dl { display: grid; grid-template-columns: 1fr 1fr; gap: 8px 14px; margin: 10px 0 0; }.preflight-card dl div { min-width: 0; }.preflight-card dt { color: var(--cs-text-muted); font-size: 8px; font-weight: 750; }.preflight-card dd { margin: 2px 0 0; overflow-wrap: anywhere; color: var(--cs-text-secondary); font-size: 9px; line-height: 1.4; }.preflight-card > p { margin: 10px 0 0; padding: 7px 8px; border-radius: 7px; background: var(--cs-brand-50); color: var(--cs-brand-700); font-size: 8px; }.delegate-fields { display: grid; gap: 13px; padding: 16px 20px 4px; }.delegate-fields label { display: grid; gap: 5px; color: var(--cs-text-secondary); font-size: 9px; font-weight: 750; }.delegate-fields small { color: var(--cs-text-muted); font-weight: 500; }.delegate-fields input, .delegate-fields textarea { width: 100%; min-height: 36px; padding: 8px 10px; border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-text); font: 10px var(--cs-font-sans); }.delegate-fields textarea { resize: vertical; }.delegate-fields [aria-invalid="true"] { border-color: var(--cs-danger); }.delegate-fields p, .delegate-unavailable { margin: 0; color: var(--cs-text-muted); font-size: 9px; line-height: 1.5; }.delegate-unavailable { margin: 16px 20px 0; padding: 13px; border-radius: var(--cs-radius-md); background: var(--cs-warning-soft); color: var(--cs-warning); }.delegate-error { margin: 12px 20px 0; color: var(--cs-danger); font-size: 10px; }.delegate-dialog > footer { display: flex; justify-content: flex-end; gap: 7px; padding: 17px 20px 20px; }.spin { animation: spin 1s linear infinite; }@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 767px) { .delegate-backdrop { align-items: end; padding: 0; }.delegate-dialog { width: 100%; max-height: 94vh; border-radius: 18px 18px 0 0; }.delegate-dialog > header { padding: 17px 16px; }.responsibility-preview, .conversation-source-note, .agent-selection { margin-inline: 16px; }.selection-grid, .preflight-card dl { grid-template-columns: 1fr; }.delegate-fields { padding-inline: 16px; }.delegate-dialog > footer { display: grid; padding-inline: 16px; }.delegate-dialog > footer > * { width: 100%; } }
.dialog-header { display: grid; grid-template-columns: 42px minmax(0, 1fr) 32px; align-items: start; gap: 11px; padding: 20px; border-bottom: 1px solid var(--cs-border); }.dialog-header div > span { color: var(--cs-text-muted); font-size: 10px; }.dialog-header button { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 8px; background: var(--cs-surface-subtle); cursor: pointer; }.dialog-footer { display: flex; justify-content: flex-end; gap: 7px; padding: 17px 20px 20px; }
.preflight-header { display: flex; align-items: center; justify-content: space-between; gap: 10px; }.preflight-header > div { display: flex; align-items: center; gap: 6px; color: var(--cs-success); font-size: 10px; }.preflight-header > span { padding: 3px 7px; border-radius: 999px; font-size: 8px; font-weight: 850; }.preflight-header > span.personal { background: var(--cs-agent-soft); color: var(--cs-agent); }.preflight-header > span.team { background: var(--cs-brand-100); color: var(--cs-brand-700); }
@media (max-width: 767px) { .dialog-header { padding: 17px 16px; }.dialog-footer { display: grid; padding-inline: 16px; }.dialog-footer > * { width: 100%; } }
</style>
