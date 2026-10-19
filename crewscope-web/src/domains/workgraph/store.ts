import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import { CrewScopeApiError } from '../../api/client'
import { createCommandGateway } from '../../api/commandGateway'
import type { WorkGraphGateway } from './gateway'
import { workItemRelationErrorLabels } from './labels'
import type {
  BlockedWorkItemRow,
  BlockingChain,
  CreateWorkItemRelationInput,
  GraphEdge,
  GraphNode,
  WorkGraphCommandReceipt,
  WorkGraphQueryScope,
  WorkItemRelationCommandScope,
  WorkItemRelations,
} from './types'
import { GRAPH_NODE_LIMIT } from './layout'
import { isAbort, presentError as sharedPresentError } from '../../api/errors'
import { createRequestScope } from '../../api/requestScope'

export type WorkGraphPhase = 'idle' | 'loading' | 'ready' | 'empty' | 'error'

/** Relation-specific codes carry actionable copy (409 duplicate et al.); other API errors
 * fall through to the shared presenter. */
function presentError(error: unknown, fallback: string): string {
  if (error instanceof CrewScopeApiError) {
    return workItemRelationErrorLabels[error.envelope.code] ?? sharedPresentError(error, fallback)
  }
  return fallback
}

/** One page per request, at most ten pages: past 1000 rows the graph is unusable anyway. */
const MAX_PAGES = 10
const PAGE_LIMIT = 100

interface BlockedGraphState {
  phase: WorkGraphPhase
  pagesLoaded: number
  /** The pager stopped on its hard page cap instead of a real end — reported, never silent. */
  capped: boolean
  /** At least one row's blocker window was shorter than its authoritative blockerCount. */
  windowTruncated: boolean
  errorMessage: string | null
  errorStatus: number | null
}

interface RelationAnchorState {
  phase: WorkGraphPhase
  scope: WorkGraphQueryScope | null
  workItemId: string | null
  relations: WorkItemRelations | null
  chain: BlockingChain | null
  errorMessage: string | null
  errorStatus: number | null
}

interface CommandState {
  pending: boolean
  errorMessage: string | null
  errorStatus: number | null
}

interface WorkGraphState {
  blocked: BlockedGraphState
  nodes: Map<string, GraphNode>
  edges: Map<string, GraphEdge>
  anchor: RelationAnchorState
  command: CommandState
}

export interface WorkGraphStore {
  state: Readonly<WorkGraphState>
  loadBlockedGraph(scope: WorkItemRelationCommandScope, projectId?: string, force?: boolean): Promise<void>
  loadRelations(scope: WorkGraphQueryScope, workItemId: string, force?: boolean): Promise<void>
  loadBlockingChain(scope: WorkGraphQueryScope, workItemId: string, force?: boolean): Promise<void>
  createRelation(
    scope: WorkItemRelationCommandScope,
    input: CreateWorkItemRelationInput,
    idempotencyKey: string,
  ): Promise<WorkGraphCommandReceipt | null>
  removeRelation(
    scope: WorkItemRelationCommandScope,
    relationId: string,
    idempotencyKey: string,
  ): Promise<WorkGraphCommandReceipt | null>
  blockedCountOf(workItemId: string): number
  exceedsNodeLimit(): boolean
  reset(): void
}

export const WORK_GRAPH_STORE: InjectionKey<WorkGraphStore> = Symbol('crewscope-work-graph-store')

/**
 * The active blocking DAG and the relation facts of the currently open item (M11-F02).
 *
 * The blocked aggregate loads on entering WorkPage regardless of the active view — the
 * 「被 N 项阻塞」 badge on list and board cards is this store's `blockedCountOf`, and its absence
 * after a silent failure simply means "no facts to show", never a wrong zero shown loudly.
 * Only the graph view surfaces the error phase.
 */
