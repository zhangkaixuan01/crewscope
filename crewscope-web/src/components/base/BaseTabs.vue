<script setup lang="ts">
export interface BaseTab { label: string; value: string; disabled?: boolean }
const props = withDefaults(defineProps<{ modelValue?: string; tabs?: readonly BaseTab[] }>(), { modelValue: '', tabs: () => [] })
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
</script>
<template><div class="base-tabs" role="tablist"><button v-for="tab in props.tabs" :key="tab.value" type="button" role="tab" :aria-selected="props.modelValue === tab.value" :disabled="tab.disabled" @click="emit('update:modelValue', tab.value)">{{ tab.label }}</button><slot /></div></template>
<style scoped>
.base-tabs { display: flex; gap: var(--cs-space-1); border-bottom: 1px solid var(--cs-border); }.base-tabs button { min-height: var(--cs-density-control-height); padding: 0 var(--cs-space-3); border-bottom: 2px solid transparent; background: transparent; color: var(--cs-text-muted); font-size: var(--cs-text-base); cursor: pointer; }.base-tabs button[aria-selected="true"] { border-color: var(--cs-brand-600); color: var(--cs-text); font-weight: var(--cs-weight-semibold); }.base-tabs button:disabled { cursor: not-allowed; opacity: .5; }
</style>
