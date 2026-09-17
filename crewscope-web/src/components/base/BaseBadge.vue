<script setup lang="ts">
import type { SemanticTone } from './types'
withDefaults(defineProps<{ tone?: SemanticTone }>(), { tone: 'neutral' })
</script>
<template><span class="base-badge" :class="`base-badge--${tone}`"><slot /></span></template>
<style scoped>
.base-badge { display: inline-flex; align-items: center; min-height: var(--cs-space-24); padding: 0 var(--cs-space-8); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }
.base-badge--info { background: var(--cs-info-soft); color: var(--cs-info); }.base-badge--agent { background: var(--cs-agent-soft); color: var(--cs-agent); }.base-badge--warning { background: var(--cs-warning-soft); color: var(--cs-warning); }.base-badge--danger { background: var(--cs-danger-soft); color: var(--cs-danger); }.base-badge--success { background: var(--cs-success-soft); color: var(--cs-success); }
</style>
