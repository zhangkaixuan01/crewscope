<script setup lang="ts">
import { Columns3, List, Network } from '@lucide/vue'
import { computed, inject, onMounted, onBeforeUnmount, ref } from 'vue'
import { workItemStatusLabels, workItemStatusTone } from '../../domains/workitem/labels'
import type { WorkItemScope } from '../../domains/workitem/types'
import { WORK_GRAPH_STORE } from '../../domains/workgraph/store'
import { blockedGraphSummaryLabel } from '../../domains/workgraph/labels'
import { focusSubgraph, hierarchyGroups } from '../../domains/workgraph/layout'
import type { GraphNode } from '../../domains/workgraph/types'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'
import WorkGraphCanvas from './WorkGraphCanvas.vue'
import WorkGraphHierarchyList from './WorkGraphHierarchyList.vue'

/**
 * The shell of the third WorkPage view (M11-F02): header with the list/board/graph switcher, the
 * scale routing, and the selected node's facts.
 *
 * Scale routing follows ADR-033: up to 500 nodes the interactive SVG; beyond it the hierarchy
 * list, with the depth-≤2 focus subgraph as the graph-shaped escape hatch from any row. A phone
 * (767px) starts in the list form — the same facts, no squeezing a DAG into 390px. Entering the
 * view force-reloads the aggregate: an accepted relation command re-reads the stores, but the
 * graph should never greet a returning member with a stale picture when one request fixes it.
 */
const props = defineProps<{
  teamName: string | undefined
  projectKey: string
  scope: WorkItemScope | null
}>()

const emit = defineEmits<{
  'switch-view': [view: 'list' | 'board']
  'open-item': [workItemId: string]
}>()

const graphStore = inject(WORK_GRAPH_STORE, null)

onMounted(() => {
  const scope = props.scope
  if (!scope) return
  void graphStore?.loadBlockedGraph(
    { organizationId: scope.organizationId, teamId: scope.teamId },
    scope.projectId,
    true,
  )
})

/** Phones start in the list form. matchMedia is absent in jsdom; absence means the wide form. */
const narrow = ref(false)
let mediaQuery: MediaQueryList | null = null
function onMediaChange(event: MediaQueryListEvent): void {
  narrow.value = event.matches
}
onMounted(() => {
  if (typeof window.matchMedia !== 'function') return
  mediaQuery = window.matchMedia('(max-width: 767px)')
  narrow.value = mediaQuery.matches
  mediaQuery.addEventListener('change', onMediaChange)
})
onBeforeUnmount(() => {
  mediaQuery?.removeEventListener('change', onMediaChange)
})

const blocked = computed(() => graphStore?.state.blocked ?? null)
const allNodes = computed(() => [...(graphStore?.state.nodes.values() ?? [])])
const allEdges = computed(() => [...(graphStore?.state.edges.values() ?? [])])
const exceedsLimit = computed(() => (graphStore?.state.nodes.size ?? 0) > 500)

/** Set when a row (or the detail panel) asked for the depth-≤2 neighbourhood of one node. */
const focusCenterId = ref<string | null>(null)
const selectedId = ref<string | null>(null)

const focus = computed(() => focusCenterId.value
  ? focusSubgraph(allNodes.value, allEdges.value, focusCenterId.value)
  : null)
const visibleNodes = computed<GraphNode[]>(() => focus.value?.nodes ?? allNodes.value)
const visibleEdges = computed(() => focus.value?.edges ?? allEdges.value)
const groups = computed(() => hierarchyGroups(visibleNodes.value, visibleEdges.value))

/**
 * Graph when it fits and the viewport is wide. Beyond 500 nodes the whole-graph picture degrades
 * to the layered list — but a focused subgraph is capped at 200 by layout, so from the list (or
 * the detail panel) the graph form is always the destination, never a dead end.
 */
const showCanvas = computed(() => (!exceedsLimit.value || focusCenterId.value !== null) && !narrow.value)

const selectedNode = computed(() => selectedId.value
  ? graphStore?.state.nodes.get(selectedId.value) ?? null
  : null)
const selectedBlockers = computed(() => selectedNode.value
  ? allEdges.value
      .filter(edge => edge.blockedId === selectedNode.value!.id)
      .map(edge => graphStore?.state.nodes.get(edge.blockerId))
      .filter((node): node is GraphNode => Boolean(node))
  : [])

const truncationNote = computed(() => {
  const state = blocked.value
  if (!state) return ''
  if (state.capped) return '图规模已达读取上限，仅显示最近的部分；可用聚焦子图查看具体链路。'
  if (state.windowTruncated) return '部分工作项的阻塞项列表只显示最近 20 项，徽标计数是权威值。'
  return ''
})

function onSelect(nodeId: string): void {
  selectedId.value = nodeId
}
function focusFromRow(nodeId: string): void {
  focusCenterId.value = nodeId
  selectedId.value = nodeId
}
function enterFocus(): void {
  if (selectedId.value) focusCenterId.value = selectedId.value
}
function exitFocus(): void {
  focusCenterId.value = null
}
</script>

