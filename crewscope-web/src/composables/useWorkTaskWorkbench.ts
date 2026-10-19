import { computed, ref, watch, type ComputedRef } from 'vue'
import type { RouteLocationNormalizedLoaded, Router } from 'vue-router'
import { queryValue } from '../utils/queryParams'
import type { usePageRequestScope } from './usePageRequestScope'
import { useTaskStore } from '../domains/task/store'
import { parseExecutionSelection, resolveExecutionSelection } from '../domains/task/executionSelection'
import type { MemberTaskCommandOperation } from '../domains/task/types'
import { quotaAdmissionProblem } from '../domains/quota/presentation'
import {
  codingRouteMatchesScope,
  codingRouteSelection,
  isRestorableCodingRoute,
  withCodingRoute,
} from '../domains/coding/route'
import { useCodingStore } from '../domains/coding/store'
import type { CodingScope } from '../domains/coding/types'
import { reviewAttemptKey, reviewDetailKey, useReviewStore } from '../domains/review/store'
import type { ReviewDecisionInput } from '../domains/review/types'
import { deliveryAttemptKey, deliveryBundleKey, useDeliveryStore } from '../domains/delivery/store'
import { useInjectionStore, viewKey } from '../domains/injection/store'
import type { TeamSummary } from '../domains/scope/types'
import type { AuthenticatedPrincipal } from '../app/auth'
import { resolveWorkspaceAction, type WorkspaceAction } from '../domains/task/workspaceAction'

type PageRequests = ReturnType<typeof usePageRequestScope>

/** The page-owned workbench object the TaskDetailDrawer subscribes to (M12b-F05): projections and
 *  command wrappers stay page-lifecycle; the drawer reads them, it never owns them. */
export type TaskWorkbench = ReturnType<typeof useWorkTaskWorkbench>

/**
 * The Task workbench of WorkPage (M12b-F05): the selected-execution state machine, the resource
 * projections the TaskDetailDrawer renders, the three synchronizers (Coding studio, Review and
 * Delivery workbenches) and their watches, and the thin command wrappers around the four stores.
 * Moved verbatim from the page — the page keeps routing, focus orchestration and the collection
 * views; everything anchored on `route.query.task` lives here.
 */
