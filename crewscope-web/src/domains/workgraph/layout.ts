import type { GraphEdge, GraphNode } from './types'

/** Interactive-graph ceiling (ADR-033): above this the workspace degrades to the hierarchy list. */
export const GRAPH_NODE_LIMIT = 500
/** Focus neighbourhood depth and ceiling (ADR-033): selected node + depth ≤ 2, at most 200 nodes. */
export const FOCUS_DEPTH = 2
export const FOCUS_NODE_LIMIT = 200

/** Layout geometry in SVG user units; the canvas scales them through its viewBox. */
export const NODE_WIDTH = 220
export const NODE_HEIGHT = 64
export const LAYER_GAP = 96
export const COLUMN_GAP = 32
export const CANVAS_PADDING = 48

export interface NodeLayout {
  layer: number
  x: number
  y: number
}

export interface LayoutResult {
  nodes: Map<string, NodeLayout>
  width: number
  height: number
  /** Nodes a topological sort could not place (on or below a cycle — the server 409s cycles, this is defense). */
  cyclicNodeIds: string[]
}

/**
 * Longest-path layered layout with deterministic ordering (M11-F02, no graph library).
 *
 * Layer 0 holds the pure blockers (no in-edges); every other node sits one below its highest
 * blocker, so a longest chain reads as a straight column. Within a layer, one barycenter pass
 * sorts nodes by their blockers' average order to cut edge crossings, with (title, id) as the
 * stable tie-break. All comparisons are code-point, never `localeCompare`: the picture must be
 * byte-identical across the vitest, Playwright and CI ICU builds the visual baseline runs on.
 */
export function layoutWorkGraph(nodes: readonly GraphNode[], edges: readonly GraphEdge[]): LayoutResult {
  const ids = nodes.map(node => node.id)
  const idSet = new Set(ids)
  const blockersOf = new Map<string, string[]>()
  const blockingOf = new Map<string, string[]>()
  for (const id of ids) {
    blockersOf.set(id, [])
    blockingOf.set(id, [])
  }
  for (const edge of edges) {
    if (!idSet.has(edge.blockerId) || !idSet.has(edge.blockedId) || edge.blockerId === edge.blockedId) continue
    blockersOf.get(edge.blockedId)!.push(edge.blockerId)
    blockingOf.get(edge.blockerId)!.push(edge.blockedId)
  }

  // Longest-path layering by relaxation: each pass pushes a node below its highest blocker.
  // A DAG settles within |nodes| passes; a contract-violating cycle keeps pushing forever, so
  // the pass cap parks it and Kahn reports exactly which nodes a sort could not place.
  const layer = new Map<string, number>(ids.map(id => [id, 0]))
  let settled = false
  for (let pass = 0; pass <= ids.length && !settled; pass += 1) {
    settled = true
    for (const id of ids) {
      let candidate = 0
      for (const blocker of blockersOf.get(id)!) candidate = Math.max(candidate, layer.get(blocker)! + 1)
      if (candidate > layer.get(id)!) {
        layer.set(id, candidate)
        settled = false
      }
    }
  }
  const cyclicNodeIds = settled ? [] : unplaceableIds(ids, blockersOf, blockingOf)
  const maxLayer = ids.reduce((top, id) => Math.max(top, layer.get(id)!), 0)

  // Deterministic seed order, then one barycenter pass per layer over blocker positions.
  const titleOf = new Map(nodes.map(node => [node.id, node.title]))
  /** Stable node order: title first, id as the tie-break — never the map insertion order. */
  const byStableOrder = (a: string, b: string): number => {
    const byTitle = compareText(titleOf.get(a)!, titleOf.get(b)!)
    return byTitle !== 0 ? byTitle : compareText(a, b)
  }
  const order = new Map<string, number>()
  for (let current = 0; current <= maxLayer; current += 1) {
    const row = ids.filter(id => layer.get(id) === current).sort(byStableOrder)
    if (current > 0) {
      row.sort((a, b) => {
        const averageA = barycenter(a, blockersOf, order)
        const averageB = barycenter(b, blockersOf, order)
        if (averageA !== averageB) return averageA - averageB
        return byStableOrder(a, b)
      })
    }
    row.forEach((id, index) => order.set(id, index))
  }

  const layouts = new Map<string, NodeLayout>()
  for (const id of ids) {
    const currentLayer = layer.get(id)!
    const column = order.get(id)!
    layouts.set(id, {
      layer: currentLayer,
      x: CANVAS_PADDING + column * (NODE_WIDTH + COLUMN_GAP),
      y: CANVAS_PADDING + currentLayer * (NODE_HEIGHT + LAYER_GAP),
    })
  }
  const columns = maxLayer === 0
    ? Math.max(ids.length, 1)
    : ids.reduce((widest, id) => Math.max(widest, order.get(id)! + 1), 1)
  return {
    nodes: layouts,
    width: CANVAS_PADDING * 2 + columns * NODE_WIDTH + Math.max(columns - 1, 0) * COLUMN_GAP,
    height: CANVAS_PADDING * 2 + (maxLayer + 1) * NODE_HEIGHT + maxLayer * LAYER_GAP,
    cyclicNodeIds,
  }
}