<template>
  <section class="graph-workspace panel" aria-label="依赖图视图">
    <header class="graph-workspace__header">
      <div class="graph-workspace__headline">
        <p class="eyebrow">{{ teamName }} · {{ projectKey }}</p>
        <h2>依赖图</h2>
        <span>{{ graphStore ? blockedGraphSummaryLabel(visibleNodes.length, visibleEdges.length) : '等待加载' }}</span>
      </div>
      <nav class="view-switcher" aria-label="工作项视图">
        <button type="button" aria-label="列表视图" @click="emit('switch-view', 'list')"><List :size="15" />List</button>
        <button type="button" aria-label="看板视图" @click="emit('switch-view', 'board')"><Columns3 :size="15" />Board</button>
        <button type="button" class="active" aria-label="依赖图视图" aria-current="true"><Network :size="15" />Graph</button>
      </nav>
    </header>

    <StatePanel v-if="blocked?.phase === 'loading' || blocked?.phase === 'idle'" state="loading" description="正在加载阻塞关系" />
    <StatePanel v-else-if="blocked?.phase === 'error'" state="error" :description="blocked.errorMessage ?? undefined" @retry="scope && graphStore?.loadBlockedGraph({ organizationId: scope.organizationId, teamId: scope.teamId }, scope.projectId, true)" />
    <StatePanel v-else-if="blocked?.phase === 'empty'" state="empty" title="当前没有阻塞中的工作项" description="当有进行中的工作项阻塞其他工作项时，这里会呈现依赖图。" />
    <template v-else>
      <p v-if="truncationNote" class="graph-workspace__note" role="status">{{ truncationNote }}</p>
      <p v-if="exceedsLimit" class="graph-workspace__note" role="status">图超过 500 个工作项，已切换为分层列表；选择任意一项可聚焦它的邻域子图。</p>
      <div class="graph-workspace__body" :class="{ 'graph-workspace__body--stacked': Boolean(selectedNode) }">
        <WorkGraphCanvas
          v-if="showCanvas"
          :nodes="visibleNodes"
          :edges="visibleEdges"
          :selected-id="selectedId"
          @select="onSelect"
        />
        <WorkGraphHierarchyList
          v-else
          :groups="groups"
          :selected-id="selectedId"
          @focus="focusFromRow"
        />
        <aside v-if="selectedNode" class="graph-workspace__detail" :aria-label="`选中节点:${selectedNode.title}`">
          <h3>{{ selectedNode.title }}</h3>
          <div class="graph-workspace__detail-meta">
            <StatusBadge :tone="workItemStatusTone[selectedNode.status]" dot>{{ workItemStatusLabels[selectedNode.status] }}</StatusBadge>
            <span v-if="selectedNode.blockedCount > 0">被 {{ selectedNode.blockedCount }} 项阻塞</span>
          </div>
          <p v-if="selectedBlockers.length" class="graph-workspace__detail-blockers">
            阻塞来源:{{ selectedBlockers.map(node => node.title).join('、') }}
          </p>
          <div class="graph-workspace__detail-actions">
            <BaseButton size="small" variant="secondary" @click="emit('open-item', selectedNode.id)">打开工作项</BaseButton>
            <BaseButton v-if="!focusCenterId" size="small" variant="ghost" @click="enterFocus">聚焦此邻域</BaseButton>
            <BaseButton v-else size="small" variant="ghost" @click="exitFocus">返回全图</BaseButton>
          </div>
        </aside>
      </div>
    </template>
  </section>
</template>

<style scoped>
.graph-workspace { display: grid; gap: var(--cs-space-12); padding: var(--cs-space-16) var(--cs-space-20); }
.graph-workspace__header { display: flex; align-items: flex-end; justify-content: space-between; gap: var(--cs-space-12); }
.graph-workspace__headline p { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); letter-spacing: .08em; text-transform: uppercase; }
.graph-workspace__headline h2 { margin: var(--cs-space-2) 0; font-size: var(--cs-text-lg); }
.graph-workspace__headline > span { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.view-switcher { display: flex; gap: var(--cs-space-4); padding: var(--cs-space-4); border: 1px solid var(--cs-border); border-radius: 9px; background: var(--cs-surface-subtle); }
.view-switcher button { display: flex; min-height: var(--cs-density-control-height); align-items: center; gap: var(--cs-space-4); padding: 0 var(--cs-space-8); border-radius: 6px; background: transparent; color: var(--cs-text-muted); font-size: var(--cs-text-sm); cursor: pointer; }
.view-switcher button.active { background: var(--cs-surface); box-shadow: var(--cs-shadow-hairline); color: var(--cs-text-brand); font-weight: var(--cs-weight-semibold); }
.graph-workspace__note { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }
.graph-workspace__body { display: grid; gap: var(--cs-space-12); grid-template-columns: minmax(0, 1fr); }
.graph-workspace__body--stacked { grid-template-columns: minmax(0, 1fr) 280px; align-items: start; }
.graph-workspace__detail { display: grid; gap: var(--cs-space-8); padding: var(--cs-space-16); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }
.graph-workspace__detail h3 { margin: 0; font-size: var(--cs-text-base); }
.graph-workspace__detail-meta { display: flex; align-items: center; gap: var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.graph-workspace__detail-blockers { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }
.graph-workspace__detail-actions { display: flex; flex-wrap: wrap; gap: var(--cs-space-8); }
@media (max-width: 1050px) { .graph-workspace__body--stacked { grid-template-columns: 1fr; } }
@media (max-width: 767px) { .graph-workspace { padding: var(--cs-space-16); } .graph-workspace__header { flex-wrap: wrap; } }
</style>
