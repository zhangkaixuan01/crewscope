<script setup lang="ts">
import { ref } from 'vue'
export interface BaseDropdownItem { label: string; value: string; disabled?: boolean }
withDefaults(defineProps<{ items?: readonly BaseDropdownItem[] }>(), { items: () => [] })
const emit = defineEmits<{ select: [value: string] }>()
const open = ref(false)
</script>
<template><span class="base-dropdown"><button type="button" aria-haspopup="menu" :aria-expanded="open" @click="open = !open"><slot name="trigger">更多</slot></button><div v-if="open" class="base-dropdown__menu" role="menu"><button v-for="item in items" :key="item.value" type="button" role="menuitem" :disabled="item.disabled" @click="emit('select', item.value); open = false">{{ item.label }}</button><slot /></div></span></template>
<style scoped>
.base-dropdown { position: relative; display: inline-flex; }.base-dropdown__menu { position: absolute; z-index: var(--cs-z-popover); top: calc(100% + var(--cs-space-2)); right: 0; display: grid; min-width: 180px; padding: var(--cs-space-1); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface-raised); box-shadow: var(--cs-shadow-float); }.base-dropdown__menu button { padding: var(--cs-space-2) var(--cs-space-3); border-radius: var(--cs-radius-sm); background: transparent; color: var(--cs-text); text-align: left; cursor: pointer; }.base-dropdown__menu button:hover:not(:disabled) { background: var(--cs-surface-subtle); }.base-dropdown__menu button:disabled { cursor: not-allowed; opacity: .5; }
</style>
