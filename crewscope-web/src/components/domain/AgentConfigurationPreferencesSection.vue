<script setup lang="ts">
import { reactive, watch } from 'vue'
import { CircleAlert } from '@lucide/vue'
import type { AgentTemplateSummary, CurrentAgentConfiguration } from '../../domains/agent/types'
import type { PreferenceForm, PreferenceNumber } from '../../domains/agent/configurationTypes'
import {
  generateOptionFields,
  limitBound,
  limitErrorMessage,
  limitFloor,
  limitRangeText,
  limitShape,
  seedBound,
  seedErrorMessage,
  withinGenerateOptionLimit,
  withinSeedBound,
  type AgentGenerateOptionField,
} from '../../domains/agent/limits'
import FormField from '../form/FormField.vue'

const props = defineProps<{
  template: AgentTemplateSummary
  current: CurrentAgentConfiguration | null
  preferences: PreferenceForm
  slotAvailable: (slot: string) => boolean
  memberSlot: (slot: string) => boolean
}>()
const emit = defineEmits<{ updatePreferences: [value: PreferenceForm], toggleSkill: [key: string] }>()
const localPreferences = reactive<PreferenceForm>({ ...props.preferences, approvedSkillKeys: [...props.preferences.approvedSkillKeys] })
let syncing = false
watch(() => props.preferences, value => {
  if (JSON.stringify(localPreferences) === JSON.stringify(value)) return
  syncing = true; Object.assign(localPreferences, value, { approvedSkillKeys: [...value.approvedSkillKeys] }); syncing = false
}, { deep: true })
watch(localPreferences, value => {
  if (!syncing && JSON.stringify(value) !== JSON.stringify(props.preferences)) emit('updatePreferences', { ...value, approvedSkillKeys: [...value.approvedSkillKeys] })
}, { deep: true })

/**
 * One label per published field.
 *
 * Typed as a full record on purpose: the field list comes from the domain table, so a bound added
 * there fails the type check here until it also has a name a member can read, instead of rendering an
 * unattributed input.
 */
const fieldLabels: Record<AgentGenerateOptionField, string> = {
  temperature: 'Temperature',
  topP: 'Top P',
  maximumOutputTokens: 'Maximum output tokens',
  maximumAttempts: 'Maximum attempts',
}

/** The field's visible error, or null while the value is in range. */
function preferenceError(field: AgentGenerateOptionField, value: PreferenceNumber): string | null {
  return withinGenerateOptionLimit(field, value) ? null : limitErrorMessage(field)
}

/** A field is a whole-number or a decimal input depending on the shape the server publishes. */
function inputMode(field: AgentGenerateOptionField): 'numeric' | 'decimal' {
  return limitShape(field) === 'INTEGER' ? 'numeric' : 'decimal'
}
</script>

<template>
  <section class="form-section">
    <header><div><p class="eyebrow">Template slots</p><h3>受控配置</h3><span>页面只呈现 Template 声明的可配置槽位，固定 Prompt、Tool 与 Schema 不进入表单。</span></div></header>
    <div class="preference-fields">
      <label v-if="memberSlot('SUPPLEMENTAL_INSTRUCTIONS')" class="wide"><span>补充指令 <small>{{ localPreferences.supplementalInstructions.length }}/16384</small></span><textarea v-model="localPreferences.supplementalInstructions" rows="5" maxlength="16384" placeholder="作为低优先级补充，不会覆盖系统策略或扩展 Tool 权限。" /></label>
      <fieldset v-if="slotAvailable('APPROVED_SKILLS')" class="wide skill-picker"><legend>批准 Skill</legend><label v-for="key in template.approvedSkillKeys" :key="key"><input type="checkbox" :checked="localPreferences.approvedSkillKeys.includes(key)" @change="emit('toggleSkill', key)" /><span class="mono">{{ key }}</span></label><p v-if="template.approvedSkillKeys.length === 0">Template 没有公开可启用的 Skill。</p></fieldset>
      <template v-if="slotAvailable('OUTPUT_PREFERENCE')">
        <label><span>Reasoning</span><select v-model="localPreferences.reasoningMode"><option value="DEFAULT">遵循模型默认</option><option value="ENABLED">启用</option><option value="DISABLED">关闭</option></select></label>
        <FormField
          v-for="field in generateOptionFields()"
          :id="`preference-${field}`"
          :key="field"
          :label="fieldLabels[field]"
          :hint="limitRangeText(field)"
          :error="preferenceError(field, localPreferences[field])"
          :min="limitFloor(field)"
          :max="limitBound(field, 'maximum')"
          :step="limitBound(field, 'step')"
        >
          <template #default="{ ariaDescribedby, ariaInvalid, min, max, step }">
            <input
              :id="`preference-${field}`"
              v-model="localPreferences[field]"
              type="number"
              :min="min"
              :max="max"
              :step="step"
              :inputmode="inputMode(field)"
              placeholder="模型默认"
              :aria-invalid="ariaInvalid"
              :aria-describedby="ariaDescribedby"
            >
          </template>
        </FormField>
        <FormField
          id="preference-seed"
          label="Seed"
          hint="安全整数范围"
          :error="withinSeedBound(localPreferences.seed) ? null : seedErrorMessage"
          :min="seedBound.minimum"
          :max="seedBound.maximum"
          step="1"
        >
          <template #default="{ ariaDescribedby, ariaInvalid, min, max, step }">
            <input id="preference-seed" v-model="localPreferences.seed" type="number" :min="min" :max="max" :step="step" inputmode="numeric" placeholder="不固定" :aria-invalid="ariaInvalid" :aria-describedby="ariaDescribedby">
          </template>
        </FormField>
        <label class="toggle"><input v-model="localPreferences.cacheEnabled" type="checkbox" /><span>允许模型缓存</span></label><label class="toggle"><input v-model="localPreferences.parallelToolCalls" type="checkbox" /><span>允许并行 Tool Call</span></label>
      </template>
      <section v-if="slotAvailable('KNOWLEDGE_SCOPE') || slotAvailable('BUDGET')" class="policy-preservation wide"><CircleAlert :size="17" /><div><strong>知识范围与预算策略</strong><span>当前引用：Memory {{ current?.memoryPolicy ? `${current.memoryPolicy.id} v${current.memoryPolicy.version}` : '未绑定' }}；Budget {{ current?.budgetPolicy ? `${current.budgetPolicy.id} v${current.budgetPolicy.version}` : '未绑定' }}。公开候选目录尚未交付，本页保留精确引用，不接受手填 UUID。</span></div></section>
    </div>
  </section>
</template>

<style scoped>
.form-section { display: grid; gap: var(--cs-space-12); }.form-section > header h3 { margin: 0; }.form-section > header span { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.preference-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--cs-space-12); }.preference-fields label { display: grid; gap: var(--cs-space-4); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); }.preference-fields .wide { grid-column: 1 / -1; }.preference-fields textarea,.preference-fields input,.preference-fields select { min-height: 36px; padding: var(--cs-space-8); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); font-size: var(--cs-text-base); }.preference-fields textarea { resize: vertical; }.skill-picker { display: grid; gap: var(--cs-space-8); margin: 0; padding: var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); }.skill-picker label { display: flex; align-items: center; gap: var(--cs-space-8); }.skill-picker p { margin: 0; color: var(--cs-danger); font-size: var(--cs-text-xs); }.toggle { display: flex !important; align-items: center; }.policy-preservation { display: flex; align-items: flex-start; gap: var(--cs-space-8); padding: var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface-subtle); }.policy-preservation span { display: block; margin-top: var(--cs-space-4); }
</style>
