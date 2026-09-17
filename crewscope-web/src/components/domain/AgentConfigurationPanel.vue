<script setup lang="ts">
import { Bot, CheckCircle2, Save, ShieldCheck, X } from '@lucide/vue'
import { computed, nextTick, onMounted, reactive, ref, useTemplateRef, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { permissions } from '../../app/auth'
import { useAgentStore } from '../../domains/agent/store'
import { useScopeStore } from '../../domains/scope/store'
import type {
  AgentConfigurationInput,
  AgentExecutionScope,
  AgentGenerateOptionsInput,
  AgentLifecycleTransition,
  AgentModelBindingInput,
  AgentModelSelectionInput,
  AgentModelSelectionSummary,
  AgentSummary,
  AgentTemplateSummary,
  CurrentAgentConfiguration,
  SelectableAgentModel,
} from '../../domains/agent/types'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'
import AgentConfigurationHistoryPanel from './AgentConfigurationHistoryPanel.vue'
import AgentConfigurationRevisionView from './AgentConfigurationRevisionView.vue'
import AgentConfigurationModelBindingSection from './AgentConfigurationModelBindingSection.vue'
import AgentConfigurationPreferencesSection from './AgentConfigurationPreferencesSection.vue'
import AgentConfigurationLifecycleSection from './AgentConfigurationLifecycleSection.vue'
import { generateOptionFields, withinGenerateOptionLimit, withinSeedBound } from '../../domains/agent/limits'
import { useClipboard } from '../../composables/useClipboard'
import { useDirtyForm } from '../../composables/useDirtyForm'
import {
  agentBindingSourceLabels,
  agentExecutionScopeLabels,
  agentOwnershipTypeLabels,
  agentRuntimeRoleLabels,
  agentStatusLabels,
} from '../../domains/agent/labels'
import { enumLabel } from '../../domains/shared/labels'
import type { AgentBindingForms, BindingForm, PreferenceForm, PreferenceNumber } from '../../domains/agent/configurationTypes'

const props = defineProps<{
  agent: AgentSummary
  template: AgentTemplateSummary | null
  canConfigure: boolean
  selectedRevision: number | null
}>()

const emit = defineEmits<{
  close: []
  refreshed: []
  selectRevision: [revision: number]
}>()

const store = useAgentStore()
const scopeStore = useScopeStore()
const route = useRoute()
const heading = useTemplateRef<HTMLElement>('heading')
const initializedRevision = ref<number | null | 'none'>(null)
const submitted = ref(false)
const saveKey = ref('')
const saveSignature = ref('')
const lifecycleConfirmation = ref<AgentLifecycleTransition | null>(null)
const lifecycleKey = ref('')
const localNotice = ref<string | null>(null)
const bindings = reactive<AgentBindingForms>({
  PERSONAL: { kind: 'DIRECT', primary: '', fallback: '' },
  TEAM: { kind: 'DIRECT', primary: '', fallback: '' },
})
const preferences = reactive<PreferenceForm>({
  supplementalInstructions: '',
  approvedSkillKeys: [] as string[],
  temperature: '',
  topP: '',
  maximumOutputTokens: '',
  reasoningMode: 'DEFAULT',
  cacheEnabled: true,
  parallelToolCalls: false,
  seed: '',
  maximumAttempts: '1',
})

const formSnapshot = computed(() => ({
  bindings: { PERSONAL: { ...bindings.PERSONAL }, TEAM: { ...bindings.TEAM } },
  preferences: { ...preferences, approvedSkillKeys: [...preferences.approvedSkillKeys] },
}))
const dirtyForm = useDirtyForm(formSnapshot, { draftKey: `crewscope:agent-configuration:${props.agent.id}` })
const clipboard = useClipboard()
const copiedHash = computed(() => clipboard.copied.value)
const draftAvailable = ref(Boolean(dirtyForm.restoreDraft()))

// Scope switches update the same route component, so Vue Router does not invoke
// onBeforeRouteLeave. Persist a dirty configuration before the selected Team changes so the
// member can return to this Agent and explicitly recover it.
watch(() => route.query.team, (teamId, previousTeamId) => {
  if (teamId === previousTeamId || !dirtyForm.isDirty.value) return
  dirtyForm.saveDraft()
  draftAvailable.value = true
})
watch(() => scopeStore.state.selectedTeamId, (teamId, previousTeamId) => {
  if (!teamId || teamId === previousTeamId || !dirtyForm.isDirty.value) return
  dirtyForm.saveDraft()
  draftAvailable.value = true
})

const currentResource = computed(() => store.state.currentConfigurations[props.agent.id])
const platformManaged = computed(() => props.template?.platformManaged
  ?? props.agent.templateKey === 'team-observer')
const current = computed(() => currentResource.value?.value?.value ?? null)
const historyResource = computed(() => store.state.configurationHistory[props.agent.id])
const history = computed(() => historyResource.value?.value ?? [])
const compareRevision = ref<number | null>(null)
const selectedHistory = computed(() => {
  const revision = props.selectedRevision ?? props.agent.currentConfigurationRevision
  return revision ? history.value.find(item => item.revision === revision) ?? null : null
})
const selectedHistoryConfiguration = computed(() => selectedHistory.value?.configuration ?? null)
const selectedPreviousConfiguration = computed(() => {
  const revision = compareRevision.value ?? selectedHistory.value?.previousRevision
  return revision ? history.value.find(item => item.revision === revision)?.configuration ?? null : null
})
const viewingCurrent = computed(() => props.selectedRevision === null || props.selectedRevision === props.agent.currentConfigurationRevision)
const commandForAgent = computed(() => store.state.command.resourceId === props.agent.id ? store.state.command : null)
const saving = computed(() => commandForAgent.value?.phase === 'pending' && commandForAgent.value.operation === 'configure')
const lifecyclePending = computed(() => commandForAgent.value?.phase === 'pending' && commandForAgent.value.operation !== 'configure')
const allowedScopes = computed<AgentExecutionScope[]>(() => {
  if (!props.template) return []
  return props.template.allowedExecutionScopes
    .filter((scope): scope is AgentExecutionScope => scope === 'PERSONAL' || scope === 'TEAM')
    .filter(scope => !(scope === 'TEAM' && props.agent.runtimeRole === 'PERSONAL_ASSISTANT'))
})
const historicalRevisionMissing = computed(() => Boolean(
  props.selectedRevision
  && historyResource.value?.phase === 'ready'
  && historyResource.value.nextOffset === null
  && !selectedHistory.value,
))
const formValid = computed(() => allowedScopes.value.length > 0 && allowedScopes.value.every(scope => {
  const binding = bindings[scope]
  if (binding.kind === 'INHERIT_TEAM_DEFAULT') return true
  const availableKeys = new Set(models(scope).map(modelKey))
  return Boolean(binding.primary
    && availableKeys.has(binding.primary)
    && binding.primary !== binding.fallback
    && (!binding.fallback || availableKeys.has(binding.fallback)))
}) && validPreferences.value)
const validPreferences = computed(() => {
  if (!slotAvailable('OUTPUT_PREFERENCE')) return true
  // The same table the form renders drives the submit gate, so a value the field accepted can never
  // be the value that blocks the command.
  return generateOptionFields().every(field => withinGenerateOptionLimit(field, preferences[field]))
    && withinSeedBound(preferences.seed)
})

watch(() => props.agent.id, () => {
  initializedRevision.value = null
  submitted.value = false
  localNotice.value = null
  lifecycleConfirmation.value = null
  compareRevision.value = null
  void loadFacts(true)
}, { immediate: true })

watch(() => props.template, (template, previous) => {
  if (!template || !props.canConfigure) return
  if (previous
    && `${previous.publisherType}:${previous.publisherId}:${previous.key}:${previous.version}`
      === `${template.publisherType}:${template.publisherId}:${template.key}:${template.version}`) return
  // Template and Agent directory load independently; request model facts once the exact Template arrives.
  void Promise.all(allowedScopes.value.map(scope => store.loadSelectableModels(props.agent.id, scope, true)))
})

watch([current, () => props.template], ([configuration]) => {
  const revision = configuration?.revision ?? 'none'
  if (initializedRevision.value === revision) return
  initializeForm(configuration)
  dirtyForm.markClean()
  draftAvailable.value = Boolean(dirtyForm.restoreDraft())
  initializedRevision.value = revision
}, { immediate: true })

watch(selectedHistory, value => { compareRevision.value = value?.previousRevision ?? null })

watch(formSnapshot, value => dirtyForm.sync(value), { deep: true })

onMounted(() => void nextTick(() => heading.value?.focus()))

async function loadFacts(force = false): Promise<void> {
  await Promise.all([
    store.loadAgent(props.agent.id, force),
    store.loadConfigurationHistory(props.agent.id, false, force),
    props.agent.currentConfigurationRevision !== null
      ? store.loadCurrentConfiguration(props.agent.id, force)
      : Promise.resolve(),
  ])
  await loadHistoryUntilSelected()
  if (props.canConfigure && props.template) {
    await Promise.all(allowedScopes.value.map(scope => store.loadSelectableModels(props.agent.id, scope, force)))
  }
}

async function loadHistoryUntilSelected(): Promise<void> {
  const target = props.selectedRevision
  if (!target || history.value.some(item => item.revision === target)) return
  let previousOffset: number | null | undefined
  while (historyResource.value?.nextOffset !== null
    && historyResource.value?.nextOffset !== previousOffset
    && !history.value.some(item => item.revision === target)) {
    previousOffset = historyResource.value?.nextOffset
    await store.loadConfigurationHistory(props.agent.id, true)
  }
}

function initializeForm(configuration: CurrentAgentConfiguration | null): void {
  initializeBinding('PERSONAL', configuration?.personalBinding ?? null)
  initializeBinding('TEAM', configuration?.teamBinding ?? null)
  preferences.supplementalInstructions = configuration?.supplementalInstructions ?? ''
  preferences.approvedSkillKeys = [...(configuration?.approvedSkillKeys ?? [])]
  preferences.temperature = configuration?.generateOptions.temperature ?? ''
  preferences.topP = configuration?.generateOptions.topP ?? ''
  preferences.maximumOutputTokens = nullableString(configuration?.generateOptions.maximumOutputTokens)
  preferences.reasoningMode = configuration?.generateOptions.reasoningMode ?? 'DEFAULT'
  preferences.cacheEnabled = configuration?.generateOptions.cacheEnabled ?? true
  preferences.parallelToolCalls = configuration?.generateOptions.parallelToolCalls ?? false
  preferences.seed = nullableString(configuration?.generateOptions.seed)
  preferences.maximumAttempts = String(configuration?.generateOptions.maximumAttempts ?? 1)
}

function initializeBinding(scope: AgentExecutionScope, binding: CurrentAgentConfiguration['personalBinding']): void {
  // Preserve an existing inherited revision, but keep a first revision directly configurable.
  // Deployments without an administrator-published default must never submit a phantom default.
  bindings[scope].kind = binding?.kind === 'INHERIT_TEAM_DEFAULT'
    ? 'INHERIT_TEAM_DEFAULT'
    : 'DIRECT'
  bindings[scope].primary = binding?.primary ? modelKey(binding.primary) : ''
  bindings[scope].fallback = binding?.fallback ? modelKey(binding.fallback) : ''
}

function models(scope: AgentExecutionScope): SelectableAgentModel[] {
  return store.state.selectableModels[`${props.agent.id}:${scope}`]?.value ?? []
}

function modelResourcePhase(scope: AgentExecutionScope): string {
  return store.state.selectableModels[`${props.agent.id}:${scope}`]?.phase ?? 'idle'
}

function modelResourceError(scope: AgentExecutionScope): string | null {
  return store.state.selectableModels[`${props.agent.id}:${scope}`]?.errorMessage ?? null
}

function modelKey(model: SelectableAgentModel | AgentModelSelectionSummary): string {
  return `${model.connectionId}:${model.catalogEntryId}:${model.catalogRevision}`
}

function currentSelectionMissing(scope: AgentExecutionScope, role: 'primary' | 'fallback'): boolean {
  const value = bindings[scope][role]
  return Boolean(value && !models(scope).some(model => modelKey(model) === value))
}

function fallbackModels(scope: AgentExecutionScope): SelectableAgentModel[] {
  return models(scope).filter(model => modelKey(model) !== bindings[scope].primary)
}

function slotAvailable(slot: string): boolean {
  return Boolean(props.template?.memberConfigurableSlots.includes(slot)
    || props.template?.administratorConfigurableSlots.includes(slot))
}

function memberSlot(slot: string): boolean {
  return Boolean(props.template?.memberConfigurableSlots.includes(slot))
}

function toggleSkill(key: string): void {
  const index = preferences.approvedSkillKeys.indexOf(key)
  if (index >= 0) preferences.approvedSkillKeys.splice(index, 1)
  else preferences.approvedSkillKeys.push(key)
}

function modelSelection(value: string, scope: AgentExecutionScope): AgentModelSelectionInput | null {
  const option = models(scope).find(model => modelKey(model) === value)
  return option ? {
    connectionId: option.connectionId,
    catalogEntryId: option.catalogEntryId,
    catalogRevision: option.catalogRevision,
  } : null
}

function bindingInput(scope: AgentExecutionScope): AgentModelBindingInput | null {
  if (!allowedScopes.value.includes(scope)) return null
  const binding = bindings[scope]
  if (binding.kind === 'INHERIT_TEAM_DEFAULT') {
    return { kind: 'INHERIT_TEAM_DEFAULT', primary: null, fallback: null }
  }
  return {
    kind: 'DIRECT',
    primary: modelSelection(binding.primary, scope),
    fallback: binding.fallback ? modelSelection(binding.fallback, scope) : null,
  }
}

function configurationInput(): AgentConfigurationInput {
  return {
    personalModelBinding: bindingInput('PERSONAL'),
    teamModelBinding: bindingInput('TEAM'),
    supplementalInstructions: memberSlot('SUPPLEMENTAL_INSTRUCTIONS')
      ? preferences.supplementalInstructions.trim() || null
      : current.value?.supplementalInstructions ?? null,
    approvedSkillKeys: slotAvailable('APPROVED_SKILLS')
      ? [...preferences.approvedSkillKeys].sort()
      : [...(current.value?.approvedSkillKeys ?? [])],
    // No public policy catalog exists yet; preserve exact current references rather than accepting arbitrary UUIDs.
    memoryPolicy: current.value?.memoryPolicy ? { ...current.value.memoryPolicy } : null,
    budgetPolicy: current.value?.budgetPolicy ? { ...current.value.budgetPolicy } : null,
    generateOptions: generateOptions(),
  }
}

function generateOptions(): AgentGenerateOptionsInput | null {
  if (!slotAvailable('OUTPUT_PREFERENCE')) {
    const value = current.value?.generateOptions
    return value ? {
      temperature: numberOrNull(value.temperature), topP: numberOrNull(value.topP),
      maximumOutputTokens: value.maximumOutputTokens, reasoningMode: value.reasoningMode,
      cacheEnabled: value.cacheEnabled, parallelToolCalls: value.parallelToolCalls,
      seed: value.seed, maximumAttempts: value.maximumAttempts,
    } : null
  }
  return {
    temperature: numberOrNull(preferences.temperature),
    topP: numberOrNull(preferences.topP),
    maximumOutputTokens: integerOrNull(preferences.maximumOutputTokens),
    reasoningMode: preferences.reasoningMode,
    cacheEnabled: preferences.cacheEnabled,
    parallelToolCalls: preferences.parallelToolCalls,
    seed: integerOrNull(preferences.seed),
    maximumAttempts: Number(preferences.maximumAttempts),
  }
}

async function save(): Promise<void> {
  submitted.value = true
  localNotice.value = null
  if (!props.canConfigure || !viewingCurrent.value || !formValid.value) return
  const input = configurationInput()
  const signature = JSON.stringify(input)
  if (signature !== saveSignature.value) {
    saveSignature.value = signature
    saveKey.value = crypto.randomUUID()
  }
  const etag = currentResource.value?.value?.etag ?? '"0"'
  const success = await store.appendConfiguration(props.agent.id, input, etag, saveKey.value)
  if (!success) return
  await Promise.all([
    store.loadCurrentConfiguration(props.agent.id, true),
    store.loadConfigurationHistory(props.agent.id, false, true),
    ...allowedScopes.value.map(scope => store.loadPreflight(props.agent.id, scope, true)),
  ])
  initializedRevision.value = null
  submitted.value = false
  localNotice.value = `Configuration Revision ${store.state.command.receipt?.committedVersion ?? ''} 已提交并通过服务端 Preflight。`
  dirtyForm.markClean()
  dirtyForm.clearDraft()
  emit('refreshed')
}

async function requestClose(): Promise<void> {
  await dirtyForm.closeWithGuard(() => emit('close'))
}

function restoreDraft(): void {
  const draft = dirtyForm.restoreDraft()
  if (!draft || typeof draft !== 'object') return
  const candidate = draft as { bindings?: Record<string, BindingForm>; preferences?: Partial<typeof preferences> }
  for (const scope of ['PERSONAL', 'TEAM'] as AgentExecutionScope[]) {
    const binding = candidate.bindings?.[scope]
    if (binding?.kind === 'DIRECT' || binding?.kind === 'INHERIT_TEAM_DEFAULT') bindings[scope] = { kind: binding.kind, primary: binding.primary ?? '', fallback: binding.fallback ?? '' }
  }
  if (candidate.preferences) Object.assign(preferences, candidate.preferences, { approvedSkillKeys: [...(candidate.preferences.approvedSkillKeys ?? preferences.approvedSkillKeys)] })
  draftAvailable.value = false
  dirtyForm.markDirty()
}

async function transition(transition: AgentLifecycleTransition): Promise<void> {
  if (lifecycleConfirmation.value !== transition) {
    lifecycleConfirmation.value = transition
    lifecycleKey.value = crypto.randomUUID()
    return
  }
  const success = await store.transitionAgent(props.agent.id, transition, lifecycleKey.value)
  if (!success) return
  lifecycleConfirmation.value = null
  localNotice.value = transition === 'activate' ? 'Agent 已启用。' : transition === 'disable' ? 'Agent 已禁用。' : 'Agent 已归档。'
  emit('refreshed')
}

function agentStatusLabel(value: string): string { return enumLabel(value, agentStatusLabels) }
function ownershipLabel(value: string): string { return enumLabel(value, agentOwnershipTypeLabels) }
function roleLabel(value: string): string { return enumLabel(value, agentRuntimeRoleLabels) }
function scopeLabel(value: string): string { return enumLabel(value, agentExecutionScopeLabels) }
function bindingSourceLabel(value: string | null | undefined): string {
  return enumLabel(value, agentBindingSourceLabels)
}

function preflight(scope: AgentExecutionScope) {
  return store.state.preflights[`${props.agent.id}:${scope}`]?.value ?? null
}

function nullableString(value: number | null | undefined): string {
  return value === null || value === undefined ? '' : String(value)
}

// A number input hands over the number it parsed, not the text that was typed, so both spellings are
// read here. Empty means "unset" for every one of these fields, never zero.
function numberOrNull(value: PreferenceNumber | null | undefined): number | null {
  if (value === null || value === undefined || String(value).trim() === '') return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

function integerOrNull(value: PreferenceNumber): number | null {
  const parsed = numberOrNull(value)
  return parsed === null ? null : Math.trunc(parsed)
}
</script>

<template>
  <section class="agent-configuration panel" aria-labelledby="agent-configuration-title">
    <header class="configuration-header">
      <span class="configuration-icon"><Bot :size="21" aria-hidden="true" /></span>
      <div><p class="eyebrow">Agent 设置 · {{ ownershipLabel(agent.ownershipType) }}{{ roleLabel(agent.runtimeRole) }}</p><h2 id="agent-configuration-title" ref="heading" tabindex="-1">{{ agent.displayName }}</h2><span class="mono">{{ agent.templateKey }}@{{ agent.templateVersion }} · Profile v{{ agent.version }}</span></div>
      <StatusBadge :tone="agent.status === 'ACTIVE' ? 'success' : agent.status === 'DISABLED' ? 'warning' : 'neutral'" dot>{{ agentStatusLabel(agent.status) }}</StatusBadge>
      <button type="button" aria-label="关闭 Agent 设置" @click="requestClose"><X :size="18" /></button>
    </header>

    <div class="configuration-layout">
      <AgentConfigurationHistoryPanel
        :history-resource="historyResource"
        :history="history"
        :selected-revision="selectedRevision"
        :current-revision="agent.currentConfigurationRevision"
        @select-revision="emit('selectRevision', $event)"
        @retry="loadFacts(true)"
        @load-more="store.loadConfigurationHistory(agent.id, true)"
      />

      <div class="configuration-main">
        <StatePanel v-if="historicalRevisionMissing" state="error" compact title="配置版本不存在" description="该 Revision 不在当前可见历史中。" @retry="loadFacts(true)" />
        <AgentConfigurationRevisionView
          v-else-if="!viewingCurrent && selectedHistory"
          :selected-revision="selectedRevision"
          :current-revision="agent.currentConfigurationRevision"
          :selected-history="selectedHistory"
          :history="history"
          :selected-history-configuration="selectedHistoryConfiguration"
          :selected-previous-configuration="selectedPreviousConfiguration"
          :compare-revision="compareRevision"
          :copied-hash="copiedHash"
          @update-compare-revision="compareRevision = $event"
          @copy-hash="(hash, key) => clipboard.copy(hash, key)"
          @return-current="emit('selectRevision', $event)"
        />

        <template v-else>
          <section class="effect-note">
            <ShieldCheck :size="18" aria-hidden="true" /><div><strong>{{ platformManaged ? '平台托管 Team Observer' : '版本生效范围' }}</strong><span>{{ platformManaged ? '平台负责创建唯一 Observer 并固定只读能力边界；管理员在此配置 TEAM 模型并完成 Preflight，Team Observer 运行时会在首次安全调用时完成就绪激活。' : '保存会追加不可变 Configuration Revision。新 Task 与新 Conversation 使用新版本；已有 Conversation 保持 Pin，运行中 Task 和默认 Retry 继续使用固定 PolicySnapshot。' }}</span><small v-if="current?.configurationHash" class="current-hash mono">当前 Hash：{{ current.configurationHash }} <button type="button" class="copy-button" aria-label="复制当前配置 Hash" @click="clipboard.copy(current.configurationHash, 'current-configuration')">{{ copiedHash === 'current-configuration' ? '已复制' : '复制' }}</button></small></div>
          </section>

          <StatePanel v-if="!canConfigure" state="forbidden" compact title="只读 Agent" description="你可以发现这个团队 Agent，但配置和生命周期操作需要 Agent 管理权限。"><template #action><RouterLink :to="{ name: 'access-denied', query: { requiredPermission: permissions.agentManage } }"><BaseButton variant="secondary" size="small">查看权限说明</BaseButton></RouterLink></template></StatePanel>
          <StatePanel v-else-if="!template" state="error" compact title="Template 元数据不可用" description="无法安全判断允许配置的槽位，设置已失败关闭。" @retry="loadFacts(true)" />

          <form v-else class="configuration-form" @submit.prevent="save">
            <section v-if="draftAvailable" class="draft-recovery" role="status"><div><strong>发现未保存的本地草稿</strong><span>上次切换 Team 时已安全保留当前浏览器中的配置修改。</span></div><div><BaseButton type="button" variant="secondary" size="small" @click="restoreDraft">恢复草稿</BaseButton><BaseButton type="button" variant="ghost" size="small" @click="dirtyForm.clearDraft(); draftAvailable = false">丢弃</BaseButton></div></section>
            <AgentConfigurationModelBindingSection
              :allowed-scopes="allowedScopes"
              :bindings="bindings"
              :models="models"
              :model-resource-phase="modelResourcePhase"
              :model-resource-error="modelResourceError"
              :model-key="modelKey"
              :fallback-models="fallbackModels"
              :current-selection-missing="currentSelectionMissing"
              :scope-label="scopeLabel"
              :submitted="submitted"
              :agent-id="agent.id"
              @update-bindings="value => { bindings.PERSONAL = value.PERSONAL; bindings.TEAM = value.TEAM }"
              @retry="store.loadSelectableModels(agent.id, $event, true)"
            />

            <AgentConfigurationPreferencesSection
              v-if="template"
              :template="template"
              :current="current"
              :preferences="preferences"
              :slot-available="slotAvailable"
              :member-slot="memberSlot"
              @update-preferences="value => Object.assign(preferences, value)"
              @toggle-skill="toggleSkill"
            />

            <section v-if="allowedScopes.some(scope => preflight(scope))" class="preflight-results" aria-label="Model Preflight 结果">
              <article v-for="scope in allowedScopes.filter(item => preflight(item))" :key="scope"><CheckCircle2 :size="17" /><div><strong>{{ scopeLabel(scope) }} Preflight 通过</strong><span>{{ preflight(scope)?.primary.modelId }} · {{ bindingSourceLabel(preflight(scope)?.bindingSource) }} · Price Revision {{ preflight(scope)?.primary.priceRevision }}</span></div></article>
            </section>
            <p v-if="localNotice" class="command-notice" role="status">{{ localNotice }}</p>
            <p v-if="commandForAgent?.errorMessage" class="command-error" role="alert">{{ commandForAgent.errorMessage }}</p>
            <div v-if="commandForAgent?.phase === 'conflict'" class="conflict-actions"><span>其他成员已经追加了新 Revision，请刷新后重新应用设置。</span><BaseButton type="button" variant="secondary" size="small" @click="loadFacts(true)">刷新当前事实</BaseButton></div>
            <p v-if="agent.status === 'ARCHIVED'" id="agent-config-archived-reason" class="sr-only">Agent 已归档，需要先恢复为可用状态才能保存配置变更。</p>
            <footer class="save-actions"><span>“保存并预检”在服务端提交事务内先验证候选 Binding；失败不会追加 Revision。</span><BaseButton type="submit" :loading="saving" :disabled="!formValid || agent.status === 'ARCHIVED'" :aria-describedby="agent.status === 'ARCHIVED' ? 'agent-config-archived-reason' : undefined"><Save :size="14" />{{ commandForAgent?.retryable ? '使用原请求重试' : '保存并预检' }}</BaseButton></footer>
          </form>

          <AgentConfigurationLifecycleSection
            :visible="canConfigure"
            :platform-managed="platformManaged"
            :status="agent.status"
            :default-profile="agent.defaultProfile"
            :pending="lifecyclePending"
            :confirmation="lifecycleConfirmation"
            @transition="transition"
          />
        </template>
      </div>
    </div>
  </section>
</template>

<style scoped>
.agent-configuration { overflow: hidden; scroll-margin-top: var(--cs-space-20); }
/* F10 readable configuration baseline: desktop and narrow layouts share the same minimum size. */
.configuration-main input, .configuration-main select, .configuration-main textarea { font-size: var(--cs-text-base); }
.configuration-main label, .configuration-main legend, .configuration-main .binding-heading strong { font-size: var(--cs-text-sm); }
.configuration-main .field-warning, .configuration-main .field-error, .configuration-main .save-actions > span { font-size: var(--cs-text-xs); }
.field-error { margin: 0; color: var(--cs-danger); font-weight: var(--cs-weight-medium); }
.copy-button { min-height: 28px; padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text-brand); cursor: pointer; font-size: var(--cs-text-xs); }
.model-picker__native-fallback { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; clip-path: inset(50%); }
.draft-recovery { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-12); padding: var(--cs-space-12); border: 1px solid var(--cs-warning); border-radius: var(--cs-radius-md); background: var(--cs-warning-soft); }.draft-recovery strong, .draft-recovery span { display: block; }.draft-recovery strong { color: var(--cs-text); font-size: var(--cs-text-sm); }.draft-recovery span { margin-top: var(--cs-space-4); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.draft-recovery > div:last-child { display: flex; gap: var(--cs-space-8); flex: 0 0 auto; }
.current-hash { display: flex; align-items: center; gap: var(--cs-space-8); flex-wrap: wrap; margin-top: var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
@media (max-width: 900px) { .configuration-layout { grid-template-columns: 1fr; } }
@media (max-width: 600px) { .configuration-header { grid-template-columns: 38px minmax(0, 1fr) 30px; padding: var(--cs-space-16); }.configuration-icon { width: 38px; height: 38px; }.configuration-header > .status-badge { grid-column: 2; justify-self: start; }.configuration-header > button { grid-column: 3; grid-row: 1; }.configuration-main { padding: var(--cs-space-12); }.binding-fields, .preference-fields { grid-template-columns: 1fr; }.binding-mode, .preference-fields .wide { grid-column: 1; }.binding-fields select, .preference-fields select, .preference-fields input, .preference-fields textarea { font-size: var(--cs-text-md); }.save-actions { align-items: stretch; flex-direction: column; }.save-actions .base-button { width: 100%; } }
</style>
