<script setup lang="ts">
import { computed, ref } from 'vue'
import { workItemStatusLabels, workItemStatusTone } from '../../domains/workitem/labels'
import type { GraphEdge, GraphNode } from '../../domains/workgraph/types'
import { layoutWorkGraph, NODE_HEIGHT, NODE_WIDTH } from '../../domains/workgraph/layout'
import BaseButton from '../base/BaseButton.vue'

/**
 * The hand-drawn SVG of the active blocking DAG (M11-F02, no graph library).
 *
 * Deterministic layered layout in, one picture out. Nodes are focusable groups in reading order
 * (layer by layer), so Tab walks the same chain the eye does; the sr-only list below the picture
 * says every fact the picture says, which is the screen-reader equivalent of the canvas. The svg
 * carries `role=group` (not `img`): its nodes are interactive descendants, and an img that
 * contains buttons is a nested-interactive violation, not a picture. Zoom is a viewBox scale —
 * buttons step it, +/−/0 keys step it, nothing else moves.
 */
const props = defineProps<{
  nodes: readonly GraphNode[]
  edges: readonly GraphEdge[]
  selectedId: string | null
}>()

const emit = defineEmits<{ select: [nodeId: string] }>()

const layout = computed(() => layoutWorkGraph(props.nodes, props.edges))
const nodeById = computed(() => new Map(props.nodes.map(node => [node.id, node])))

/** Step zoom in [0.5, 2.5]; `0` resets. The picture is the source of truth, zoom is a lens. */
const zoom = ref(1)
function stepZoom(delta: number): void {
  zoom.value = Math.min(2.5, Math.max(0.5, Math.round((zoom.value + delta) * 10) / 10))
}
function resetZoom(): void {
  zoom.value = 1
}
function onCanvasKeydown(event: KeyboardEvent): void {
  if (event.key === '+' || event.key === '=') { event.preventDefault(); stepZoom(0.1) }
  else if (event.key === '-') { event.preventDefault(); stepZoom(-0.1) }
  else if (event.key === '0') { event.preventDefault(); resetZoom() }
}

function nodeLabel(node: GraphNode): string {
  const blocked = node.blockedCount > 0 ? `,被 ${node.blockedCount} 项阻塞` : ''
  return `${node.title},${workItemStatusLabels[node.status]}${blocked}`
}

function nodeClass(node: GraphNode): string[] {
  const classes = [`graph-node--${workItemStatusTone[node.status]}`]
  if (node.id === props.selectedId) classes.push('graph-node--selected')
  if (node.blockedCount > 0) classes.push('graph-node--blocked')
  return classes
}

function nodeTransform(id: string): string {
  const position = layout.value.nodes.get(id)
  return position ? `translate(${position.x}, ${position.y})` : 'translate(0, 0)'
}

/** Blocker bottom edge → blocked top edge, one vertical bezier per edge. */
function edgePath(edge: GraphEdge): string {
  const from = layout.value.nodes.get(edge.blockerId)
  const to = layout.value.nodes.get(edge.blockedId)
  if (!from || !to) return ''
  const x1 = from.x + NODE_WIDTH / 2
  const y1 = from.y + NODE_HEIGHT
  const x2 = to.x + NODE_WIDTH / 2
  const y2 = to.y
  const middle = (y1 + y2) / 2
  return `M ${x1} ${y1} C ${x1} ${middle}, ${x2} ${middle}, ${x2} ${y2}`
}
</script>

