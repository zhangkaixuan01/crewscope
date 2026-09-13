<script setup lang="ts">
import type { SemanticTone } from './types'

withDefaults(defineProps<{
  tone?: SemanticTone
  dot?: boolean
  interactive?: boolean
  availableActions?: readonly { id: string; label: string; enabled?: boolean; reason?: string }[]
}>(), {
  tone: 'neutral',
  dot: false,
  interactive: false,
  availableActions: () => [],
})
defineEmits<{ action: [id: string] }>()
</script>

<template>
  <span v-if="!interactive" class="status-badge" :class="`status-badge--${tone}`">
    <span v-if="dot" class="status-badge__dot" aria-hidden="true" />
    <slot />
  </span>
  <span v-else class="status-badge__menu">
    <button type="button" class="status-badge status-badge--interactive" :class="`status-badge--${tone}`" aria-haspopup="menu"><span v-if="dot" class="status-badge__dot" aria-hidden="true" /><slot /></button>
    <span class="status-badge__actions" role="menu"><button v-for="action in availableActions" :key="action.id" type="button" role="menuitem" :disabled="action.enabled === false" :aria-label="action.reason ? `${action.label}：${action.reason}` : undefined" @click="$emit('action', action.id)">{{ action.label }}</button></span>
  </span>
</template>

<style scoped>
.status-badge { display: inline-flex; min-height: calc(var(--cs-text-2xs) + var(--cs-space-4)); align-items: center; gap: var(--cs-space-1); padding: var(--cs-space-1) var(--cs-space-2); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-pill); background: var(--cs-surface-subtle); color: var(--cs-text-secondary); font-size: var(--cs-text-2xs); font-weight: var(--cs-weight-bold); white-space: nowrap; }
.status-badge__dot { width: var(--cs-space-1); height: var(--cs-space-1); border-radius: 50%; background: currentColor; }
.status-badge--info { border-color: #c9dff3; background: var(--cs-info-soft); color: #326597; }
.status-badge--agent { border-color: #d9cdef; background: var(--cs-agent-soft); color: #6546a3; }
.status-badge--warning { border-color: #f0d5ad; background: var(--cs-warning-soft); color: #955d19; }
.status-badge--danger { border-color: #efc7c7; background: var(--cs-danger-soft); color: #a33f3f; }
.status-badge--success { border-color: #c4e3d0; background: var(--cs-success-soft); color: #347149; }
.status-badge--interactive { cursor: pointer; }.status-badge__menu { position: relative; display: inline-flex; }.status-badge__actions { position: absolute; z-index: var(--cs-z-popover); top: calc(100% + var(--cs-space-1)); left: 0; display: none; min-width: 150px; padding: var(--cs-space-1); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface-raised); box-shadow: var(--cs-shadow-float); }.status-badge__menu:focus-within .status-badge__actions, .status-badge__menu:hover .status-badge__actions { display: grid; }.status-badge__actions button { padding: var(--cs-space-2); background: transparent; color: var(--cs-text); text-align: left; cursor: pointer; }.status-badge__actions button:hover:not(:disabled) { background: var(--cs-surface-subtle); }.status-badge__actions button:disabled { cursor: not-allowed; opacity: .5; }
</style>
