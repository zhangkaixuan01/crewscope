<script setup lang="ts">
import { Bot, CheckCircle2, ChevronRight, CircleAlert, Clock3, Power, Save, ShieldCheck, X } from '@lucide/vue'
import { computed, nextTick, onMounted, reactive, ref, useTemplateRef, watch } from 'vue'
import { useAgentStore } from '../../domains/agent/store'
import type {
  AgentConfigurationInput,
  AgentConfigurationHistoryItem,
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

interface BindingForm {
  kind: 'DIRECT' | 'INHERIT_TEAM_DEFAULT'
  primary: string
  fallback: string
}

const store = useAgentStore()
const heading = useTemplateRef<HTMLElement>('heading')
const initializedRevision = ref<number | null | 'none'>(null)
const submitted = ref(false)
const saveKey = ref('')
const saveSignature = ref('')
const lifecycleConfirmation = ref<AgentLifecycleTransition | null>(null)
const lifecycleKey = ref('')
const localNotice = ref<string | null>(null)
const bindings = reactive<Record<AgentExecutionScope, BindingForm>>({
  PERSONAL: { kind: 'DIRECT', primary: '', fallback: '' },
  TEAM: { kind: 'DIRECT', primary: '', fallback: '' },
})
const preferences = reactive({
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

const currentResource = computed(() => store.state.currentConfigurations[props.agent.id])
const platformManaged = computed(() => props.template?.platformManaged
  ?? props.agent.templateKey === 'team-observer')
const current = computed(() => currentResource.value?.value?.value ?? null)
const historyResource = computed(() => store.state.configurationHistory[props.agent.id])
const history = computed(() => historyResource.value?.value ?? [])
const selectedHistory = computed(() => {
  const revision = props.selectedRevision ?? props.agent.currentConfigurationRevision
  return revision ? history.value.find(item => item.revision === revision) ?? null : null
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
  return optionalDecimal(preferences.temperature, 0, 2, true)
    && optionalDecimal(preferences.topP, 0, 1, false)
    && optionalInteger(preferences.maximumOutputTokens, 1, 10_000_000)
    && optionalInteger(preferences.seed, Number.MIN_SAFE_INTEGER, Number.MAX_SAFE_INTEGER)
    && optionalInteger(preferences.maximumAttempts, 1, 10)
})

watch(() => props.agent.id, () => {
  initializedRevision.value = null
  submitted.value = false
  localNotice.value = null
  lifecycleConfirmation.value = null
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
  initializedRevision.value = revision
}, { immediate: true })

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

function optionLabel(model: SelectableAgentModel): string {
  return `${model.providerDisplayName} · ${model.modelDisplayName} · ${model.connectionOwnerType} · ${model.region}`
}

function optionPrice(model: SelectableAgentModel): string {
  return `${model.price.inputPerMillionTokens}/${model.price.outputPerMillionTokens} ${model.price.currencyCode} / 1M tokens`
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
  emit('refreshed')
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

function transitionLabel(transition: AgentLifecycleTransition): string {
  return transition === 'activate' ? '启用' : transition === 'disable' ? '禁用' : '归档'
}

function historyBinding(item: AgentConfigurationHistoryItem, scope: AgentExecutionScope): string {
  const binding = scope === 'PERSONAL' ? item.personalBinding : item.teamBinding
  if (!binding) return '不适用'
  if (binding.kind === 'INHERIT_TEAM_DEFAULT') return '继承 Team 默认'
  if (binding.kind === 'ORCHESTRATION_ONLY') return '仅编排'
  return binding.primary?.modelId ?? '未解析'
}

function preflight(scope: AgentExecutionScope) {
  return store.state.preflights[`${props.agent.id}:${scope}`]?.value ?? null
}

function nullableString(value: number | null | undefined): string {
  return value === null || value === undefined ? '' : String(value)
}

function numberOrNull(value: string | null): number | null {
  return value === null || value.trim() === '' ? null : Number(value)
}

function integerOrNull(value: string): number | null {
  return value.trim() === '' ? null : Number(value)
}

function optionalDecimal(value: string, minimum: number, maximum: number, includeMinimum: boolean): boolean {
  if (!value.trim()) return true
  const parsed = Number(value)
  return Number.isFinite(parsed) && (includeMinimum ? parsed >= minimum : parsed > minimum) && parsed <= maximum
}

function optionalInteger(value: string, minimum: number, maximum: number): boolean {
  if (!value.trim()) return true
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed >= minimum && parsed <= maximum
}
</script>

<template>
  <section class="agent-configuration panel" aria-labelledby="agent-configuration-title">
    <header class="configuration-header">
      <span class="configuration-icon"><Bot :size="21" aria-hidden="true" /></span>
      <div><p class="eyebrow">Agent profile · {{ agent.ownershipType }}</p><h2 id="agent-configuration-title" ref="heading" tabindex="-1">{{ agent.displayName }}</h2><span class="mono">{{ agent.templateKey }}@{{ agent.templateVersion }} · Profile v{{ agent.version }}</span></div>
      <StatusBadge :tone="agent.status === 'ACTIVE' ? 'success' : agent.status === 'DISABLED' ? 'warning' : 'neutral'" dot>{{ agent.status }}</StatusBadge>
      <button type="button" aria-label="关闭 Agent 设置" @click="emit('close')"><X :size="18" /></button>
    </header>

    <div class="configuration-layout">
      <aside class="revision-rail" aria-label="Configuration 历史">
        <h3>配置版本</h3>
        <StatePanel v-if="historyResource?.phase === 'loading' || historyResource?.phase === 'idle'" state="loading" compact />
        <StatePanel v-else-if="historyResource?.phase === 'error'" state="error" compact :description="historyResource.errorMessage ?? undefined" @retry="loadFacts(true)" />
        <p v-else-if="history.length === 0" class="revision-empty">尚未创建 Configuration。首次保存将生成 Revision 1。</p>
        <button
          v-for="item in history"
          :key="item.revision"
          type="button"
          :class="{ active: (selectedRevision ?? agent.currentConfigurationRevision) === item.revision }"
          @click="emit('selectRevision', item.revision)"
        >
          <Clock3 :size="14" /><span><strong>Revision {{ item.revision }}</strong><small>{{ item.createdAt.slice(0, 10) }} · {{ item.templateKey }}@{{ item.templateVersion }}</small></span><ChevronRight :size="14" />
        </button>
        <button
          v-if="historyResource?.nextOffset !== null && historyResource?.phase === 'ready'"
          type="button"
          :disabled="historyResource.loadingMore"
          @click="store.loadConfigurationHistory(agent.id, true)"
        >
          <Clock3 :size="14" /><span><strong>{{ historyResource.loadingMore ? '正在加载…' : '加载更早版本' }}</strong><small>继续读取不可变历史</small></span><ChevronRight :size="14" />
        </button>
      </aside>

      <div class="configuration-main">
        <StatePanel v-if="historicalRevisionMissing" state="error" compact title="配置版本不存在" description="该 Revision 不在当前可见历史中。" @retry="loadFacts(true)" />
        <section v-else-if="!viewingCurrent && selectedHistory" class="historical-view" aria-label="历史 Configuration">
          <div><p class="eyebrow">Immutable history</p><h3>Revision {{ selectedHistory.revision }}</h3><p>历史版本不可编辑。它继续服务于已经固定该 Revision 的 Conversation、Task 和 Retry。</p></div>
          <dl><div><dt>PERSONAL</dt><dd>{{ historyBinding(selectedHistory, 'PERSONAL') }}</dd></div><div><dt>TEAM</dt><dd>{{ historyBinding(selectedHistory, 'TEAM') }}</dd></div><div><dt>Configuration Hash</dt><dd class="mono">{{ selectedHistory.configurationHash.slice(0, 16) }}…</dd></div></dl>
          <BaseButton variant="secondary" size="small" @click="emit('selectRevision', agent.currentConfigurationRevision ?? selectedHistory.revision)">返回当前版本</BaseButton>
        </section>

        <template v-else>
          <section class="effect-note">
            <ShieldCheck :size="18" aria-hidden="true" /><div><strong>{{ platformManaged ? '平台托管 Team Observer' : '版本生效范围' }}</strong><span>{{ platformManaged ? '平台负责创建唯一 Observer 并固定只读能力边界；管理员在此配置 TEAM 模型并完成 Preflight，Team Observer 运行时会在首次安全调用时完成就绪激活。' : '保存会追加不可变 Configuration Revision。新 Task 与新 Conversation 使用新版本；已有 Conversation 保持 Pin，运行中 Task 和默认 Retry 继续使用固定 PolicySnapshot。' }}</span></div>
          </section>

          <StatePanel v-if="!canConfigure" state="forbidden" compact title="只读 Agent" description="你可以发现这个团队 Agent，但配置和生命周期操作需要 Agent 管理权限。" />
          <StatePanel v-else-if="!template" state="error" compact title="Template 元数据不可用" description="无法安全判断允许配置的槽位，设置已失败关闭。" @retry="loadFacts(true)" />

          <form v-else class="configuration-form" @submit.prevent="save">
            <section class="form-section">
              <header><div><p class="eyebrow">Model binding</p><h3>执行模型</h3><span>候选项是服务端按 Ownership、健康、能力、区域和策略计算的实时交集。</span></div></header>
              <article v-for="scope in allowedScopes" :key="scope" class="binding-editor">
                <div class="binding-heading"><div><strong>{{ scope }}</strong><span>{{ scope === 'PERSONAL' ? '个人任务范围' : '团队任务范围' }}</span></div><StatusBadge tone="info">{{ models(scope).length }} 个候选</StatusBadge></div>
                <StatePanel v-if="modelResourcePhase(scope) === 'loading' || modelResourcePhase(scope) === 'idle'" state="loading" compact title="正在计算可选模型" />
                <StatePanel v-else-if="modelResourcePhase(scope) === 'error'" state="error" compact :description="modelResourceError(scope) ?? undefined" @retry="store.loadSelectableModels(agent.id, scope, true)" />
                <div v-else class="binding-fields">
                  <label v-if="scope === 'TEAM'" class="binding-mode"><span>绑定方式</span><select v-model="bindings[scope].kind"><option value="DIRECT">直接选择受管模型</option><option value="INHERIT_TEAM_DEFAULT">继承已发布的 Team/Organization 默认</option></select></label>
                  <template v-if="bindings[scope].kind === 'DIRECT'">
                    <StatePanel v-if="models(scope).length === 0" state="empty" compact title="没有符合条件的模型" description="连接健康、Template 能力、区域或团队策略没有形成可选交集。API Key 请在“模型与凭证”页面单向录入，本页不会保存 Key。" />
                    <template v-else>
                      <label><span>主模型</span><select v-model="bindings[scope].primary" :aria-invalid="submitted && !bindings[scope].primary"><option value="">请选择主模型</option><option v-for="model in models(scope)" :key="modelKey(model)" :value="modelKey(model)">{{ optionLabel(model) }} · {{ optionPrice(model) }}</option></select></label>
                      <p v-if="currentSelectionMissing(scope, 'primary')" class="field-warning" role="status">当前主模型已不在可选交集中，请选择新的健康模型后再保存。</p>
                      <label><span>Fallback</span><select v-model="bindings[scope].fallback"><option value="">不配置 Fallback</option><option v-for="model in fallbackModels(scope)" :key="modelKey(model)" :value="modelKey(model)">{{ optionLabel(model) }} · {{ optionPrice(model) }}</option></select></label>
                      <p v-if="currentSelectionMissing(scope, 'fallback')" class="field-warning" role="status">当前 Fallback 已不可选；清空或选择新的候选项。</p>
                    </template>
                  </template>
                  <p v-else class="inherit-note">运行时先解析 Team Template 默认，再解析 Organization Template 默认，并把精确结果固定到 PolicySnapshot。</p>
                </div>
              </article>
            </section>

            <section class="form-section">
              <header><div><p class="eyebrow">Template slots</p><h3>受控配置</h3><span>页面只呈现 Template 声明的可配置槽位，固定 Prompt、Tool 与 Schema 不进入表单。</span></div></header>
              <div class="preference-fields">
                <label v-if="memberSlot('SUPPLEMENTAL_INSTRUCTIONS')" class="wide"><span>补充指令 <small>{{ preferences.supplementalInstructions.length }}/16384</small></span><textarea v-model="preferences.supplementalInstructions" rows="5" maxlength="16384" placeholder="作为低优先级补充，不会覆盖系统策略或扩展 Tool 权限。" /></label>
                <fieldset v-if="slotAvailable('APPROVED_SKILLS')" class="wide skill-picker"><legend>批准 Skill</legend><label v-for="key in template?.approvedSkillKeys ?? []" :key="key"><input type="checkbox" :checked="preferences.approvedSkillKeys.includes(key)" @change="toggleSkill(key)" /><span class="mono">{{ key }}</span></label><p v-if="template?.approvedSkillKeys.length === 0">Template 没有公开可启用的 Skill。</p></fieldset>
                <template v-if="slotAvailable('OUTPUT_PREFERENCE')">
                  <label><span>Reasoning</span><select v-model="preferences.reasoningMode"><option value="DEFAULT">遵循模型默认</option><option value="ENABLED">启用</option><option value="DISABLED">关闭</option></select></label>
                  <label><span>Maximum output tokens</span><input v-model="preferences.maximumOutputTokens" inputmode="numeric" placeholder="模型默认" :aria-invalid="submitted && !optionalInteger(preferences.maximumOutputTokens, 1, 10000000)" /></label>
                  <label><span>Temperature</span><input v-model="preferences.temperature" inputmode="decimal" placeholder="模型默认" :aria-invalid="submitted && !optionalDecimal(preferences.temperature, 0, 2, true)" /></label>
                  <label><span>Top P</span><input v-model="preferences.topP" inputmode="decimal" placeholder="模型默认" :aria-invalid="submitted && !optionalDecimal(preferences.topP, 0, 1, false)" /></label>
                  <label><span>Maximum attempts</span><input v-model="preferences.maximumAttempts" inputmode="numeric" :aria-invalid="submitted && !optionalInteger(preferences.maximumAttempts, 1, 10)" /></label>
                  <label><span>Seed</span><input v-model="preferences.seed" inputmode="numeric" placeholder="不固定" :aria-invalid="submitted && !optionalInteger(preferences.seed, Number.MIN_SAFE_INTEGER, Number.MAX_SAFE_INTEGER)" /></label>
                  <label class="toggle"><input v-model="preferences.cacheEnabled" type="checkbox" /><span>允许模型缓存</span></label>
                  <label class="toggle"><input v-model="preferences.parallelToolCalls" type="checkbox" /><span>允许并行 Tool Call</span></label>
                </template>
                <section v-if="slotAvailable('KNOWLEDGE_SCOPE') || slotAvailable('BUDGET')" class="policy-preservation wide">
                  <CircleAlert :size="17" /><div><strong>知识范围与预算策略</strong><span>当前引用：Memory {{ current?.memoryPolicy ? `${current.memoryPolicy.id} v${current.memoryPolicy.version}` : '未绑定' }}；Budget {{ current?.budgetPolicy ? `${current.budgetPolicy.id} v${current.budgetPolicy.version}` : '未绑定' }}。公开候选目录尚未交付，本页保留精确引用，不接受手填 UUID。</span></div>
                </section>
              </div>
            </section>

            <section v-if="allowedScopes.some(scope => preflight(scope))" class="preflight-results" aria-label="Model Preflight 结果">
              <article v-for="scope in allowedScopes.filter(item => preflight(item))" :key="scope"><CheckCircle2 :size="17" /><div><strong>{{ scope }} Preflight 通过</strong><span>{{ preflight(scope)?.primary.modelId }} · {{ preflight(scope)?.bindingSource }} · Price Revision {{ preflight(scope)?.primary.priceRevision }}</span></div></article>
            </section>
            <p v-if="localNotice" class="command-notice" role="status">{{ localNotice }}</p>
            <p v-if="commandForAgent?.errorMessage" class="command-error" role="alert">{{ commandForAgent.errorMessage }}</p>
            <div v-if="commandForAgent?.phase === 'conflict'" class="conflict-actions"><span>其他成员已经追加了新 Revision，请刷新后重新应用设置。</span><BaseButton type="button" variant="secondary" size="small" @click="loadFacts(true)">刷新当前事实</BaseButton></div>
            <footer class="save-actions"><span>“保存并预检”在服务端提交事务内先验证候选 Binding；失败不会追加 Revision。</span><BaseButton type="submit" :loading="saving" :disabled="!formValid || agent.status === 'ARCHIVED'"><Save :size="14" />{{ commandForAgent?.retryable ? '使用原请求重试' : '保存并预检' }}</BaseButton></footer>
          </form>

          <section v-if="canConfigure && !agent.defaultProfile && !platformManaged" class="lifecycle-section">
            <div><p class="eyebrow">Lifecycle</p><h3>Agent 生命周期</h3><span>禁用可恢复；归档是不可逆终态。服务端同步 Principal 与 Profile 并使用强版本校验。</span></div>
            <div class="lifecycle-actions">
              <BaseButton v-if="agent.status === 'DISABLED'" variant="secondary" size="small" :loading="lifecyclePending" @click="transition('activate')"><Power :size="14" />{{ lifecycleConfirmation === 'activate' ? '确认启用' : '启用' }}</BaseButton>
              <BaseButton v-if="agent.status === 'ACTIVE'" variant="secondary" size="small" :loading="lifecyclePending" @click="transition('disable')">{{ lifecycleConfirmation === 'disable' ? '确认禁用' : '禁用' }}</BaseButton>
              <BaseButton v-if="agent.status !== 'ARCHIVED'" variant="danger" size="small" :loading="lifecyclePending" @click="transition('archive')">{{ lifecycleConfirmation === 'archive' ? '确认永久归档' : '归档' }}</BaseButton>
            </div>
          </section>
          <section v-else-if="canConfigure && platformManaged" class="lifecycle-section">
            <div><p class="eyebrow">Managed lifecycle</p><h3>平台托管生命周期</h3><span>Team Observer 不支持重复创建或通用归档。有效 TEAM Configuration 通过 Preflight 后，专用运行时在首次调用时完成就绪激活。</span></div>
          </section>
        </template>
      </div>
    </div>
  </section>
</template>

<style scoped>
.agent-configuration { overflow: hidden; scroll-margin-top: 18px; }.configuration-header { display: grid; grid-template-columns: 42px minmax(0, 1fr) auto 32px; align-items: center; gap: 11px; padding: 18px 20px; border-bottom: 1px solid var(--cs-border); background: linear-gradient(135deg, var(--cs-surface), var(--cs-brand-50)); }.configuration-icon { display: grid; width: 42px; height: 42px; place-items: center; border-radius: 12px; background: var(--cs-brand-100); color: var(--cs-brand-700); }.configuration-header h2 { margin: 0 0 2px; font-size: 17px; }.configuration-header h2:focus-visible { outline: 3px solid var(--cs-brand-200); outline-offset: 3px; }.configuration-header div > span { color: var(--cs-text-muted); font-size: 9px; }.configuration-header > button { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 8px; background: rgb(255 255 255 / 75%); cursor: pointer; }.configuration-layout { display: grid; grid-template-columns: 210px minmax(0, 1fr); min-height: 400px; }.revision-rail { padding: 15px 10px; border-right: 1px solid var(--cs-border); background: var(--cs-surface-subtle); }.revision-rail h3 { padding: 0 8px 8px; font-size: 11px; }.revision-rail > button { display: grid; width: 100%; grid-template-columns: 16px 1fr 14px; align-items: center; gap: 6px; padding: 9px 7px; border-radius: 8px; background: transparent; color: var(--cs-text-secondary); text-align: left; cursor: pointer; }.revision-rail > button.active { background: var(--cs-brand-100); color: var(--cs-brand-800); }.revision-rail strong, .revision-rail small { display: block; }.revision-rail strong { font-size: 9px; }.revision-rail small { margin-top: 2px; color: var(--cs-text-muted); font-size: 8px; }.revision-empty { padding: 8px; color: var(--cs-text-muted); font-size: 9px; line-height: 1.5; }.configuration-main { display: grid; align-content: start; gap: 14px; min-width: 0; padding: 18px; }.effect-note { display: flex; align-items: flex-start; gap: 9px; padding: 11px 12px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-brand-50); color: var(--cs-brand-700); }.effect-note svg { flex: 0 0 auto; }.effect-note strong, .effect-note span { display: block; }.effect-note strong { font-size: 10px; }.effect-note span { margin-top: 2px; color: var(--cs-text-muted); font-size: 9px; line-height: 1.5; }.historical-view { display: grid; gap: 14px; }.historical-view h3 { margin-bottom: 4px; }.historical-view p { margin: 0; color: var(--cs-text-muted); font-size: 10px; }.historical-view dl { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin: 0; }.historical-view dl div { padding: 11px; border-radius: 9px; background: var(--cs-surface-subtle); }.historical-view dt { color: var(--cs-text-muted); font-size: 8px; }.historical-view dd { margin-top: 4px; font-size: 10px; font-weight: 700; }.historical-view .base-button { justify-self: start; }.configuration-form { display: grid; gap: 14px; }.form-section { overflow: hidden; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); }.form-section > header { padding: 14px 16px; border-bottom: 1px solid var(--cs-border); background: var(--cs-surface-subtle); }.form-section h3 { margin-bottom: 3px; font-size: 13px; }.form-section header span { color: var(--cs-text-muted); font-size: 9px; }.binding-editor { padding: 14px 16px; border-bottom: 1px solid var(--cs-border); }.binding-editor:last-child { border-bottom: 0; }.binding-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 10px; }.binding-heading strong, .binding-heading span { display: block; }.binding-heading strong { font-size: 11px; }.binding-heading div span { margin-top: 2px; color: var(--cs-text-muted); font-size: 8px; }.binding-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }.binding-fields label, .preference-fields > label { display: grid; gap: 5px; color: var(--cs-text-secondary); font-size: 9px; font-weight: 750; }.binding-fields select, .preference-fields select, .preference-fields input, .preference-fields textarea { width: 100%; min-height: 36px; padding: 7px 9px; border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); font: 10px var(--cs-font-sans); }.binding-mode { grid-column: 1 / -1; }.binding-fields [aria-invalid='true'], .preference-fields [aria-invalid='true'] { border-color: var(--cs-danger); }.field-warning { grid-column: 1 / -1; margin: -4px 0 0; color: #8c5a1d; font-size: 8px; }.inherit-note { grid-column: 1 / -1; margin: 0; padding: 10px; border-radius: 8px; background: var(--cs-brand-50); color: var(--cs-text-muted); font-size: 9px; }.preference-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 11px; padding: 14px 16px; }.preference-fields .wide { grid-column: 1 / -1; }.preference-fields textarea { resize: vertical; }.preference-fields label > span { display: flex; justify-content: space-between; }.preference-fields label small { color: var(--cs-text-muted); font-weight: 500; }.preference-fields .toggle { display: flex; min-height: 36px; grid-template-columns: auto 1fr; flex-direction: row; align-items: center; gap: 7px; padding: 8px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); }.preference-fields .toggle input { width: auto; min-height: 0; }.skill-picker { display: flex; flex-wrap: wrap; gap: 7px; padding: 0; border: 0; }.skill-picker legend { width: 100%; margin-bottom: 3px; color: var(--cs-text-secondary); font-size: 9px; font-weight: 750; }.skill-picker label { display: inline-flex; align-items: center; gap: 5px; padding: 6px 8px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-pill); font-size: 9px; }.skill-picker p { margin: 0; color: var(--cs-text-muted); font-size: 9px; }.policy-preservation { display: flex; align-items: flex-start; gap: 9px; padding: 11px; border-radius: 8px; background: var(--cs-warning-soft); color: #7b531f; }.policy-preservation svg { flex: 0 0 auto; }.policy-preservation strong, .policy-preservation span { display: block; }.policy-preservation strong { font-size: 9px; }.policy-preservation span { margin-top: 2px; font-size: 8px; line-height: 1.5; }.preflight-results { display: grid; gap: 7px; }.preflight-results article { display: flex; align-items: flex-start; gap: 8px; padding: 10px; border: 1px solid #c4e3d0; border-radius: 8px; background: var(--cs-success-soft); color: var(--cs-success); }.preflight-results strong, .preflight-results span { display: block; }.preflight-results strong { font-size: 9px; }.preflight-results span { margin-top: 2px; color: var(--cs-text-muted); font-size: 8px; }.command-notice { margin: 0; color: var(--cs-success); font-size: 10px; }.command-error { margin: 0; color: var(--cs-danger); font-size: 10px; }.conflict-actions { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 10px; border-radius: 8px; background: var(--cs-warning-soft); color: #7b531f; font-size: 9px; }.save-actions { display: flex; align-items: center; justify-content: space-between; gap: 14px; }.save-actions > span { max-width: 560px; color: var(--cs-text-muted); font-size: 8px; }.lifecycle-section { display: flex; align-items: center; justify-content: space-between; gap: 15px; padding: 14px 16px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); }.lifecycle-section h3 { margin-bottom: 3px; font-size: 12px; }.lifecycle-section div > span { color: var(--cs-text-muted); font-size: 8px; }.lifecycle-actions { display: flex; gap: 7px; flex: 0 0 auto; }
@media (max-width: 900px) { .configuration-layout { grid-template-columns: 1fr; }.revision-rail { display: flex; overflow-x: auto; align-items: center; gap: 5px; border-right: 0; border-bottom: 1px solid var(--cs-border); }.revision-rail h3 { flex: 0 0 auto; padding: 0 6px; }.revision-rail > button { min-width: 150px; }.revision-empty { margin: 0; }.historical-view dl { grid-template-columns: 1fr; } }
@media (max-width: 600px) { .configuration-header { grid-template-columns: 38px minmax(0, 1fr) 30px; padding: 14px; }.configuration-icon { width: 38px; height: 38px; }.configuration-header > .status-badge { grid-column: 2; justify-self: start; }.configuration-header > button { grid-column: 3; grid-row: 1; }.configuration-main { padding: 12px; }.binding-fields, .preference-fields { grid-template-columns: 1fr; }.binding-mode, .preference-fields .wide { grid-column: 1; }.binding-fields select, .preference-fields select, .preference-fields input, .preference-fields textarea { font-size: 16px; }.save-actions, .lifecycle-section { align-items: stretch; flex-direction: column; }.save-actions .base-button { width: 100%; }.lifecycle-actions { display: grid; }.historical-view dl { grid-template-columns: 1fr; } }
</style>
