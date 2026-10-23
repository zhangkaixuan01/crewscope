<script setup lang="ts">
import { Users } from '@lucide/vue'
import { computed, ref } from 'vue'
import BaseAvatar from '../base/BaseAvatar.vue'
import { presenceLabels } from '../../domains/collaboration/labels'
import type { PresenceEntry } from '../../domains/collaboration/store'

/**
 * Who is viewing this scope right now (M11-F01). Overlapped initials with a +N
 * overflow; a connection badge marks principals on multiple tabs. The group is one
 * accessible name (the full roster), so each avatar stays decorative.
 *
 * M13b-F05（R36）: hover titles are the desktop convenience only — touch and keyboard
 * need a real control, so the roster toggle opens the full names as an inline panel.
 */
const props = withDefaults(defineProps<{
  entries: readonly PresenceEntry[]
  max?: number
  degraded?: boolean
}>(), { max: 5, degraded: false })

const visible = computed(() => props.entries.slice(0, props.max))
const overflow = computed(() => Math.max(0, props.entries.length - props.max))
const summary = computed(() => presenceLabels.summary(props.entries.map(entry => entry.displayName)))
const rosterOpen = ref(false)
</script>

<template>
  <span v-if="degraded" class="presence-group presence-group--degraded">{{ presenceLabels.unavailable }}</span>
  <span v-else-if="entries.length > 0" class="presence-group" role="group" :aria-label="summary">
    <span class="presence-group__row">
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
    <button
      type="button"
      class="presence-group__toggle"
      :aria-expanded="rosterOpen"
      aria-haspopup="true"
      :aria-label="`查看在场名单（共 ${entries.length} 人）`"
      @click="rosterOpen = !rosterOpen"
      @keydown.esc.stop="rosterOpen = false"
    >
      <Users :size="12" aria-hidden="true" />
    </button>
    <!-- The toggle's label already carries the count; the panel itself is the list of names. -->
    <ul v-if="rosterOpen" class="presence-group__roster" aria-label="在场名单">
      <li v-for="entry in entries" :key="entry.principalId" class="presence-group__roster-row">
        <BaseAvatar :display-name="entry.displayName" size="sm" />
        <span class="presence-group__roster-name">{{ entry.displayName }}</span>
        <span v-if="entry.connections > 1" class="presence-group__roster-connections">{{ entry.connections }} 个标签页</span>
      </li>
    </ul>
  </span>
</template>

<style scoped>
.presence-group { position: relative; display: inline-flex; align-items: center; }
.presence-group__row { display: inline-flex; align-items: center; }
.presence-group__avatar { position: relative; margin-left: calc(var(--cs-space-8) * -1); }
.presence-group__avatar:first-child { margin-left: 0; }
.presence-group__avatar :deep(.base-avatar) { border: 2px solid var(--cs-canvas); transition: border-color var(--cs-motion-fast) var(--cs-ease-out); }
.presence-group__avatar:hover :deep(.base-avatar) { border-color: var(--cs-border-accent-strong); }
.presence-group__badge { position: absolute; right: -4px; bottom: -4px; min-width: 14px; padding: 0 var(--cs-space-2); border-radius: 7px; background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); line-height: var(--cs-leading-tight); text-align: center; }
.presence-group__more { margin-left: var(--cs-space-4); color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }
.presence-group--degraded { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.presence-group__toggle { display: inline-flex; align-items: center; justify-content: center; margin-left: var(--cs-space-8); min-width: 22px; min-height: 22px; padding: 0; border: 1px solid var(--cs-border); border-radius: 50%; background: var(--cs-surface); color: var(--cs-text-muted); cursor: pointer; }
.presence-group__toggle:hover { color: var(--cs-text-secondary); border-color: var(--cs-border-strong); }
.presence-group__toggle:focus-visible { outline: none; box-shadow: var(--cs-focus-ring); }
.presence-group__roster { position: absolute; top: calc(100% + var(--cs-space-8)); left: 0; z-index: var(--cs-z-popover); margin: 0; padding: var(--cs-space-8); min-width: max-content; list-style: none; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); box-shadow: var(--cs-shadow-float); }
.presence-group__roster-row { display: flex; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-4) 0; }
.presence-group__roster-name { color: var(--cs-text-secondary); font-size: var(--cs-text-sm); }
.presence-group__roster-connections { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
</style>
