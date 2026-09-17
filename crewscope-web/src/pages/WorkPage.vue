<script setup lang="ts">
import { MessageSquare, Plus, ShieldCheck } from '@lucide/vue'
import { computed, inject, nextTick, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter, type LocationQueryRaw } from 'vue-router'
import { AUTH_PRINCIPAL, can, permissions } from '../app/auth'
import { useNetworkStatus } from '../app/network'
import { useToast } from '../composables/useToast'
import { useBoardDrag } from '../composables/useBoardDrag'
import { useListSort } from '../composables/useListSort'
import { useSelection } from '../composables/useSelection'
import BaseButton from '../components/base/BaseButton.vue'
import WorkItemDetailDrawer from '../components/domain/WorkItemDetailDrawer.vue'
import ActivityStream from '../components/domain/ActivityStream.vue'
import DelegateToAgentDialog from '../components/domain/DelegateToAgentDialog.vue'
import TaskListPanel from '../components/domain/TaskListPanel.vue'
import TaskDetailDrawer from '../components/domain/TaskDetailDrawer.vue'
import WorkProjectCreateDialog from '../components/domain/WorkProjectCreateDialog.vue'
import WorkItemCreateDialog from '../components/domain/WorkItemCreateDialog.vue'
import WorkItemsWorkspace from '../components/domain/WorkItemsWorkspace.vue'
import WorkItemsToolbar from '../components/domain/WorkItemsToolbar.vue'
import WorkItemsBulkBar from '../components/domain/WorkItemsBulkBar.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import AppShell from '../components/layout/AppShell.vue'
import { useAgentStore } from '../domains/agent/store'
import { useScopeStore } from '../domains/scope/store'
import { principalDisplayName, principalNameDirectory } from '../domains/scope/memberDirectory'
import { createWorkProjectCreationFlow } from '../domains/scope/workProjectCreation'
import type { ConversationWorkItemAssociation } from '../domains/conversation/workItemLinkGateway'
import { useConversationWorkItemLinkStore } from '../domains/conversation/workItemLinkStore'
import { useWorkItemStore } from '../domains/workitem/store'
import { useUndoOffer } from '../domains/workitem/useUndoOffer'
import { useTransitionConfirm } from '../domains/workitem/useTransitionConfirm'
import { workItemPriorityLabels, workItemResponsibilityRoleLabels, workItemStatusLabels as statusLabels, workItemTypeLabels } from '../domains/workitem/labels'
import { bulkSummaryMessage, summarizeBulkResults, type WorkItemBulkAssignmentRole, type WorkItemBulkRowResult } from '../domains/workitem/bulk'
import {
  bulkTransitionTargets,
  defaultSortDirection,
  readWorkItemSortDirection,
  readWorkItemSortKey,
  sortWorkItems,
  workItemSortKeys,
  type WorkItemSortKey,
} from '../domains/workitem/list'
import { useTaskStore } from '../domains/task/store'
import { clearTaskDelegationDraft } from '../domains/task/delegationDraft'
import { resolveTaskExecution, taskRouteSelection } from '../domains/task/route'
import { clearCodingTargetDraft } from '../domains/coding/draft'
import {
  codingRouteMatchesScope,
  codingRouteSelection,
  isRestorableCodingRoute,
  withCodingRoute,
  withoutCodingRoute,
} from '../domains/coding/route'
import { useCodingStore } from '../domains/coding/store'
import { reviewAttemptKey, reviewDetailKey, useReviewStore } from '../domains/review/store'
import type { ReviewDecisionInput } from '../domains/review/types'
import { deliveryAttemptKey, deliveryBundleKey, useDeliveryStore } from '../domains/delivery/store'
import { useTeamOpsStore, workItemActivityCacheKey } from '../domains/teamops/store'
import type { WorkItemActivityRoute } from '../domains/teamops/types'
import type { CodingScope } from '../domains/coding/types'
import type { PrincipalScope } from '../domains/principal/types'
import {
  taskStatuses,
  type CreateTaskInput,
  type MemberTaskCommandOperation,
  type TaskStatus,
  type TaskSummary,
} from '../domains/task/types'
import {
  workItemPriorities,
  workItemStatuses,
  workItemTypes,
  allowedWorkItemTransitions,
  type CreateWorkItemInput,
  type WorkItemAvailableTransition,
  type WorkItemPriority,
  type WorkItemScope,
  type WorkItemStatus,
  type WorkItemSummary,
  type WorkItemType,
} from '../domains/workitem/types'

type WorkView = 'list' | 'board'
type FilterValue<T extends string> = T | 'all'

/**
 * How many rows one batch may cover.
 *
 * Every selected row costs its own command and its own idempotency key, and a batch nobody would
 * read the summary of is not a batch — it is a bulk edit nobody reviewed. The cap is stated when it
 * is reached rather than silently enforced.
 */
const MAX_SELECTED_WORK_ITEMS = 100

const route = useRoute()
const router = useRouter()
const principal = inject(AUTH_PRINCIPAL)
const scopeStore = useScopeStore()
const agentStore = useAgentStore()
const workStore = useWorkItemStore()
const { offerUndo, undoTransition } = useUndoOffer(workStore)
// The command in flight is the Store's own flag, not a page-local one: the drawer and the card run
// the same command, so a card that tracked its own pending state would let a member fire the same
// transition twice from two surfaces.
const {
  confirmingTarget,
  confirmingSubject,
  submit: submitTransition,
  reset: resetTransition,
} = useTransitionConfirm(() => workStore.state.detailCommandPending === 'transition')
const linkStore = useConversationWorkItemLinkStore()
const taskStore = useTaskStore()
const codingStore = useCodingStore()
const reviewStore = useReviewStore()
const deliveryStore = useDeliveryStore()
const teamOpsStore = useTeamOpsStore()
const isOnline = useNetworkStatus()
const toast = useToast()
const listSort = useListSort<WorkItemSortKey>({
  defaultKey: 'updatedAt',
  defaultDirection: 'desc',
  allowedKeys: workItemSortKeys,
})
const team = scopeStore.selectedTeam
const project = scopeStore.selectedProject
const canCreate = computed(() => Boolean(principal && can(principal, permissions.workCreate)))
const canManageProjects = computed(() => Boolean(principal && can(principal, permissions.workProjectsManage)))
const canParticipate = computed(() => Boolean(principal && can(principal, permissions.workParticipate)))
const canManageResponsibility = computed(() => Boolean(principal && can(principal, permissions.responsibilityManage)))
/** The scope every command and read on this page is issued against, or null before it resolves. */
const workScope = computed<WorkItemScope | null>(() => (principal && team.value && project.value
  ? { organizationId: principal.organizationId, teamId: team.value.id, projectId: project.value.id }
  : null))
/**
 * Selection is offered only when the member has something to do with it.
 *
 * A checkbox that leads to a bar with no buttons on it is worse than no checkbox: it invites work
 * that cannot be finished. A member who may only read this project gets the list, unadorned.
 */
