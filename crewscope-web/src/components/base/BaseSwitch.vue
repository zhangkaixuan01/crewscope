<script setup lang="ts">
withDefaults(defineProps<{ modelValue?: boolean; disabled?: boolean; label?: string }>(), { modelValue: false, disabled: false, label: '' })
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
</script>

<template><label class="base-switch"><button type="button" role="switch" :aria-checked="modelValue" :disabled="disabled" @click="emit('update:modelValue', !modelValue)"><span aria-hidden="true" /></button><span v-if="label"><slot>{{ label }}</slot></span></label></template>

<style scoped>
.base-switch { display: inline-flex; align-items: center; gap: var(--cs-space-2); color: var(--cs-text-secondary); font-size: var(--cs-text-base); }
.base-switch button { position: relative; width: 36px; height: 20px; padding: var(--cs-space-1); border-radius: var(--cs-radius-pill); background: var(--cs-border-strong); cursor: pointer; transition: background var(--cs-motion-fast) var(--cs-ease-out); }
.base-switch button span { display: block; width: 16px; height: 16px; border-radius: 50%; background: var(--cs-surface); transition: transform var(--cs-motion-fast) var(--cs-ease-out); }
.base-switch button[aria-checked="true"] { background: var(--cs-action-primary); }
.base-switch button[aria-checked="true"] span { transform: translateX(16px); }
.base-switch button:disabled { cursor: not-allowed; opacity: .6; }
</style>