export function createWorkGraphStore(sourceGateway: WorkGraphGateway): WorkGraphStore {
  const commandIntents = createCommandGateway(sourceGateway, {
    createRelation: 2, removeRelation: 2,
  })
  const gateway = commandIntents.gateway
  const state = reactive<WorkGraphState>({
    blocked: {
      phase: 'idle', pagesLoaded: 0, capped: false, windowTruncated: false,
      errorMessage: null, errorStatus: null,
    },
    nodes: new Map(),
    edges: new Map(),
    anchor: {
      phase: 'idle', scope: null, workItemId: null, relations: null, chain: null,
      errorMessage: null, errorStatus: null,
    },
    command: { pending: false, errorMessage: null, errorStatus: null },
  })
  let blockedScope: WorkItemRelationCommandScope | null = null
  let blockedProjectFilter: string | undefined
  // One channel per face (M12b-F05): the blocked aggregate and the anchor adjacency each
  // supersede their own kind — the controller + version pairs this replaces.
  const blockedRequests = createRequestScope(() => 'workgraph-blocked')
  const anchorRequests = createRequestScope(() => 'workgraph-anchor')

  async function loadBlockedGraph(
    scope: WorkItemRelationCommandScope,
    projectId?: string,
    force = false,
  ): Promise<void> {
    const scopeChanged = scope.organizationId !== blockedScope?.organizationId
      || scope.teamId !== blockedScope?.teamId
      || projectId !== blockedProjectFilter
    if (!force && !scopeChanged && ['ready', 'empty'].includes(state.blocked.phase)) return
    const request = blockedRequests.begin('blocked')
    blockedScope = scope
    blockedProjectFilter = projectId
    state.blocked.phase = 'loading'
    state.blocked.errorMessage = null
    state.blocked.errorStatus = null
    state.blocked.pagesLoaded = 0
    state.blocked.capped = false
    state.blocked.windowTruncated = false
    // Clear on every load: a retry after error must not merge the new pages into stale nodes.
    state.nodes.clear()
    state.edges.clear()
    try {
      let after: string | undefined
      do {
        const page = await gateway.listBlockedWorkItems(scope, projectId, after, PAGE_LIMIT, request.signal)
        if (!request.isCurrent()) return
        for (const row of page.items) mergeBlockedRow(row)
        state.blocked.pagesLoaded += 1
        after = page.nextCursor ?? undefined
        if (state.blocked.pagesLoaded >= MAX_PAGES && after) {
          state.blocked.capped = true
          break
        }
      } while (after)
      if (!request.isCurrent()) return
      state.blocked.phase = state.nodes.size === 0 ? 'empty' : 'ready'
    } catch (error) {
      if (!request.isCurrent() || isAbort(error)) return
      state.blocked.phase = 'error'
      state.blocked.errorMessage = presentError(error, '暂时无法加载阻塞关系')
      state.blocked.errorStatus = error instanceof CrewScopeApiError ? error.status : null
    } finally {
      request.finish()
    }
  }

  async function loadRelations(scope: WorkGraphQueryScope, workItemId: string, force = false): Promise<void> {
    const anchorChanged = scope.organizationId !== state.anchor.scope?.organizationId
      || scope.teamId !== state.anchor.scope?.teamId
      || scope.projectId !== state.anchor.scope?.projectId
      || workItemId !== state.anchor.workItemId
    if (!force && !anchorChanged && ['ready', 'empty'].includes(state.anchor.phase)) return
    const request = anchorRequests.begin('anchor')
    state.anchor.scope = scope
    state.anchor.workItemId = workItemId
    state.anchor.phase = 'loading'
    state.anchor.errorMessage = null
    state.anchor.errorStatus = null
    // Clear on every load: a retry must not render the previous item's edges as this item's.
    state.anchor.relations = null
    state.anchor.chain = null
    try {
      const relations = await gateway.listRelations(scope, workItemId, request.signal)
      if (!request.isCurrent()) return
      state.anchor.relations = relations
      state.anchor.phase = relations.edges.length === 0 ? 'empty' : 'ready'
    } catch (error) {
      if (!request.isCurrent() || isAbort(error)) return
      state.anchor.phase = 'error'
      state.anchor.errorMessage = presentError(error, '暂时无法加载工作项关系')
      state.anchor.errorStatus = error instanceof CrewScopeApiError ? error.status : null
    } finally {
      request.finish()
    }
  }

  async function loadBlockingChain(scope: WorkGraphQueryScope, workItemId: string, force = false): Promise<void> {
    await loadRelations(scope, workItemId, force)
    if (state.anchor.phase !== 'ready' && state.anchor.phase !== 'empty') return
    // The chain rides the anchor's epoch: a new anchor load voids this capture, so a stale
    // chain can never land on top of fresh adjacency facts.
    const owner = anchorRequests.capture()
    try {
      const chain = await gateway.listBlockingChain(scope, workItemId, undefined)
      if (!owner.isCurrent()) return
      state.anchor.chain = chain
    } catch (error) {
      if (!owner.isCurrent() || isAbort(error)) return
      // The chain is exploratory depth on top of the adjacency facts: failing it degrades the
      // detail panel to "chain unavailable" and never blanks the relations already rendered.
      state.anchor.chain = null
      state.anchor.errorMessage = state.anchor.errorMessage ?? presentError(error, '暂时无法加载阻塞链')
    }
  }

  async function runRelationCommand(dispatch: () => Promise<WorkGraphCommandReceipt>): Promise<WorkGraphCommandReceipt | null> {
    if (state.command.pending) return null
    state.command.pending = true
    state.command.errorMessage = null
    state.command.errorStatus = null
    try {
      const receipt = await dispatch()
      state.command.pending = false
      return receipt
    } catch (error) {
      state.command.pending = false
      if (error instanceof DOMException && error.name === 'AbortError') return null
      state.command.errorMessage = presentError(error, '暂时无法保存工作项关系')
      state.command.errorStatus = error instanceof CrewScopeApiError ? error.status : null
      return null
    }
  }

  async function createRelation(
    scope: WorkItemRelationCommandScope,
    input: CreateWorkItemRelationInput,
    idempotencyKey: string,
  ): Promise<WorkGraphCommandReceipt | null> {
    const receipt = await runRelationCommand(() => gateway.createRelation(scope, input, idempotencyKey))
    if (receipt) await refreshAfterCommand(scope)
    return receipt
  }

  async function removeRelation(
    scope: WorkItemRelationCommandScope,
    relationId: string,
    idempotencyKey: string,
  ): Promise<WorkGraphCommandReceipt | null> {
    const receipt = await runRelationCommand(() => gateway.removeRelation(scope, relationId, idempotencyKey))
    if (receipt) await refreshAfterCommand(scope)
    return receipt
  }

  /**
   * A 202 receipt means the command was accepted, not that the read model moved. Rather than
   * predicting the projection's timing with an optimistic edge, re-read the blocked aggregate and
   * whatever anchor the panel is showing; the F01 resource_changed signal covers the drawer in
   * between.
   */
  async function refreshAfterCommand(scope: WorkItemRelationCommandScope): Promise<void> {
    const anchorScope = state.anchor.scope
    const anchorId = state.anchor.workItemId
    await Promise.all([
      loadBlockedGraph(scope, blockedProjectFilter, true),
      anchorScope && anchorId ? loadRelations(anchorScope, anchorId, true) : Promise.resolve(),
    ])
  }

  function mergeBlockedRow(row: BlockedWorkItemRow): void {
    const existing = state.nodes.get(row.workItemId)
    state.nodes.set(row.workItemId, {
      id: row.workItemId,
      itemKey: row.itemKey,
      title: row.title,
      status: row.status,
      projectId: row.projectId,
      blockedCount: Math.max(row.blockerCount, existing?.blockedCount ?? 0),
    })
    if (row.blockerCount > row.blockers.length) state.blocked.windowTruncated = true
    for (const blocker of row.blockers) {
      if (!state.nodes.has(blocker.workItemId)) {
        state.nodes.set(blocker.workItemId, {
          id: blocker.workItemId,
          itemKey: null,
          title: blocker.title,
          status: blocker.status,
          projectId: blocker.projectId,
          blockedCount: 0,
        })
      }
      state.edges.set(`${blocker.workItemId}->${row.workItemId}`, {
        blockerId: blocker.workItemId,
        blockedId: row.workItemId,
      })
    }
  }

  function blockedCountOf(workItemId: string): number {
    return state.nodes.get(workItemId)?.blockedCount ?? 0
  }

  function exceedsNodeLimit(): boolean {
    return state.nodes.size > GRAPH_NODE_LIMIT
  }

  function reset(): void {
    commandIntents.clear()
    blockedRequests.invalidate()
    anchorRequests.invalidate()
    blockedScope = null
    blockedProjectFilter = undefined
    state.blocked.phase = 'idle'
    state.blocked.pagesLoaded = 0
    state.blocked.capped = false
    state.blocked.windowTruncated = false
    state.blocked.errorMessage = null
    state.blocked.errorStatus = null
    state.nodes.clear()
    state.edges.clear()
    state.anchor.phase = 'idle'
    state.anchor.scope = null
    state.anchor.workItemId = null
    state.anchor.relations = null
    state.anchor.chain = null
    state.anchor.errorMessage = null
    state.anchor.errorStatus = null
    state.command.pending = false
    state.command.errorMessage = null
    state.command.errorStatus = null
  }

  return {
    state: readonly(state) as Readonly<WorkGraphState>,
    loadBlockedGraph,
    loadRelations,
    loadBlockingChain,
    createRelation,
    removeRelation,
    blockedCountOf,
    exceedsNodeLimit,
    reset,
  }
}

export function installWorkGraphStore(app: App, gateway: WorkGraphGateway): WorkGraphStore {
  const store = createWorkGraphStore(gateway)
  app.provide(WORK_GRAPH_STORE, store)
  return store
}

export function useWorkGraphStore(): WorkGraphStore {
  const store = inject(WORK_GRAPH_STORE)
  if (!store) throw new Error('CrewScope WorkGraph Store is not installed')
  return store
}
