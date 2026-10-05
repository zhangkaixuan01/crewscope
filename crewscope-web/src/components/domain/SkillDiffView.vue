<script setup lang="ts">
import { computed } from 'vue'
import { diffLines } from '../../domains/skill/diff'

/**
 * F02 D3: a single unified diff column. Side-by-side is unreadable at the 390px narrow
 * breakpoint, and a rollback whose contentHash repeats renders as all-same here without
 * any "unchanged?" ceremony — identical hashes are the legal rollback shape (contract §3).
 */
const props = defineProps<{
  before: string
  after: string
  beforeLabel: string
  afterLabel: string
}>()

const diff = computed(() => diffLines(props.before, props.after))
</script>

<template>
  <div class="skill-diff">
    <header class="skill-diff__head">
      <span>{{ beforeLabel }}</span>
      <span v-if="diff.addedCount > 0" class="skill-diff__added">+{{ diff.addedCount }} 行</span>
      <span v-if="diff.removedCount > 0" class="skill-diff__removed">-{{ diff.removedCount }} 行</span>
      <span>{{ afterLabel }}</span>
    </header>
    <p v-if="diff.collapsed" class="skill-diff__collapsed" role="note">两侧行数过多，已折叠为全量变更对照，不是最小编辑序列。</p>
    <ol class="skill-diff__lines" :data-collapsed="diff.collapsed ? 'true' : undefined">
      <li v-for="(line, index) in diff.lines" :key="index" :class="`skill-diff__line skill-diff__line--${line.type}`">
        <span class="skill-diff__marker" aria-hidden="true">{{ line.type === 'added' ? '+' : line.type === 'removed' ? '-' : ' ' }}</span>
        <span class="skill-diff__text">{{ line.text || ' ' }}</span>
      </li>
    </ol>
  </div>
</template>

<style scoped>
.skill-diff { display: flex; min-width: 0; flex-direction: column; gap: var(--cs-space-8); }
.skill-diff__head { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.skill-diff__added { color: var(--cs-success); font-weight: var(--cs-weight-semibold); }
.skill-diff__removed { color: var(--cs-danger); font-weight: var(--cs-weight-semibold); }
.skill-diff__collapsed { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.skill-diff__lines { display: flex; margin: 0; max-height: 24rem; overflow: auto; padding: var(--cs-space-8); flex-direction: column; border-radius: var(--cs-radius-sm, 4px); background: var(--cs-surface-inset, var(--cs-surface)); list-style: none; }
.skill-diff__line { display: grid; grid-template-columns: 1ch minmax(0, 1fr); gap: var(--cs-space-8); font-family: var(--cs-font-mono, ui-monospace, monospace); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }
.skill-diff__marker { color: var(--cs-text-muted); user-select: none; }
.skill-diff__text { min-width: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.skill-diff__line--added .skill-diff__marker, .skill-diff__line--added .skill-diff__text { color: var(--cs-success); }
.skill-diff__line--removed .skill-diff__marker, .skill-diff__line--removed .skill-diff__text { color: var(--cs-danger); }
</style>