/** Kahn residual: nodes never reaching in-degree zero sit on or below a cycle. */
function unplaceableIds(
  ids: string[],
  blockersOf: Map<string, string[]>,
  blockingOf: Map<string, string[]>,
): string[] {
  const indegree = new Map(ids.map(id => [id, blockersOf.get(id)!.length]))
  const queue = ids.filter(id => indegree.get(id) === 0)
  const placed = new Set(queue)
  while (queue.length > 0) {
    const id = queue.shift()!
    for (const blocked of blockingOf.get(id)!) {
      const remaining = indegree.get(blocked)! - 1
      indegree.set(blocked, remaining)
      if (remaining === 0) {
        queue.push(blocked)
        placed.add(blocked)
      }
    }
  }
  return ids.filter(id => !placed.has(id)).sort(compareText)
}

function barycenter(
  id: string,
  blockersOf: Map<string, string[]>,
  order: Map<string, number>,
): number {
  const blockers = blockersOf.get(id)!.map(blocker => order.get(blocker)).filter(index => index !== undefined)
  if (blockers.length === 0) return Number.MAX_SAFE_INTEGER
  return blockers.reduce((sum, index) => sum + index, 0) / blockers.length
}

/** Code-point comparison — deterministic across every ICU build, unlike `localeCompare`. */
function compareText(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0
}

export interface FocusResult {
  nodes: GraphNode[]
  edges: GraphEdge[]
  truncated: boolean
}

/**
 * The depth-≤2 undirected neighbourhood of one node, capped at {@link FOCUS_NODE_LIMIT}. This is
 * the graph-shaped escape hatch from the hierarchy list when the whole graph exceeds
 * {@link GRAPH_NODE_LIMIT}: the member still gets an interactive picture of what matters.
 */
export function focusSubgraph(
  nodes: readonly GraphNode[],
  edges: readonly GraphEdge[],
  centerId: string,
): FocusResult {
  const nodeById = new Map(nodes.map(node => [node.id, node]))
  if (!nodeById.has(centerId)) return { nodes: [], edges: [], truncated: false }
  const neighbours = new Map<string, string[]>(nodes.map(node => [node.id, []]))
  for (const edge of edges) {
    if (neighbours.has(edge.blockerId) && neighbours.has(edge.blockedId) && edge.blockerId !== edge.blockedId) {
      neighbours.get(edge.blockerId)!.push(edge.blockedId)
      neighbours.get(edge.blockedId)!.push(edge.blockerId)
    }
  }
  const frontier = new Set<string>([centerId])
  const visited = new Set<string>([centerId])
  for (let depth = 0; depth < FOCUS_DEPTH; depth += 1) {
    const next = new Set<string>()
    for (const id of frontier) {
      for (const neighbour of neighbours.get(id) ?? []) {
        if (!visited.has(neighbour)) next.add(neighbour)
      }
    }
    for (const id of next) visited.add(id)
    for (const id of next) frontier.add(id)
    if (frontier.size === 0) break
  }
  const kept = [...visited].sort(compareText)
  const truncated = kept.length > FOCUS_NODE_LIMIT
  const keepSet = new Set(truncated ? kept.slice(0, FOCUS_NODE_LIMIT) : kept)
  if (!keepSet.has(centerId)) keepSet.add(centerId)
  return {
    nodes: nodes.filter(node => keepSet.has(node.id)),
    edges: edges.filter(edge => keepSet.has(edge.blockerId) && keepSet.has(edge.blockedId)),
    truncated,
  }
}