<template>
  <div class="graph-canvas" tabindex="0" role="group" aria-label="依赖图画面:加号放大,减号缩小,0 重置" @keydown="onCanvasKeydown">
    <div class="graph-canvas__controls" role="group" aria-label="缩放控制">
      <BaseButton size="small" variant="ghost" aria-label="放大依赖图" @click="stepZoom(0.1)">＋</BaseButton>
      <BaseButton size="small" variant="ghost" aria-label="缩小依赖图" @click="stepZoom(-0.1)">－</BaseButton>
      <BaseButton size="small" variant="ghost" aria-label="重置缩放" @click="resetZoom">重置</BaseButton>
      <span class="graph-canvas__zoom" aria-live="polite">{{ Math.round(zoom * 100) }}%</span>
    </div>
    <div class="graph-canvas__scroll">
      <svg
        :width="layout.width * zoom"
        :height="layout.height * zoom"
        :viewBox="`0 0 ${layout.width} ${layout.height}`"
        class="graph-canvas__svg"
        role="group"
        :aria-label="`阻塞依赖图,共 ${nodes.length} 个工作项、${edges.length} 条阻塞关系`"
      >
        <defs>
          <marker id="graph-edge-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M 0 1 L 9 5 L 0 9 z" class="graph-edge__arrow" />
          </marker>
        </defs>
        <g v-for="edge in edges" :key="`${edge.blockerId}->${edge.blockedId}`">
          <path
            v-if="layout.nodes.get(edge.blockerId) && layout.nodes.get(edge.blockedId)"
            class="graph-edge"
            :class="{ 'graph-edge--selected': edge.blockerId === selectedId || edge.blockedId === selectedId }"
            :d="edgePath(edge)"
          />
        </g>
        <g
          v-for="node in nodes"
          :key="node.id"
          class="graph-node"
          :class="nodeClass(node)"
          tabindex="0"
          role="button"
          :transform="nodeTransform(node.id)"
          :aria-label="`${nodeLabel(node)},按 Enter 查看详情`"
          @click="emit('select', node.id)"
          @keydown.enter.prevent="emit('select', node.id)"
          @keydown.space.prevent="emit('select', node.id)"
        >
          <rect :width="NODE_WIDTH" :height="NODE_HEIGHT" rx="10" />
          <text class="graph-node__title" :x="14" :y="24">{{ node.title.length > 14 ? `${node.title.slice(0, 14)}…` : node.title }}</text>
          <text class="graph-node__meta" :x="14" :y="46">{{ workItemStatusLabels[node.status] }}</text>
          <!-- The count rides the meta row's right edge: the title row stays free for the title. -->
          <text v-if="node.blockedCount > 0" class="graph-node__count" :x="NODE_WIDTH - 12" :y="46" text-anchor="end">被{{ node.blockedCount }}项</text>
        </g>
      </svg>
    </div>
    <!-- The non-graph equivalent: every fact the picture says, one line per node. -->
    <ul class="sr-only" aria-label="依赖图节点清单">
      <li v-for="node in nodes" :key="node.id">{{ nodeLabel(node) }}</li>
    </ul>
  </div>
</template>

<style scoped>
.graph-canvas { display: grid; gap: var(--cs-space-8); outline: none; }
.graph-canvas:focus-visible { box-shadow: var(--cs-ring-brand); border-radius: var(--cs-radius-sm); }
.graph-canvas__controls { display: flex; align-items: center; gap: var(--cs-space-4); }
.graph-canvas__zoom { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.graph-canvas__scroll { overflow: auto; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }
.graph-canvas__svg { display: block; }
.graph-edge { fill: none; stroke: var(--cs-border-strong); stroke-width: 1.5; marker-end: url(#graph-edge-arrow); }
.graph-edge__arrow { fill: var(--cs-border-strong); }
.graph-edge--selected { stroke: var(--cs-focus); stroke-width: 2.5; }
.graph-node { cursor: pointer; outline: none; }
.graph-node rect { fill: var(--cs-surface-subtle); stroke: var(--cs-border-strong); stroke-width: 1.5; }
.graph-node--danger rect { fill: var(--cs-danger-soft); stroke: var(--cs-danger); }
.graph-node--success rect { fill: var(--cs-success-soft); stroke: var(--cs-success); }
.graph-node--info rect { fill: var(--cs-info-soft); stroke: var(--cs-info); }
.graph-node--warning rect { fill: var(--cs-warning-soft); stroke: var(--cs-warning); }
.graph-node__title { fill: var(--cs-text); font-size: var(--cs-text-base); font-weight: var(--cs-weight-semibold); }
.graph-node__meta { fill: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.graph-node__count { fill: var(--cs-danger); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }
.graph-node--selected rect { stroke: var(--cs-focus); stroke-width: 3; }
.graph-node:focus-visible rect { stroke: var(--cs-focus); stroke-width: 3; }

/* M12b-F01：悬停反馈补齐——此前这些可点元素对指针移动完全沉默。 */
.graph-node:hover:not(:disabled) { background: var(--cs-surface-subtle); border-color: var(--cs-border-accent); }
.graph-node { transition: background-color var(--cs-motion-fast) var(--cs-ease-out), border-color var(--cs-motion-fast) var(--cs-ease-out); }
</style>
