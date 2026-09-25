import { createCommandGateway } from '../../api/commandGateway'
import { acknowledgeCreation } from '../../api/creationRecovery'
import { secureId } from '../../api/secureId'
import {
  inject,
  reactive,
  readonly,
  type App,
  type InjectionKey,
} from 'vue'
import { CrewScopeApiError } from '../../api/client'
import { canonicalCommandInput, commandFailure, commandFailureMessage, createCommandIntents } from '../../api/commandIntent'
import { exclusionReason } from './bulk'
import type { WorkItemGateway } from './gateway'
import type {
  WorkItemBulkAssignmentRole,
  WorkItemBulkOutcome,
  WorkItemBulkProgress,
  WorkItemBulkRowResult,
} from './bulk'
import type {
  AddWorkItemCommentInput,
  CreateWorkItemInput,
  LinkWorkItemResourceInput,
  ResponsibilityAssignment,
  WorkItemAvailableTransition,
  WorkItemDetails,
  WorkItemScope,
  WorkItemStatus,
  WorkItemSummary,
  WorkItemTimelineEvent,
  WorkItemUndoOffer,
  WorkItemVersionConflict,
  WorkItemListFilter,
} from './types'

export type WorkItemPhase = 'idle' | 'loading' | 'ready' | 'empty' | 'error'
export type WorkItemDetailCommand = 'transition' | 'undo' | 'comment' | 'resource'

/**
 * How long a reversible transition stays undoable.
 *
 * Long enough to catch a misclick, short enough that the reverse edge is still the same decision
 * rather than a new one. The window is advisory only: the undo itself is an ordinary transition
 * command, so the server re-checks status, permission and version regardless of what this says.
 */
export const WORK_ITEM_UNDO_WINDOW_MS = 10_000
export type ResponsibilityCommand = 'owner' | 'executor' | 'gate-reviewer' | 'advisory-reviewer' | `release:${string}`

/**
 * What a row-level action did, so a surface can say something true about it.
 *
 * `refused` is a verdict — the server does not offer that action right now and said why — while
 * `failed` means the surface could not get far enough to ask, or asked and never learned the answer.
 * A version conflict lands in `failed` carrying the same sanitized sentence the drawer's conflict
 * panel shows, and the Store still records `versionConflict` and reloads, so the drawer's panel
 * keeps working when the drawer is the surface that asked.
 */
export type WorkItemActionResult =
  | { readonly status: 'executed' }
  | { readonly status: 'refused'; readonly message: string }
  | { readonly status: 'failed'; readonly message: string }

/**
 * The least a surface must know to execute one of the server's actions.
 *
 * `WorkItemSummary` satisfies this, and so does a purpose-built row built from a projection that
 * carries the same three facts — the personal WorkDesk being the one that does. Declaring the
 * contract here rather than accepting the full summary is what lets the home page reach the same
 * funnel instead of growing a second command path of its own.
 */
export interface WorkItemActionRow {
  readonly id: string
  readonly version?: number
  /** How the row names itself in a refusal. A projection without a key falls back to the id. */
  readonly key: string | null
  readonly availableActions: readonly WorkItemAvailableTransition[]
}

/** Batch rules and result shapes live in `./bulk`; the Store is their only executor. */

export type {
  WorkItemBulkAssignmentRole,
  WorkItemBulkOutcome,
  WorkItemBulkProgress,
  WorkItemBulkRowResult,
  WorkItemBulkSummary,
} from './bulk'

interface WorkItemState {
  phase: WorkItemPhase
  items: WorkItemSummary[]
  nextCursor: string | null
  loadingMore: boolean
  commandPending: boolean
  errorMessage: string | null
  commandErrorMessage: string | null
  detailPhase: WorkItemPhase
  selectedWorkItemId: string | null
  detail: WorkItemDetails | null
  detailErrorMessage: string | null
  detailCommandPending: WorkItemDetailCommand | null
  detailCommandErrorMessage: string | null
  versionConflict: WorkItemVersionConflict | null
  /**
   * The row whose action is in flight, when the command came from a card rather than the drawer.
   *
   * `detailCommandPending` says a command is running; it does not say which card started it, and on
   * a board of twenty cards that is the difference between disabling the one being executed and
   * disabling all twenty.
   */
  rowActionItemId: string | null
  /** The batch in flight, when the command came from a selection rather than a single row. */
  bulkPending: WorkItemBulkProgress | null
  availabilityPhase: WorkItemPhase
  availableTransitions: WorkItemAvailableTransition[]
  availabilityErrorMessage: string | null
  undoOffer: WorkItemUndoOffer | null
  responsibilityPhase: WorkItemPhase
  responsibilities: ResponsibilityAssignment[]
  responsibilityErrorMessage: string | null
  responsibilityCommandPending: ResponsibilityCommand | null
  responsibilityCommandErrorMessage: string | null
  timelinePhase: WorkItemPhase
  timeline: WorkItemTimelineEvent[]
  timelineNextCursor: string | null
  timelineLoadingMore: boolean
  timelineErrorMessage: string | null
}

