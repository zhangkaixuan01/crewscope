<script setup lang="ts">
import { computed, useId } from 'vue'
export interface BaseTab { label: string; value: string; disabled?: boolean }
const props = withDefaults(defineProps<{ modelValue?: string; tabs?: readonly BaseTab[]; id?: string }>(), { modelValue: '', tabs: () => [], id: undefined })
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const autoId = useId()
const uid = computed(() => props.id ?? autoId)
const enabled = computed(() => props.tabs.filter(tab => !tab.disabled))
// roving tabindex 的落点：选中项优先；modelValue 不在启用集时退回首项，保证 Tab 键永远进得来。
const focusTarget = computed(() => enabled.value.some(tab => tab.value === props.modelValue) ? props.modelValue : enabled.value[0]?.value ?? '')
function select(value: string): void {
  if (enabled.value.some(tab => tab.value === value)) emit('update:modelValue', value)
}
function onKeydown(event: KeyboardEvent): void {
  const list = enabled.value
  if (!list.length) return
  const index = list.findIndex(tab => tab.value === focusTarget.value)
  let next: number
  if (event.key === 'ArrowRight') next = (Math.max(index, 0) + 1) % list.length
  else if (event.key === 'ArrowLeft') next = (index <= 0 ? list.length : index) - 1
  else if (event.key === 'Home') next = 0
  else if (event.key === 'End') next = list.length - 1
  else return
  event.preventDefault()
  const target = list[next]
  document.getElementById(`${uid.value}-tab-${target.value}`)?.focus()
  emit('update:modelValue', target.value)
}
</script>
<template><div class="base-tabs" role="tablist" @keydown="onKeydown"><button v-for="tab in props.tabs" :key="tab.value" :id="`${uid}-tab-${tab.value}`" type="button" role="tab" :aria-selected="props.modelValue === tab.value" :aria-controls="`${uid}-panel-${tab.value}`" :tabindex="focusTarget === tab.value ? 0 : -1" :disabled="tab.disabled" @click="select(tab.value)">{{ tab.label }}</button></div></template>
<style scoped>
.base-tabs { display: flex; gap: var(--cs-space-4); border-bottom: 1px solid var(--cs-border); }.base-tabs button { min-height: var(--cs-density-control-height); padding: 0 var(--cs-space-12); border-bottom: 2px solid transparent; background: transparent; color: var(--cs-text-muted); font-size: var(--cs-text-base); cursor: pointer; }.base-tabs button:hover:not(:disabled):not([aria-selected="true"]) { border-color: var(--cs-border-strong); color: var(--cs-text-secondary); }.base-tabs button[aria-selected="true"] { border-color: var(--cs-border-accent-strong); color: var(--cs-text); font-weight: var(--cs-weight-semibold); }.base-tabs button:focus-visible { outline: var(--cs-focus) solid 1px; outline-offset: -1px; }.base-tabs button:disabled { cursor: not-allowed; opacity: .5; }
</style>
