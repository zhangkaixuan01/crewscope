<script setup lang="ts">
import { ref } from 'vue'
const open = ref(false)
const id = `cs-popover-${Math.random().toString(36).slice(2, 9)}`
</script>
<template><span class="base-popover"><button type="button" :aria-expanded="open" :aria-controls="id" @click="open = !open"><slot name="trigger">打开</slot></button><div v-if="open" :id="id" class="base-popover__panel" role="dialog"><slot /></div></span></template>
<style scoped>
.base-popover { position: relative; display: inline-flex; }.base-popover__panel { position: absolute; z-index: var(--cs-z-popover); top: calc(100% + var(--cs-space-2)); right: 0; min-width: 220px; padding: var(--cs-space-3); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface-raised); box-shadow: var(--cs-shadow-float); }
</style>
