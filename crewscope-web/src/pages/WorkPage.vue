<script setup lang="ts">
import { Columns3, Filter, List, MessageSquare, Plus, ShieldCheck, X } from '@lucide/vue'
import { computed, inject, nextTick, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AUTH_PRINCIPAL, can, permissions } from '../app/auth'
import { useNetworkStatus } from '../app/network'
import BaseButton from '../components/base/BaseButton.vue'
import StatusBadge from '../components/base/StatusBadge.vue'
import WorkItemCard from '../components/domain/WorkItemCard.vue'
import WorkItemDetailDrawer from '../components/domain/WorkItemDetailDrawer.vue'
import DelegateToAgentDialog from '../components/domain/DelegateToAgentDialog.vue'
import TaskListPanel from '../components/domain/TaskListPanel.vue'
import TaskDetailDrawer from '../components/domain/TaskDetailDrawer.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import AppShell from '../components/layout/AppShell.vue'
import { useScopeStore } from '../domains/scope/store'
import type { ConversationWorkItemAssociation } from '../domains/conversation/workItemLinkGateway'
import { useConversationWorkItemLinkStore } from '../domains/conversation/workItemLinkStore'
import { useWorkItemStore } from '../domains/workitem/store'
import { useTaskStore } from '../domains/task/store'
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
  type CreateWorkItemInput,
  type WorkItemPriority,
  type WorkItemStatus,
  type WorkItemSummary,
  type WorkItemType,
} from '../domains/workitem/types'

type WorkView = 'list' | 'board'
type FilterValue<T extends string> = T | 'all'

const route = useRoute()
const router = useRouter()
const principal = inject(AUTH_PRINCIPAL)
const scopeStore = useScopeStore()
const workStore = useWorkItemStore()
const linkStore = useConversationWorkItemLinkStore()
const taskStore = useTaskStore()
const isOnline = useNetworkStatus()
const team = scopeStore.selectedTeam
const project = scopeStore.selectedProject
const canCreate = computed(() => Boolean(principal && can(principal, permissions.workCreate)))
const canParticipate = computed(() => Boolean(principal && can(principal, permissions.workParticipate)))
const canManageResponsibility = computed(() => Boolean(principal && can(principal, permissions.responsibilityManage)))
const responsibilityCandidates = computed(() => scopeStore.state.members
  .filter(member => member.status === 'ACTIVE')
  .map(member => ({
    principalId: member.userPrincipalId,
    displayName: member.userPrincipalId === principal?.id
      ? principal.displayName
      : `团队成员 · ${member.userPrincipalId.slice(0, 8)}`,
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
    displayName: `负责人 · ${taskOwnerFilter.value.slice(0, 8)}`,
  }]
})
const taskConversationSource = computed(() => {
  const conversationId = queryValue(route.query.conversation)
  const messageId = queryValue(route.query.sourceMessage)
  return conversationId && messageId ? { conversationId, messageId } : null
})
const showCreate = ref(false)
const showDelegate = ref(false)
const submitted = ref(false)
const titleInput = ref<HTMLInputElement | null>(null)
const form = reactive({ key: '', type: 'TASK' as WorkItemType, title: '', description: '', priority: 'MEDIUM' as WorkItemPriority, labels: '', dueAt: '' })
let detailTriggerId: string | null = null
let taskDetailTriggerId: string | null = null
const selectedTaskExecutionId = ref<string | null>(null)

const selectedRuntimeResource = computed(() => {
  const taskId = taskStore.state.selectedTaskId
  const executionId = selectedTaskExecutionId.value
  return taskId && executionId ? taskStore.state.runtimeFacts[`${taskId}:${executionId}`] ?? null : null
})
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
const taskForbidden = computed(() => [
  taskStore.state.errorStatus,
  taskStore.state.detailErrorStatus,
  taskStore.state.createErrorStatus,
  taskStore.state.commandErrorStatus,
  selectedRuntimeResource.value?.errorStatus,
  runtimeHealthResource.value?.errorStatus,
  taskAssociationResource.value?.errorStatus,
  taskEventResource.value?.errorStatus,
  ...Object.values(taskStore.state.associationPages).map(resource => resource.errorStatus),
].some(status => status === 403))
let liveFactRefreshTimer: ReturnType<typeof setTimeout> | null = null

