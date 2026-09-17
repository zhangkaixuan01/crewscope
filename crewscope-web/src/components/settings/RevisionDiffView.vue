<script setup lang="ts">
import { computed } from 'vue'
import BaseBadge from '../base/BaseBadge.vue'

const props = withDefaults(defineProps<{ before: Record<string, unknown> | null; after: Record<string, unknown> | null; beforeLabel?: string; afterLabel?: string }>(), { beforeLabel: '版本 A', afterLabel: '版本 B' })
interface DiffRow { key: string; before: string; after: string; changed: boolean }
function flatten(value: Record<string, unknown> | null, prefix = ''): Record<string, string> {
  const result: Record<string, string> = {}
  for (const [key, item] of Object.entries(value ?? {})) {
    const path = prefix ? `${prefix}.${key}` : key
    if (item && typeof item === 'object' && !Array.isArray(item)) Object.assign(result, flatten(item as Record<string, unknown>, path))
    else result[path] = item === null || item === undefined ? '—' : typeof item === 'string' ? item : JSON.stringify(item)
  }
  return result
}
const rows = computed<DiffRow[]>(() => {
  const before = flatten(props.before); const after = flatten(props.after)
  return [...new Set([...Object.keys(before), ...Object.keys(after)])].sort().map(key => ({ key, before: before[key] ?? '—', after: after[key] ?? '—', changed: before[key] !== after[key] }))
})
const changedCount = computed(() => rows.value.filter(row => row.changed).length)
</script>

<template>
  <section class="revision-diff" aria-label="配置版本差异">
    <header><div><h3>字段差异</h3><p>仅展示只读差异，不提供覆盖当前配置的操作。</p></div><BaseBadge :tone="changedCount ? 'warning' : 'success'">{{ changedCount }} 项变更</BaseBadge></header>
    <div class="revision-diff__head"><span>字段</span><span>{{ beforeLabel }}</span><span>{{ afterLabel }}</span></div>
    <div v-if="rows.length === 0" class="revision-diff__empty">两个版本均无可比较字段。</div>
    <div v-for="row in rows" :key="row.key" class="revision-diff__row" :class="{ changed: row.changed }"><code>{{ row.key }}</code><span>{{ row.before }}</span><span>{{ row.after }}</span></div>
  </section>
</template>

<style scoped>
.revision-diff { overflow: hidden; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }
.revision-diff header, .revision-diff__head, .revision-diff__row { display: grid; grid-template-columns: minmax(150px, .8fr) minmax(0, 1fr) minmax(0, 1fr); gap: var(--cs-space-12); align-items: start; }
.revision-diff header { grid-template-columns: 1fr auto; padding: var(--cs-space-16); border-bottom: 1px solid var(--cs-border); }
.revision-diff h3 { margin: 0; font-size: var(--cs-text-md); }.revision-diff p { margin: var(--cs-space-4) 0 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.revision-diff__head { padding: var(--cs-space-8) var(--cs-space-16); background: var(--cs-surface-subtle); color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }
.revision-diff__row { padding: var(--cs-space-12) var(--cs-space-16); border-top: 1px solid var(--cs-border); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); word-break: break-word; }.revision-diff__row.changed { background: var(--cs-warning-soft); }.revision-diff__row code { color: var(--cs-text); font-weight: var(--cs-weight-semibold); }.revision-diff__empty { padding: var(--cs-space-16); color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
@media (max-width: 640px) { .revision-diff__head, .revision-diff__row { grid-template-columns: 1fr; gap: var(--cs-space-4); }.revision-diff__head span:not(:first-child)::before, .revision-diff__row span::before { display: inline-block; width: 72px; color: var(--cs-text-muted); font-weight: var(--cs-weight-semibold); }.revision-diff__head span:nth-child(2)::before, .revision-diff__row span:nth-child(2)::before { content: '版本 A：'; }.revision-diff__head span:nth-child(3)::before, .revision-diff__row span:nth-child(3)::before { content: '版本 B：'; } }
</style>
