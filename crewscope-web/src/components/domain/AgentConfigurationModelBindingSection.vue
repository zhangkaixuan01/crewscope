<script setup lang="ts">
import { reactive, watch } from 'vue'
import type { AgentExecutionScope, SelectableAgentModel } from '../../domains/agent/types'
import type { AgentBindingForms } from '../../domains/agent/configurationTypes'
import ModelOptionPicker from '../settings/ModelOptionPicker.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'

const props = defineProps<{
  allowedScopes: AgentExecutionScope[]
  bindings: AgentBindingForms
  models: (scope: AgentExecutionScope) => SelectableAgentModel[]
  modelResourcePhase: (scope: AgentExecutionScope) => string
  modelResourceError: (scope: AgentExecutionScope) => string | null
  modelKey: (model: SelectableAgentModel) => string
  fallbackModels: (scope: AgentExecutionScope) => SelectableAgentModel[]
  currentSelectionMissing: (scope: AgentExecutionScope, role: 'primary' | 'fallback') => boolean
  scopeLabel: (scope: AgentExecutionScope) => string
  submitted: boolean
  agentId: string
}>()

const emit = defineEmits<{
  retry: [scope: AgentExecutionScope]
  updateBindings: [value: AgentBindingForms]
}>()

const localBindings = reactive<AgentBindingForms>({
  PERSONAL: { ...props.bindings.PERSONAL },
  TEAM: { ...props.bindings.TEAM },
})
let syncing = false
watch(() => props.bindings, value => {
  if (JSON.stringify(localBindings) === JSON.stringify(value)) return
  syncing = true
  localBindings.PERSONAL = { ...value.PERSONAL }
  localBindings.TEAM = { ...value.TEAM }
  syncing = false
}, { deep: true })
watch(localBindings, value => {
  if (syncing || JSON.stringify(value) === JSON.stringify(props.bindings)) return
  emit('updateBindings', { PERSONAL: { ...value.PERSONAL }, TEAM: { ...value.TEAM } })
}, { deep: true })
</script>

<template>
  <section class="form-section">
    <header><div><p class="eyebrow">Model binding</p><h3>执行模型</h3><span>候选项是服务端按 Ownership、健康、能力、区域和策略计算的实时交集。</span></div></header>
    <article v-for="scope in allowedScopes" :key="scope" class="binding-editor">
      <div class="binding-heading"><div><strong>{{ scopeLabel(scope) }}</strong><span>{{ scope === 'PERSONAL' ? '由成员自己发起的执行' : '由团队发起的执行' }}</span></div><StatusBadge tone="info">{{ models(scope).length }} 个候选</StatusBadge></div>
      <StatePanel v-if="modelResourcePhase(scope) === 'loading' || modelResourcePhase(scope) === 'idle'" state="loading" compact title="正在计算可选模型" />
      <StatePanel v-else-if="modelResourcePhase(scope) === 'error'" state="error" compact :description="modelResourceError(scope) ?? undefined" @retry="emit('retry', scope)" />
      <div v-else class="binding-fields">
        <label v-if="scope === 'TEAM'" class="binding-mode"><span>绑定方式</span><select v-model="localBindings[scope].kind"><option value="DIRECT">直接选择受管模型</option><option value="INHERIT_TEAM_DEFAULT">继承已发布的 Team/Organization 默认</option></select></label>
        <template v-if="localBindings[scope].kind === 'DIRECT'">
          <StatePanel v-if="models(scope).length === 0" state="empty" compact title="没有符合条件的模型" description="连接健康、Template 能力、区域或团队策略没有形成可选交集。API Key 请在“模型与凭证”页面单向录入，本页不会保存 Key。" />
          <template v-else>
            <label><span>主模型</span><ModelOptionPicker v-model="localBindings[scope].primary" :models="models(scope)" placeholder="搜索并选择主模型" /><select class="model-picker__native-fallback" v-model="localBindings[scope].primary" aria-label="主模型" aria-hidden="true" tabindex="-1"><option value="">请选择主模型</option><option v-for="model in models(scope)" :key="modelKey(model)" :value="modelKey(model)">{{ model.modelDisplayName }}</option></select><p v-if="submitted && !localBindings[scope].primary" class="field-error">请选择主模型。</p></label>
            <p v-if="currentSelectionMissing(scope, 'primary')" class="field-warning" role="status">当前主模型已不在可选交集中，请选择新的健康模型后再保存。</p>
            <label><span>Fallback</span><ModelOptionPicker v-model="localBindings[scope].fallback" :models="fallbackModels(scope)" placeholder="不配置 Fallback（可选）" /><select class="model-picker__native-fallback" v-model="localBindings[scope].fallback" aria-label="Fallback" aria-hidden="true" tabindex="-1"><option value="">不配置 Fallback</option><option v-for="model in fallbackModels(scope)" :key="modelKey(model)" :value="modelKey(model)">{{ model.modelDisplayName }}</option></select></label>
            <p v-if="currentSelectionMissing(scope, 'fallback')" class="field-warning" role="status">当前 Fallback 已不可选；清空或选择新的候选项。</p>
          </template>
        </template>
        <p v-else class="inherit-note">运行时先解析 Team Template 默认，再解析 Organization Template 默认，并把精确结果固定到 PolicySnapshot。</p>
      </div>
    </article>
  </section>
</template>

<style scoped>
.form-section { display: grid; gap: var(--cs-space-12); }.form-section > header h3 { margin: 0; }.form-section > header span { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.binding-editor { display: grid; gap: var(--cs-space-8); padding: var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); }.binding-heading { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-8); }.binding-heading strong,.binding-heading span { display: block; }.binding-heading strong { font-size: var(--cs-text-sm); }.binding-heading span { margin-top: var(--cs-space-2); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.binding-fields { display: grid; gap: var(--cs-space-8); }.binding-fields label { display: grid; gap: var(--cs-space-4); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); }.binding-mode select,.binding-fields > label > select { min-height: 36px; padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); }.model-picker__native-fallback { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; clip-path: inset(50%); }.field-error { margin: 0; color: var(--cs-danger); font-size: var(--cs-text-xs); }.field-warning { margin: 0; color: var(--cs-warning); font-size: var(--cs-text-xs); }.inherit-note { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }
</style>