const filteredItems = computed(() => workStore.state.items.filter(item =>
  (typeFilter.value === 'all' || item.type === typeFilter.value)
  && (priorityFilter.value === 'all' || item.priority === priorityFilter.value),
))

const boardStatuses = computed<WorkItemStatus[]>(() => {
  if (statusFilter.value !== 'all') return [statusFilter.value]
  const workflow: WorkItemStatus[] = ['BACKLOG', 'READY', 'IN_PROGRESS', 'IN_REVIEW', 'BLOCKED', 'DONE']
  for (const terminal of ['CANCELLED', 'ARCHIVED'] as const) {
    if (filteredItems.value.some(item => item.status === terminal)) workflow.push(terminal)
  }
  return workflow
})

const formValid = computed(() => (
  /^[A-Z][A-Z0-9]{1,9}-[1-9][0-9]*$/.test(form.key.trim())
  && form.key.startsWith(`${project.value?.key ?? ''}-`)
  && form.title.trim().length > 0
  && form.title.trim().length <= 240
))
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

watch(
  () => [scopeStore.state.phase, route.query.view, route.query.status, route.query.type, route.query.priority],
  () => {
    // Avoid changing the route while AppShell is still restoring a Team/Project deep link.
    if (scopeStore.state.phase !== 'ready') return
    const canonical = {
      ...route.query,
      view: view.value,
      status: statusFilter.value,
      type: typeFilter.value,
      priority: priorityFilter.value,
    }
    if (
      route.query.view !== canonical.view
      || route.query.status !== canonical.status
      || route.query.type !== canonical.type
      || route.query.priority !== canonical.priority
    ) void router.replace({ query: canonical })
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
      if (taskStore.state.details && ['COMPLETED', 'FAILED', 'CANCELLED'].includes(taskStore.state.details.status)) {
        taskStore.stopLiveTasks()
      }
    }, 350)
  },
)

watch(
  () => [taskStore.state.details?.id, taskStore.state.details?.currentExecutionId, taskStore.state.attempts.map(item => item.id).join(',')] as const,
  ([taskId, currentExecutionId]) => {
    if (!taskId) {
      selectedTaskExecutionId.value = null
      return
    }
    const selectedStillExists = taskStore.state.attempts.some(item => item.id === selectedTaskExecutionId.value)
    selectedTaskExecutionId.value = selectedStillExists
      ? selectedTaskExecutionId.value
      : currentExecutionId ?? taskStore.state.attempts[0]?.id ?? null
    if (selectedTaskExecutionId.value) {
      void taskStore.loadRuntimeFacts(taskId, selectedTaskExecutionId.value)
    }
    void taskStore.loadAssociations(taskId)
    // Refresh this no-store server projection whenever a Task detail is opened.
    void taskStore.loadRuntimeHealth(true)
  },
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
    const scope = { organizationId: principal.organizationId, teamId, projectId }
    void Promise.all([
      workStore.loadDetails(scope, workItemId),
      linkStore.loadByWorkItem(scope, workItemId),
    ])
  },
  { immediate: true },
)

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

// The association Store is shared with Conversation Mode. Do not retain a WorkItem-scoped
// response after this route leaves the tree; the next entry must read current server facts.
onUnmounted(() => {
  linkStore.reset()
  taskStore.stopLiveTasks()
  if (liveFactRefreshTimer) clearTimeout(liveFactRefreshTimer)
})

function updateQuery(name: 'view' | 'status' | 'type' | 'priority', value: string): void {
  void router.replace({ query: { ...route.query, [name]: value } })
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
  Object.assign(form, { key: `${prefix}${lastNumber + 1}`, type: 'TASK', title: '', description: '', priority: 'MEDIUM', labels: '', dueAt: '' })
  submitted.value = false
  showCreate.value = true
  void nextTick(() => titleInput.value?.focus())
}

async function createWorkItem(): Promise<void> {
  submitted.value = true
  if (!formValid.value) return
  const input: CreateWorkItemInput = {
    key: form.key.trim(),
    type: form.type,
    title: form.title.trim(),
    description: form.description.trim() || null,
    priority: form.priority,
    labels: [...new Set(form.labels.split(',').map(value => value.trim()).filter(Boolean))],
    dueAt: form.dueAt ? new Date(form.dueAt).toISOString() : null,
  }
  try {
    await workStore.create(input)
    showCreate.value = false
    submitted.value = false
  } catch {
    // The Store publishes a sanitized command error; global handling owns unexpected details.
  }
}