export interface WorkItemStore {
  state: Readonly<WorkItemState>
  /**
   * Loads the first page for one filter. The legacy `load(scope, status)` shape still works: a bare
   * status is read as `{ status }`.
   */
  load(
    scope: WorkItemScope,
    filter?: WorkItemListFilter | WorkItemStatus,
    force?: boolean,
  ): Promise<void>
  loadMore(): Promise<void>
  create(input: CreateWorkItemInput): Promise<string | null>
  loadDetails(scope: WorkItemScope, workItemId: string, force?: boolean): Promise<void>
  closeDetails(): void
  transition(targetStatus: WorkItemStatus): Promise<void>
  transitionFromRow(
    scope: WorkItemScope,
    row: WorkItemActionRow,
    targetStatus: WorkItemStatus,
    ownsPage?: () => boolean,
  ): Promise<WorkItemActionResult>
  transitionRows(
    scope: WorkItemScope,
    rows: readonly WorkItemActionRow[],
    targetStatus: WorkItemStatus,
    ownsPage?: () => boolean,
  ): Promise<WorkItemBulkRowResult[]>
  assignRows(
    scope: WorkItemScope,
    rows: readonly WorkItemActionRow[],
    role: WorkItemBulkAssignmentRole,
    actorPrincipalId: string,
    ownsPage?: () => boolean,
  ): Promise<WorkItemBulkRowResult[]>
  undoTransition(): Promise<void>
  dismissUndoOffer(): void
  addComment(input: AddWorkItemCommentInput): Promise<void>
  linkResource(input: LinkWorkItemResourceInput): Promise<void>
  replaceOwner(actorPrincipalId: string): Promise<void>
  assignExecutor(actorPrincipalId: string): Promise<void>
  assignGateReviewer(actorPrincipalId: string): Promise<void>
  assignAdvisoryReviewer(actorPrincipalId: string): Promise<void>
  releaseResponsibility(assignment: ResponsibilityAssignment): Promise<void>
  loadTimelineMore(): Promise<void>
  reset(): void
}

export const WORK_ITEM_STORE: InjectionKey<WorkItemStore> = Symbol('crewscope-work-item-store')