export interface HierarchyGroup {
  /** Root blocker titles (the chain heads the group hangs from), deterministic. */
  roots: string[]
  nodes: GraphNode[]
}

/**
 * The non-graph equivalent of the canvas (accessibility + >500 degradation): weakly connected
 * components in blocker-first order. Group roots are the component's pure blockers — the items
 * that hold everything else up — so each group reads as "this chain of waiting starts here".
 */
export function hierarchyGroups(nodes: readonly GraphNode[], edges: readonly GraphEdge[]): HierarchyGroup[] {
  const nodeById = new Map(nodes.map(node => [node.id, node]))
  const blockersOf = new Map<string, string[]>(nodes.map(node => [node.id, []]))
  const blockingOf = new Map<string, string[]>(nodes.map(node => [node.id, []]))
  for (const edge of edges) {
    if (!nodeById.has(edge.blockerId) || !nodeById.has(edge.blockedId) || edge.blockerId === edge.blockedId) continue
    blockersOf.get(edge.blockedId)!.push(edge.blockerId)
    blockingOf.get(edge.blockerId)!.push(edge.blockedId)
  }

  const visited = new Set<string>()
  const groups: HierarchyGroup[] = []
  const ordered = [...nodes].sort(byNodeOrder)
  for (const seed of ordered) {
    if (visited.has(seed.id)) continue
    const component: GraphNode[] = []
    const stack = [seed.id]
    visited.add(seed.id)
    while (stack.length > 0) {
      const id = stack.pop()!
      const node = nodeById.get(id)
      if (node) component.push(node)
      for (const blocker of blockersOf.get(id) ?? []) {
        if (!visited.has(blocker)) { visited.add(blocker); stack.push(blocker) }
      }
      for (const blocked of blockingOf.get(id) ?? []) {
        if (!visited.has(blocked)) { visited.add(blocked); stack.push(blocked) }
      }
    }
    component.sort((a, b) => {
      const byDepth = nodeDepth(a.id, blockersOf) - nodeDepth(b.id, blockersOf)
      if (byDepth !== 0) return byDepth
      return byNodeOrder(a, b)
    })
    const roots = component.filter(node => (blockersOf.get(node.id) ?? []).length === 0)
      .map(node => node.title)
      .sort(compareText)
    groups.push({ roots, nodes: component })
  }
  return groups
}

function byNodeOrder(a: GraphNode, b: GraphNode): number {
  const byTitle = compareText(a.title, b.title)
  return byTitle !== 0 ? byTitle : compareText(a.id, b.id)
}

/** In-component depth (0 for roots), tolerant of cycles by never revisiting a path. */
function nodeDepth(id: string, blockersOf: Map<string, string[]>, seen = new Set<string>()): number {
  const blockers = (blockersOf.get(id) ?? []).filter(blocker => !seen.has(blocker))
  if (blockers.length === 0) return 0
  const nextSeen = new Set(seen)
  for (const blocker of blockers) nextSeen.add(blocker)
  return 1 + Math.max(...blockers.map(blocker => nodeDepth(blocker, blockersOf, nextSeen)))
}