function selectItem(item: WorkItemSummary): void {
  detailTriggerId = item.id
  void router.replace({ query: { ...route.query, workItem: item.id, focus: item.key } })
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

function updateTaskStatus(value: TaskStatus | 'all'): void {
  void router.replace({ query: { ...route.query, taskStatus: value } })
}

function updateTaskOwner(value: string | 'all'): void {
  void router.replace({ query: { ...route.query, taskOwner: value } })
}

function selectTask(task: TaskSummary): void {
  taskDetailTriggerId = task.id
  void router.replace({ query: {
    ...route.query,
    task: task.id,
    workItem: task.workItemId,
  } })
}

function selectTaskAttempt(executionId: string): void {
  const taskId = taskStore.state.selectedTaskId
  if (!taskId || selectedTaskExecutionId.value === executionId) return
  selectedTaskExecutionId.value = executionId
  void taskStore.loadRuntimeFacts(taskId, executionId)
}

async function closeTaskDetails(): Promise<void> {
  taskStore.stopLiveTasks()
  await router.replace({ query: { ...route.query, task: undefined } })
  taskStore.clearSelection()
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

async function commandTask(operation: MemberTaskCommandOperation, reason?: string): Promise<void> {
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
  })
  focusCurrentTaskAttempt()
}

async function retryTaskCommand(): Promise<void> {
  await taskStore.retryTaskCommand()
  focusCurrentTaskAttempt()
}

function focusCurrentTaskAttempt(): void {
  const taskId = taskStore.state.details?.id
  const executionId = taskStore.state.details?.currentExecutionId
  if (!taskId || !executionId || selectedTaskExecutionId.value === executionId) return
  selectedTaskExecutionId.value = executionId
  void taskStore.loadRuntimeFacts(taskId, executionId, true)
}

