<script setup lang="ts">
import { computed, ref } from 'vue'
import type { SelectableAgentModel } from '../../domains/agent/types'
import { enumLabel } from '../../domains/shared/labels'
import { ownerTypeLabels } from '../../domains/settings/labels'

const props = withDefaults(defineProps<{ models: SelectableAgentModel[]; modelValue: string; placeholder?: string; disabled?: boolean }>(), { placeholder: '搜索并选择模型', disabled: false })
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const query = ref('')
const open = ref(false)

function key(model: SelectableAgentModel): string { return `${model.connectionId}:${model.catalogEntryId}:${model.catalogRevision}` }
const selected = computed(() => props.models.find(model => key(model) === props.modelValue) ?? null)
const filtered = computed(() => {
  const needle = query.value.trim().toLocaleLowerCase()
  if (!needle) return props.models
  return props.models.filter(model => [model.providerDisplayName, model.modelDisplayName, model.modelId, model.region, ...model.capabilities].join(' ').toLocaleLowerCase().includes(needle))
})
const groups = computed(() => [...new Set(filtered.value.map(model => model.providerDisplayName))].map(provider => ({ provider, models: filtered.value.filter(model => model.providerDisplayName === provider) })))

function choose(model: SelectableAgentModel): void { emit('update:modelValue', key(model)); query.value = ''; open.value = false }
function clear(): void { emit('update:modelValue', ''); query.value = ''; open.value = false }
</script>

<template>
  <div class="model-picker" @keydown.esc="open = false">
    <button type="button" class="model-picker__trigger" :disabled="disabled" :aria-expanded="open" @click="open = !open">
      <span v-if="selected"><strong>{{ selected.modelDisplayName }}</strong><small>{{ selected.providerDisplayName }} · {{ selected.region }}</small></span>
      <span v-else class="model-picker__placeholder">{{ placeholder }}</span>
      <span aria-hidden="true">⌄</span>
    </button>
    <div v-if="open" class="model-picker__popover">
      <input v-model="query" autofocus type="search" aria-label="搜索模型" placeholder="按 Provider、能力或名称搜索" />
      <button v-if="modelValue" type="button" class="model-picker__clear" @click="clear">清除当前选择</button>
      <section v-for="group in groups" :key="group.provider" class="model-picker__group">
        <h4>{{ group.provider }}</h4>
        <button v-for="model in group.models" :key="key(model)" type="button" :class="{ selected: key(model) === modelValue }" @click="choose(model)">
          <span><strong>{{ model.modelDisplayName }}</strong><small>{{ model.modelId }} · {{ enumLabel(model.connectionOwnerType, ownerTypeLabels) }}连接 · {{ model.region }}</small><small class="capabilities">{{ model.capabilities.join(' · ') || '基础文本能力' }}</small></span>
          <em>{{ model.price.inputPerMillionTokens }}/{{ model.price.outputPerMillionTokens }} {{ model.price.currencyCode }}</em>
        </button>
      </section>
      <p v-if="groups.length === 0" class="model-picker__empty">没有匹配的模型。</p>
    </div>
  </div>
</template>

<style scoped>
.model-picker { position: relative; }
.model-picker__trigger { display: flex; width: 100%; min-height: 40px; align-items: center; justify-content: space-between; gap: var(--cs-space-8); padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); text-align: left; cursor: pointer; font-size: var(--cs-text-base); }
.model-picker__trigger:disabled { cursor: not-allowed; opacity: .58; }.model-picker__trigger strong, .model-picker__trigger small { display: block; }.model-picker__trigger small { margin-top: var(--cs-space-2); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.model-picker__placeholder { color: var(--cs-text-muted); }
.model-picker__popover { position: absolute; z-index: var(--cs-z-popover); top: calc(100% + var(--cs-space-4)); right: 0; left: 0; max-height: 360px; overflow: auto; padding: var(--cs-space-8); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-md); background: var(--cs-surface); box-shadow: var(--cs-shadow-float); }.model-picker__popover > input { width: 100%; min-height: 38px; margin-bottom: var(--cs-space-8); padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); font-size: var(--cs-text-base); }.model-picker__group h4 { margin: var(--cs-space-8) var(--cs-space-8) var(--cs-space-4); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.model-picker__group > button { display: flex; width: 100%; justify-content: space-between; gap: var(--cs-space-8); padding: var(--cs-space-8); border: 0; border-radius: var(--cs-radius-sm); background: transparent; color: var(--cs-text); text-align: left; cursor: pointer; }.model-picker__group > button:hover, .model-picker__group > button.selected { background: var(--cs-surface-accent); }.model-picker__group strong, .model-picker__group small { display: block; }.model-picker__group small { margin-top: var(--cs-space-2); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.model-picker__group .capabilities { color: var(--cs-text-brand); }.model-picker__group em { flex: 0 0 auto; color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-style: normal; }.model-picker__clear { width: 100%; margin-bottom: var(--cs-space-4); padding: var(--cs-space-4); border: 0; background: transparent; color: var(--cs-danger); text-align: left; cursor: pointer; font-size: var(--cs-text-xs); }.model-picker__empty { padding: var(--cs-space-12); color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
</style>
