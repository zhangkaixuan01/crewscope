import type { GraphEdge, GraphNode } from './types'
import { focusSubgraph, hierarchyGroups, layoutWorkGraph, FOCUS_NODE_LIMIT, GRAPH_NODE_LIMIT } from './layout'

describe('layoutWorkGraph', () => {
  it('layers by longest path so a diamond reads as two columns into one', () => {
    const result = layoutWorkGraph(
      [node('a', '甲'), node('b', '乙'), node('c', '丙'), node('d', '丁')],
      [edge('a', 'b'), edge('a', 'c'), edge('b', 'd'), edge('c', 'd')],
    )
    // a is the pure blocker (layer 0); b and c sit under it; d waits on both, one below the deeper.
    expect(result.nodes.get('a')?.layer).toBe(0)
    expect(result.nodes.get('b')?.layer).toBe(1)
    expect(result.nodes.get('c')?.layer).toBe(1)
    expect(result.nodes.get('d')?.layer).toBe(2)
    expect(result.nodes.get('b')?.y).toBe(result.nodes.get('c')?.y)
    expect(result.nodes.get('d')?.y).toBeGreaterThan(result.nodes.get('b')!.y)
    expect(result.cyclicNodeIds).toEqual([])
  })

  it('is deterministic for identical input regardless of array order', () => {
    const nodes = [node('a', 'alpha'), node('b', 'beta'), node('c', 'gamma')]
    const edges = [edge('a', 'b'), edge('b', 'c'), edge('a', 'c')]
    const first = layoutWorkGraph([...nodes].reverse(), [...edges].reverse())
    const second = layoutWorkGraph(nodes, edges)
    const snapshot = (result: ReturnType<typeof layoutWorkGraph>) =>
      [...result.nodes.entries()].map(([id, layout]) => [id, layout.layer, layout.x, layout.y]).sort()
    expect(snapshot(first)).toEqual(snapshot(second))
    // Same layer means different columns, never two nodes on one spot.
    const spots = [...second.nodes.values()].map(layout => `${layout.x},${layout.y}`)
    expect(new Set(spots).size).toBe(spots.length)
  })

  it('orders each layer by title with id as tie-break', () => {
    const result = layoutWorkGraph(
      [node('z', 'beta'), node('y', 'gamma'), node('x', 'alpha'), node('w', 'alpha')],
      [edge('x', 'w')],
    )
    const row = [...result.nodes.entries()]
      .filter(([, layout]) => layout.layer === 1)
      .sort((a, b) => a[1].x - b[1].x)
      .map(([id]) => id)
    expect(row).toEqual(['w'])
    const layerZero = [...result.nodes.entries()]
      .filter(([, layout]) => layout.layer === 0)
      .sort((a, b) => a[1].x - b[1].x)
      .map(([id]) => id)
    // Title order alpha < beta < gamma; the alpha-alpha tie between x and w breaks by id (x < w).
    expect(layerZero).toEqual(['x', 'z', 'y'])
  })

  it('parks a contract-violating cycle deterministically instead of looping', () => {
    const result = layoutWorkGraph([node('a', '甲'), node('b', '乙')], [edge('a', 'b'), edge('b', 'a')])
    expect(result.cyclicNodeIds.sort()).toEqual(['a', 'b'])
    expect(result.nodes.size).toBe(2)
  })
})

describe('focusSubgraph', () => {
  it('keeps the depth-2 neighbourhood of the selected node', () => {
    const nodes = [node('a', '甲'), node('b', '乙'), node('c', '丙'), node('d', '丁'), node('e', '戊')]
    // Chain a→b→c→d plus an unrelated e; depth 2 around b keeps a, b, c, d and drops e.
    const edges = [edge('a', 'b'), edge('b', 'c'), edge('c', 'd')]
    const result = focusSubgraph(nodes, edges, 'b')
    expect(result.nodes.map(current => current.id).sort()).toEqual(['a', 'b', 'c', 'd'])
    expect(result.edges).toEqual([edge('a', 'b'), edge('b', 'c'), edge('c', 'd')])
    expect(result.truncated).toBe(false)
  })

  it('caps the neighbourhood at the 200-node focus ceiling and reports it', () => {
    const center = node('center', '中心')
    const nodes = [center, ...Array.from({ length: FOCUS_NODE_LIMIT + 10 }, (_, index) => node(`n${index}`, `项${index}`))]
    const edges = nodes.slice(1).map(peripheral => edge(peripheral.id, 'center'))
    const result = focusSubgraph(nodes, edges, 'center')
    expect(result.truncated).toBe(true)
    expect(result.nodes.length).toBe(FOCUS_NODE_LIMIT)
    expect(result.nodes.some(current => current.id === 'center')).toBe(true)
  })

  it('returns an empty subgraph for an unknown center', () => {
    expect(focusSubgraph([node('a', '甲')], [], 'missing')).toEqual({ nodes: [], edges: [], truncated: false })
  })
})

describe('hierarchyGroups', () => {
  it('groups weakly connected components with their pure blockers as roots', () => {
    const nodes = [node('a', '甲'), node('b', '乙'), node('c', '丙'), node('d', '丁')]
    const groups = hierarchyGroups(nodes, [edge('a', 'b'), edge('a', 'c'), edge('b', 'c')])
    expect(groups.length).toBe(2)
    const first = groups.find(group => group.nodes.some(current => current.id === 'a'))!
    expect(first.roots).toEqual(['甲'])
    expect(first.nodes.map(current => current.id).sort()).toEqual(['a', 'b', 'c'])
    const isolated = groups.find(group => group.nodes.every(current => current.id === 'd'))!
    expect(isolated.roots).toEqual(['丁'])
  })

  it('orders members by chain depth so a group reads blocker-first', () => {
    const groups = hierarchyGroups(
      [node('a', '甲'), node('b', '乙'), node('c', '丙')],
      [edge('a', 'b'), edge('b', 'c')],
    )
    expect(groups[0]?.nodes.map(current => current.id)).toEqual(['a', 'b', 'c'])
  })
})

describe('scale ceilings', () => {
  it('keeps the ADR-033 thresholds where the workspace routes on them', () => {
    expect(GRAPH_NODE_LIMIT).toBe(500)
    expect(FOCUS_NODE_LIMIT).toBe(200)
  })
})

function node(id: string, title: string): GraphNode {
  return { id, itemKey: null, title, status: 'IN_PROGRESS', projectId: 'p', blockedCount: 0 }
}

function edge(blockerId: string, blockedId: string): GraphEdge {
  return { blockerId, blockedId }
}