function openTaskConversation(conversationId: string): void {
  void router.push({
    name: 'conversation',
    query: {
      ...route.query,
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
  void router.replace({ query: { ...route.query, workItem: task.workItemId, task: undefined } })
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

const statusLabels: Record<WorkItemStatus, string> = {
  BACKLOG: '待规划', READY: '待执行', IN_PROGRESS: '进行中', IN_REVIEW: '审查中', BLOCKED: '已阻塞', DONE: '已完成', CANCELLED: '已取消', ARCHIVED: '已归档',
}
</script>

<template>
  <AppShell eyebrow="Work · Project scope" :title="project?.name ?? '项目工作区'">
    <template #actions>
      <RouterLink v-slot="{ navigate }" custom :to="{ name: 'conversation', query: route.query }">
        <BaseButton variant="secondary" size="small" @click="navigate"><MessageSquare :size="14" />带到对话</BaseButton>
      </RouterLink>
    </template>

    <StatePanel v-if="scopeStore.state.phase === 'loading' || scopeStore.state.phase === 'idle'" state="loading" />
    <StatePanel v-else-if="scopeStore.state.phase === 'error'" state="error" :description="scopeStore.state.errorMessage ?? undefined" @retry="scopeStore.reload" />
    <StatePanel v-else-if="scopeStore.state.phase === 'empty'" state="empty" title="还没有可访问的 Team" />
    <StatePanel v-else-if="!project" state="empty" title="这个 Team 还没有 WorkProject" description="创建 WorkProject 后即可管理团队工作项。" />

    <div v-else class="work-page page-shell">
      <section class="work-toolbar panel">
        <div class="work-toolbar__scope">
          <p class="eyebrow">{{ team?.name }} · {{ project.key }}</p>
          <h2>团队工作项</h2>
          <span>{{ filteredItems.length }} 项当前结果</span>
          <BaseButton v-if="canCreate" class="create-work-item" size="small" @click="openCreate"><Plus :size="14" />新建工作项</BaseButton>
        </div>
        <div class="filters" aria-label="工作项筛选">
          <label><span>状态</span><select :value="statusFilter" @change="updateQuery('status', ($event.target as HTMLSelectElement).value)"><option value="all">全部状态</option><option v-for="status in workItemStatuses" :key="status" :value="status">{{ statusLabels[status] }}</option></select></label>
          <label><span>类型</span><select :value="typeFilter" @change="updateQuery('type', ($event.target as HTMLSelectElement).value)"><option value="all">全部类型</option><option v-for="itemType in workItemTypes" :key="itemType" :value="itemType">{{ itemType }}</option></select></label>
          <label><span>优先级</span><select :value="priorityFilter" @change="updateQuery('priority', ($event.target as HTMLSelectElement).value)"><option value="all">全部优先级</option><option v-for="priority in workItemPriorities" :key="priority" :value="priority">{{ priority }}</option></select></label>
        </div>
        <div class="view-switcher" aria-label="工作项视图">
          <button type="button" :class="{ active: view === 'list' }" aria-label="列表视图" @click="updateQuery('view', 'list')"><List :size="15" />List</button>
          <button type="button" :class="{ active: view === 'board' }" aria-label="看板视图" @click="updateQuery('view', 'board')"><Columns3 :size="15" />Board</button>
        </div>
      </section>

      <section class="work-content" :class="`work-content--${view}`">
        <StatePanel v-if="workStore.state.phase === 'loading'" state="loading" />
        <StatePanel v-else-if="workStore.state.phase === 'error'" state="error" :description="workStore.state.errorMessage ?? undefined" @retry="retry" />
        <StatePanel v-else-if="workStore.state.phase === 'empty'" state="empty" title="当前范围还没有工作项" description="创建第一个 WorkItem，让团队目标进入可追踪的执行流。"><template #action><BaseButton v-if="canCreate" @click="openCreate"><Plus :size="15" />新建工作项</BaseButton></template></StatePanel>
        <StatePanel v-else-if="filteredItems.length === 0" state="empty" title="没有符合筛选条件的工作项" description="调整类型或优先级筛选即可恢复结果。"><template #action><BaseButton variant="secondary" @click="clearLocalFilters"><Filter :size="15" />清除本地筛选</BaseButton></template></StatePanel>

        <div v-else-if="view === 'list'" class="work-list" aria-label="工作项列表">
          <WorkItemCard v-for="item in filteredItems" :key="item.id" :item="item" layout="list" @select="selectItem" />
        </div>

        <div v-else class="work-board" aria-label="工作项看板">
          <section v-for="status in boardStatuses" :key="status" class="board-column" :aria-label="statusLabels[status]">
            <header><span>{{ statusLabels[status] }}</span><StatusBadge>{{ itemsFor(status).length }}</StatusBadge></header>
            <div class="board-column__items">
              <WorkItemCard v-for="item in itemsFor(status)" :key="item.id" :item="item" layout="board" @select="selectItem" />
              <p v-if="itemsFor(status).length === 0">暂无工作项</p>
            </div>
          </section>
        </div>

        <div v-if="workStore.state.nextCursor" class="load-more">
          <BaseButton variant="secondary" :loading="workStore.state.loadingMore" @click="workStore.loadMore">加载更多工作项</BaseButton>
          <p v-if="workStore.state.errorMessage" role="alert">{{ workStore.state.errorMessage }}</p>
        </div>
      </section>

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

    <div v-if="showCreate" class="dialog-backdrop" @click.self="showCreate = false">
      <form class="create-dialog panel" role="dialog" aria-modal="true" aria-labelledby="create-work-item-title" @submit.prevent="createWorkItem" @keydown.esc="showCreate = false">
        <header><div><p class="eyebrow">{{ project?.key }} · Native WorkItem</p><h2 id="create-work-item-title">新建工作项</h2><span>创建者将成为初始 Owner，服务端原子提交工作项与责任事实。</span></div><button type="button" aria-label="关闭新建工作项" @click="showCreate = false"><X :size="18" /></button></header>
        <div class="form-grid">
          <label class="field-key"><span>工作项 Key</span><input v-model="form.key" class="mono" autocomplete="off" :aria-invalid="submitted && !/^[A-Z][A-Z0-9]{1,9}-[1-9][0-9]*$/.test(form.key.trim())"></label>
          <label class="field-title"><span>标题</span><input ref="titleInput" v-model="form.title" maxlength="240" autocomplete="off" placeholder="描述团队需要完成的结果" :aria-invalid="submitted && !form.title.trim()"></label>
          <label><span>类型</span><select v-model="form.type"><option v-for="itemType in workItemTypes" :key="itemType" :value="itemType">{{ itemType }}</option></select></label>
          <label><span>优先级</span><select v-model="form.priority"><option v-for="priority in workItemPriorities" :key="priority" :value="priority">{{ priority }}</option></select></label>
          <label><span>到期时间</span><input v-model="form.dueAt" type="datetime-local"></label>
          <label><span>标签</span><input v-model="form.labels" placeholder="frontend, collaboration"></label>
          <label class="field-wide"><span>描述</span><textarea v-model="form.description" rows="4" placeholder="补充背景、范围和验收结果" /></label>
        </div>
        <p v-if="submitted && !formValid" class="form-error" role="alert">请填写有效标题，并使用当前项目的 Key 格式（例如 {{ project?.key }}-1）。</p>
        <p v-if="workStore.state.commandErrorMessage" class="form-error" role="alert">{{ workStore.state.commandErrorMessage }}</p>
        <footer><BaseButton type="button" variant="ghost" @click="showCreate = false">取消</BaseButton><BaseButton type="submit" :loading="workStore.state.commandPending">创建工作项</BaseButton></footer>
      </form>
    </div>

    <DelegateToAgentDialog
      v-if="showDelegate && workStore.state.detail"
      :work-item="workStore.state.detail.workItem"
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
      :phase="workStore.state.detailPhase"
      :details="workStore.state.detail"
      :error-message="workStore.state.detailErrorMessage"
      :command-pending="workStore.state.detailCommandPending"
      :command-error-message="workStore.state.detailCommandErrorMessage"
      :version-conflict="workStore.state.versionConflict"
      :can-participate="canParticipate"
      :can-delegate="canDelegate"
      :can-manage-responsibility="canManageResponsibility"
      :responsibility-phase="workStore.state.responsibilityPhase"
      :responsibilities="workStore.state.responsibilities"
      :responsibility-candidates="responsibilityCandidates"
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
      :on-transition="workStore.transition"
      :on-add-comment="workStore.addComment"
      :on-link-resource="workStore.linkResource"
      :on-replace-owner="workStore.replaceOwner"
      :on-assign-executor="workStore.assignExecutor"
      :on-assign-gate-reviewer="workStore.assignGateReviewer"
      :on-assign-advisory-reviewer="workStore.assignAdvisoryReviewer"
      :on-release-responsibility="workStore.releaseResponsibility"
      :on-load-timeline-more="workStore.loadTimelineMore"
      :on-retry-associations="retryLinks"
      @close="closeDetails"
      @conversation="openConversation"
      @open-conversation="openLinkedConversation"
      @delegate="openDelegate"
    />

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
      :principals="responsibilityCandidates"
      :can-control="canControlTask"
      :online="isOnline"
      :command-pending="taskStore.state.commandPending"
      :command-error-message="taskStore.state.commandErrorMessage"
      :command-retryable="taskStore.state.commandRetryable"
      :command-version-conflict="taskStore.state.commandVersionConflict"
      :on-select-attempt="selectTaskAttempt"
      :on-retry="retryTaskDetails"
      :on-retry-runtime="retryTaskRuntime"
      :on-retry-fleet="retryRuntimeHealth"
      :on-retry-associations="retryTaskAssociations"
      :on-load-events-more="loadTaskEventsMore"
      :on-retry-events="retryTaskEvents"
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
.work-toolbar { display: grid; grid-template-columns: minmax(190px, .65fr) minmax(430px, 1.5fr) auto; align-items: end; gap: 18px; padding: 16px 18px; }.work-toolbar__scope { position: relative; padding-right: 104px; }.work-toolbar__scope h2 { margin: 0; font-size: 17px; }.work-toolbar__scope > span { color: var(--cs-text-muted); font-size: 9px; }.create-work-item { position: absolute; right: 0; bottom: 0; }.filters { display: grid; grid-template-columns: repeat(3, minmax(110px, 1fr)); gap: 8px; }.filters label, .form-grid label { display: grid; gap: 5px; color: var(--cs-text-secondary); font-size: 9px; font-weight: 750; }.filters select, .form-grid input, .form-grid select, .form-grid textarea { width: 100%; min-height: 34px; padding: 0 9px; border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-text); font: 10px var(--cs-font-sans); }.form-grid textarea { padding-block: 9px; resize: vertical; }.view-switcher { display: flex; gap: 3px; padding: 3px; border: 1px solid var(--cs-border); border-radius: 9px; background: var(--cs-surface-subtle); }.view-switcher button { display: flex; min-height: 30px; align-items: center; gap: 5px; padding: 0 9px; border-radius: 6px; background: transparent; color: var(--cs-text-muted); font-size: 10px; cursor: pointer; }.view-switcher button.active { background: var(--cs-surface); box-shadow: 0 1px 3px rgb(21 35 29 / 10%); color: var(--cs-brand-700); font-weight: 750; }
.work-content { min-width: 0; }.work-content > :deep(.state-panel) { border: 1px solid var(--cs-border); border-radius: var(--cs-radius-lg); background: var(--cs-surface); }.work-list { display: grid; gap: 7px; }.work-board { display: grid; grid-auto-columns: minmax(255px, 1fr); grid-auto-flow: column; gap: 10px; overflow-x: auto; padding-bottom: 6px; scroll-snap-type: x proximity; }.board-column { min-height: 390px; overflow: hidden; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: #f7f9f7; scroll-snap-align: start; }.board-column > header { display: flex; min-height: 47px; align-items: center; justify-content: space-between; padding: 0 12px; border-bottom: 1px solid var(--cs-border); color: var(--cs-text-secondary); font-size: 10px; font-weight: 800; }.board-column__items { display: grid; align-content: start; gap: 8px; padding: 8px; }.board-column__items > p { padding: 24px 8px; color: var(--cs-text-muted); font-size: 9px; text-align: center; }.load-more { display: grid; justify-items: center; gap: 7px; padding: 16px; }.load-more p { margin: 0; color: var(--cs-danger); font-size: 10px; }.scope-rule { display: flex; align-items: flex-start; gap: 9px; padding: 12px 14px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface-subtle); color: var(--cs-text-muted); font-size: 9px; }.scope-rule svg { flex: 0 0 auto; color: var(--cs-brand-600); }
.dialog-backdrop { position: fixed; inset: 0; z-index: 50; display: grid; place-items: center; padding: 18px; background: rgb(21 35 29 / 34%); backdrop-filter: blur(3px); }.create-dialog { width: min(720px, 100%); max-height: calc(100vh - 36px); overflow-y: auto; box-shadow: var(--cs-shadow-float); }.create-dialog > header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 20px 22px; border-bottom: 1px solid var(--cs-border); }.create-dialog h2 { margin-bottom: 3px; font-size: 18px; }.create-dialog header span { color: var(--cs-text-muted); font-size: 10px; }.create-dialog header button { display: grid; width: 31px; height: 31px; flex: 0 0 auto; place-items: center; border-radius: 8px; background: var(--cs-surface-subtle); cursor: pointer; }.form-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 14px; padding: 20px 22px 8px; }.field-title, .field-wide { grid-column: 1 / -1; }.form-grid input[aria-invalid="true"] { border-color: var(--cs-danger); }.form-error { margin: 8px 22px 0; color: var(--cs-danger); font-size: 10px; }.create-dialog > footer { display: flex; justify-content: flex-end; gap: 7px; padding: 17px 22px 20px; }
@media (max-width: 1050px) { .work-toolbar { grid-template-columns: 1fr auto; }.filters { grid-column: 1 / -1; grid-row: 2; }.view-switcher { grid-column: 2; grid-row: 1; } }
@media (max-width: 767px) { .work-toolbar { grid-template-columns: 1fr; align-items: stretch; gap: 12px; padding: 14px; }.work-toolbar__scope { padding-right: 112px; }.filters { grid-column: 1; grid-template-columns: 1fr 1fr; }.filters label:first-child { grid-column: 1 / -1; }.view-switcher { grid-column: 1; grid-row: auto; }.view-switcher button { flex: 1; justify-content: center; }.work-board { grid-auto-columns: minmax(272px, 84vw); }.dialog-backdrop { align-items: end; padding: 0; }.create-dialog { width: 100%; max-height: 92vh; border-radius: 18px 18px 0 0; }.create-dialog > header, .form-grid, .create-dialog > footer { padding-inline: 16px; }.form-grid { grid-template-columns: 1fr; }.field-title, .field-wide { grid-column: 1; }.form-error { margin-inline: 16px; } }
</style>