const selectionEnabled = computed(() => canParticipate.value || canManageResponsibility.value)
const projectCreation = createWorkProjectCreationFlow(scopeStore, router, route)
const principalNames = computed(() => principalNameDirectory(scopeStore.state.members))
const responsibilityCandidates = computed(() => scopeStore.state.members
  .filter(member => member.status === 'ACTIVE')
  .map(member => ({
    principalId: member.userPrincipalId,
    displayName: member.displayName,
  })))
const responsibilityAgentCandidates = computed(() => (agentStore.state.agents.value ?? [])
  .filter(agent => agent.status === 'ACTIVE'
    && agent.principalStatus === 'ACTIVE'
    && agent.teamId === scopeStore.state.selectedTeamId
    && agent.workspaceId === project.value?.workspaceId)
  .map(agent => ({
    principalId: agent.principalId,
    displayName: agent.displayName,
    ownershipType: agent.ownershipType,
    runtimeRole: agent.runtimeRole,
  })))
const view = computed<WorkView>(() => oneOf(route.query.view, ['list', 'board'] as const, 'list'))
const statusFilter = computed<FilterValue<WorkItemStatus>>(() => oneOf(route.query.status, ['all', ...workItemStatuses] as const, 'all'))
const typeFilter = computed<FilterValue<WorkItemType>>(() => oneOf(route.query.type, ['all', ...workItemTypes] as const, 'all'))
const priorityFilter = computed<FilterValue<WorkItemPriority>>(() => oneOf(route.query.priority, ['all', ...workItemPriorities] as const, 'all'))
const taskStatusFilter = computed<FilterValue<TaskStatus>>(() => oneOf(route.query.taskStatus, ['all', ...taskStatuses] as const, 'all'))
const taskOwnerFilter = computed<string | 'all'>(() => {
  const value = queryValue(route.query.taskOwner)
  return value && /^[0-9a-f-]{36}$/i.test(value) ? value : 'all'
})
const taskOwnerOptions = computed(() => {
  if (taskOwnerFilter.value === 'all'
    || responsibilityCandidates.value.some(item => item.principalId === taskOwnerFilter.value)) {
    return responsibilityCandidates.value
  }
  return [...responsibilityCandidates.value, {
    principalId: taskOwnerFilter.value,
    displayName: principalDisplayName(principalNames.value, taskOwnerFilter.value, '历史负责人'),
  }]
})
const taskConversationSource = computed(() => {
  const conversationId = queryValue(route.query.conversation)
  const messageId = queryValue(route.query.sourceMessage)
  return conversationId && messageId ? { conversationId, messageId } : null
})
const codingScope = computed<CodingScope | null>(() => principal && team.value && project.value
  ? { organizationId: principal.organizationId, teamId: team.value.id, projectId: project.value.id }
  : null)
// Subject pickers search the Team directory, so they need the Team scope without the project.
const principalScope = computed<PrincipalScope | null>(() => principal && team.value
  ? { organizationId: principal.organizationId, teamId: team.value.id }
  : null)
