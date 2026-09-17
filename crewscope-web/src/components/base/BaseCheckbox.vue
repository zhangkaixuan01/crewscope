<script setup lang="ts">
withDefaults(defineProps<{ modelValue?: boolean; disabled?: boolean }>(), { modelValue: false, disabled: false })
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
</script>

<template><label class="base-check"><input type="checkbox" :checked="modelValue" :disabled="disabled" @change="emit('update:modelValue', ($event.target as HTMLInputElement).checked)"><span><slot /></span></label></template>

<style scoped>
.base-check { display: inline-flex; align-items: center; gap: var(--cs-space-8); color: var(--cs-text-secondary); font-size: var(--cs-text-base); cursor: pointer; }
.base-check input { width: 16px; height: 16px; accent-color: var(--cs-action-primary); }
.base-check:has(input:disabled) { cursor: not-allowed; opacity: .6; }
</style>
