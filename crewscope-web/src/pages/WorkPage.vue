<script setup lang="ts">
import { MessageSquare, Plus, ShieldCheck } from '@lucide/vue'
import { computed, inject, nextTick, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter, type LocationQuery, type LocationQueryRaw } from 'vue-router'
import { oneOf, queryValue } from '../utils/queryParams'
import { AUTH_PRINCIPAL, can, permissions } from '../app/auth'
import { useNetworkStatus } from '../app/network'
import { useToast } from '../composables/useToast'
import { useCreationEntry } from '../composables/useCreationEntry'
import { useBoardDrag } from '../composables/useBoardDrag'
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
import WorkGraphWorkspace from '../components/domain/WorkGraphWorkspace.vue'
import PinnedProjects from '../components/domain/PinnedProjects.vue'
import SavedViewsControl from '../components/domain/SavedViewsControl.vue'
import WorkItemsBulkBar from '../components/domain/WorkItemsBulkBar.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import AppShell from '../components/layout/AppShell.vue'
import { useAgentStore } from '../domains/agent/store'
import { useScopeStore } from '../domains/scope/store'
import { type CollaborationScope } from '../domains/collaboration/types'
import { principalDisplayName, principalNameDirectory } from '../domains/scope/memberDirectory'
import { createWorkProjectCreationFlow } from '../domains/scope/workProjectCreation'
import type { ConversationWorkItemAssociation } from '../domains/conversation/workItemLinkGateway'
import { useConversationWorkItemLinkStore } from '../domains/conversation/workItemLinkStore'
import { useWorkItemStore } from '../domains/workitem/store'
import { useWorkGraphStore } from '../domains/workgraph/store'
import { clearWorkItemCreateDraft } from '../domains/workitem/createDraft'
import { useUndoOffer } from '../domains/workitem/useUndoOffer'
import { useTransitionConfirm } from '../domains/workitem/useTransitionConfirm'
import { workItemPriorityLabels, workItemResponsibilityRoleLabels, workItemStatusLabels as statusLabels, workItemTypeLabels } from '../domains/workitem/labels'
import { bulkSummaryMessage, summarizeBulkResults, type WorkItemBulkAssignmentRole, type WorkItemBulkRowResult } from '../domains/workitem/bulk'
import {
  bulkTransitionTargets,
  readWorkItemSortKey,
  type WorkItemSortKey,
} from '../domains/workitem/list'
import { useTaskStore } from '../domains/task/store'
import { clearTaskDelegationDraft } from '../domains/task/delegationDraft'
import type { ResultReference } from '../domains/workitem/outcome'
import { clearCodingTargetDraft } from '../domains/coding/draft'
import {
  withoutCodingRoute,
} from '../domains/coding/route'
import { useReviewStore } from '../domains/review/store'
import { useDeliveryStore } from '../domains/delivery/store'
import { useInjectionStore } from '../domains/injection/store'
import { useTeamOpsStore, workItemActivityCacheKey } from '../domains/teamops/store'
import type { WorkItemActivityRoute } from '../domains/teamops/types'
import type { CodingScope } from '../domains/coding/types'
import type { PrincipalScope } from '../domains/principal/types'
import {
  taskStatuses,
  type CreateTaskInput,
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
import { usePageRequestScope } from '../composables/usePageRequestScope'
import { useCollaborationPresence } from '../composables/useCollaborationPresence'
import { useWorkSavedViews } from '../composables/useWorkSavedViews'
import { useWorkTaskWorkbench } from '../composables/useWorkTaskWorkbench'

/** M11-F02: the third view is the blocking graph — same route, same scope, its own shell. */
type WorkView = 'list' | 'board' | 'graph'
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
// Task command continuations key on the coding route coordinates only. The page itself writes
// bypass query parameters after refreshes (review selection, focus keys, list filters); those
// writes must not invalidate an in-flight command continuation, while unloading or switching
// the routed work item/task/attempt/workspace still does.
const pageRequests = usePageRequestScope(undefined, () => JSON.stringify([
  route.path,
  route.query.team, route.query.project, route.query.workItem,
  route.query.task, route.query.attempt, route.query.workspace,
]))
const principal = inject(AUTH_PRINCIPAL)
const scopeStore = useScopeStore()
const agentStore = useAgentStore()
const workStore = useWorkItemStore()
// M11-F02: the blocking aggregate backs the「被 N 项阻塞」badges in list/board and the whole
// graph view. The page loads it; the detail drawer's relations panel pulls its own anchor.
const graphStore = useWorkGraphStore()
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
const reviewStore = useReviewStore()
const deliveryStore = useDeliveryStore()
const injectionStore = useInjectionStore()
const teamOpsStore = useTeamOpsStore()
const isOnline = useNetworkStatus()
const toast = useToast()
/**
 * The URL owns the ordering, so a link pasted into a message reopens the same list. The key is the
 * server's own sort name (M9b-A06): the direction of every ordering is fixed in the server
 * contract, which is why there is no direction here to read or write.
 */
const sortKey = computed<WorkItemSortKey>(() => readWorkItemSortKey(route.query.sort))
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
const view = computed<WorkView>(() => oneOf(route.query.view, ['list', 'board', 'graph'] as const, 'list'))
/** The toolbar and workspace only ever see the two collection views (M11-F02): the graph branch
    renders its own shell, so narrowing here keeps their prop types honest. */
const collectionView = computed<'list' | 'board'>(() => (view.value === 'graph' ? 'list' : view.value))

// --- R41 saved views: the member's own filter definitions for this list, on this machine --
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
// M11-F01: board presence rides the project scope of the collaboration channel. The
// subscription follows the board view itself — the list view costs no signal traffic.
const boardCollabScope = computed<CollaborationScope | null>(() => {
  if (view.value !== 'board' || !principal || !team.value || !project.value) return null
  return {
    organization: principal.organizationId,
    team: team.value.id,
    resourceType: 'work_project',
    resourceId: project.value.id,
  }
})
const { presence: boardPresence, degraded: collaborationDegraded } = useCollaborationPresence(boardCollabScope)
const showCreate = ref(false)
useCreationEntry('work', () => canCreate.value && scopeStore.state.phase === 'ready'
  ? project.value?.id ?? null : null, openCreate)
const showDelegate = ref(false)
const delegatePrefillNote = ref('')
watch(() => [scopeStore.state.selectedTeamId, scopeStore.state.selectedProjectId], () => {
  showCreate.value = false
  showDelegate.value = false
  resetTransition()
}, { flush: 'sync' })
watch(() => route.query.workItem, () => { showDelegate.value = false; resetTransition() }, { flush: 'sync' })
let detailTriggerId: string | null = null
let taskDetailTriggerId: string | null = null
const selectedWorkItemActivityRoute = computed<WorkItemActivityRoute | null>(() => {
  const projectId = scopeStore.state.selectedProjectId
  const workItemId = workStore.state.detail?.workItem.id
  return projectId && workItemId ? { projectId, workItemId } : null
})
const selectedWorkItemActivity = computed(() => {
  const coordinates = selectedWorkItemActivityRoute.value
  return coordinates ? teamOpsStore.state.workItemActivity[workItemActivityCacheKey(coordinates)] ?? null : null
})

/**
 * The rows the server returned for the applied filter and ordering.
 *
 * Filtering and ordering are the server's (M9b-A06): the URL's status/type/priority/sort travel
 * with the list query, so what arrives is already narrowed and ordered for the whole project, not
 * just the loaded page — the row order below is the order the server chose and is never reshuffled
 * here, because a shuffle would only ever apply to the rows in hand.
 */
const filteredItems = computed(() => workStore.state.items)

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
// Everything anchored on `route.query.task` — the selected-execution state machine, the resource
// projections, the three synchronizers and their watches, and the command wrappers around the
// task/coding/review/delivery stores — lives in one composable (M12b-F05). The drawer subscribes
// to this object directly; only its page-level entry points (close path, unmount cleanup) are
// reached through the `taskWorkbench` handle below.
const taskWorkbench = useWorkTaskWorkbench({ route, router, pageRequests, principal, team, codingScope, canControlTask })
/**
 * The WorkItem-level §4.7 level-5 feed: the real parallel Task list, offered only while the member
 * has not opened a Task — the summary's selectionRequired flag says the server refuses to guess too.
 */
const workItemParallelTasks = computed(() => {
  const workItem = workStore.state.detail?.workItem
  return workItem ? taskStore.state.items.filter(task => task.workItemId === workItem.id) : []
})
const workItemTaskSelectionRequired = computed(() => Boolean(
  workStore.state.detail?.workItem.summary?.selectionRequired && !queryValue(route.query.task),
))

// R41 saved views and the URL canonicalization pass that keeps filter links shareable live in
// one composable: the stored default applies once on entry, then every read key is written back
// canonically (or dropped when it equals the default spelling).
const { savedViewsOwner, committedWorkFilters, applySavedView } = useWorkSavedViews({
  route, router, principal, scopeStore,
  view, statusFilter, typeFilter, priorityFilter, sortKey,
})

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
  const pageOwner = pageRequests.capture()
  if (!principal || !team.value) return
  agentStore.activateScope({ organizationId: principal.organizationId, teamId: team.value.id })
  await agentStore.loadAgents(more, force)
  if (!pageOwner.isCurrent()) return
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
    // The drawer's injection panel self-pulls per execution; its store only needs the Team scope.
    injectionStore.activateScope({ organizationId: principal.organizationId, teamId })
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
  () => [scopeStore.state.phase, scopeStore.state.selectedTeamId, scopeStore.state.selectedProjectId, statusFilter.value, typeFilter.value, priorityFilter.value, sortKey.value] as const,
  ([phase, teamId, projectId, status, type, priority, sort]) => {
    if (phase !== 'ready' || !teamId || !projectId || !principal) {
      if (phase === 'ready' && !projectId) workStore.reset()
      return
    }
    // Every dimension travels with the query (M9b-A06): a change to any of them mints a different
    // continuation scope, so the store restarts the list from its first page rather than trying to
    // continue a cursor the server will refuse.
    void workStore.load(
      { organizationId: principal.organizationId, teamId, projectId },
      {
        status: status === 'all' ? undefined : status,
        type: type === 'all' ? undefined : [type],
        priority: priority === 'all' ? undefined : [priority],
        sort,
      },
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

// M11-F02: the blocking aggregate is team+project scoped, so it rides its own watch — a filter or
// sort change does not mint a new scope, and the store skips an identical re-entry. Outside the
// graph view a failure stays silent: an absent badge reads as「not blocked」, and the graph shell
// surfaces its own error state with a retry.
watch(
  () => [scopeStore.state.phase, scopeStore.state.selectedTeamId, scopeStore.state.selectedProjectId] as const,
  ([phase, teamId, projectId]) => {
    if (phase !== 'ready' || !teamId || !projectId || !principal) return
    void graphStore.loadBlockedGraph({ organizationId: principal.organizationId, teamId }, projectId)
  },
  { immediate: true },
)

// The association Store is shared with Conversation Mode. Do not retain a WorkItem-scoped
// response after this route leaves the tree; the next entry must read current server facts.
onUnmounted(() => {
  workStore.closeDetails()
  linkStore.reset()
  taskStore.stopLiveTasks()
  reviewStore.reset()
  deliveryStore.reset()
  taskWorkbench.cancelLiveFactRefresh()
})

function updateQuery(name: 'view' | 'status' | 'type' | 'priority', value: string): void {
  void router.replace({ query: { ...route.query, [name]: value } })
}

/**
 * M11-F02: a graph node asks for its drawer. The detail watch below loads by the query key alone,
 * so this is a plain query write — a node that exists only as a blocker (not in the loaded list)
 * still opens, because the drawer's facts come from loadDetails, not from the list.
 */
function openGraphItem(workItemId: string): void {
  void router.replace({ query: { ...route.query, workItem: workItemId } })
}

/** M11-F02: the badge count one card shows (0 = no badge). The graph store owns the number. */
function blockedCountOf(workItemId: string): number {
  return graphStore.blockedCountOf(workItemId)
}

/** A pinned entry hands the member to that project's list; the scope follows the URL (R41). */
function updateQueryProject(projectId: string): void {
  void router.replace({ query: { ...route.query, project: projectId } })
}

/**
 * Asks the server for the ordering the member pressed.
 *
 * There is nothing to toggle: each ordering runs in the direction the server contract fixed (newest
 * first, most urgent first, soonest due first), so pressing the active key again is the honest
 * no-op it always appeared to be, and the URL records only the sort name.
 */
function sortBy(key: WorkItemSortKey): void {
  if (key === sortKey.value) return
  void router.replace({ query: { ...route.query, sort: key } })
}

/**
 * Runs one action across the selection.
 *
 * Same verdicts as a single card, in a different shape: the batch goes through the Store's row path,
 * which posts the same command to the same endpoint with a fresh idempotency key per row, and the
 * member is told what happened to every row — including the ones nothing happened to and why.
 */
async function runBulkTransition(targetStatus: WorkItemStatus): Promise<void> {
  const pageOwner = pageRequests.captureSelection()
  const scope = workScope.value
  const rows = actionableSelectedItems.value
  if (!scope || !rows.length) return
  const label = bulkTargets.value.find(target => target.targetStatus === targetStatus)?.label
    ?? statusLabels[targetStatus]
  const results = await workStore.transitionRows(scope, rows, targetStatus, pageOwner.isCurrent)
  if (!pageOwner.isCurrent()) return
  reportBulk(results, label)
}

/** Assigns one responsibility across the selection, through the existing assignment commands. */
async function runBulkAssign(payload: { role: WorkItemBulkAssignmentRole; actorPrincipalId: string }): Promise<void> {
  const pageOwner = pageRequests.captureSelection()
  const scope = workScope.value
  const rows = actionableSelectedItems.value
  if (!scope || !rows.length) return
  const member = responsibilityCandidates.value
    .find(candidate => candidate.principalId === payload.actorPrincipalId)?.displayName
  const label = `${workItemResponsibilityRoleLabels[payload.role]}指派给${member ?? '所选成员'}`
  const results = await workStore.assignRows(scope, rows, payload.role, payload.actorPrincipalId, pageOwner.isCurrent)
  if (!pageOwner.isCurrent()) return
  reportBulk(results, label)
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

function clearWorkFilters(): void {
  // Clear the applied filters atomically so concurrent router replacements cannot restore one stale value.
  void router.replace({ query: { ...route.query, status: 'all', type: 'all', priority: 'all' } })
}

function openCreate(): void {
  showCreate.value = true
}

async function createWorkItem(input: CreateWorkItemInput): Promise<void> {
  const pageOwner = pageRequests.captureSelection()
  try {
    const createdId = await workStore.create(input)
    if (!pageOwner.isCurrent()) return
    // Only a successful create discards the browser draft; failures keep it for retry.
    if (principalScope.value && project.value) clearWorkItemCreateDraft(principalScope.value, project.value.key, principal)
    showCreate.value = false
    if (createdId) await router.replace({ query: { ...route.query, workItem: createdId } })
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
  const pageOwner = pageRequests.captureSelection()
  if (!principal || !team.value || !project.value) return
  const result = await workStore.transitionFromRow(
    { organizationId: principal.organizationId, teamId: team.value.id, projectId: project.value.id },
    item,
    target,
    pageOwner.isCurrent,
  )
  if (!pageOwner.isCurrent()) return
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
  const pageOwner = pageRequests.captureSelection()
  await submitTransition(item.id, action, async transition => runRowAction(item, transition.targetStatus))
  if (!pageOwner.isCurrent()) return
}

/** Runs a drawer-initiated transition and offers the same undo every other surface offers. */
async function transitionWorkItem(target: WorkItemStatus): Promise<void> {
  const pageOwner = pageRequests.captureSelection()
  await workStore.transition(target)
  if (!pageOwner.isCurrent()) return
  offerUndo()
}

async function closeDetails(): Promise<void> {
  const pageOwner = pageRequests.capture()
  const navigationFailure = await router.replace({ query: { ...route.query, workItem: undefined } })
  if (navigationFailure || !pageOwner.isCurrent()) return
  workStore.closeDetails()
  linkStore.reset()
  await nextTick()
  if (!pageOwner.isCurrent()) return
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

function openParallelTask(taskId: string): void {
  const task = taskStore.state.items.find(candidate => candidate.id === taskId)
  if (task) selectTask(task)
}

/**
 * The WorkItem outcome panel's evidence jump: it opens the Task the reference provably belongs
 * to, anchored on the referenced execution (§4.1), and hands the pinned reference to the Task
 * workspace so a hash mismatch is stated there instead of silently glossed over. The handoff is
 * page state, never a URL parameter — the S01 query whitelist is frozen.
 */
const taskOutcomeHandoff = ref<ResultReference | null>(null)

function openTaskFromOutcome(reference: ResultReference, taskId: string): void {
  if (reference.kind !== 'coding-attempt') return
  taskOutcomeHandoff.value = reference
  const task = taskStore.state.items.find(candidate => candidate.id === taskId)
  const query: LocationQueryRaw = {
    ...withoutCodingRoute(route.query),
    task: taskId,
    workItem: task?.workItemId ?? queryValue(route.query.workItem) ?? undefined,
    taskExecution: reference.executionId,
  }
  delete query.review
  void router.replace({ query })
}

// Leaving the Task workspace ends the pinned comparison — reopening a Task later starts fresh.
watch(() => route.query.task, value => {
  if (!value) taskOutcomeHandoff.value = null
})

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

/**
 * Resolves the drawer's close path: routing first, then the workbench's own selection clear
 * (one call for the task/coding/review/delivery stores), then focus restoration.
 */
async function closeTaskDetails(): Promise<void> {
  const pageOwner = pageRequests.capture()
  taskStore.stopLiveTasks()
  const query: LocationQueryRaw = {
    ...withoutCodingRoute(route.query),
    task: undefined,
    taskExecution: undefined,
  }
  delete query.review
  await router.replace({ query })
  if (!pageOwner.isCurrent()) return
  taskWorkbench.clearTaskSelection()
  await nextTick()
  if (!pageOwner.isCurrent()) return
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

function openDelegate(note?: string): void {
  taskStore.clearCreate()
  // R42: the “让 Agent 处理” comment intent pre-fills the dialog objective; a plain open clears it.
  delegatePrefillNote.value = typeof note === 'string' && note.trim() ? note.trim() : ''
  showDelegate.value = true
}

async function delegateToAgent(input: CreateTaskInput): Promise<void> {
  const pageOwner = pageRequests.captureSelection()
  if (!principal || !team.value || !project.value || !workStore.state.detail) return
  try {
    const taskId = await taskStore.createTask({
      scope: { organizationId: principal.organizationId, teamId: team.value.id },
      projectId: project.value.id,
      workItemId: workStore.state.detail.workItem.id,
      expectedVersion: workStore.state.detail.workItem.version,
      input,
    })
    if (!pageOwner.isCurrent()) return
    await finishDelegation(taskId)
    if (!pageOwner.isCurrent()) return
  } catch {
    // Store retains the exact command and idempotency key for an explicit retry.
  }
}

/** “仅分配”意图：复用既有 Executor 责任命令，不创建 Task、不启动执行。 */
async function assignExecutorOnly(target: { agentPrincipalId: string }): Promise<void> {
  try {
    await workStore.assignExecutor(target.agentPrincipalId)
  } catch {
    // Store 已保留服务端原因并刷新责任链；对话框继续展示，成员可确认后重试。
    return
  }
  showDelegate.value = false
  const projectId = codingScope.value?.projectId
  const workItemId = workStore.state.detail?.workItem.id
  if (projectId && workItemId) taskStore.clearDelegationContext(projectId, workItemId)
}

async function retryDelegation(): Promise<void> {
  const pageOwner = pageRequests.captureSelection()
  try {
    const taskId = await taskStore.retryCreate()
    if (!pageOwner.isCurrent()) return
    await finishDelegation(taskId)
    if (!pageOwner.isCurrent()) return
  } catch {
    // The same request remains available while the server marks it retryable.
  }
}

async function finishDelegation(taskId: string | null): Promise<void> {
  const pageOwner = pageRequests.capture()
  if (taskStore.state.createPhase !== 'success') return
  showDelegate.value = false
  const workItemId = workStore.state.detail?.workItem.id
  if (workItemId && codingScope.value) {
    clearCodingTargetDraft(codingScope.value, workItemId, principal)
    clearTaskDelegationDraft(codingScope.value, codingScope.value.projectId, workItemId, principal)
    taskStore.clearDelegationPreflight(codingScope.value.projectId, workItemId)
    taskStore.clearDelegationContext(codingScope.value.projectId, workItemId)
  }
  taskStore.clearCreate()
  if (taskId && workItemId) {
    await router.replace({ query: { ...route.query, workItem: workItemId, task: taskId } })
    if (!pageOwner.isCurrent()) return
  }
}

function closeDelegate(): void {
  if (taskStore.state.createPhase === 'submitting') return
  showDelegate.value = false
  taskStore.clearCreate()
}

function itemsFor(status: WorkItemStatus): WorkItemSummary[] {
  return filteredItems.value.filter(item => item.status === status)
}

function retry(): void {
  if (!principal || !team.value || !project.value) return
  void workStore.load({ organizationId: principal.organizationId, teamId: team.value.id, projectId: project.value.id }, statusFilter.value === 'all' ? undefined : statusFilter.value, true)
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
    <StatePanel v-else-if="scopeStore.state.phase === 'empty'" state="empty" title="还没有可访问的 Team" description="创建你的第一个 Team；收到邀请链接？直接打开即可加入。"><template #action><RouterLink :to="{ name: 'onboarding' }"><BaseButton size="small">创建 Team</BaseButton></RouterLink></template></StatePanel>
    <StatePanel v-else-if="!project" state="empty" title="这个 Team 还没有 WorkProject" description="创建 WorkProject 后即可管理团队工作项。">
      <template v-if="canManageProjects" #action><BaseButton size="small" @click="projectCreation.show"><Plus :size="14" />创建 WorkProject</BaseButton></template>
    </StatePanel>

    <div v-else class="work-page page-shell page-shell--full">
      <!-- M11-F02: the third view. The graph owns the whole column — no filters (the aggregate is
           the blocking DAG; filtering it would hide a blocker behind the filter), no bulk bar, no
           task panel. Its shell carries the tri-state switcher back to the collection views. -->
      <WorkGraphWorkspace
        v-if="view === 'graph'"
        :team-name="team?.name"
        :project-key="project.key"
        :scope="workScope"
        @switch-view="next => updateQuery('view', next)"
        @open-item="openGraphItem"
      />

      <template v-else>
      <WorkItemsToolbar
        :result-count="filteredItems.length"
        :view="collectionView"
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
        :sort-key="sortKey"
        :loaded-count="filteredItems.length"
        :has-more="Boolean(workStore.state.nextCursor)"
        @update-query="updateQuery"
        @sort="sortBy"
        @create="openCreate"
      >
        <template #entry>
          <SavedViewsControl route-name="work" :filters="committedWorkFilters" :owner="savedViewsOwner" @apply="applySavedView" />
        </template>
      </WorkItemsToolbar>

      <PinnedProjects
        class="work-pins"
        :owner="savedViewsOwner"
        :projects="scopeStore.state.projects"
        :active-project-id="scopeStore.state.selectedProjectId"
        @select="projectId => updateQueryProject(projectId)"
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
        :has-applied-filters="statusFilter !== 'all' || typeFilter !== 'all' || priorityFilter !== 'all'"
        :view="collectionView"
        :board-statuses="boardStatuses"
        :status-labels="statusLabels"
        :board-presence="boardPresence"
        :collaboration-degraded="collaborationDegraded"
        :next-cursor="workStore.state.nextCursor"
        :loading-more="workStore.state.loadingMore"
        :can-create="canCreate"
        :dragged-work-item="draggedWorkItem"
        :drag-over-status="dragOverStatus"
        :board-announcement="boardAnnouncement"
        :pending-item-id="confirmingSubject"
        :confirming-target="confirmingTarget"
        :busy-item-id="workStore.state.rowActionItemId"
        :blocked-count-of="blockedCountOf"
        :allow-drop="allowDrop"
        :items-for="itemsFor"
        :selectable="selectionEnabled"
        :is-selected="selection.isSelected"
        :on-create="openCreate"
        :on-retry="retry"
        :on-clear-filters="clearWorkFilters"
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
      </template>

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
      :scope="principalScope"
      :submitting="workStore.state.commandPending"
      :error-message="workStore.state.commandErrorMessage"
      @close="showCreate = false"
      @submit="createWorkItem"
    />

    <DelegateToAgentDialog
      v-if="showDelegate && workStore.state.detail && codingScope"
      :work-item="workStore.state.detail.workItem"
      :coding-scope="codingScope"
      :submitting="taskStore.state.createPhase === 'submitting' || workStore.state.responsibilityCommandPending === 'executor'"
      :retryable="taskStore.state.createRetryable"
      :error-message="taskStore.state.createErrorMessage ?? workStore.state.responsibilityCommandErrorMessage"
      :conversation-source="taskConversationSource"
      :prefill-note="delegatePrefillNote"
      :on-submit="delegateToAgent"
      :on-assign="assignExecutorOnly"
      :on-retry="retryDelegation"
      @close="closeDelegate"
    />

    <!-- M12b-F05: the drawer subscribes to the work-item/agent/link stores itself and calls the
         work-item store commands directly; only page-owned verdicts and page retry/load
         orchestration stay as props. -->
    <WorkItemDetailDrawer
      v-if="queryValue(route.query.workItem) && !queryValue(route.query.task)"
      :key="String(route.query.workItem ?? '')"
      :scope="principalScope"
      :can-participate="canParticipate"
      :can-delegate="canDelegate"
      :can-manage-responsibility="canManageResponsibility"
      :responsibility-candidates="responsibilityCandidates"
      :responsibility-agent-candidates="responsibilityAgentCandidates"
      :on-retry="retryDetails"
      :on-content-saved="() => { retryDetails(); retry() }"
      :on-retry-availability="retryDetails"
      :on-transition="transitionWorkItem"
      :on-retry-responsibility-agents="() => loadResponsibilityAgents(true)"
      :on-load-more-responsibility-agents="() => loadResponsibilityAgents(false, true)"
      :on-retry-associations="retryLinks"
      :parallel-tasks="workItemParallelTasks"
      :task-selection-required="workItemTaskSelectionRequired"
      :on-open-task="openParallelTask"
      :on-open-outcome-reference="openTaskFromOutcome"
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

    <!-- M12b-F05: the drawer subscribes to the workbench object and the task/review stores itself;
         only page-owned verdicts (permissions, candidate roster, outcome handoff) stay as props. -->
    <TaskDetailDrawer
      v-if="queryValue(route.query.task)"
      :workbench="taskWorkbench"
      :can-gate-review="canGateReview"
      :can-confirm-delivery="canConfirmDelivery"
      :principals="[...responsibilityCandidates, ...responsibilityAgentCandidates]"
      :can-control="canControlTask"
      :pinned-result-reference="taskOutcomeHandoff"
      @close="closeTaskDetails"
      @open-work-item="showTaskWorkItem"
      @open-conversation="openTaskConversation"
    />
  </AppShell>
</template>

<style scoped>
/* M12b-F01⑦：看板/列表视图的样式已随 M11-F02 移入 WorkItemsWorkspace（自带 scoped 副本），
   本组件模板对那批类零引用——原样三行在此永不匹配，整段清除，只留本页仍在用的两块。 */
.work-pins { margin: var(--cs-space-4) 0 var(--cs-space-4) var(--cs-space-2); }
.scope-rule { display: flex; align-items: flex-start; gap: var(--cs-space-8); padding: var(--cs-space-12) var(--cs-space-16); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface-subtle); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.scope-rule svg { flex: 0 0 auto; color: var(--cs-text-brand); }
</style>