export function createWorkItemStore(gateway: WorkItemGateway): WorkItemStore {
  const commandIntents = createCommandGateway(gateway, { transitionWorkItem: 4, addComment: 3, linkResource: 3, replaceOwner: 3, assignExecutor: 3, assignGateReviewer: 3, assignAdvisoryReviewer: 3, releaseResponsibility: 4 })
  gateway = commandIntents.gateway
  const state = reactive<WorkItemState>({
    phase: 'idle',
    items: [],
    nextCursor: null,
    loadingMore: false,
    commandPending: false,
    errorMessage: null,
    commandErrorMessage: null,
    detailPhase: 'idle',
    selectedWorkItemId: null,
    detail: null,
    detailErrorMessage: null,
    detailCommandPending: null,
    detailCommandErrorMessage: null,
    versionConflict: null,
    rowActionItemId: null,
    bulkPending: null,
    availabilityPhase: 'idle',
    availableTransitions: [],
    availabilityErrorMessage: null,
    undoOffer: null,
    responsibilityPhase: 'idle',
    responsibilities: [],
    responsibilityErrorMessage: null,
    responsibilityCommandPending: null,
    responsibilityCommandErrorMessage: null,
    timelinePhase: 'idle',
    timeline: [],
    timelineNextCursor: null,
    timelineLoadingMore: false,
    timelineErrorMessage: null,
  })

  let activeScope: WorkItemScope | null = null
  const createIntents = createCommandIntents<CreateWorkItemInput, Awaited<ReturnType<WorkItemGateway['createWorkItem']>>>()
  let activeCreate: Promise<string | null> | null = null
  let commandEpoch = 0
  let detailEpoch = 0
  let activeBatch: symbol | null = null
  const batchSteps = new Map<string, { run?: () => Promise<unknown>, done: boolean, uncertain: boolean }>()
  let activeFilter: WorkItemListFilter = {}
  let activeQueryKey: string | null = null
  let requestVersion = 0
  let detailRequestVersion = 0
  let activeDetailScope: WorkItemScope | null = null
  let activeDetailKey: string | null = null

  async function load(
    scope: WorkItemScope,
    filter?: WorkItemListFilter | WorkItemStatus,
    force = false,
  ): Promise<void> {
    const normalized: WorkItemListFilter =
      typeof filter === 'string' ? { status: filter } : (filter ?? {})
    const queryKey = `${scope.organizationId}:${scope.teamId}:${scope.projectId}:${canonicalFilterKey(normalized)}`
    if (!force && queryKey === activeQueryKey && ['ready', 'empty'].includes(state.phase)) return
    const version = ++requestVersion
    if (queryKey !== activeQueryKey) {
      commandEpoch += 1
      state.bulkPending = null
      state.rowActionItemId = null
      activeCreate = null
      state.commandPending = false
    }
    activeScope = { ...scope }
    activeFilter = normalized
    activeQueryKey = queryKey
    state.phase = 'loading'
    state.errorMessage = null
    state.commandErrorMessage = null
    state.loadingMore = false
    state.items = []
    state.nextCursor = null
    try {
      const page = await gateway.listWorkItems({ ...scope, ...normalized, limit: 50 })
      if (version !== requestVersion) return
      state.items = page.items
      state.nextCursor = page.nextCursor
      state.phase = page.items.length === 0 ? 'empty' : 'ready'
    } catch (error) {
      if (version !== requestVersion) return
      state.phase = 'error'
      state.errorMessage = presentError(error, '暂时无法加载工作项，请稍后重试')
    }
  }

  async function loadMore(): Promise<void> {
    if (!activeScope || !state.nextCursor || state.loadingMore) return
    const scope = { ...activeScope }
    const cursor = state.nextCursor
    const version = requestVersion
    state.loadingMore = true
    state.errorMessage = null
    try {
      const page = await gateway.listWorkItems({
        ...scope,
        ...activeFilter,
        after: cursor,
        limit: 50,
      })
      if (version !== requestVersion) return
      const knownIds = new Set(state.items.map(item => item.id))
      state.items.push(...page.items.filter(item => !knownIds.has(item.id)))
      state.nextCursor = page.nextCursor
      state.phase = state.items.length === 0 ? 'empty' : 'ready'
    } catch (error) {
      if (version === requestVersion) {
        state.errorMessage = presentError(error, '暂时无法加载更多工作项，请稍后重试')
      }
    } finally {
      if (version === requestVersion) state.loadingMore = false
    }
  }

  function create(input: CreateWorkItemInput): Promise<string | null> {
    if (activeCreate) return activeCreate
    const pending = runCreate(input)
    activeCreate = pending
    const clear = () => { if (activeCreate === pending) activeCreate = null }
    void pending.then(clear, clear)
    return pending
  }

  async function runCreate(input: CreateWorkItemInput): Promise<string | null> {
    if (!activeScope) throw new Error('No WorkProject is selected')
    const scope = { ...activeScope }
    const epoch = commandEpoch
    const queryKey = activeQueryKey
    const filter = activeFilter
    state.commandPending = true
    state.commandErrorMessage = null
    try {
      const receipt = await createIntents.execute({ ...scope, commandType: 'CREATE_WORK_ITEM' }, input,
        (snapshot, key) => gateway.createWorkItem(scope, snapshot, key))
      // A slow create Receipt must not navigate the collection back to an earlier WorkProject.
      if (commandEpoch !== epoch || activeQueryKey !== queryKey) return null
      if (!receipt.creation) throw new Error('Created WorkItem result is not available')
      const id = receipt.creation.resourceId
      await loadDetails(scope, id, true)
      if (commandEpoch !== epoch || activeQueryKey !== queryKey) return null
      await load(scope, filter, true)
      if (commandEpoch !== epoch || activeQueryKey !== queryKey) return null
      // A follow-up read failure must still navigate to the known committed ID, never turn
      // into a failed creation that offers a new POST. Keep recovery until detail is visible.
      if (state.detailPhase === 'ready') acknowledgeCreation(receipt.recoveryKey, scope.organizationId)
      return id
    } catch (error) {
      if (commandEpoch === epoch && activeQueryKey === queryKey) {
        state.commandErrorMessage = commandFailureMessage(error, '暂时无法创建工作项，请稍后重试')
      }
      throw error
    } finally {
      if (commandEpoch === epoch) state.commandPending = false
    }
  }

  async function loadDetails(
    scope: WorkItemScope,
    workItemId: string,
    force = false,
    preserveConflict = false,
  ): Promise<void> {
    const detailKey = `${scope.organizationId}:${scope.teamId}:${scope.projectId}:${workItemId}`
    if (!force && detailKey === activeDetailKey && state.detailPhase === 'ready') return
    const version = ++detailRequestVersion
    const changed = detailKey !== activeDetailKey
    activeDetailScope = { ...scope }
    activeDetailKey = detailKey
    state.selectedWorkItemId = workItemId
    state.detailPhase = 'loading'
    state.detailErrorMessage = null
    state.detailCommandErrorMessage = null
    if (!preserveConflict) state.versionConflict = null
    if (changed) {
      detailEpoch += 1
      state.detailCommandPending = null
      state.detail = null
      clearRelatedDetailState()
    }
    const availabilityRequest = loadAvailabilityFor(scope, workItemId, detailKey, version)
    const responsibilityRequest = loadResponsibilitiesFor(scope, workItemId, detailKey, version)
    const timelineRequest = loadTimelineFor(scope, workItemId, detailKey, version)
    try {
      const details = await gateway.getWorkItem(scope, workItemId)
      if (version !== detailRequestVersion || activeDetailKey !== detailKey) return
      state.detail = details
      state.detailPhase = 'ready'
      synchronizeCollectionItem(details.workItem)
    } catch (error) {
      if (version !== detailRequestVersion || activeDetailKey !== detailKey) return
      state.detailPhase = 'error'
      state.detailErrorMessage = presentError(error, '暂时无法加载工作项详情，请稍后重试')
    } finally {
      await Promise.all([availabilityRequest, responsibilityRequest, timelineRequest])
    }
  }

  function closeDetails(): void {
    activeBatch = null
    detailEpoch += 1
    detailRequestVersion += 1
    activeDetailScope = null
    activeDetailKey = null
    state.detailPhase = 'idle'
    state.selectedWorkItemId = null
    state.detail = null
    state.detailErrorMessage = null
    state.detailCommandPending = null
    state.detailCommandErrorMessage = null
    state.versionConflict = null
    state.rowActionItemId = null
    state.bulkPending = null
    clearRelatedDetailState()
  }

  /**
   * Loads the server's verdict on every edge leaving the current status.
   *
   * The generated state machine says which edges exist; only this says which of them this member
   * may execute right now. A failure here is not a detail failure: the drawer still renders, it
   * just has to say the action list is unavailable instead of guessing a wider one.
   */
  async function loadAvailabilityFor(
    scope: WorkItemScope,
    workItemId: string,
    detailKey: string,
    version: number,
  ): Promise<void> {
    state.availabilityPhase = 'loading'
    state.availabilityErrorMessage = null
    try {
      const transitions = await gateway.listAvailableTransitions(scope, workItemId)
      if (version !== detailRequestVersion || activeDetailKey !== detailKey) return
      state.availableTransitions = transitions
      state.availabilityPhase = transitions.length === 0 ? 'empty' : 'ready'
    } catch (error) {
      if (version !== detailRequestVersion || activeDetailKey !== detailKey) return
      state.availableTransitions = []
      state.availabilityPhase = 'error'
      state.availabilityErrorMessage = presentError(error, '暂时无法加载可执行动作，请稍后重试')
    }
  }

  async function transition(targetStatus: WorkItemStatus): Promise<void> {
    const edge = state.availableTransitions.find(candidate => candidate.targetStatus === targetStatus) ?? null
    await executeTransition('transition', targetStatus, edge, '暂时无法更新工作项状态，请稍后重试')
  }

  /**
   * Executes an action a list row or card offered, through the same path a board drop takes.
   *
   * A row action is not a second command surface: the card, the list row, the board and the home
   * page all end up here, and the drawer's own action goes through {@link transition} directly.
   *
   * The row's verdict is checked twice on purpose. The first check is the row's own copy of it and
   * only avoids a pointless detail read when the server already said no. The second, after the read,
   * is the authoritative one: a row can be a reload behind — the drawer may have moved the item, or
   * another member may have — and posting a command built on the stale verdict is exactly how a
   * member ends up looking at a version conflict they did not cause. The read also supplies the
   * version the command needs, which a row does not reliably carry.
   *
   * Only an explicit refusal is taken at face value. A row that carries no entry for the target says
   * nothing about it — an empty list is what a response that predates the field, or a payload that
   * lost it, looks like — and treating silence as "no" would let a degraded list response disable an
   * action the member can actually take. Silence therefore reads the authoritative copy instead.
   */
  async function transitionFromRow(
    scope: WorkItemScope,
    row: WorkItemActionRow,
    targetStatus: WorkItemStatus,
    ownsPage: () => boolean = () => true,
  ): Promise<WorkItemActionResult> {
    const offered = row.availableActions.find(candidate => candidate.targetStatus === targetStatus)
    if (offered && !offered.enabled) return refused(row, offered.reasonMessage)
    const epoch = commandEpoch
    const version = detailRequestVersion + 1
    if (state.rowActionItemId) return refused(row, '已有操作正在提交')
    state.rowActionItemId = row.id
    try {
      await loadDetails(scope, row.id, true)
      if (!ownsPage() || epoch !== commandEpoch || version !== detailRequestVersion) return refused(row, '操作范围已切换，未继续提交')
      if (state.detailPhase === 'error') {
        return {
          status: 'failed',
          message: state.detailErrorMessage ?? '暂时无法确认该工作项的当前状态，请稍后重试',
        }
      }
      const edge = state.availableTransitions.find(candidate => candidate.targetStatus === targetStatus) ?? null
      if (!edge?.enabled) return refused(row, edge?.reasonMessage ?? null)
      try {
        await transition(targetStatus)
      } catch {
        // The command path rethrows after classifying the failure — a version conflict, a permission
        // denial, a transport error — and has already written the sanitized wording. Reporting it as
        // a result rather than an exception is what lets a card say the same sentence the drawer
        // says, instead of an unhandled rejection from a click handler.
        return { status: 'failed', message: state.detailCommandErrorMessage ?? '暂时无法更新工作项状态，请稍后重试' }
      }
      return { status: 'executed' }
    } finally {
      if (epoch === commandEpoch && state.rowActionItemId === row.id) state.rowActionItemId = null
    }
  }

  function refused(row: WorkItemActionRow, message: string | null): WorkItemActionResult {
    return { status: 'refused', message: message ?? `${row.key ?? row.id} 当前不能执行该动作` }
  }

  /**
   * Runs one transition for every row of a selection, one row at a time.
   *
   * The batch deliberately does **not** go through {@link transitionFromRow}: that path loads the
   * row into the detail slot, which is the single subject the drawer renders. A batch of thirty
   * rows would therefore finish with the drawer showing whichever row happened to be last, and would
   * pay four reads and a whole collection reload per row for the privilege. The rules are not
   * duplicated by going direct — the verdict consulted here is the same published verdict the row
   * carries, and the command is the same command endpoint with its own fresh idempotency key.
   *
   * Sequential rather than parallel on purpose. Every command moves one aggregate and invalidates
   * the collection the member is reading; firing thirty at once makes the summary order
   * non-deterministic and turns one row's conflict into thirty. Order matters here — a member reads
   * the summary top to bottom.
   *
   * Nothing is left half-said: every row gets a result, `bulkPending` advances as rows finish, and
   * the collection is reloaded once at the end if anything actually changed.
   */
  async function transitionRows(
    scope: WorkItemScope,
    rows: readonly WorkItemActionRow[],
    targetStatus: WorkItemStatus,
    ownsPage: () => boolean = () => true,
  ): Promise<WorkItemBulkRowResult[]> {
    if (state.bulkPending) return []
    scope = { ...scope }
    rows = JSON.parse(canonicalCommandInput(rows)) as WorkItemActionRow[]
    const epoch = commandEpoch
    const owner = Symbol('transition-batch')
    activeBatch = owner
    const isCurrent = () => epoch === commandEpoch && activeBatch === owner && ownsPage()
    const queryKey = activeQueryKey
    const results: WorkItemBulkRowResult[] = []
    state.bulkPending = { completed: 0, total: rows.length }
    try {
      for (const row of rows) {
        if (!isCurrent()) break
        results.push(await transitionRow(scope, row, targetStatus, isCurrent))
        if (!isCurrent()) break
        state.bulkPending = { completed: results.length, total: rows.length }
      }
    } finally {
      if (activeBatch === owner) state.bulkPending = null
    }
    // A batch that executed nothing changed nothing, and reloading the collection would only make
    // the member wait for a page that cannot be different from the one they are looking at.
    const changed = results.some(result => result.outcome === 'executed')
    if (results.length === rows.length && results.every(result => result.outcome === 'executed' || result.outcome === 'excluded')) {
      for (const row of rows) batchSteps.delete(canonicalCommandInput([scope, row, targetStatus]))
    }
    if (isCurrent() && changed && activeQueryKey === queryKey) {
      await load(scope, { ...activeFilter }, true)
      if (isCurrent()) await refreshOpenDrawer(scope, rows)
    }
    return results
  }

  async function transitionRow(
    scope: WorkItemScope,
    row: WorkItemActionRow,
    targetStatus: WorkItemStatus,
    isCurrent: () => boolean,
  ): Promise<WorkItemBulkRowResult> {
    const reason = exclusionReason(row, targetStatus)
    if (reason) {
      // The row already knows it may not. Reporting it as excluded rather than posting a command
      // that comes back refused keeps the count honest: this row was never asked.
      return bulkResult(row, 'excluded', reason)
    }
    try {
      await runBatchStep([scope, row, targetStatus], isCurrent, async () => {
        const details = await gateway.getWorkItem(scope, row.id)
        const version = row.version ?? details.workItem.version
        const key = secureId()
        return () => gateway.transitionWorkItem(scope, row.id, targetStatus, version, key)
      })
      return bulkResult(row, 'executed', null)
    } catch (error) {
      return failedBulkResult(scope, row, error, '暂时无法更新该工作项状态，请稍后重试')
    }
  }

  /**
   * Assigns one responsibility across a selection, reusing the single-row commands.
   *
   * An Owner replacement carries the current assignment's id and version because the server checks
   * them; the row does not carry a responsibility chain, so the chain is read here rather than
   * guessed. That read is also what makes the conflict message truthful — a batch that skipped it
   * would overwrite an assignment another member made a moment ago without noticing.
   */
  async function assignRows(
    scope: WorkItemScope,
    rows: readonly WorkItemActionRow[],
    role: WorkItemBulkAssignmentRole,
    actorPrincipalId: string,
    ownsPage: () => boolean = () => true,
  ): Promise<WorkItemBulkRowResult[]> {
    if (state.bulkPending) return []
    scope = { ...scope }
    rows = JSON.parse(canonicalCommandInput(rows)) as WorkItemActionRow[]
    const epoch = commandEpoch
    const owner = Symbol('assignment-batch')
    activeBatch = owner
    const isCurrent = () => epoch === commandEpoch && activeBatch === owner && ownsPage()
    const queryKey = activeQueryKey
    const results: WorkItemBulkRowResult[] = []
    state.bulkPending = { completed: 0, total: rows.length }
    try {
      for (const row of rows) {
        if (!isCurrent()) break
        results.push(await assignRow(scope, row, role, actorPrincipalId, isCurrent))
        if (!isCurrent()) break
        state.bulkPending = { completed: results.length, total: rows.length }
      }
    } finally {
      if (activeBatch === owner) state.bulkPending = null
    }
    if (isCurrent() && activeQueryKey === queryKey) {
      await load(scope, { ...activeFilter }, true)
      if (isCurrent()) await refreshOpenDrawer(scope, rows)
    }
    if (results.length === rows.length && results.every(result => result.outcome === 'executed')) {
      for (const row of rows) batchSteps.delete(canonicalCommandInput([scope, row, role, actorPrincipalId]))
    }
    return results
  }

  async function assignRow(
    scope: WorkItemScope,
    row: WorkItemActionRow,
    role: WorkItemBulkAssignmentRole,
    actorPrincipalId: string,
    isCurrent: () => boolean,
  ): Promise<WorkItemBulkRowResult> {
    try {
      await runBatchStep([scope, row, role, actorPrincipalId], isCurrent, async () => {
        const key = secureId()
        if (role === 'EXECUTOR') return () => gateway.assignExecutor(scope, row.id, { actorPrincipalId }, key)
        const assignments = await gateway.listResponsibilities(scope, row.id)
        const owner = assignments.find(assignment => assignment.role === 'OWNER') ?? null
        const input = { actorPrincipalId, expectedAssignmentId: owner?.id ?? null, expectedVersion: owner?.version ?? null }
        return () => gateway.replaceOwner(scope, row.id, input, key)
      })
      return bulkResult(row, 'executed', null)
    } catch (error) {
      return failedBulkResult(scope, row, error, '暂时无法指派该工作项，请稍后重试', isResponsibilityConflict(error)
        // The chain moved under us; the wording matches the drawer's, which is also the wording a
        // single-row assignment shows for the same server verdict.
        ? '责任链已发生变化，最新责任已刷新，请确认后重试'
        : null)
    }
  }

  /**
   * Classifies one row's command failure.
   *
   * A 4xx that is not a version conflict is a verdict: the command definitely did not run, and the
   * server's own sentence explains why. Anything else — a conflict, a timeout, a dropped
   * connection — is `failed`, because the member cannot know from here whether the command landed.
   * Reading a lost command as a rule they can work around is the one thing a batch must not do.
   */
  async function runBatchStep(input: unknown, isCurrent: () => boolean, prepare: () => Promise<() => Promise<unknown>>): Promise<void> {
    const fingerprint = canonicalCommandInput(input)
    let step = batchSteps.get(fingerprint)
    if (step?.done) return
    if (!step) {
      if (batchSteps.size >= 100) throw new Error('待确认批量操作过多，请先核实原操作')
      step = { done: false, uncertain: false }
      batchSteps.set(fingerprint, step)
    }
    try {
      step.run ??= await prepare()
      if (!isCurrent()) throw new DOMException('操作范围已切换，未继续提交', 'AbortError')
      await step.run()
      step.done = true
    } catch (error) {
      if (commandFailure(error) === 'unknown') step.uncertain = true
      if (!step.uncertain) batchSteps.delete(fingerprint)
      throw error
    }
  }

  async function failedBulkResult(
    scope: WorkItemScope,
    row: WorkItemActionRow,
    error: unknown,
    fallback: string,
    conflictMessage: string | null = null,
  ): Promise<WorkItemBulkRowResult> {
    if (commandFailure(error) === 'unknown') return bulkResult(row, 'failed', commandFailureMessage(error, fallback))
    if (isVersionConflict(error)) {
      // Read the row back before naming its state: the summary says "current facts", so it has to
      // have asked for them. A failed re-read is not worth failing the row twice over — the row is
      // already reported as a conflict, and the collection reload at the end covers the rest.
      await gateway.getWorkItem(scope, row.id).catch(() => null)
      return bulkResult(
        row,
        'failed',
        '工作项已被其他成员更新，已重新读取当前状态，请确认后重试',
      )
    }
    if (error instanceof CrewScopeApiError && error.status >= 400 && error.status < 500) {
      return bulkResult(row, 'refused', conflictMessage ?? error.envelope.message)
    }
    return bulkResult(row, 'failed', presentError(error, fallback))
  }

  /**
   * Re-reads the drawer when it is showing one of the rows the batch just moved.
   *
   * Reloading the collection does not touch the drawer, which keeps its own copy of the version and
   * of the published verdicts. Left alone it would post its next command with a version the batch
   * already superseded, and the member would be shown a conflict on a row they had not touched —
   * caused by their own batch. That is also why every row of the batch is refreshed and not just the
   * ones that executed: a row that failed is precisely the row whose copy was already out of date.
   */
  async function refreshOpenDrawer(
    scope: WorkItemScope,
    rows: readonly WorkItemActionRow[],
  ): Promise<void> {
    const selected = state.selectedWorkItemId
    if (selected && rows.some(row => row.id === selected) && activeDetailKey) {
      await loadDetails(scope, selected, true)
    }
  }

  function bulkResult(
    row: WorkItemActionRow,
    outcome: WorkItemBulkOutcome,
    message: string | null,
  ): WorkItemBulkRowResult {
    return {
      id: row.id,
      key: row.key ?? row.id,
      title: state.items.find(item => item.id === row.id)?.title ?? row.key ?? row.id,
      outcome,
      message,
    }
  }
  /**
   * Undo is an ordinary execution of the reverse edge, never a compensating mechanism.
   *
   * It therefore emits the same events, writes the same Audit entry and passes the same policy and
   * version checks as any other transition. The only thing the window buys the member is that the
   * reverse edge is offered without hunting for it, and only while it is still the same decision.
   */
  async function undoTransition(): Promise<void> {
    const offer = state.undoOffer
    if (!offer) return
    const context = requireDetailContext()
    if (offer.workItemId !== context.workItemId) return
    state.undoOffer = null
    if (Date.now() > offer.expiresAt) {
      state.detailCommandErrorMessage = '撤销时限已过，请从动作列表中选择目标状态'
      return
    }
    const reverse = state.availableTransitions.find(
      candidate => candidate.targetStatus === offer.fromStatus && candidate.enabled,
    )
    if (!reverse) {
      state.detailCommandErrorMessage = '回退动作当前不可执行，请从动作列表中选择目标状态'
      return
    }
    await executeTransition('undo', offer.fromStatus, null, '暂时无法撤销该操作，请稍后重试')
  }

  function dismissUndoOffer(): void {
    state.undoOffer = null
  }

  /**
   * Runs one transition command and, when the executed edge is reversible, opens the undo window.
   *
   * The offer is written after the reload so it carries the version the reverse command will need;
   * an undo never reuses the pre-transition version. Undoing an undo is deliberately not offered —
   * the member is back where they started, and a second offer would only invite ping-ponging.
   */
  async function executeTransition(
    kind: 'transition' | 'undo',
    targetStatus: WorkItemStatus,
    edge: WorkItemAvailableTransition | null,
    fallbackMessage: string,
  ): Promise<void> {
    const context = requireDetailContext()
    const fromStatus = state.detail?.workItem.status ?? null
    state.detailCommandPending = kind
    state.detailCommandErrorMessage = null
    state.versionConflict = null
    state.undoOffer = null
    try {
      await gateway.transitionWorkItem(
        context.scope,
        context.workItemId,
        targetStatus,
        context.version,
        secureId(),
      )
      if (!context.isCurrent()) return
      await Promise.all([
        loadDetails(context.scope, context.workItemId, true),
        load(context.scope, { ...activeFilter }, true),
      ])
      if (!context.isCurrent()) return
      if (kind === 'transition' && edge?.reversible && fromStatus && state.detail) {
        state.undoOffer = {
          workItemId: context.workItemId,
          actionLabel: edge.label,
          fromStatus,
          toStatus: targetStatus,
          expectedVersion: state.detail.workItem.version,
          expiresAt: Date.now() + WORK_ITEM_UNDO_WINDOW_MS,
        }
      }
    } catch (error) {
      if (context.isCurrent() && isVersionConflict(error)) {
        state.versionConflict = {
          attemptedVersion: context.version,
          currentVersion: error.envelope.currentVersion,
        }
        await loadDetails(context.scope, context.workItemId, true, true)
        if (context.isCurrent()) {
          state.detailCommandErrorMessage = '工作项已被其他成员更新，详情已刷新，请确认后重试'
        }
      } else if (context.isCurrent()) {
        state.detailCommandErrorMessage = presentError(error, fallbackMessage)
      }
      throw error
    } finally {
      if (context.isCurrent()) state.detailCommandPending = null
    }
  }

  async function addComment(input: AddWorkItemCommentInput): Promise<void> {
    const context = requireDetailContext()
    await runCollaborationCommand(
      'comment',
      context,
      () => gateway.addComment(context.scope, context.workItemId, input, secureId()),
      '暂时无法添加评论，请稍后重试',
    )
  }

  async function linkResource(input: LinkWorkItemResourceInput): Promise<void> {
    const context = requireDetailContext()
    await runCollaborationCommand(
      'resource',
      context,
      () => gateway.linkResource(context.scope, context.workItemId, input, secureId()),
      '暂时无法关联资源，请稍后重试',
    )
  }

  async function replaceOwner(actorPrincipalId: string): Promise<void> {
    const context = requireDetailContext()
    const owner = state.responsibilities.find(assignment => assignment.role === 'OWNER') ?? null
    await runResponsibilityCommand(
      'owner',
      context,
      () => gateway.replaceOwner(context.scope, context.workItemId, {
        actorPrincipalId,
        expectedAssignmentId: owner?.id ?? null,
        expectedVersion: owner?.version ?? null,
      }, secureId()),
      '暂时无法替换 Owner，请稍后重试',
    )
  }

  async function assignExecutor(actorPrincipalId: string): Promise<void> {
    const context = requireDetailContext()
    await runResponsibilityCommand(
      'executor',
      context,
      () => gateway.assignExecutor(context.scope, context.workItemId, { actorPrincipalId }, secureId()),
      '暂时无法分配 Executor，请稍后重试',
    )
  }

  async function assignGateReviewer(actorPrincipalId: string): Promise<void> {
    const context = requireDetailContext()
    await runResponsibilityCommand(
      'gate-reviewer',
      context,
      () => gateway.assignGateReviewer(context.scope, context.workItemId, { actorPrincipalId }, secureId()),
      '候选人未通过 Gate Reviewer 资格校验，请调整后重试',
    )
  }

  async function assignAdvisoryReviewer(actorPrincipalId: string): Promise<void> {
    const context = requireDetailContext()
    await runResponsibilityCommand(
      'advisory-reviewer',
      context,
      () => gateway.assignAdvisoryReviewer(context.scope, context.workItemId, { actorPrincipalId }, secureId()),
      '暂时无法分配 Advisory Reviewer，请稍后重试',
    )
  }

  async function releaseResponsibility(assignment: ResponsibilityAssignment): Promise<void> {
    if (assignment.role === 'OWNER') throw new Error('Owner must be replaced instead of released')
    const context = requireDetailContext()
    await runResponsibilityCommand(
      `release:${assignment.id}`,
      context,
      () => gateway.releaseResponsibility(
        context.scope,
        context.workItemId,
        assignment.id,
        assignment.version,
        secureId(),
      ),
      '暂时无法释放该责任，请稍后重试',
    )
  }

  async function runResponsibilityCommand(
    kind: ResponsibilityCommand,
    context: DetailContext,
    action: () => Promise<unknown>,
    fallback: string,
  ): Promise<void> {
    state.responsibilityCommandPending = kind
    state.responsibilityCommandErrorMessage = null
    try {
      await action()
      if (context.isCurrent()) {
        await Promise.all([
          refreshResponsibilities(context),
          refreshTimeline(context),
        ])
      }
    } catch (error) {
      if (context.isCurrent()) {
        // The server owns eligibility and concurrency decisions; refresh before presenting a retry.
        await refreshResponsibilities(context)
        if (context.isCurrent()) {
          state.responsibilityCommandErrorMessage = isResponsibilityConflict(error)
            ? '责任链已发生变化，最新责任已刷新，请确认后重试'
            : presentError(error, fallback)
        }
      }
      throw error
    } finally {
      if (context.isCurrent()) state.responsibilityCommandPending = null
    }
  }

  async function loadResponsibilitiesFor(
    scope: WorkItemScope,
    workItemId: string,
    detailKey: string,
    version: number,
  ): Promise<void> {
    state.responsibilityPhase = 'loading'
    state.responsibilityErrorMessage = null
    try {
      const assignments = await gateway.listResponsibilities(scope, workItemId)
      if (version !== detailRequestVersion || activeDetailKey !== detailKey) return
      state.responsibilities = assignments
      state.responsibilityPhase = assignments.length === 0 ? 'empty' : 'ready'
    } catch (error) {
      if (version !== detailRequestVersion || activeDetailKey !== detailKey) return
      state.responsibilityPhase = 'error'
      state.responsibilityErrorMessage = presentError(error, '暂时无法加载责任链，请稍后重试')
    }
  }

  async function loadTimelineFor(
    scope: WorkItemScope,
    workItemId: string,
    detailKey: string,
    version: number,
  ): Promise<void> {
    state.timelinePhase = 'loading'
    state.timelineErrorMessage = null
    state.timelineLoadingMore = false
    try {
      const page = await gateway.listTimeline(scope, workItemId, undefined, 50)
      if (version !== detailRequestVersion || activeDetailKey !== detailKey) return
      state.timeline = deduplicateTimeline(page.items)
      state.timelineNextCursor = page.nextCursor
      state.timelinePhase = state.timeline.length === 0 ? 'empty' : 'ready'
    } catch (error) {
      if (version !== detailRequestVersion || activeDetailKey !== detailKey) return
      state.timelinePhase = 'error'
      state.timelineErrorMessage = presentError(error, '暂时无法加载工作项时间线，请稍后重试')
    }
  }

  async function loadTimelineMore(): Promise<void> {
    if (!activeDetailScope || !activeDetailKey || !state.detail || !state.timelineNextCursor || state.timelineLoadingMore) return
    const scope = { ...activeDetailScope }
    const workItemId = state.detail.workItem.id
    const detailKey = activeDetailKey
    const version = detailRequestVersion
    const cursor = state.timelineNextCursor
    state.timelineLoadingMore = true
    state.timelineErrorMessage = null
    try {
      const page = await gateway.listTimeline(scope, workItemId, cursor, 50)
      if (version !== detailRequestVersion || activeDetailKey !== detailKey) return
      state.timeline = deduplicateTimeline([...state.timeline, ...page.items])
      state.timelineNextCursor = page.nextCursor
      state.timelinePhase = state.timeline.length === 0 ? 'empty' : 'ready'
    } catch (error) {
      if (version === detailRequestVersion && activeDetailKey === detailKey) {
        state.timelineErrorMessage = presentError(error, '暂时无法加载更早的时间线，请稍后重试')
      }
    } finally {
      if (version === detailRequestVersion && activeDetailKey === detailKey) state.timelineLoadingMore = false
    }
  }

  async function refreshResponsibilities(context: DetailContext): Promise<void> {
    await loadResponsibilitiesFor(
      context.scope,
      context.workItemId,
      context.detailKey,
      detailRequestVersion,
    )
  }

  async function refreshTimeline(context: DetailContext): Promise<void> {
    await loadTimelineFor(
      context.scope,
      context.workItemId,
      context.detailKey,
      detailRequestVersion,
    )
  }

  function clearRelatedDetailState(): void {
    state.availabilityPhase = 'idle'
    state.availableTransitions = []
    state.availabilityErrorMessage = null
    state.undoOffer = null
    state.responsibilityPhase = 'idle'
    state.responsibilities = []
    state.responsibilityErrorMessage = null
    state.responsibilityCommandPending = null
    state.responsibilityCommandErrorMessage = null
    state.timelinePhase = 'idle'
    state.timeline = []
    state.timelineNextCursor = null
    state.timelineLoadingMore = false
    state.timelineErrorMessage = null
  }

  async function runCollaborationCommand(
    kind: WorkItemDetailCommand,
    context: DetailContext,
    action: () => Promise<unknown>,
    fallback: string,
  ): Promise<void> {
    state.detailCommandPending = kind
    state.detailCommandErrorMessage = null
    state.versionConflict = null
    try {
      await action()
      if (context.isCurrent()) {
        await loadDetails(context.scope, context.workItemId, true)
      }
    } catch (error) {
      if (context.isCurrent()) {
        state.detailCommandErrorMessage = presentError(error, fallback)
      }
      throw error
    } finally {
      if (context.isCurrent()) state.detailCommandPending = null
    }
  }

  function requireDetailContext(): DetailContext {
    if (!activeDetailScope || !activeDetailKey || !state.detail) {
      throw new Error('No WorkItem detail is selected')
    }
    const epoch = detailEpoch
    const key = activeDetailKey
    return {
      isCurrent: () => epoch === detailEpoch && key === activeDetailKey,
      scope: { ...activeDetailScope },
      detailKey: activeDetailKey,
      workItemId: state.detail.workItem.id,
      version: state.detail.workItem.version,
    }
  }

  function synchronizeCollectionItem(item: WorkItemSummary): void {
    const index = state.items.findIndex(candidate => candidate.id === item.id)
    if (index >= 0) state.items[index] = item
  }

  function reset(): void {
    batchSteps.clear()
    commandIntents.clear()
    commandEpoch += 1
    activeCreate = null
    createIntents.clear()
    requestVersion += 1
    activeScope = null
    activeFilter = {}
    activeQueryKey = null
    state.phase = 'idle'
    state.items = []
    state.nextCursor = null
    state.loadingMore = false
    state.commandPending = false
    state.errorMessage = null
    state.commandErrorMessage = null
    closeDetails()
  }

  return {
    state: readonly(state) as Readonly<WorkItemState>,
    load,
    loadMore,
    create,
    loadDetails,
    closeDetails,
    transition,
    transitionFromRow,
    transitionRows,
    assignRows,
    undoTransition,
    dismissUndoOffer,
    addComment,
    linkResource,
    replaceOwner,
    assignExecutor,
    assignGateReviewer,
    assignAdvisoryReviewer,
    releaseResponsibility,
    loadTimelineMore,
    reset,
  }
}

