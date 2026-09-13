<script setup lang="ts">
withDefaults(defineProps<{
  modelValue?: string | number
  type?: string
  placeholder?: string
  disabled?: boolean
  invalid?: boolean
  ariaDescribedby?: string
}>(), { type: 'text', modelValue: '', placeholder: '', disabled: false, invalid: false, ariaDescribedby: undefined })

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
</script>

<template>
  <input
    class="base-input"
    :class="{ 'base-input--invalid': invalid }"
    :type="type"
    :value="modelValue"
    :placeholder="placeholder"
    :disabled="disabled"
    :aria-invalid="invalid || undefined"
    :aria-describedby="ariaDescribedby"
    @input="emit('update:modelValue', ($event.target as HTMLInputElement).value)"
  >
</template>

<style scoped>
.base-input { width: 100%; min-height: var(--cs-density-control-height); padding: 0 var(--cs-space-3); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); font-size: var(--cs-text-base); transition: border-color var(--cs-motion-fast) var(--cs-ease-out), box-shadow var(--cs-motion-fast) var(--cs-ease-out); }
.base-input::placeholder { color: var(--cs-text-muted); }
.base-input:focus { border-color: var(--cs-focus); box-shadow: var(--cs-focus-ring); outline: none; }
.base-input--invalid { border-color: var(--cs-danger); }
.base-input:disabled { cursor: not-allowed; opacity: .6; }
</style>
