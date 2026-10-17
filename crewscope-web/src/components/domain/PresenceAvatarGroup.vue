<script setup lang="ts">
import { computed } from 'vue'
import BaseAvatar from '../base/BaseAvatar.vue'
import { presenceLabels } from '../../domains/collaboration/labels'
import type { PresenceEntry } from '../../domains/collaboration/store'

/**
 * Who is viewing this scope right now (M11-F01). Overlapped initials with a +N
 * overflow; a connection badge marks principals on multiple tabs. The group is one
 * accessible name (the full roster), so each avatar stays decorative.
 */
const props = withDefaults(defineProps<{
  entries: readonly PresenceEntry[]
  max?: number
  degraded?: boolean
}>(), { max: 5, degraded: false })

const visible = computed(() => props.entries.slice(0, props.max))
const overflow = computed(() => Math.max(0, props.entries.length - props.max))
const summary = computed(() => presenceLabels.summary(props.entries.map(entry => entry.displayName)))
</script>

<template>
  <span v-if="degraded" class="presence-group presence-group--degraded">{{ presenceLabels.unavailable }}</span>
  <span v-else-if="entries.length > 0" class="presence-group" role="group" :aria-label="summary">
    <span
      v-for="entry in visible"
      :key="entry.principalId"
      class="presence-group__avatar"
      :title="entry.connections > 1 ? `${entry.displayName}（${entry.connections} 个标签页）` : entry.displayName"
    >
      <BaseAvatar :display-name="entry.displayName" size="sm" />
      <span v-if="entry.connections > 1" class="presence-group__badge" aria-hidden="true">{{ entry.connections }}</span>
    </span>
    <span v-if="overflow > 0" class="presence-group__more" aria-hidden="true">+{{ overflow }}</span>
  </span>
</template>

<style scoped>
.presence-group { display: inline-flex; align-items: center; }
.presence-group__avatar { position: relative; margin-left: calc(var(--cs-space-8) * -1); }
.presence-group__avatar:first-child { margin-left: 0; }
.presence-group__avatar :deep(.base-avatar) { border: 2px solid var(--cs-canvas); transition: border-color var(--cs-motion-fast) var(--cs-ease-out); }
.presence-group__avatar:hover :deep(.base-avatar) { border-color: var(--cs-border-accent-strong); }
.presence-group__badge { position: absolute; right: -4px; bottom: -4px; min-width: 14px; padding: 0 var(--cs-space-2); border-radius: 7px; background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); line-height: var(--cs-leading-tight); text-align: center; }
.presence-group__more { margin-left: var(--cs-space-4); color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }
.presence-group--degraded { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
</style>
