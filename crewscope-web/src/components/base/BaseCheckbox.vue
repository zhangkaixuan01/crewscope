<script setup lang="ts">
// M13b-F12（二轮 S10）：复选框同样携带错误契约——aria-invalid 报错态、aria-describedby 指说明文本。
withDefaults(defineProps<{ modelValue?: boolean; disabled?: boolean; invalid?: boolean; ariaDescribedby?: string }>(), { modelValue: false, disabled: false, invalid: false, ariaDescribedby: undefined })
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
</script>

<template><label class="base-check"><input type="checkbox" :checked="modelValue" :disabled="disabled" :aria-invalid="invalid || undefined" :aria-describedby="ariaDescribedby" @change="emit('update:modelValue', ($event.target as HTMLInputElement).checked)"><span><slot /></span></label></template>

<style scoped>
.base-check { display: inline-flex; align-items: center; gap: var(--cs-space-8); color: var(--cs-text-secondary); font-size: var(--cs-text-base); cursor: pointer; }
.base-check input { width: 16px; height: 16px; accent-color: var(--cs-action-primary); }
.base-check:has(input:disabled) { cursor: not-allowed; opacity: .6; }

/* M12b-F01：悬停反馈补齐——此前这些可点元素对指针移动完全沉默。 */
.base-check:hover:not(:disabled) { background: var(--cs-surface-accent); }
.base-check:active:not(:disabled) { transform: scale(.97); }
.base-check { transition: background-color var(--cs-motion-fast) var(--cs-ease-out), border-color var(--cs-motion-fast) var(--cs-ease-out), transform var(--cs-motion-fast) var(--cs-ease-out); }
</style>
