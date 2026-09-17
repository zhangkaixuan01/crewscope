<script setup lang="ts">
withDefaults(defineProps<{
  modelValue?: string
  rows?: number
  placeholder?: string
  disabled?: boolean
  invalid?: boolean
}>(), { modelValue: '', rows: 4, placeholder: '', disabled: false, invalid: false })

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
</script>

<template>
  <textarea class="base-textarea" :class="{ 'base-textarea--invalid': invalid }" :rows="rows" :value="modelValue" :placeholder="placeholder" :disabled="disabled" :aria-invalid="invalid || undefined" @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)" />
</template>

<style scoped>
.base-textarea { width: 100%; min-height: calc(var(--cs-density-control-height) * 2); resize: vertical; padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); font-size: var(--cs-text-base); line-height: var(--cs-leading-normal); }
.base-textarea:focus { border-color: var(--cs-focus); box-shadow: var(--cs-focus-ring); outline: none; }
.base-textarea--invalid { border-color: var(--cs-danger); }
.base-textarea:disabled { cursor: not-allowed; opacity: .6; }
</style>
