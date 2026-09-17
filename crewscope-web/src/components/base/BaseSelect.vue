<script setup lang="ts">
export interface BaseSelectOption { label: string; value: string; disabled?: boolean }

withDefaults(defineProps<{
  modelValue?: string
  options?: readonly BaseSelectOption[]
  disabled?: boolean
  invalid?: boolean
}>(), { modelValue: '', options: () => [], disabled: false, invalid: false })

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
</script>

<template>
  <select class="base-select" :class="{ 'base-select--invalid': invalid }" :value="modelValue" :disabled="disabled" :aria-invalid="invalid || undefined" @change="emit('update:modelValue', ($event.target as HTMLSelectElement).value)">
    <option v-for="option in options" :key="option.value" :value="option.value" :disabled="option.disabled">{{ option.label }}</option>
    <slot />
  </select>
</template>

<style scoped>
.base-select { width: 100%; min-height: var(--cs-density-control-height); padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); font-size: var(--cs-text-base); }
.base-select:focus { border-color: var(--cs-focus); box-shadow: var(--cs-focus-ring); outline: none; }
.base-select--invalid { border-color: var(--cs-danger); }
.base-select:disabled { cursor: not-allowed; opacity: .6; }
</style>
