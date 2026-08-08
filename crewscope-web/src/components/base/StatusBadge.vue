<script setup lang="ts">
import type { SemanticTone } from './types'

withDefaults(defineProps<{
  tone?: SemanticTone
  dot?: boolean
}>(), {
  tone: 'neutral',
  dot: false,
})
</script>

<template>
  <span class="status-badge" :class="`status-badge--${tone}`">
    <span v-if="dot" class="status-badge__dot" aria-hidden="true" />
    <slot />
  </span>
</template>

<style scoped>
.status-badge { display: inline-flex; min-height: 24px; align-items: center; gap: 6px; padding: 2px 8px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-pill); background: var(--cs-surface-subtle); color: var(--cs-text-secondary); font-size: 11px; font-weight: 700; white-space: nowrap; }
.status-badge__dot { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.status-badge--info { border-color: #c9dff3; background: var(--cs-info-soft); color: #326597; }
.status-badge--agent { border-color: #d9cdef; background: var(--cs-agent-soft); color: #6546a3; }
.status-badge--warning { border-color: #f0d5ad; background: var(--cs-warning-soft); color: #955d19; }
.status-badge--danger { border-color: #efc7c7; background: var(--cs-danger-soft); color: #a33f3f; }
.status-badge--success { border-color: #c4e3d0; background: var(--cs-success-soft); color: #347149; }
</style>
