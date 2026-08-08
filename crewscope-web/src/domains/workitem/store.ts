import {
  inject,
  reactive,
  readonly,
  type App,
  type InjectionKey,
} from 'vue'
import { CrewScopeApiError } from '../../api/client'
import type { WorkItemGateway } from './gateway'
import type {
  AddWorkItemCommentInput,
  CreateWorkItemInput,
  LinkWorkItemResourceInput,
  ResponsibilityAssignment,
  WorkItemDetails,
  WorkItemScope,
  WorkItemStatus,
  WorkItemSummary,
  WorkItemTimelineEvent,
  WorkItemVersionConflict,
} from './types'

export type WorkItemPhase = 'idle' | 'loading' | 'ready' | 'empty' | 'error'
export type WorkItemDetailCommand = 'transition' | 'comment' | 'resource'
export type ResponsibilityCommand = 'owner' | 'executor' | 'gate-reviewer' | 'advisory-reviewer' | `release:${string}`

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
  load(scope: WorkItemScope, status?: WorkItemStatus, force?: boolean): Promise<void>
  loadMore(): Promise<void>
  create(input: CreateWorkItemInput): Promise<void>
  loadDetails(scope: WorkItemScope, workItemId: string, force?: boolean): Promise<void>
  closeDetails(): void
  transition(targetStatus: WorkItemStatus): Promise<void>
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
  let activeStatus: WorkItemStatus | undefined
  let activeQueryKey: string | null = null
  let requestVersion = 0
  let detailRequestVersion = 0
  let activeDetailScope: WorkItemScope | null = null
  let activeDetailKey: string | null = null

  async function load(scope: WorkItemScope, status?: WorkItemStatus, force = false): Promise<void> {
    const queryKey = `${scope.organizationId}:${scope.teamId}:${scope.projectId}:${status ?? 'ALL'}`
    if (!force && queryKey === activeQueryKey && ['ready', 'empty'].includes(state.phase)) return
    const version = ++requestVersion
    activeScope = { ...scope }
    activeStatus = status
    activeQueryKey = queryKey
    state.phase = 'loading'
    state.errorMessage = null
    state.commandErrorMessage = null
    state.loadingMore = false
    state.items = []
    state.nextCursor = null
    try {
      const page = await gateway.listWorkItems({ ...scope, status, limit: 50 })
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
      const page = await gateway.listWorkItems({ ...scope, status: activeStatus, after: cursor, limit: 50 })
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

  async function create(input: CreateWorkItemInput): Promise<void> {
    if (!activeScope) throw new Error('No WorkProject is selected')
    const scope = { ...activeScope }
    const queryKey = activeQueryKey
    const status = activeStatus
    state.commandPending = true
    state.commandErrorMessage = null
    try {
      await gateway.createWorkItem(scope, input, crypto.randomUUID())
      // A slow create Receipt must not navigate the collection back to an earlier WorkProject.
      if (activeQueryKey === queryKey) await load(scope, status, true)
    } catch (error) {
      if (activeQueryKey === queryKey) {
        state.commandErrorMessage = presentError(error, '暂时无法创建工作项，请稍后重试')
      }
      throw error
    } finally {
      state.commandPending = false
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
      state.detail = null
      clearRelatedDetailState()
    }
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
      await Promise.all([responsibilityRequest, timelineRequest])
    }
  }

  function closeDetails(): void {
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
    clearRelatedDetailState()
  }

  async function transition(targetStatus: WorkItemStatus): Promise<void> {
    const context = requireDetailContext()
    state.detailCommandPending = 'transition'
    state.detailCommandErrorMessage = null
    state.versionConflict = null
    try {
      await gateway.transitionWorkItem(
        context.scope,
        context.workItemId,
        targetStatus,
        context.version,
        crypto.randomUUID(),
      )
      if (activeDetailKey !== context.detailKey) return
      await Promise.all([
        loadDetails(context.scope, context.workItemId, true),
        load(context.scope, activeStatus, true),
      ])
    } catch (error) {
      if (activeDetailKey === context.detailKey && isVersionConflict(error)) {
        state.versionConflict = {
          attemptedVersion: context.version,
          currentVersion: error.envelope.currentVersion,
        }
        await loadDetails(context.scope, context.workItemId, true, true)
        if (activeDetailKey === context.detailKey) {
          state.detailCommandErrorMessage = '工作项已被其他成员更新，详情已刷新，请确认后重试'
        }
      } else if (activeDetailKey === context.detailKey) {
        state.detailCommandErrorMessage = presentError(error, '暂时无法更新工作项状态，请稍后重试')
      }
      throw error
    } finally {
      if (activeDetailKey === context.detailKey) state.detailCommandPending = null
    }
  }

  async function addComment(input: AddWorkItemCommentInput): Promise<void> {
    const context = requireDetailContext()
    await runCollaborationCommand(
      'comment',
      context,
      () => gateway.addComment(context.scope, context.workItemId, input, crypto.randomUUID()),
      '暂时无法添加评论，请稍后重试',
    )
  }

  async function linkResource(input: LinkWorkItemResourceInput): Promise<void> {
    const context = requireDetailContext()
    await runCollaborationCommand(
      'resource',
      context,
      () => gateway.linkResource(context.scope, context.workItemId, input, crypto.randomUUID()),
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
      }, crypto.randomUUID()),
      '暂时无法替换 Owner，请稍后重试',
    )
  }

  async function assignExecutor(actorPrincipalId: string): Promise<void> {
    const context = requireDetailContext()
    await runResponsibilityCommand(
      'executor',
      context,
      () => gateway.assignExecutor(context.scope, context.workItemId, { actorPrincipalId }, crypto.randomUUID()),
      '暂时无法分配 Executor，请稍后重试',
    )
  }

  async function assignGateReviewer(actorPrincipalId: string): Promise<void> {
    const context = requireDetailContext()
    await runResponsibilityCommand(
      'gate-reviewer',
      context,
      () => gateway.assignGateReviewer(context.scope, context.workItemId, { actorPrincipalId }, crypto.randomUUID()),
      '候选人未通过 Gate Reviewer 资格校验，请调整后重试',
    )
  }

  async function assignAdvisoryReviewer(actorPrincipalId: string): Promise<void> {
    const context = requireDetailContext()
    await runResponsibilityCommand(
      'advisory-reviewer',
      context,
      () => gateway.assignAdvisoryReviewer(context.scope, context.workItemId, { actorPrincipalId }, crypto.randomUUID()),
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
        crypto.randomUUID(),
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
      if (activeDetailKey === context.detailKey) {
        await Promise.all([
          refreshResponsibilities(context),
          refreshTimeline(context),
        ])
      }
    } catch (error) {
      if (activeDetailKey === context.detailKey) {
        // The server owns eligibility and concurrency decisions; refresh before presenting a retry.
        await refreshResponsibilities(context)
        if (activeDetailKey === context.detailKey) {
          state.responsibilityCommandErrorMessage = isResponsibilityConflict(error)
            ? '责任链已发生变化，最新责任已刷新，请确认后重试'
            : presentError(error, fallback)
        }
      }
      throw error
    } finally {
      if (activeDetailKey === context.detailKey) state.responsibilityCommandPending = null
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
      if (activeDetailKey === context.detailKey) {
        await loadDetails(context.scope, context.workItemId, true)
      }
    } catch (error) {
      if (activeDetailKey === context.detailKey) {
        state.detailCommandErrorMessage = presentError(error, fallback)
      }
      throw error
    } finally {
      if (activeDetailKey === context.detailKey) state.detailCommandPending = null
    }
  }

  function requireDetailContext(): DetailContext {
    if (!activeDetailScope || !activeDetailKey || !state.detail) {
      throw new Error('No WorkItem detail is selected')
    }
    return {
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
    requestVersion += 1
    activeScope = null
    activeStatus = undefined
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
  scope: WorkItemScope
  detailKey: string
  workItemId: string
  version: number
}