const showCreate = ref(false)
const showDelegate = ref(false)
const createInitialKey = ref('')
let detailTriggerId: string | null = null
let taskDetailTriggerId: string | null = null
const selectedTaskExecutionId = ref<string | null>(null)
const selectedWorkItemActivityRoute = computed<WorkItemActivityRoute | null>(() => {
  const projectId = scopeStore.state.selectedProjectId
  const workItemId = workStore.state.detail?.workItem.id
  return projectId && workItemId ? { projectId, workItemId } : null
})
const selectedWorkItemActivity = computed(() => {
  const coordinates = selectedWorkItemActivityRoute.value
  return coordinates ? teamOpsStore.state.workItemActivity[workItemActivityCacheKey(coordinates)] ?? null : null
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
const taskForbidden = computed(() => [
  taskStore.state.errorStatus,
  taskStore.state.detailErrorStatus,
  taskStore.state.createErrorStatus,
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
  ...selectedCodingArtifactErrorStatuses.value,
  ...Object.values(taskStore.state.associationPages).map(resource => resource.errorStatus),
].some(status => status === 403))
let liveFactRefreshTimer: ReturnType<typeof setTimeout> | null = null

/**
 * The rows in hand, narrowed by the filters and ordered by the member's choice.
 *
 * Both steps act on the loaded page rather than on the collection: the server lists one WorkProject
 * by its updated-time keyset and takes neither a type nor a sort parameter. What makes that honest
 * is that every surface says which set it is describing — the toolbar counts the loaded rows and
 * the sort control names them.
 */
const filteredItems = computed(() => sortWorkItems(
  workStore.state.items.filter(item =>
    (typeFilter.value === 'all' || item.type === typeFilter.value)
    && (priorityFilter.value === 'all' || item.priority === priorityFilter.value),
  ),
  listSort.key.value,
  listSort.direction.value,
))

/**
 * Selection lives above the board and the list both, so switching view does not lose it. The cap is
 * a batch cost: every selected row costs its own command, and a four-figure batch is not a batch
 * anybody reviews before it runs.
 */
const selection = useSelection<WorkItemSummary>({
  items: computed(() => filteredItems.value),
  getId: item => item.id,
  maxSelected: MAX_SELECTED_WORK_ITEMS,
})
/** What a batch would act on: selected rows that are also loaded and visible right now. */
const actionableSelectedItems = computed(() => selection.selectedItems.value)
/**
 * The selection's own count, lifted to the top level of the setup bindings.
 *
 * The composable returns refs on an object, and only top-level bindings are unwrapped in a template:
 * `selection.selectedCount` there would be a ref object, which is always truthy and never equal to a
 * number. Reading it here keeps the template comparing counts.
 */
const selectedCount = selection.selectedCount
const bulkTargets = computed(() => bulkTransitionTargets(actionableSelectedItems.value))
const bulkResults = ref<readonly WorkItemBulkRowResult[] | null>(null)

const boardStatuses = computed<WorkItemStatus[]>(() => {
  if (statusFilter.value !== 'all') return [statusFilter.value]
  const workflow: WorkItemStatus[] = ['BACKLOG', 'READY', 'IN_PROGRESS', 'IN_REVIEW', 'BLOCKED', 'DONE']
  for (const terminal of ['CANCELLED', 'ARCHIVED'] as const) {
    if (filteredItems.value.some(item => item.status === terminal)) workflow.push(terminal)
  }
  return workflow
})

const canDelegate = computed(() => Boolean(
  canParticipate.value
  && principal
  && workStore.state.responsibilities.some(assignment =>
    assignment.actorPrincipalId === principal.id
    && (assignment.role === 'OWNER' || assignment.role === 'EXECUTOR'),
  ),
))
const canControlTask = computed(() => Boolean(
  canParticipate.value
  && principal
  && workStore.state.responsibilities.some(assignment =>
    assignment.actorPrincipalId === principal.id
    && (assignment.role === 'OWNER' || assignment.role === 'EXECUTOR'),
  ),
))
const canGateReview = computed(() => Boolean(
  canParticipate.value
  && principal
  && workStore.state.responsibilities.some(assignment =>
    assignment.role === 'REVIEWER'
    && assignment.actorType === 'USER'
    && assignment.actorPrincipalId === principal.id,
  ),
))
const canConfirmDelivery = computed(() => Boolean(
  canParticipate.value
  && principal
  && workStore.state.responsibilities.some(assignment =>
    assignment.role === 'OWNER'
    && assignment.actorType === 'USER'
    && assignment.actorPrincipalId === principal.id,
  ),
))

watch(
  () => [scopeStore.state.phase, route.query.view, route.query.status, route.query.type, route.query.priority, route.query.sort, route.query.direction],
  () => {
    // Avoid changing the route while AppShell is still restoring a Team/Project deep link.
    if (scopeStore.state.phase !== 'ready') return
    const canonical = {
      ...route.query,
      view: view.value,
      status: statusFilter.value,
      type: typeFilter.value,
      priority: priorityFilter.value,
      sort: listSort.key.value,
      direction: listSort.direction.value,
    }
    if (
      route.query.view !== canonical.view
      || route.query.status !== canonical.status
      || route.query.type !== canonical.type
      || route.query.priority !== canonical.priority
      || route.query.sort !== canonical.sort
      || route.query.direction !== canonical.direction
    ) void router.replace({ query: canonical })
  },
  { immediate: true },
)

/**
 * The URL owns the ordering, so a link pasted into a message reopens the same list.
 *
 * Reading the query back into the sort state is what keeps the composable from being a second
 * source of truth: the member's click writes the URL, and the URL is what the list is built from.
 */
watch(
  () => [route.query.sort, route.query.direction] as const,
  ([key, direction]) => {
    const sortKey = readWorkItemSortKey(key)
    listSort.setSort(sortKey, readWorkItemSortDirection(direction, defaultSortDirection(sortKey)))
  },
  { immediate: true },
)

watch(
  taskForbidden,
  forbidden => {
    if (forbidden) void router.replace({ name: 'access-denied', query: { from: route.fullPath } })
  },
)

watch(
  () => taskStore.state.selectedTaskId,
  async taskId => {
    taskStore.stopLiveTasks()
    if (!taskId) return
    await taskStore.loadEvents(taskId)
    if (taskStore.state.selectedTaskId === taskId) taskStore.synchronizeLiveTasks([taskId])
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
    liveFactRefreshTimer = setTimeout(async () => {
      liveFactRefreshTimer = null
      if (taskStore.state.selectedTaskId !== taskId || !principal || !team.value) return
      await taskStore.select({ organizationId: principal.organizationId, teamId: team.value.id }, taskId, true)
      if (taskStore.state.selectedTaskId !== taskId) return
      const executionId = selectedTaskExecutionId.value
      if (executionId) await taskStore.loadRuntimeFacts(taskId, executionId, true)
      if (codingScope.value) await synchronizeCodingStudio(true)
      await synchronizeReviewWorkbench(true)
      await synchronizeDeliveryWorkbench(true)
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
  ] as const,
  ([taskId, currentExecutionId]) => {
    if (!taskId) {
      selectedTaskExecutionId.value = null
      return
    }
    selectedTaskExecutionId.value = resolveTaskExecution(
      taskRouteSelection(route.query),
      taskStore.state.attempts,
      { selectedId: selectedTaskExecutionId.value, currentExecutionId: currentExecutionId ?? null },
    )
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
  () => [scopeStore.state.phase, route.query.taskStatus, route.query.taskOwner],
  () => {
    if (scopeStore.state.phase !== 'ready') return
    const canonical = {
      ...route.query,
      taskStatus: taskStatusFilter.value,
      taskOwner: taskOwnerFilter.value,
    }
    if (route.query.taskStatus !== canonical.taskStatus || route.query.taskOwner !== canonical.taskOwner) {
      void router.replace({ query: canonical })
    }
  },
  { immediate: true },
)

watch(
  () => [scopeStore.state.phase, scopeStore.state.selectedTeamId, scopeStore.state.selectedProjectId, route.query.workItem] as const,
  ([phase, teamId, projectId, selected]) => {
    const workItemId = queryValue(selected)
    if (phase !== 'ready' || !teamId || !projectId || !principal || !workItemId) {
      if (!workItemId || phase === 'ready') {
        workStore.closeDetails()
        linkStore.reset()
      }
      return
    }
    void scopeStore.loadMembers()
    if (canManageResponsibility.value) void loadResponsibilityAgents()
    const scope = { organizationId: principal.organizationId, teamId, projectId }
    void Promise.all([
      workStore.loadDetails(scope, workItemId),
      linkStore.loadByWorkItem(scope, workItemId),
    ])
  },
  { immediate: true },
)

async function loadResponsibilityAgents(force = false, more = false): Promise<void> {
  if (!principal || !team.value) return
  agentStore.activateScope({ organizationId: principal.organizationId, teamId: team.value.id })
  await agentStore.loadAgents(more, force)
}

watch(
  () => [
    scopeStore.state.phase,
    scopeStore.state.selectedTeamId,
    scopeStore.state.selectedProjectId,
    taskStatusFilter.value,
    taskOwnerFilter.value,
    route.query.task,
  ] as const,
  ([phase, teamId, projectId, taskStatus, taskOwner, selectedTask]) => {
    if (phase !== 'ready' || !teamId || !projectId || !principal) {
      if (phase === 'ready' && !projectId) taskStore.reset()
      return
    }
    void scopeStore.loadMembers()
    void taskStore.synchronize(
      { organizationId: principal.organizationId, teamId },
      {
        projectId,
        status: taskStatus === 'all' ? undefined : taskStatus,
        ownerPrincipalId: taskOwner === 'all' ? undefined : taskOwner,
        taskId: queryValue(selectedTask),
      },
    )
  },
  { immediate: true },
)

watch(
  () => workStore.state.detail?.workItem.key,
  key => {
    if (key && queryValue(route.query.workItem) && queryValue(route.query.focus) !== key) {
      void router.replace({ query: { ...route.query, focus: key } })
    }
  },
)

watch(
  () => [scopeStore.state.selectedTeamId, selectedWorkItemActivityRoute.value?.projectId, selectedWorkItemActivityRoute.value?.workItemId] as const,
  ([teamId, projectId, workItemId]) => {
    if (!principal || !teamId || !projectId || !workItemId) return
    teamOpsStore.activateScope({ organizationId: principal.organizationId, teamId })
    void teamOpsStore.loadWorkItemActivity({ projectId, workItemId }, {}, false, true)
  },
  { immediate: true },
)

watch(
  () => [route.query.delegate, workStore.state.detail?.workItem.id, canDelegate.value] as const,
  ([delegate, workItemId, permitted]) => {
    if (delegate !== 'coding' || !workItemId || !permitted) return
    openDelegate()
    const query = { ...route.query }
    delete query.delegate
    void router.replace({ query })
  },
)

watch(
  () => [scopeStore.state.phase, scopeStore.state.selectedTeamId, scopeStore.state.selectedProjectId, statusFilter.value] as const,
  ([phase, teamId, projectId, status]) => {
    if (phase !== 'ready' || !teamId || !projectId || !principal) {
      if (phase === 'ready' && !projectId) workStore.reset()
      return
    }
    void workStore.load(
      { organizationId: principal.organizationId, teamId, projectId },
      status === 'all' ? undefined : status,
    )
  },
  { immediate: true },
)

// A pending confirmation belongs to a card the member is looking at. When the set of cards or the
// selected item changes, the armed card may no longer be on screen, and a confirmation that
// outlives its card is a click that executes an irreversible action nobody was warned about.
watch(
  () => [workStore.state.items, statusFilter.value, view.value, workStore.state.selectedWorkItemId] as const,
  resetTransition,
)

// The association Store is shared with Conversation Mode. Do not retain a WorkItem-scoped
// response after this route leaves the tree; the next entry must read current server facts.
onUnmounted(() => {
  linkStore.reset()
  taskStore.stopLiveTasks()
  reviewStore.reset()
  deliveryStore.reset()
  if (liveFactRefreshTimer) clearTimeout(liveFactRefreshTimer)
})

function updateQuery(name: 'view' | 'status' | 'type' | 'priority', value: string): void {
  void router.replace({ query: { ...route.query, [name]: value } })
}

/**
 * Sorts by the key the member pressed, toggling when it is already the active one.
 *
 * A key that is being switched to opens in the direction that key is worth reading first — newest,
 * most urgent, soonest due — rather than always ascending, which would show the least urgent rows
 * at the top of a list sorted by priority.
 */
function sortBy(key: WorkItemSortKey): void {
  if (key === listSort.key.value) listSort.toggleSort(key)
  else listSort.setSort(key, defaultSortDirection(key))
  void router.replace({
    query: { ...route.query, sort: listSort.key.value, direction: listSort.direction.value },
  })
}

/**
 * Runs one action across the selection.
 *
 * Same verdicts as a single card, in a different shape: the batch goes through the Store's row path,
 * which posts the same command to the same endpoint with a fresh idempotency key per row, and the
 * member is told what happened to every row — including the ones nothing happened to and why.
 */
async function runBulkTransition(targetStatus: WorkItemStatus): Promise<void> {
  const scope = workScope.value
  const rows = actionableSelectedItems.value
  if (!scope || !rows.length) return
  const label = bulkTargets.value.find(target => target.targetStatus === targetStatus)?.label
    ?? statusLabels[targetStatus]
  reportBulk(await workStore.transitionRows(scope, rows, targetStatus), label)
}

/** Assigns one responsibility across the selection, through the existing assignment commands. */
async function runBulkAssign(payload: { role: WorkItemBulkAssignmentRole; actorPrincipalId: string }): Promise<void> {
  const scope = workScope.value
  const rows = actionableSelectedItems.value
  if (!scope || !rows.length) return
  const member = responsibilityCandidates.value
    .find(candidate => candidate.principalId === payload.actorPrincipalId)?.displayName
  const label = `${workItemResponsibilityRoleLabels[payload.role]}指派给${member ?? '所选成员'}`
  reportBulk(await workStore.assignRows(scope, rows, payload.role, payload.actorPrincipalId), label)
}

/**
 * Publishes a batch's outcome: the sentence in the toast, the per-row detail in the bar.
 *
 * A fully executed batch clears the selection because it is finished — leaving it selected invites
 * running the same command twice. A partial one keeps it, because the member now has rows to
 * reconsider and clearing their selection would make them hunt for the same rows again.
 */
function reportBulk(results: readonly WorkItemBulkRowResult[], actionLabel: string): void {
  bulkResults.value = results
  const summary = summarizeBulkResults(results)
  toast.show(bulkSummaryMessage(summary, actionLabel), {
    tone: summary.failed > 0 ? 'danger' : summary.partial ? 'warning' : 'success',
  })
  if (!summary.partial) selection.clear()
}

function clearLocalFilters(): void {
  // Update both client-side filters atomically so concurrent router replacements cannot restore one stale value.
  void router.replace({ query: { ...route.query, type: 'all', priority: 'all' } })
}

function openCreate(): void {
  const prefix = `${project.value?.key ?? 'WORK'}-`
  const lastNumber = workStore.state.items.reduce((highest, item) => {
    const match = item.key.startsWith(prefix) ? Number(item.key.slice(prefix.length)) : 0
    return Number.isInteger(match) ? Math.max(highest, match) : highest
  }, 0)
  createInitialKey.value = `${prefix}${lastNumber + 1}`
  showCreate.value = true
}

async function createWorkItem(input: CreateWorkItemInput): Promise<void> {
  try {
    await workStore.create(input)
    showCreate.value = false
  } catch {
    // The Store publishes a sanitized command error; global handling owns unexpected details.
  }
}

/**
 * Selects every row the current result shows, up to the cap.
 *
 * The composable stops at the cap rather than throwing, so the count it returns is how many rows
 * actually joined; saying so is the difference between "all selected" and "selected as far as the
 * limit allowed".
 */
function selectVisiblePage(): void {
  const wanted = filteredItems.value.filter(item => !selection.isSelected(item.id)).length
  const added = selection.selectPage(filteredItems.value)
  if (added < wanted) {
    toast.show(`一次最多选择 ${MAX_SELECTED_WORK_ITEMS} 项，已选满`, { tone: 'warning' })
  }
}

/** Toggles one row's membership in the batch selection. Overflow is refused by the composable. */
function toggleSelect(item: WorkItemSummary): void {
  const accepted = selection.toggle(item.id)
  if (!accepted) {
    toast.show(`一次最多选择 ${MAX_SELECTED_WORK_ITEMS} 项，请先清除部分选择`, { tone: 'warning' })
  }
}

function selectItem(item: WorkItemSummary): void {
  detailTriggerId = item.id
  void router.replace({ query: { ...route.query, workItem: item.id, focus: item.key } })
}

const boardDrag = useBoardDrag<WorkItemSummary, WorkItemStatus>({
  columns: () => boardStatuses.value,
  columnLabel: status => statusLabels[status],
  columnOf: item => item.status,
  // The board's columns are the six workflow statuses; the irreversible edges lead to CANCELLED and
  // ARCHIVED, which have no column. So every target reachable by dragging is reversible and the undo
  // window covers it, which is why a drop needs no second click.
  allowEdge: (item, status) => Boolean(allowedWorkItemTransitions[item.status]?.includes(status)),
  findRow: workItemId => workStore.state.items.find(candidate => candidate.id === workItemId) ?? null,
  keyOf: item => item.id,
  nameOf: item => item.key,
  locked: () => !isOnline.value || !canParticipate.value,
  // `allowEdge` only knows which edges exist; the Store's row path knows which of them the server
  // offers this member right now, and refuses the rest with the server's own wording. A drop onto a
  // blocked column therefore says why here rather than posting a command that comes back rejected
  // with the reason buried in a drawer nobody has open.
  drop: async (item, status) => { await runRowAction(item, status) },
})
const draggedWorkItem = boardDrag.dragged
const dragOverStatus = boardDrag.overColumn
const boardAnnouncement = boardDrag.announcement

function startWorkItemDrag(item: WorkItemSummary): void { boardDrag.start(item) }
function endWorkItemDrag(): void { boardDrag.end() }
function markDragOver(status: WorkItemStatus): void { boardDrag.over(status) }
function allowDrop(status: WorkItemStatus): boolean { return boardDrag.allowDrop(status) }
function handleBoardKeydown(event: KeyboardEvent): void {
  const target = event.target instanceof HTMLElement ? event.target.closest<HTMLElement>('[data-work-item-id]') : null
  boardDrag.onKeydown(event, target?.dataset.workItemId)
}

/**
 * Runs one action offered by a list row, a board card or the home page.
 *
 * The Store owns the command and the verdict; this only decides how the verdict is shown, so every
 * surface reports the same refusal in the same words and offers the same undo afterwards.
 */
async function runRowAction(item: WorkItemSummary, target: WorkItemStatus): Promise<void> {
  if (!principal || !team.value || !project.value) return
  const result = await workStore.transitionFromRow(
    { organizationId: principal.organizationId, teamId: team.value.id, projectId: project.value.id },
    item,
    target,
  )
  if (result.status === 'executed') {
    offerUndo(item.key)
    return
  }
  // A refusal and a failure are different news: the first says the work item is not in a state that
  // allows the action, the second says we do not know whether the command landed. Reporting both as
  // a warning would let a member read a lost command as a rule they can work around.
  toast.show(result.message, { tone: result.status === 'refused' ? 'warning' : 'danger' })
}

/**
 * Runs an action chosen from a list row's or a board card's status menu.
 *
 * The menu is where the irreversible ones get their second click, so this is the only row path that
 * confirms; the drag path above does not need to, for the reason recorded there.
 */
async function runCardAction(item: WorkItemSummary, action: WorkItemAvailableTransition): Promise<void> {
  await submitTransition(item.id, action, async transition => runRowAction(item, transition.targetStatus))
}

/** Runs a drawer-initiated transition and offers the same undo every other surface offers. */
async function transitionWorkItem(target: WorkItemStatus): Promise<void> {
  await workStore.transition(target)
  offerUndo()
}

async function closeDetails(): Promise<void> {
  await router.replace({ query: { ...route.query, workItem: undefined } })
  workStore.closeDetails()
  linkStore.reset()
  await nextTick()
  if (detailTriggerId) {
    const triggerId = detailTriggerId
    // Wait until the drawer has left the focus tree before restoring the live collection control.
    requestAnimationFrame(() => {
      document.querySelector<HTMLElement>(`[data-work-item-id="${triggerId}"]`)?.focus()
    })
  }
  detailTriggerId = null
}

function retryDetails(): void {
  if (!principal || !team.value || !project.value || !workStore.state.selectedWorkItemId) return
  void workStore.loadDetails(
    { organizationId: principal.organizationId, teamId: team.value.id, projectId: project.value.id },
    workStore.state.selectedWorkItemId,
    true,
  )
}

function openConversation(): void {
  void router.push({ name: 'conversation', query: route.query })
}

function openLinkedConversation(association: ConversationWorkItemAssociation): void {
  void router.push({
    name: 'conversation',
    query: {
      ...route.query,
      conversation: association.conversation.id,
      project: association.workItem.projectId,
      workItem: association.workItem.id,
      focus: association.workItem.key,
    },
  })
}

function retryLinks(): void {
  if (!principal || !team.value || !project.value || !workStore.state.selectedWorkItemId) return
  void linkStore.loadByWorkItem(
    { organizationId: principal.organizationId, teamId: team.value.id, projectId: project.value.id },
    workStore.state.selectedWorkItemId,
    true,
  )
}

function retryWorkItemActivity(): void {
  if (!selectedWorkItemActivityRoute.value) return
  void teamOpsStore.loadWorkItemActivity(selectedWorkItemActivityRoute.value, {}, false, true)
}

function updateTaskStatus(value: TaskStatus | 'all'): void {
  void router.replace({ query: { ...route.query, taskStatus: value } })
}

function updateTaskOwner(value: string | 'all'): void {
  void router.replace({ query: { ...route.query, taskOwner: value } })
}

function selectTask(task: TaskSummary): void {
  taskDetailTriggerId = task.id
  const query: LocationQueryRaw = {
    ...withoutCodingRoute(route.query),
    task: task.id,
    workItem: task.workItemId,
    // The WorkDesk hint belongs to the Task it was written for; a different Task starts from its own
    // current execution.
    taskExecution: undefined,
  }
  delete query.review
  void router.replace({ query })
}

function selectTaskAttempt(executionId: string): void {
  const taskId = taskStore.state.selectedTaskId
  if (!taskId || selectedTaskExecutionId.value === executionId) return
  selectedTaskExecutionId.value = executionId
  void taskStore.loadRuntimeFacts(taskId, executionId)
  if (!codingScope.value || !project.value) return
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
  // `attempt` now names the selected execution; keeping the consumed hint would let a reload
  // override the member's own choice.
  delete query.taskExecution
  void router.replace({ query })
}

async function closeTaskDetails(): Promise<void> {
  taskStore.stopLiveTasks()
  const query: LocationQueryRaw = {
    ...withoutCodingRoute(route.query),
    task: undefined,
    taskExecution: undefined,
  }
  delete query.review
  await router.replace({ query })
  taskStore.clearSelection()
  codingStore.clearSelection()
  reviewStore.clearSelection()
  deliveryStore.clearSelection()
  selectedTaskExecutionId.value = null
  await nextTick()
  const remainingModals = document.querySelectorAll<HTMLElement>('[role="dialog"][aria-modal="true"]')
  const remainingModal = remainingModals.item(remainingModals.length - 1)
  if (remainingModal) {
    requestAnimationFrame(() => remainingModal.querySelector<HTMLElement>('button:not(:disabled)')?.focus())
    taskDetailTriggerId = null
    return
  }
  if (taskDetailTriggerId) {
    const triggerId = taskDetailTriggerId
    requestAnimationFrame(() => {
      document.querySelector<HTMLElement>(`[data-task-id="${triggerId}"]`)?.focus()
    })
  }
  taskDetailTriggerId = null
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

async function synchronizeCodingStudio(force = false): Promise<void> {
  const scope = codingScope.value
  const taskId = taskStore.state.details?.id
  const selection = codingRouteSelection(route.query)
  if (!scope || !taskId || selection.taskId !== taskId) {
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
  if (route.query.attempt !== executionId || route.query.workspace !== workspaceId) {
    await router.replace({ query: withCodingRoute(route.query, {
      teamId: scope.teamId,
      projectId: scope.projectId,
      workItemId: taskStore.state.details?.workItemId,
      taskId,
      executionId,
      workspaceId,
    }) })
  }
}

async function synchronizeReviewWorkbench(force = false): Promise<void> {
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
  if (!selectedReviewCoordinates.value
    || reviewAttemptKey(selectedReviewCoordinates.value) !== reviewAttemptKey(coordinates)) return
  const selected = reviewStore.state.selectedReviewRequestId
  if (selected && route.query.review !== selected) {
    await router.replace({ query: { ...route.query, review: selected } })
  } else if (!selected && route.query.review) {
    const query = { ...route.query }
    delete query.review
    await router.replace({ query })
  }
}

async function synchronizeDeliveryWorkbench(force = false): Promise<void> {
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
    await deliveryStore.refresh()
    return
  }
  await deliveryStore.synchronize(
    { organizationId: scope.organizationId, teamId: scope.teamId }, coordinates,
  )
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
  await synchronizeCodingStudio(true)
  focusCurrentTaskAttempt()
}

async function retryTaskCommand(): Promise<void> {
  await taskStore.retryTaskCommand()
  await synchronizeCodingStudio(true)
  focusCurrentTaskAttempt()
}

function focusCurrentTaskAttempt(): void {
  const taskId = taskStore.state.details?.id
  const executionId = taskStore.state.details?.currentExecutionId
  if (!taskId || !executionId || selectedTaskExecutionId.value === executionId) return
  selectTaskAttempt(executionId)
  void taskStore.loadRuntimeFacts(taskId, executionId, true)
}

function openTaskConversation(conversationId: string): void {
  void router.push({
    name: 'conversation',
    query: {
      ...withoutCodingRoute(route.query),
      conversation: conversationId,
      project: taskStore.state.details?.projectId,
      workItem: taskStore.state.details?.workItemId,
      task: undefined,
    },
  })
}

function showTaskWorkItem(): void {
  void closeTaskDetails()
}

function openTaskWorkItem(task: TaskSummary): void {
  void router.replace({ query: { ...withoutCodingRoute(route.query), workItem: task.workItemId, task: undefined } })
}

function retryTasks(): void {
  if (!principal || !team.value || !project.value) return
  void taskStore.load(
    { organizationId: principal.organizationId, teamId: team.value.id },
    project.value.id,
    taskStatusFilter.value === 'all' ? undefined : taskStatusFilter.value,
    taskOwnerFilter.value === 'all' ? undefined : taskOwnerFilter.value,
    true,
  )
}

function openDelegate(): void {
  taskStore.clearCreate()
  showDelegate.value = true
}

async function delegateToAgent(input: CreateTaskInput): Promise<void> {
  if (!principal || !team.value || !project.value || !workStore.state.detail) return
  try {
    const taskId = await taskStore.createTask({
      scope: { organizationId: principal.organizationId, teamId: team.value.id },
      projectId: project.value.id,
      workItemId: workStore.state.detail.workItem.id,
      expectedVersion: workStore.state.detail.workItem.version,
      input,
    })
    await finishDelegation(taskId)
  } catch {
    // Store retains the exact command and idempotency key for an explicit retry.
  }
}

async function retryDelegation(): Promise<void> {
  try {
    await finishDelegation(await taskStore.retryCreate())
  } catch {
    // The same request remains available while the server marks it retryable.
  }
}

async function finishDelegation(taskId: string | null): Promise<void> {
  if (taskStore.state.createPhase !== 'success') return
  showDelegate.value = false
  const workItemId = workStore.state.detail?.workItem.id
  if (workItemId && codingScope.value) {
    clearCodingTargetDraft(codingScope.value, workItemId)
    clearTaskDelegationDraft(codingScope.value, codingScope.value.projectId, workItemId)
    taskStore.clearDelegationPreflight(codingScope.value.projectId, workItemId)
  }
  taskStore.clearCreate()
  if (taskId && workItemId) {
    await router.replace({ query: { ...route.query, workItem: workItemId, task: taskId } })
  }
}

function closeDelegate(): void {
  if (taskStore.state.createPhase === 'submitting') return
  showDelegate.value = false
  taskStore.clearCreate()
}

function queryValue(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null
}

function itemsFor(status: WorkItemStatus): WorkItemSummary[] {
  return filteredItems.value.filter(item => item.status === status)
}

function retry(): void {
  if (!principal || !team.value || !project.value) return
  void workStore.load({ organizationId: principal.organizationId, teamId: team.value.id, projectId: project.value.id }, statusFilter.value === 'all' ? undefined : statusFilter.value, true)
}

function oneOf<const T extends readonly string[]>(value: unknown, options: T, fallback: T[number]): T[number] {
  return typeof value === 'string' && (options as readonly string[]).includes(value) ? value as T[number] : fallback
}

</script>

<template>
  <AppShell eyebrow="工作 · 项目范围" :title="project?.name ?? '项目工作区'">
    <template #actions>
      <BaseButton v-if="!project && canManageProjects" size="small" @click="projectCreation.show"><Plus :size="14" />新建项目</BaseButton>
      <RouterLink v-slot="{ navigate }" custom :to="{ name: 'conversation', query: route.query }">
        <BaseButton variant="secondary" size="small" @click="navigate"><MessageSquare :size="14" />带到对话</BaseButton>
      </RouterLink>
    </template>

    <StatePanel v-if="scopeStore.state.phase === 'loading' || scopeStore.state.phase === 'idle'" state="loading" />
    <StatePanel v-else-if="scopeStore.state.phase === 'error'" state="error" :description="scopeStore.state.errorMessage ?? undefined" @retry="scopeStore.reload" />
    <StatePanel v-else-if="scopeStore.state.phase === 'empty'" state="empty" title="还没有可访问的 Team"><template #action><RouterLink :to="{ name: 'onboarding' }"><BaseButton size="small">创建或加入 Team</BaseButton></RouterLink></template></StatePanel>
    <StatePanel v-else-if="!project" state="empty" title="这个 Team 还没有 WorkProject" description="创建 WorkProject 后即可管理团队工作项。">
      <template v-if="canManageProjects" #action><BaseButton size="small" @click="projectCreation.show"><Plus :size="14" />创建 WorkProject</BaseButton></template>
    </StatePanel>

    <div v-else class="work-page page-shell">
      <WorkItemsToolbar
        :team-name="team?.name"
        :project-key="project.key"
        :result-count="filteredItems.length"
        :view="view"
        :status="statusFilter"
        :type="typeFilter"
        :priority="priorityFilter"
        :statuses="workItemStatuses"
        :types="workItemTypes"
        :priorities="workItemPriorities"
        :status-labels="statusLabels"
        :type-labels="workItemTypeLabels"
        :priority-labels="workItemPriorityLabels"
        :can-create="canCreate"
        :sort-key="listSort.key.value"
        :sort-direction="listSort.direction.value"
        :loaded-count="filteredItems.length"
        :has-more="Boolean(workStore.state.nextCursor)"
        @update-query="updateQuery"
        @sort="sortBy"
        @create="openCreate"
      />

      <WorkItemsBulkBar
        v-if="selectionEnabled && (selectedCount > 0 || bulkResults)"
        :selected-count="selectedCount"
        :actionable-count="actionableSelectedItems.length"
        :visible-count="filteredItems.length"
        :targets="bulkTargets"
        :members="responsibilityCandidates"
        :progress="workStore.state.bulkPending"
        :results="bulkResults"
        :can-transition="canParticipate"
        :can-assign="canManageResponsibility"
        :online="isOnline"
        @transition="runBulkTransition"
        @assign="runBulkAssign"
        @select-page="selectVisiblePage"
        @clear-selection="selection.clear"
        @dismiss-results="bulkResults = null"
      />

      <WorkItemsWorkspace
        :phase="workStore.state.phase"
        :error-message="workStore.state.errorMessage"
        :filtered-items="filteredItems"
        :view="view"
        :board-statuses="boardStatuses"
        :status-labels="statusLabels"
        :next-cursor="workStore.state.nextCursor"
        :loading-more="workStore.state.loadingMore"
        :can-create="canCreate"
        :dragged-work-item="draggedWorkItem"
        :drag-over-status="dragOverStatus"
        :board-announcement="boardAnnouncement"
        :pending-item-id="confirmingSubject"
        :confirming-target="confirmingTarget"
        :busy-item-id="workStore.state.rowActionItemId"
        :allow-drop="allowDrop"
        :items-for="itemsFor"
        :selectable="selectionEnabled"
        :is-selected="selection.isSelected"
        :on-create="openCreate"
        :on-retry="retry"
        :on-clear-filters="clearLocalFilters"
        :on-load-more="workStore.loadMore"
        :on-select="selectItem"
        :on-toggle-select="toggleSelect"
        :on-action="runCardAction"
        :on-drag-start="startWorkItemDrag"
        :on-drag-end="endWorkItemDrag"
        :on-drag-over="markDragOver"
        :on-drag-leave="boardDrag.leave"
        :on-drop="boardDrag.pointerDrop"
        :on-board-keydown="handleBoardKeydown"
      />

      <TaskListPanel
        :phase="taskStore.state.phase"
        :items="taskStore.state.items"
        :status="taskStatusFilter"
        :owner-principal-id="taskOwnerFilter"
        :owners="taskOwnerOptions"
        :selected-task-id="taskStore.state.selectedTaskId"
        :next-cursor="taskStore.state.nextCursor"
        :loading-more="taskStore.state.loadingMore"
        :error-message="taskStore.state.errorMessage"
        :on-status-change="updateTaskStatus"
        :on-owner-change="updateTaskOwner"
        :on-select="selectTask"
        :on-open-work-item="openTaskWorkItem"
        :on-retry="retryTasks"
        :on-load-more="taskStore.loadMore"
      />

      <section class="scope-rule"><ShieldCheck :size="16" /><span>URL 保存 Team、WorkProject、视图和筛选状态；服务端仍逐次校验 Membership 与完整 Scope，前端筛选不构成授权边界。</span></section>
    </div>

    <WorkProjectCreateDialog
      v-if="projectCreation.open.value && team"
      :team-name="team.name"
      :submitting="scopeStore.state.projectCommandPending"
      :retryable="scopeStore.state.projectCommandRetryable"
      :error-message="scopeStore.state.projectCommandErrorMessage"
      :check-key="scopeStore.checkWorkProjectKey"
      @close="projectCreation.close"
      @input-changed="scopeStore.clearProjectCommand"
      @submit="projectCreation.submit"
    />

    <WorkItemCreateDialog
      v-if="showCreate && project"
      :project-key="project.key"
      :initial-key="createInitialKey"
      :submitting="workStore.state.commandPending"
      :error-message="workStore.state.commandErrorMessage"
      @close="showCreate = false"
      @submit="createWorkItem"
    />

    <DelegateToAgentDialog
      v-if="showDelegate && workStore.state.detail && codingScope"
      :work-item="workStore.state.detail.workItem"
      :coding-scope="codingScope"
      :responsibilities="workStore.state.responsibilities"
      :submitting="taskStore.state.createPhase === 'submitting'"
      :retryable="taskStore.state.createRetryable"
      :error-message="taskStore.state.createErrorMessage"
      :conversation-source="taskConversationSource"
      :on-submit="delegateToAgent"
      :on-retry="retryDelegation"
      @close="closeDelegate"
    />

    <WorkItemDetailDrawer
      v-if="queryValue(route.query.workItem) && !queryValue(route.query.task)"
      :scope="principalScope"
      :phase="workStore.state.detailPhase"
      :details="workStore.state.detail"
      :error-message="workStore.state.detailErrorMessage"
      :command-pending="workStore.state.detailCommandPending"
      :command-error-message="workStore.state.detailCommandErrorMessage"
      :version-conflict="workStore.state.versionConflict"
      :availability-phase="workStore.state.availabilityPhase"
      :available-transitions="workStore.state.availableTransitions"
      :availability-error-message="workStore.state.availabilityErrorMessage"
      :can-participate="canParticipate"
      :can-delegate="canDelegate"
      :can-manage-responsibility="canManageResponsibility"
      :responsibility-phase="workStore.state.responsibilityPhase"
      :responsibilities="workStore.state.responsibilities"
      :responsibility-candidates="responsibilityCandidates"
      :responsibility-agent-candidates="responsibilityAgentCandidates"
      :responsibility-agent-phase="agentStore.state.agents.phase"
      :responsibility-agent-error-message="agentStore.state.agents.errorMessage"
      :responsibility-agent-loading-more="agentStore.state.agents.loadingMore"
      :responsibility-agent-has-more="agentStore.state.agents.nextOffset !== null"
      :responsibility-error-message="workStore.state.responsibilityErrorMessage"
      :responsibility-command-pending="workStore.state.responsibilityCommandPending"
      :responsibility-command-error-message="workStore.state.responsibilityCommandErrorMessage"
      :timeline-phase="workStore.state.timelinePhase"
      :timeline="workStore.state.timeline"
      :timeline-next-cursor="workStore.state.timelineNextCursor"
      :timeline-loading-more="workStore.state.timelineLoadingMore"
      :timeline-error-message="workStore.state.timelineErrorMessage"
      :association-phase="linkStore.state.phase"
      :associations="linkStore.state.associations"
      :association-error-message="linkStore.state.errorMessage"
      :on-retry="retryDetails"
      :on-retry-availability="retryDetails"
      :on-transition="transitionWorkItem"
      :on-add-comment="workStore.addComment"
      :on-link-resource="workStore.linkResource"
      :on-replace-owner="workStore.replaceOwner"
      :on-assign-executor="workStore.assignExecutor"
      :on-assign-gate-reviewer="workStore.assignGateReviewer"
      :on-assign-advisory-reviewer="workStore.assignAdvisoryReviewer"
      :on-release-responsibility="workStore.releaseResponsibility"
      :on-retry-responsibility-agents="() => loadResponsibilityAgents(true)"
      :on-load-more-responsibility-agents="() => loadResponsibilityAgents(false, true)"
      :on-load-timeline-more="workStore.loadTimelineMore"
      :on-retry-associations="retryLinks"
      @close="closeDetails"
      @conversation="openConversation"
      @open-conversation="openLinkedConversation"
      @delegate="openDelegate"
    >
      <template #activity>
        <ActivityStream
          :phase="selectedWorkItemActivity?.phase ?? 'idle'"
          :items="selectedWorkItemActivity?.value ?? []"
          :next-cursor="selectedWorkItemActivity?.nextCursor ?? null"
          :loading-more="selectedWorkItemActivity?.loadingMore ?? false"
          :error="selectedWorkItemActivity?.error ?? null"
          realtime-phase="idle"
          :online="isOnline"
          compact
          heading="WorkItem Activity"
          description="此工作项在 Team Activity 投影中的公开事实。"
          :principal-names="principalNames"
          @retry="retryWorkItemActivity"
          @load-more="selectedWorkItemActivityRoute && teamOpsStore.loadWorkItemActivity(selectedWorkItemActivityRoute, {}, true)"
        />
      </template>
    </WorkItemDetailDrawer>

    <TaskDetailDrawer
      v-if="queryValue(route.query.task)"
      :phase="taskStore.state.detailPhase"
      :details="taskStore.state.details"
      :attempts="taskStore.state.attempts"
      :selected-execution-id="selectedTaskExecutionId"
      :error-message="taskStore.state.detailErrorMessage"
      :runtime-phase="selectedRuntimeResource?.phase ?? 'idle'"
      :runtime-facts="selectedRuntimeResource?.value ?? null"
      :runtime-error-message="selectedRuntimeResource?.errorMessage ?? null"
      :coding-phase="codingStudioPhase"
      :coding-attempt="selectedCodingAttemptResource?.value ?? null"
      :coding-error-message="codingStudioErrorMessage"
      :coding-commands-phase="selectedCodingCommandsResource?.phase ?? 'idle'"
      :coding-commands="selectedCodingCommandsResource?.value ?? null"
      :coding-commands-error-message="selectedCodingCommandsResource?.errorMessage ?? null"
      :coding-tests-phase="selectedCodingTestsResource?.phase ?? 'idle'"
      :coding-tests="selectedCodingTestsResource?.value ?? null"
      :coding-tests-error-message="selectedCodingTestsResource?.errorMessage ?? null"
      :coding-command-log="codingCommandLog"
      :coding-test-report="codingTestReport"
      :coding-patch-phase="selectedCodingPatchResource?.phase ?? 'idle'"
      :coding-patch="selectedCodingPatchResource?.value ?? null"
      :coding-patch-error-message="selectedCodingPatchResource?.errorMessage ?? null"
      :fleet-phase="runtimeHealthResource?.phase ?? 'idle'"
      :fleet="runtimeHealthResource?.value ?? null"
      :fleet-error-message="runtimeHealthResource?.errorMessage ?? null"
      :association-phase="taskAssociationResource?.phase ?? 'idle'"
      :associations="taskAssociationResource?.value ?? null"
      :association-error-message="taskAssociationResource?.errorMessage ?? null"
      :event-phase="taskEventResource?.phase ?? 'idle'"
      :event-page="taskEventResource?.value ?? null"
      :event-error-message="taskEventResource?.errorMessage ?? null"
      :live-state="taskLiveState"
      :review-list-phase="selectedReviewListResource?.phase ?? 'idle'"
      :reviews="selectedReviewListResource?.value ?? null"
      :selected-review-request-id="reviewStore.state.selectedReviewRequestId"
      :review-detail-phase="selectedReviewDetailResource?.phase ?? 'idle'"
      :review="selectedReviewDetailResource?.value ?? null"
      :review-list-error-message="selectedReviewListResource?.errorMessage ?? null"
      :review-detail-error-message="selectedReviewDetailResource?.errorMessage ?? null"
      :review-comments="selectedReviewCommentsResource?.value ?? []"
      :on-add-review-comment="addReviewComment"
      :review-command="reviewStore.state.command"
      :can-gate-review="canGateReview"
      :can-confirm-delivery="canConfirmDelivery"
      :principals="[...responsibilityCandidates, ...responsibilityAgentCandidates]"
      :can-control="canControlTask"
      :online="isOnline"
      :command-pending="taskStore.state.commandPending"
      :command-error-message="taskStore.state.commandErrorMessage"
      :command-retryable="taskStore.state.commandRetryable"
      :command-version-conflict="taskStore.state.commandVersionConflict"
      :on-select-attempt="selectTaskAttempt"
      :on-retry="retryTaskDetails"
      :on-retry-runtime="retryTaskRuntime"
      :on-retry-coding="retryCodingStudio"
      :on-load-coding-patch="loadCodingPatch"
      :on-load-coding-commands-more="loadCodingCommandsMore"
      :on-load-coding-tests-more="loadCodingTestsMore"
      :on-load-coding-command-log="loadCodingCommandLog"
      :on-load-coding-test-report="loadCodingTestReport"
      :on-retry-fleet="retryRuntimeHealth"
      :on-retry-associations="retryTaskAssociations"
      :on-load-events-more="loadTaskEventsMore"
      :on-retry-events="retryTaskEvents"
      :on-select-review="selectReview"
      :on-retry-reviews="retryReviews"
      :on-retry-review-detail="retryReviewDetail"
      :on-execute-reviewer="executeReviewer"
      :on-decide-review="decideReview"
      :on-request-review-changes="requestReviewChanges"
      :on-retry-review-command="retryReviewCommand"
      :on-clear-review-command="reviewStore.clearCommand"
      :on-command="commandTask"
      :on-retry-command="retryTaskCommand"
      :on-clear-command="taskStore.clearTaskCommand"
      @close="closeTaskDetails"
      @open-work-item="showTaskWorkItem"
      @open-conversation="openTaskConversation"
    />
  </AppShell>
</template>

<style scoped>
.work-content { min-width: 0; }.work-content > :deep(.state-panel) { border: 1px solid var(--cs-border); border-radius: var(--cs-radius-lg); background: var(--cs-surface); }.work-list { display: grid; gap: var(--cs-space-8); }.work-board { display: grid; grid-auto-columns: minmax(255px, 1fr); grid-auto-flow: column; gap: var(--cs-space-12); overflow-x: auto; padding-bottom: var(--cs-space-8); scroll-snap-type: x proximity; }.board-column { min-height: 390px; overflow: hidden; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface-subtle); scroll-snap-align: start; }.board-column > header { display: flex; min-height: 47px; align-items: center; justify-content: space-between; padding: 0 var(--cs-space-12); border-bottom: 1px solid var(--cs-border); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }.board-column__items { display: grid; align-content: start; gap: var(--cs-space-8); padding: var(--cs-space-8); }.board-column__items > p { padding: var(--cs-space-24) var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-xs); text-align: center; }.load-more { display: grid; justify-items: center; gap: var(--cs-space-8); padding: var(--cs-space-16); }.load-more p { margin: 0; color: var(--cs-danger); font-size: var(--cs-text-sm); }.scope-rule { display: flex; align-items: flex-start; gap: var(--cs-space-8); padding: var(--cs-space-12) var(--cs-space-16); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface-subtle); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.scope-rule svg { flex: 0 0 auto; color: var(--cs-text-brand); }
.board-column.drop-target { border-color: var(--cs-border-accent-strong); background: var(--cs-surface-accent); box-shadow: inset 0 0 0 2px var(--cs-ring-brand); }.board-column.drop-rejected { opacity: .62; }
@media (max-width: 767px) { .work-board { grid-auto-columns: minmax(272px, 84vw); } }
</style>