export function useWorkTaskWorkbench(options: {
  route: RouteLocationNormalizedLoaded
  router: Router
  pageRequests: PageRequests
  principal: AuthenticatedPrincipal | undefined
  team: ComputedRef<TeamSummary | null>
  codingScope: ComputedRef<CodingScope | null>
  canControlTask: ComputedRef<boolean>
}) {
  const { route, router, pageRequests, principal, team, codingScope, canControlTask } = options
  const taskStore = useTaskStore()
  const codingStore = useCodingStore()
  const reviewStore = useReviewStore()
  const deliveryStore = useDeliveryStore()
  const injectionStore = useInjectionStore()

  const selectedTaskExecutionId = ref<string | null>(null)
  /** `taskExecution=` and `attempt=` name different executions: both spellings present, neither chosen. */
  const executionConflict = computed(() => {
    const parse = parseExecutionSelection(route.query)
    return parse.conflict && parse.unified && parse.alias
      ? { unified: parse.unified, alias: parse.alias }
      : null
  })

  const selectedRuntimeResource = computed(() => {
    const taskId = taskStore.state.selectedTaskId
    const executionId = selectedTaskExecutionId.value
    return taskId && executionId ? taskStore.state.runtimeFacts[`${taskId}:${executionId}`] ?? null : null
  })
  const selectedCodingAttemptResource = computed(() => {
    const taskId = taskStore.state.selectedTaskId
    const executionId = codingStore.state.selectedExecutionId
    return taskId && executionId ? codingStore.state.attempts[`${taskId}:${executionId}`] ?? null : null
  })
  const selectedCodingCommandsResource = computed(() => {
    const taskId = taskStore.state.selectedTaskId
    const executionId = codingStore.state.selectedExecutionId
    return taskId && executionId ? codingStore.state.commands[`${taskId}:${executionId}`] ?? null : null
  })
  const selectedCodingTestsResource = computed(() => {
    const taskId = taskStore.state.selectedTaskId
    const executionId = codingStore.state.selectedExecutionId
    return taskId && executionId ? codingStore.state.testEvidence[`${taskId}:${executionId}`] ?? null : null
  })
  const selectedCodingPatchResource = computed(() => {
    const taskId = taskStore.state.selectedTaskId
    const executionId = codingStore.state.selectedExecutionId
    return taskId && executionId ? codingStore.state.patches[`${taskId}:${executionId}`] ?? null : null
  })
  const selectedReviewCoordinates = computed(() => {
    const taskId = taskStore.state.selectedTaskId
    const executionId = codingStore.state.selectedExecutionId
    return taskId && executionId ? { taskId, executionId } : null
  })
  const selectedReviewListResource = computed(() => {
    const coordinates = selectedReviewCoordinates.value
    return coordinates ? reviewStore.state.lists[reviewAttemptKey(coordinates)] ?? null : null
  })
  const selectedReviewDetailResource = computed(() => {
    const coordinates = selectedReviewCoordinates.value
    const reviewRequestId = reviewStore.state.selectedReviewRequestId
    return coordinates && reviewRequestId
      ? reviewStore.state.details[reviewDetailKey(coordinates, reviewRequestId)] ?? null
      : null
  })
  const selectedReviewCommentsResource = computed(() => {
    const coordinates = selectedReviewCoordinates.value
    const reviewRequestId = reviewStore.state.selectedReviewRequestId
    return coordinates && reviewRequestId
      ? reviewStore.state.comments[reviewDetailKey(coordinates, reviewRequestId)] ?? null
      : null
  })
  const selectedDeliveryListResource = computed(() => {
    const coordinates = selectedReviewCoordinates.value
    return coordinates ? deliveryStore.state.bundles[deliveryAttemptKey(coordinates)] ?? null : null
  })
  const selectedDeliveryDetailResource = computed(() => {
    const coordinates = selectedReviewCoordinates.value
    const bundleId = deliveryStore.state.selectedBundleId
    return coordinates && bundleId
      ? deliveryStore.state.bundleDetails[deliveryBundleKey(coordinates, bundleId)] ?? null
      : null
  })
  const codingRouteInvalid = computed(() => {
    if (!queryValue(route.query.task) || !codingScope.value) return false
    const selection = codingRouteSelection(route.query)
    return !isRestorableCodingRoute(selection) || !codingRouteMatchesScope(selection, codingScope.value)
  })
  const codingStudioPhase = computed(() => {
    if (codingRouteInvalid.value || codingStore.state.routePhase === 'error') return 'error'
    return selectedCodingAttemptResource.value?.phase ?? codingStore.state.routePhase
  })
  const codingStudioErrorMessage = computed(() => (codingRouteInvalid.value
    ? 'Coding 深链接的 Team、WorkProject、Task、attempt 或 Workspace 坐标不完整'
    : codingStore.state.routeErrorMessage)
    ?? selectedCodingAttemptResource.value?.errorMessage
    ?? null)
  const runtimeHealthResource = computed(() => taskStore.state.runtimeHealth.default ?? null)
  const taskAssociationResource = computed(() => taskStore.state.selectedTaskId
    ? taskStore.state.taskAssociations[taskStore.state.selectedTaskId] ?? null
    : null)
  const taskEventResource = computed(() => taskStore.state.selectedTaskId
    ? taskStore.state.events[taskStore.state.selectedTaskId] ?? null
    : null)
  const taskLiveState = computed(() => taskStore.state.selectedTaskId
    ? taskStore.state.liveTasks[taskStore.state.selectedTaskId] ?? null
    : null)
  const selectedCodingArtifactErrorStatuses = computed(() => {
    const taskId = taskStore.state.selectedTaskId
    const executionId = codingStore.state.selectedExecutionId
    if (!taskId || !executionId) return []
    const prefix = `${taskId}:${executionId}:`
    // Artifact caches survive drawer navigation for fast replay; permission routing must only
    // observe resources that belong to the currently selected Task attempt.
    return [codingStore.state.commandLogs, codingStore.state.testReports]
      .flatMap(cache => Object.entries(cache))
      .filter(([key]) => key.startsWith(prefix))
      .map(([, resource]) => resource.errorStatus)
  })
  const selectedInjectionErrorStatus = computed(() => {
    const taskId = taskStore.state.selectedTaskId
    const executionId = codingStore.state.selectedExecutionId
    if (!taskId || !executionId) return null
    return injectionStore.state.views[viewKey(taskId, executionId)]?.errorStatus ?? null
  })
  /** A quota admission denial (403 policy_denied with a known admission action) is a business
   *  answer shown inside the delegation dialog, not a revoked permission — it must not eject
   *  the whole page to access-denied (M12-F02). */
  const quotaAdjustedCreateErrorStatus = computed(() => {
    const status = taskStore.state.createErrorStatus
    if (status !== 403) return status
    return taskStore.state.createErrorCode === 'policy_denied'
      && quotaAdmissionProblem(taskStore.state.createErrorDetails.action) !== null
      ? null
      : status
  })
  const taskForbidden = computed(() => [
    taskStore.state.errorStatus,
    taskStore.state.detailErrorStatus,
    quotaAdjustedCreateErrorStatus.value,
    taskStore.state.commandErrorStatus,
    selectedRuntimeResource.value?.errorStatus,
    runtimeHealthResource.value?.errorStatus,
    taskAssociationResource.value?.errorStatus,
    taskEventResource.value?.errorStatus,
    codingStore.state.routeErrorStatus,
    selectedCodingAttemptResource.value?.errorStatus,
    selectedCodingCommandsResource.value?.errorStatus,
    selectedCodingTestsResource.value?.errorStatus,
    selectedCodingPatchResource.value?.errorStatus,
    selectedReviewListResource.value?.errorStatus,
    selectedReviewDetailResource.value?.errorStatus,
    reviewStore.state.command.errorStatus,
    deliveryStore.state.connections.errorStatus,
    selectedDeliveryListResource.value?.errorStatus,
    selectedDeliveryDetailResource.value?.errorStatus,
    deliveryStore.state.command.errorStatus,
    selectedInjectionErrorStatus.value,
    ...selectedCodingArtifactErrorStatuses.value,
    ...Object.values(taskStore.state.associationPages).map(resource => resource.errorStatus),
  ].some(status => status === 403))
  let liveFactRefreshTimer: ReturnType<typeof setTimeout> | null = null

  /**
   * The nine-level primary action (contract §4.7), assembled from authoritative store facts only —
   * the anchored execution is the explicit selection when it is still live, otherwise the server's
   * current one; review and delivery verdicts come from the same resources the panels render.
   */
  const workspaceTaskAction = computed<WorkspaceAction>(() => {
    if (executionConflict.value) {
      return {
        state: 'FORBIDDEN',
        headline: '执行坐标冲突',
        detail: 'taskExecution 与 attempt 指向不同执行，写动作已禁止；在执行选择区择一后恢复。',
        operation: null,
        anchor: null,
      }
    }
    const attempts = taskStore.state.attempts
    const anchored = attempts.find(item => item.id === selectedTaskExecutionId.value)
      ?? attempts.find(item => item.id === taskStore.state.details?.currentExecutionId)
      ?? null
    const reviews = selectedReviewListResource.value?.value ?? null
    const bundles = selectedDeliveryListResource.value?.value ?? null
    return resolveWorkspaceAction({
      canControl: canControlTask.value,
      historyView: Boolean(selectedTaskExecutionId.value
        && !attempts.some(item => item.id === selectedTaskExecutionId.value)),
      commandPending: taskStore.state.commandPending !== null,
      versionConflict: taskStore.state.commandVersionConflict !== null,
      selectionRequired: false,
      configurationMissing: false,
      executionStatus: anchored?.status ?? null,
      waitingReason: anchored?.waiting?.reason ?? null,
      reviewAwaited: Boolean(reviews?.some(review => review.status === 'OPEN' || review.status === 'IN_PROGRESS')),
      deliveryAwaited: Boolean(bundles?.some(bundle => bundle.actions.some(action =>
        action.dispatch && ['UNKNOWN', 'RECONCILING', 'MANUAL_REVIEW'].includes(action.dispatch.status)))),
      taskStatus: taskStore.state.details?.status ?? null,
    })
  })

  watch(
    () => taskStore.state.selectedTaskId,
    async taskId => {
      const pageOwner = pageRequests.capture()
      taskStore.stopLiveTasks()
      if (!taskId) return
      await taskStore.loadEvents(taskId)
      if (!pageOwner.isCurrent()) return
      if (pageOwner.isCurrent() && taskStore.state.selectedTaskId === taskId) taskStore.synchronizeLiveTasks([taskId])
    },
  )

  watch(
    () => taskStore.state.liveRefreshVersion,
    () => {
      const taskId = taskStore.state.liveUpdatedTaskId
      if (!taskId || taskId !== taskStore.state.selectedTaskId || !principal || !team.value) return
      if (liveFactRefreshTimer) clearTimeout(liveFactRefreshTimer)
      // Bursty Agent events update the Timeline immediately. Task and Runtime projections are
      // coalesced into one authoritative re-read so event timing can never roll status backwards.
      // The authoritative re-read is page-scoped, not selection-scoped: the selection coordinate
      // includes route.fullPath, and query canonicalization written right after a live event (for
      // example the workspace coordinate) cancelled this coalesced refresh mid-flight, which then
      // skipped the terminal stopLiveTasks and left the stream reconnecting forever. Task identity
      // is guarded explicitly by the selectedTaskId checks below.
      const pageOwner = pageRequests.capture()
      liveFactRefreshTimer = setTimeout(async () => {
        if (!pageOwner.isCurrent()) return
        liveFactRefreshTimer = null
        if (taskStore.state.selectedTaskId !== taskId || !principal || !team.value) return
        await taskStore.select({ organizationId: principal.organizationId, teamId: team.value.id }, taskId, true)
        if (!pageOwner.isCurrent() || taskStore.state.selectedTaskId !== taskId) return
        const executionId = selectedTaskExecutionId.value
        if (executionId) await taskStore.loadRuntimeFacts(taskId, executionId, true)
        if (!pageOwner.isCurrent()) return
        if (codingScope.value) await synchronizeCodingStudio(true)
        if (!pageOwner.isCurrent()) return
        await synchronizeReviewWorkbench(true)
        if (!pageOwner.isCurrent()) return
        await synchronizeDeliveryWorkbench(true)
        if (!pageOwner.isCurrent()) return
        if (taskStore.state.details && ['COMPLETED', 'FAILED', 'CANCELLED'].includes(taskStore.state.details.status)) {
          taskStore.stopLiveTasks()
        }
      }, 350)
    },
  )

  watch(
    () => [
      taskStore.state.details?.id,
      taskStore.state.details?.currentExecutionId,
      taskStore.state.attempts.map(item => item.id).join(','),
      route.query.taskExecution,
      route.query.attempt,
    ] as const,
    ([taskId, currentExecutionId]) => {
      if (!taskId) {
        selectedTaskExecutionId.value = null
        return
      }
      // Contract §4.1: the URL and the server facts resolve through the four-state selection — a
      // conflict chooses nothing (writes stay blocked until the member picks one), and an explicit
      // execution the Task no longer has stays in view as history instead of silently swapping.
      const resolution = resolveExecutionSelection(
        parseExecutionSelection(route.query),
        taskStore.state.attempts,
        { selectedId: selectedTaskExecutionId.value, currentExecutionId: currentExecutionId ?? null },
      )
      selectedTaskExecutionId.value = resolution.state === 'CONFLICT' || resolution.state === 'NO_EXECUTION'
        ? null
        : resolution.executionId
      if (selectedTaskExecutionId.value) {
        void taskStore.loadRuntimeFacts(taskId, selectedTaskExecutionId.value)
      }
      void taskStore.loadAssociations(taskId)
      // Refresh this no-store server projection whenever a Task detail is opened.
      void taskStore.loadRuntimeHealth(true)
    },
  )

  watch(
    () => [
      codingScope.value?.organizationId,
      codingScope.value?.teamId,
      taskStore.state.selectedTaskId,
      codingStore.state.selectedExecutionId,
      codingStore.state.routePhase,
      route.query.review,
    ] as const,
    () => { void synchronizeReviewWorkbench() },
    { immediate: true },
  )

  watch(
    () => [
      codingScope.value?.organizationId,
      codingScope.value?.teamId,
      taskStore.state.selectedTaskId,
      codingStore.state.selectedExecutionId,
      codingStore.state.routePhase,
    ] as const,
    () => { void synchronizeDeliveryWorkbench() },
    { immediate: true },
  )

  watch(
    () => [
      codingScope.value?.organizationId,
      codingScope.value?.teamId,
      codingScope.value?.projectId,
      taskStore.state.details?.id,
      route.query.task,
      route.query.attempt,
      route.query.workspace,
    ] as const,
    () => { void synchronizeCodingStudio() },
    { immediate: true },
  )

  watch(
    taskForbidden,
    forbidden => {
      if (forbidden) void router.replace({ name: 'access-denied', query: { from: route.fullPath } })
    },
  )

  /**
   * Resolves a `taskExecution`/`attempt` coordinate conflict with one explicit choice.
   *
   * The picked execution becomes the unified parameter and the other spelling leaves with it; the
   * workspace and review coordinates also reset, because they belong to the conflicting selection.
   */
  function resolveExecutionConflict(executionId: string): void {
    const taskId = queryValue(route.query.task)
    const teamId = queryValue(route.query.team)
    const projectId = queryValue(route.query.project)
    if (!taskId || !teamId || !projectId) return
    const query = withCodingRoute(route.query, {
      teamId,
      projectId,
      workItemId: queryValue(route.query.workItem),
      taskId,
      executionId,
      workspaceId: null,
    })
    delete query.review
    void router.replace({ query })
  }

  function selectTaskAttempt(executionId: string): void {
    const taskId = taskStore.state.selectedTaskId
    if (!taskId || selectedTaskExecutionId.value === executionId) return
    selectedTaskExecutionId.value = executionId
    void taskStore.loadRuntimeFacts(taskId, executionId)
    // A non-null coding scope already proves the project resolved (the page builds it from the
    // same selection), so the guard below needs no separate project check.
    if (!codingScope.value) return
    const cached = codingStore.state.attempts[`${taskId}:${executionId}`]?.value
    const query = withCodingRoute(route.query, {
      teamId: codingScope.value.teamId,
      projectId: codingScope.value.projectId,
      workItemId: taskStore.state.details?.workItemId,
      taskId,
      executionId,
      workspaceId: cached?.coding ? cached.details?.workspace.id : null,
    })
    delete query.review
    void router.replace({ query })
  }

  async function synchronizeCodingStudio(force = false): Promise<void> {
    const pageOwner = pageRequests.captureSelection()
    const scope = codingScope.value
    const taskId = taskStore.state.details?.id
    const selection = codingRouteSelection(route.query)
    // A conflicting coordinate pair is not restorable: choosing one side silently is exactly what the
    // contract forbids, so the studio stays closed until the conflict is resolved.
    if (!scope || !taskId || selection.taskId !== taskId || selection.executionConflict) {
      codingStore.clearSelection()
      return
    }
    if (!isRestorableCodingRoute(selection) || !codingRouteMatchesScope(selection, scope)) {
      codingStore.clearSelection()
      return
    }
    if (force) codingStore.invalidateTask(taskId)
    await codingStore.synchronize(scope, {
      taskId,
      executionId: selection.executionId,
      workspaceId: selection.workspaceId,
    })
    if (!pageOwner.isCurrent()) return
    if (taskStore.state.details?.id !== taskId || codingStore.state.selectedTaskId !== taskId) return
    const executionId = codingStore.state.selectedExecutionId
    if (!executionId) return
    if (taskStore.state.attempts.some(attempt => attempt.id === executionId)) {
      selectedTaskExecutionId.value = executionId
      void taskStore.loadRuntimeFacts(taskId, executionId)
    }
    if (codingStore.state.routePhase !== 'ready') return
    void codingStore.loadCommands(taskId, executionId)
    void codingStore.loadTestEvidence(taskId, executionId)
    const workspaceId = codingStore.state.selectedWorkspaceId
    // The unified parameter is canonicalized here too: a restored legacy `attempt=` link rewrites to
    // `taskExecution=` once its execution is live, so the alias can never resurface as a conflict.
    if (route.query.taskExecution !== executionId || route.query.attempt || route.query.workspace !== workspaceId) {
      await router.replace({ query: withCodingRoute(route.query, {
        teamId: scope.teamId,
        projectId: scope.projectId,
        workItemId: taskStore.state.details?.workItemId,
        taskId,
        executionId,
        workspaceId,
      }) })
      if (!pageOwner.isCurrent()) return
    }
  }

  async function synchronizeReviewWorkbench(force = false): Promise<void> {
    const pageOwner = pageRequests.captureSelection()
    const scope = codingScope.value
    const coordinates = selectedReviewCoordinates.value
    if (!scope || !coordinates) {
      reviewStore.clearSelection()
      return
    }
    // Coding restoration can briefly re-enter loading when canonical attempt/workspace query
    // coordinates are written. Preserve the same Review request instead of aborting it; the ready
    // transition below will synchronize against the authoritative Coding attempt.
    if (codingStore.state.routePhase !== 'ready') return
    if (force) reviewStore.invalidateAttempt(coordinates)
    await reviewStore.synchronize(
      { organizationId: scope.organizationId, teamId: scope.teamId },
      coordinates,
      queryValue(route.query.review),
    )
    if (!pageOwner.isCurrent()) return
    if (!selectedReviewCoordinates.value
      || reviewAttemptKey(selectedReviewCoordinates.value) !== reviewAttemptKey(coordinates)) return
    const selected = reviewStore.state.selectedReviewRequestId
    if (selected && route.query.review !== selected) {
      await router.replace({ query: { ...route.query, review: selected } })
      if (!pageOwner.isCurrent()) return
    } else if (!selected && route.query.review) {
      const query = { ...route.query }
      delete query.review
      await router.replace({ query })
      if (!pageOwner.isCurrent()) return
    }
  }

  async function synchronizeDeliveryWorkbench(force = false): Promise<void> {
    const pageOwner = pageRequests.captureSelection()
    const scope = codingScope.value
    const coordinates = selectedReviewCoordinates.value
    if (!scope || !coordinates) {
      deliveryStore.clearSelection()
      return
    }
    // ActionBundle is closed over the same authoritative Coding attempt as Review.
    if (codingStore.state.routePhase !== 'ready') return
    if (force) {
      await deliveryStore.synchronize(
        { organizationId: scope.organizationId, teamId: scope.teamId }, coordinates,
      )
      if (!pageOwner.isCurrent()) return
      await deliveryStore.refresh()
      if (!pageOwner.isCurrent()) return
      return
    }
    await deliveryStore.synchronize(
      { organizationId: scope.organizationId, teamId: scope.teamId }, coordinates,
    )
    if (!pageOwner.isCurrent()) return
  }

  function selectReview(reviewRequestId: string): void {
    const coordinates = selectedReviewCoordinates.value
    if (!coordinates) return
    void router.replace({ query: { ...route.query, review: reviewRequestId } })
  }

  function retryReviews(): void {
    void synchronizeReviewWorkbench(true)
  }

  function retryReviewDetail(): void {
    const coordinates = selectedReviewCoordinates.value
    const id = reviewStore.state.selectedReviewRequestId
    if (coordinates && id) void reviewStore.select(coordinates, id, true)
  }

  function addReviewComment(input: { filePath: string; side: 'OLD' | 'NEW'; lineNumber: number; hunkHeader: string; lineContentHash: string; diffGeneration: number; content: string }) {
    return reviewStore.addComment(input)
  }

  /** The review gate opens once the coding attempt delivered its diff and exact test evidence. */
  const canCreateReview = computed(() => {
    const attempt = selectedCodingAttemptResource.value?.value ?? null
    if (!attempt?.coding || !attempt.details) return false
    const delivered = attempt.executionStatus === 'COMPLETED'
    const evidenced = Boolean(attempt.details.diffManifest)
      && attempt.details.testEvidenceCount > 0
    const hasReview = (selectedReviewListResource.value?.value ?? []).length > 0
    return delivered && evidenced && !hasReview
  })

  function createReview(): Promise<boolean> {
    return reviewStore.createReview()
  }

  function executeReviewer(): Promise<boolean> {
    return reviewStore.execute()
  }

  function decideReview(input: ReviewDecisionInput): Promise<boolean> {
    return reviewStore.decide(input)
  }

  function requestReviewChanges(rationale: string): Promise<boolean> {
    return reviewStore.requestChanges(rationale)
  }

  function retryReviewCommand(): Promise<boolean> {
    return reviewStore.retryCommand()
  }

  function retryCodingStudio(): void {
    void synchronizeCodingStudio(true)
  }

  function loadCodingPatch(): void {
    const taskId = taskStore.state.selectedTaskId
    const executionId = codingStore.state.selectedExecutionId
    if (taskId && executionId) void codingStore.loadPatch(taskId, executionId, selectedCodingPatchResource.value?.phase === 'error')
  }

  function loadCodingCommandsMore(): void {
    const taskId = taskStore.state.selectedTaskId
    const executionId = codingStore.state.selectedExecutionId
    if (taskId && executionId) void codingStore.loadCommands(taskId, executionId, Boolean(selectedCodingCommandsResource.value?.value))
  }

  function loadCodingTestsMore(): void {
    const taskId = taskStore.state.selectedTaskId
    const executionId = codingStore.state.selectedExecutionId
    if (taskId && executionId) void codingStore.loadTestEvidence(taskId, executionId, Boolean(selectedCodingTestsResource.value?.value))
  }

  function codingCommandLog(evidenceId: string) {
    const taskId = taskStore.state.selectedTaskId
    const executionId = codingStore.state.selectedExecutionId
    return taskId && executionId ? codingStore.state.commandLogs[`${taskId}:${executionId}:${evidenceId}`] ?? null : null
  }

  function codingTestReport(evidenceId: string) {
    const taskId = taskStore.state.selectedTaskId
    const executionId = codingStore.state.selectedExecutionId
    return taskId && executionId ? codingStore.state.testReports[`${taskId}:${executionId}:${evidenceId}`] ?? null : null
  }

  function loadCodingCommandLog(evidenceId: string, more = false): void {
    const taskId = taskStore.state.selectedTaskId
    const executionId = codingStore.state.selectedExecutionId
    if (taskId && executionId) void codingStore.loadCommandLog(taskId, executionId, evidenceId, more)
  }

  function loadCodingTestReport(evidenceId: string, more = false): void {
    const taskId = taskStore.state.selectedTaskId
    const executionId = codingStore.state.selectedExecutionId
    if (taskId && executionId) void codingStore.loadTestReport(taskId, executionId, evidenceId, more)
  }

  function retryRuntimeHealth(): void {
    void taskStore.loadRuntimeHealth(true)
  }

  function retryTaskAssociations(): void {
    if (!taskStore.state.selectedTaskId) return
    taskStore.invalidateAssociations(taskStore.state.selectedTaskId)
    void taskStore.loadAssociations(taskStore.state.selectedTaskId)
  }

  function loadTaskEventsMore(): void {
    if (taskStore.state.selectedTaskId) void taskStore.loadEvents(taskStore.state.selectedTaskId, true)
  }

  function retryTaskEvents(): void {
    const pageOwner = pageRequests.captureSelection()
    const taskId = taskStore.state.selectedTaskId
    if (!taskId) return
    void taskStore.loadEvents(taskId).then(() => {
      if (taskStore.state.selectedTaskId === taskId) taskStore.synchronizeLiveTasks([taskId])
    })
  }

  async function commandTask(
    operation: MemberTaskCommandOperation,
    reason?: string,
    agentConfigurationRevision?: number,
  ): Promise<void> {
    const pageOwner = pageRequests.captureSelection()
    // While the execution coordinates conflict, no write action may run against a guessed target.
    if (executionConflict.value) return
    const details = taskStore.state.details
    const attempt = taskStore.state.attempts.find(item => item.id === details?.currentExecutionId)
    if (!principal || !team.value || !details || !attempt) return
    await taskStore.commandTask({
      scope: { organizationId: principal.organizationId, teamId: team.value.id },
      taskId: details.id,
      executionId: attempt.id,
      expectedVersion: attempt.version,
      operation,
      reason,
      agentConfigurationRevision,
    })
    if (!pageOwner.isCurrent()) return
    await synchronizeCodingStudio(true)
    if (!pageOwner.isCurrent()) return
    focusCurrentTaskAttempt()
  }

  async function retryTaskCommand(): Promise<void> {
    const pageOwner = pageRequests.captureSelection()
    if (executionConflict.value) return
    await taskStore.retryTaskCommand()
    if (!pageOwner.isCurrent()) return
    await synchronizeCodingStudio(true)
    if (!pageOwner.isCurrent()) return
    focusCurrentTaskAttempt()
  }

  function focusCurrentTaskAttempt(): void {
    const taskId = taskStore.state.details?.id
    const executionId = taskStore.state.details?.currentExecutionId
    if (!taskId || !executionId || selectedTaskExecutionId.value === executionId) return
    selectTaskAttempt(executionId)
    void taskStore.loadRuntimeFacts(taskId, executionId, true)
  }

  function retryTaskDetails(): void {
    if (!principal || !team.value || !taskStore.state.selectedTaskId) return
    void taskStore.select(
      { organizationId: principal.organizationId, teamId: team.value.id },
      taskStore.state.selectedTaskId,
      true,
    )
  }

  function retryTaskRuntime(): void {
    if (!taskStore.state.selectedTaskId || !selectedTaskExecutionId.value) return
    void taskStore.loadRuntimeFacts(taskStore.state.selectedTaskId, selectedTaskExecutionId.value, true)
  }

  /** Clears every workbench selection at once (the page's close path calls this after routing). */
  function clearTaskSelection(): void {
    taskStore.clearSelection()
    codingStore.clearSelection()
    reviewStore.clearSelection()
    deliveryStore.clearSelection()
    selectedTaskExecutionId.value = null
  }

  function cancelLiveFactRefresh(): void {
    if (liveFactRefreshTimer) clearTimeout(liveFactRefreshTimer)
  }

  return {
    selectedTaskExecutionId,
    executionConflict,
    selectedRuntimeResource,
    selectedCodingAttemptResource,
    selectedCodingCommandsResource,
    selectedCodingTestsResource,
    selectedCodingPatchResource,
    selectedReviewCoordinates,
    selectedReviewListResource,
    selectedReviewDetailResource,
    selectedReviewCommentsResource,
    selectedDeliveryListResource,
    selectedDeliveryDetailResource,
    codingStudioPhase,
    codingStudioErrorMessage,
    runtimeHealthResource,
    taskAssociationResource,
    taskEventResource,
    taskLiveState,
    taskForbidden,
    workspaceTaskAction,
    canCreateReview,
    resolveExecutionConflict,
    selectTaskAttempt,
    synchronizeCodingStudio,
    synchronizeReviewWorkbench,
    synchronizeDeliveryWorkbench,
    selectReview,
    retryReviews,
    retryReviewDetail,
    addReviewComment,
    createReview,
    executeReviewer,
    decideReview,
    requestReviewChanges,
    retryReviewCommand,
    retryCodingStudio,
    loadCodingPatch,
    loadCodingCommandsMore,
    loadCodingTestsMore,
    codingCommandLog,
    codingTestReport,
    loadCodingCommandLog,
    loadCodingTestReport,
    retryRuntimeHealth,
    retryTaskAssociations,
    loadTaskEventsMore,
    retryTaskEvents,
    commandTask,
    retryTaskCommand,
    retryTaskDetails,
    retryTaskRuntime,
    clearTaskSelection,
    cancelLiveFactRefresh,
  }
}
