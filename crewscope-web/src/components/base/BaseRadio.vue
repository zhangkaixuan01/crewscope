<script setup lang="ts">
withDefaults(defineProps<{ modelValue?: string; value: string; name: string; disabled?: boolean }>(), { modelValue: '', disabled: false })
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
</script>

<template><label class="base-radio"><input type="radio" :name="name" :value="value" :checked="modelValue === value" :disabled="disabled" @change="emit('update:modelValue', value)"><span><slot /></span></label></template>

<style scoped>
.base-radio { display: inline-flex; align-items: center; gap: var(--cs-space-8); color: var(--cs-text-secondary); font-size: var(--cs-text-base); cursor: pointer; }
.base-radio input { width: 16px; height: 16px; accent-color: var(--cs-action-primary); }
.base-radio:has(input:disabled) { cursor: not-allowed; opacity: .6; }
</style>
