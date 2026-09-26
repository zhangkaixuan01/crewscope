<script setup lang="ts">
import { workItemResponsibilityRoleLabels } from '../../domains/workitem/labels'
import { enumLabel } from '../../domains/shared/labels'

/**
 * The responsibility snapshot as one glanceable strip under the workspace header (contract §4.1).
 *
 * The full accountability detail stays in the overview section; this strip answers only “who is on
 * the hook right now” with the server's own role vocabulary, no client-side role invention.
 */
const props = defineProps<{
  lines: Array<{ role: string, name: string }>
}>()

const roleOrder: Record<string, number> = { OWNER: 0, EXECUTOR: 1, REVIEWER: 2 }
const ordered = [...props.lines].sort((left, right) =>
  (roleOrder[left.role] ?? 9) - (roleOrder[right.role] ?? 9))
</script>

<template>
  <p v-if="ordered.length" class="responsibility-strip" aria-label="责任快照摘要">
    <template v-for="(line, index) in ordered" :key="`${line.role}-${line.name}-${index}`">
      <span v-if="index" class="responsibility-strip__sep" aria-hidden="true">·</span>
      <span class="responsibility-strip__entry"><em>{{ enumLabel(line.role, workItemResponsibilityRoleLabels) }}</em>{{ line.name }}</span>
    </template>
  </p>
  <p v-else class="responsibility-strip responsibility-strip--empty">这个 Task 没有责任快照。</p>
</template>

<style scoped>
.responsibility-strip { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cs-space-8); margin: 0; color: var(--cs-text-secondary); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }
.responsibility-strip__entry { display: inline-flex; align-items: baseline; gap: var(--cs-space-4); min-width: 0; }
.responsibility-strip__entry em { padding: var(--cs-space-2) var(--cs-space-4); border-radius: 5px; background: var(--cs-surface-subtle); color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-style: normal; font-weight: var(--cs-weight-semibold); letter-spacing: .04em; }
.responsibility-strip__sep { color: var(--cs-text-muted); }
.responsibility-strip--empty { color: var(--cs-text-muted); }
</style>
