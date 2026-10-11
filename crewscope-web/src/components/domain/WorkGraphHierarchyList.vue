<script setup lang="ts">
import { CircleSlash } from '@lucide/vue'
import { workItemStatusLabels, workItemStatusTone } from '../../domains/workitem/labels'
import type { GraphNode } from '../../domains/workgraph/types'
import type { HierarchyGroup } from '../../domains/workgraph/layout'
import StatusBadge from '../base/StatusBadge.vue'

/**
 * The layered-list equivalent of the canvas (M11-F02): what the graph says, said as groups and
 * rows. It renders when the whole graph exceeds the 500-node interactive ceiling, when the focus
 * subgraph is requested from there, and on a phone — and it is always in the accessibility tree,
 * because it *is* the non-graph expression of the same facts.
 */
defineProps<{
  groups: readonly HierarchyGroup[]
  selectedId: string | null
}>()

const emit = defineEmits<{ focus: [nodeId: string] }>()
</script>

<template>
  <div class="graph-hierarchy" aria-label="依赖关系分层列表">
    <section v-for="(group, index) in groups" :key="index" class="graph-hierarchy__group">
      <h4>
        <span>{{ group.roots.length ? `阻塞链:${group.roots.join('、')}` : '独立工作项' }}</span>
        <StatusBadge>{{ group.nodes.length }}</StatusBadge>
      </h4>
      <ul>
        <li v-for="node in group.nodes" :key="node.id">
          <button
            type="button"
            :class="{ 'graph-hierarchy__row--selected': node.id === selectedId }"
            :aria-pressed="node.id === selectedId"
            @click="emit('focus', node.id)"
          >
            <span class="graph-hierarchy__title">{{ node.title }}</span>
            <span class="graph-hierarchy__meta">
              <StatusBadge :tone="workItemStatusTone[node.status]" dot>{{ workItemStatusLabels[node.status] }}</StatusBadge>
              <span v-if="node.blockedCount > 0" class="graph-hierarchy__blocked"><CircleSlash :size="11" aria-hidden="true" />被 {{ node.blockedCount }} 项阻塞</span>
            </span>
          </button>
        </li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.graph-hierarchy { display: grid; gap: var(--cs-space-12); }
.graph-hierarchy__group { border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }
.graph-hierarchy__group > h4 { display: flex; min-height: 44px; align-items: center; justify-content: space-between; gap: var(--cs-space-8); margin: 0; padding: 0 var(--cs-space-12); border-bottom: 1px solid var(--cs-border); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.graph-hierarchy ul { display: grid; margin: 0; padding: var(--cs-space-8); list-style: none; gap: var(--cs-space-4); }
.graph-hierarchy button { display: flex; width: 100%; align-items: center; justify-content: space-between; gap: var(--cs-space-8); padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid transparent; border-radius: 8px; background: var(--cs-surface-subtle); color: var(--cs-text); font-size: var(--cs-text-sm); text-align: left; cursor: pointer; }
.graph-hierarchy button:hover { border-color: var(--cs-border-accent); background: var(--cs-surface-accent); }
.graph-hierarchy__row--selected { border-color: var(--cs-focus); background: var(--cs-surface-accent); }
.graph-hierarchy__title { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.graph-hierarchy__meta { display: inline-flex; flex: 0 0 auto; align-items: center; gap: var(--cs-space-8); }
.graph-hierarchy__blocked { display: inline-flex; align-items: center; gap: var(--cs-space-4); color: var(--cs-danger); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }
@media (max-width: 767px) { .graph-hierarchy button { flex-wrap: wrap; } }
</style>