export function installWorkItemStore(app: App, gateway: WorkItemGateway): WorkItemStore {
  const store = createWorkItemStore(gateway)
  app.provide(WORK_ITEM_STORE, store)
  return store
}

export function useWorkItemStore(): WorkItemStore {
  const store = inject(WORK_ITEM_STORE)
  if (!store) throw new Error('CrewScope WorkItem Store is not installed')
  return store
}

function presentError(error: unknown, fallback: string): string {
  if (error instanceof CrewScopeApiError) return error.envelope.message
  return fallback
}

/**
 * One stable string per filter value set, so any change to any dimension restarts the page from its
 * beginning — a continuation cursor is only valid for the filter that minted it. Arrays are sorted
 * before joining: `{ type: ['FEATURE','BUG'] }` and `{ type: ['BUG','FEATURE'] }` are the same query.
 */
function canonicalFilterKey(filter: WorkItemListFilter): string {
  return [
    filter.status ?? 'ALL',
    filter.type ? [...filter.type].sort().join(',') : '',
    filter.priority ? [...filter.priority].sort().join(',') : '',
    filter.responsibilityRole ?? '',
    filter.sort ?? '',
  ].join('|')
}

function isVersionConflict(error: unknown): error is CrewScopeApiError {
  return error instanceof CrewScopeApiError
    && error.status === 409
    && error.envelope.code === 'optimistic_lock_conflict'
}

function isResponsibilityConflict(error: unknown): error is CrewScopeApiError {
  return error instanceof CrewScopeApiError
    && error.status === 409
    && ['responsibility_conflict', 'responsibility_version_conflict'].includes(error.envelope.code)
}

function deduplicateTimeline(items: WorkItemTimelineEvent[]): WorkItemTimelineEvent[] {
  const known = new Set<string>()
  return items.filter(event => {
    if (known.has(event.eventId)) return false
    known.add(event.eventId)
    return true
  })
}

interface DetailContext {
  isCurrent: () => boolean
  scope: WorkItemScope
  detailKey: string
  workItemId: string
  version: number
}
