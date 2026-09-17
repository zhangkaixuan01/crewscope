<script setup lang="ts">
import { ArrowRight, Inbox, Plus, Settings2, TriangleAlert } from '@lucide/vue'
import { computed, inject, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { AUTH_PRINCIPAL, can, permissions } from '../app/auth'
import { useNetworkStatus } from '../app/network'
import { useToast } from '../composables/useToast'
import { useBoardDrag } from '../composables/useBoardDrag'
import { formatAbsoluteTime, formatRelativeTime } from '../composables/formatRelativeTime'
import BaseButton from '../components/base/BaseButton.vue'
import BaseSkeleton from '../components/base/BaseSkeleton.vue'
import BaseTooltip from '../components/base/BaseTooltip.vue'
import WorkDeskBoardCard from '../components/domain/WorkDeskBoardCard.vue'
import WorkProjectCreateDialog from '../components/domain/WorkProjectCreateDialog.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import AppShell from '../components/layout/AppShell.vue'
import { useScopeStore } from '../domains/scope/store'
import { createWorkProjectCreationFlow } from '../domains/scope/workProjectCreation'
import { SETUP_STORE } from '../domains/setup/store'
import { WORKDESK_STORE, type WorkDeskStore } from '../domains/workdesk/store'
import { useWorkItemStore } from '../domains/workitem/store'
import { useUndoOffer } from '../domains/workitem/useUndoOffer'
import { useTransitionConfirm } from '../domains/workitem/useTransitionConfirm'
import { allowedWorkItemTransitions, workItemStatuses, type WorkItemStatus } from '../domains/workitem/types'
import { workItemStatusLabels } from '../domains/workitem/labels'
import {
  workDeskObjectTypeLabels,
  workDeskResponsibilityRoleLabels,
  workDeskStatusLabel,
  workDeskUrgencyLabels,
} from '../domains/workdesk/labels'
import {
  workDeskResponsibilityRoles,
  type WorkDeskItem,
  type WorkDeskResponsibilityRole,
} from '../domains/workdesk/types'
import { enumLabel, enumLabelOr } from '../domains/shared/labels'
import type { SemanticTone } from '../components/base/types'

/**
 * The personal WorkDesk: what today asks of this member, across every WorkProject.
 *
 * The page is a projection reader, not a second source of truth. It renders the six sections the
 * `GET /work-desk` projection returns — action required, my work, my running executions, unread
 * Inbox — and it executes an action only where the projection itself offered one. Nothing here
 * derives an action label, guesses which section a row belongs in, or moves a row locally: after a
 * command it re-reads the projection, so the section a row lands in is the server's answer.
 */
const route = useRoute()
const router = useRouter()
const principal = inject(AUTH_PRINCIPAL)
const scopeStore = useScopeStore()
const team = scopeStore.selectedTeam
const project = scopeStore.selectedProject
const canManageProjects = computed(() => Boolean(principal && can(principal, permissions.workProjectsManage)))
const canParticipate = computed(() => Boolean(principal && can(principal, permissions.workParticipate)))
const isOnline = useNetworkStatus()
const toast = useToast()
const projectCreation = createWorkProjectCreationFlow(scopeStore, router, route)
const setupStore = inject(SETUP_STORE, null)
const setupReadiness = computed(() => setupStore?.state.readiness ?? null)
const setupReadyCount = computed(() => setupReadiness.value?.capabilities.filter(item => item.required && item.status === 'READY').length ?? 0)
const setupRequiredCount = computed(() => setupReadiness.value?.capabilities.filter(item => item.required).length ?? 0)
// Keep the page mountable in isolated route/story tests where the application store is not installed.
const workDeskStore = inject(WORKDESK_STORE, null) ?? ({
  state: { phase: 'idle', scope: null, summary: null, errorMessage: null },
  activateScope: () => undefined,
  load: async () => undefined,
  reset: () => undefined,
} as WorkDeskStore)
const workItemStore = useWorkItemStore()
const { offerUndo } = useUndoOffer(workItemStore)
const { confirmingTarget, confirmingSubject, submit: submitTransition, reset: resetTransition } =
  useTransitionConfirm(() => workItemStore.state.detailCommandPending === 'transition')

const deskProject = computed(() => queryValue(route.query.deskProject) ?? 'all')
const deskRole = computed<WorkDeskResponsibilityRole | 'all'>(() => {
  const value = queryValue(route.query.deskRole)
  return value && workDeskResponsibilityRoles.includes(value as WorkDeskResponsibilityRole) ? value as WorkDeskResponsibilityRole : 'all'
})
const deskOnlyAction = computed(() => queryValue(route.query.deskAction) === 'true')
/** The axis the board's columns stand for. It is a URL fact so a shared link shows the same board. */
const deskGrouping = computed<'role' | 'status'>(() => queryValue(route.query.deskGroup) === 'role' ? 'role' : 'status')
const workDeskFilter = computed(() => ({
  projectId: deskProject.value === 'all' ? null : deskProject.value,
  responsibilityRole: deskRole.value === 'all' ? null : deskRole.value,
  onlyNeedsAction: deskOnlyAction.value,
}))

const workDeskSections = computed(() => workDeskStore.state.summary?.sections ?? [])
const sectionItems = (key: string): WorkDeskItem[] => workDeskSections.value.find(section => section.key === key)?.items ?? []
const actionRequiredRows = computed(() => ['HUMAN_GATE', 'REVIEW', 'BLOCKED'].flatMap(sectionItems))
const myWorkRows = computed(() => sectionItems('WORK_ITEM'))
const executionRows = computed(() => sectionItems('TASK_EXECUTION'))
const inboxRows = computed(() => sectionItems('INBOX'))
const inboxTotal = computed(() => workDeskSections.value.find(section => section.key === 'INBOX')?.total ?? 0)
const inboxSample = computed(() => inboxRows.value[0] ?? null)

/**
 * The day's activity, derived from the projection rather than from an event log.
 *
 * The WorkDesk carries one time per row — when the underlying fact last moved — so this is a list of
 * what changed today, not a stream of events. The heading says as much, and the empty case says the
 * truth instead of showing yesterday's rows as if they were today's.
 */
const todayStart = computed(() => { const start = new Date(); start.setHours(0, 0, 0, 0); return start.getTime() })
const todayRows = computed(() => {
  const seen = new Set<string>()
  return [...actionRequiredRows.value, ...myWorkRows.value, ...executionRows.value]
    .filter(row => {
      // A blocked work item appears both under 被阻塞 and under 我的工作项; the same fact must not be
      // reported as two pieces of news.
      const key = `${row.objectType}:${row.objectId}`
      if (seen.has(key)) return false
      seen.add(key)
      return new Date(row.updatedAt).getTime() >= todayStart.value
    })
    .sort((left, right) => new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime())
    .slice(0, 5)
})
const latestUpdate = computed(() => {
  const times = [...myWorkRows.value, ...executionRows.value, ...actionRequiredRows.value]
    .map(row => new Date(row.updatedAt).getTime())
  return times.length ? Math.max(...times) : null
})

/** Only the first load gets a skeleton; a refresh keeps the rows and says it is refreshing. */
const firstLoad = computed(() => workDeskStore.state.summary === null
  && (workDeskStore.state.phase === 'idle' || workDeskStore.state.phase === 'loading'))
const refreshing = computed(() => workDeskStore.state.summary !== null && workDeskStore.state.phase === 'loading')
const failurePhase = computed<'error' | 'offline' | null>(() => {
  const phase = workDeskStore.state.phase
  return phase === 'error' || phase === 'offline' ? phase : null
})
/** Nothing to render and a reason why: the panel is the whole section rather than a strip over it. */
const failedFirstLoad = computed(() => failurePhase.value !== null && workDeskStore.state.summary === null)

const UNKNOWN_STATUS_COLUMN = 'OTHER_STATUS'
const NO_ROLE_COLUMN = 'NO_ROLE'
/** The workflow reading of a personal board: the six columns a member's work actually moves between. */
const workflowStatuses: WorkItemStatus[] = ['BACKLOG', 'READY', 'IN_PROGRESS', 'IN_REVIEW', 'BLOCKED', 'DONE']

interface DeskColumn {
  key: string
  label: string
  /** The transition target this column stands for; null for a column that is not a status. */
  status: WorkItemStatus | null
}

const statusColumns = computed<DeskColumn[]>(() => {
  const keys = [...workflowStatuses]
  // A terminal state is a column only when something is actually in it, exactly as the work board
  // does it; otherwise every personal board would open with two empty columns.
  for (const terminal of ['CANCELLED', 'ARCHIVED'] as const) {
    if (myWorkRows.value.some(row => row.status === terminal)) keys.push(terminal)
  }
  const columns: DeskColumn[] = keys.map(status => ({ key: status as string, label: workItemStatusLabels[status], status }))
  // The projection is additive on the server side, so a status this build does not know about still
  // gets a column: dropping the row would hide a member's work rather than admit the build is behind.
  if (myWorkRows.value.some(row => !workItemStatuses.includes(row.status as WorkItemStatus))) {
    columns.push({ key: UNKNOWN_STATUS_COLUMN, label: '其他状态', status: null })
  }
  return columns
})
const roleColumns = computed<DeskColumn[]>(() => [
  ...workDeskResponsibilityRoles.map(role => ({ key: role as string, label: workDeskResponsibilityRoleLabels[role], status: null })),
  { key: NO_ROLE_COLUMN, label: '未关联责任', status: null },
])
const boardColumns = computed(() => deskGrouping.value === 'status' ? statusColumns.value : roleColumns.value)

function columnKeyOf(row: WorkDeskItem): string {
  if (deskGrouping.value === 'role') {
    return workDeskResponsibilityRoles.includes(row.responsibilityRole as WorkDeskResponsibilityRole)
      ? row.responsibilityRole as string
      : NO_ROLE_COLUMN
  }
  return workItemStatuses.includes(row.status as WorkItemStatus) ? row.status : UNKNOWN_STATUS_COLUMN
}
const columnLabel = (key: string): string => boardColumns.value.find(column => column.key === key)?.label ?? key
const rowsFor = (key: string): WorkDeskItem[] => myWorkRows.value.filter(row => columnKeyOf(row) === key)

/**
 * Dragging is only offered while the columns are the row's own status.
 *
 * In the role grouping a drop onto a column would mean "reassign this" — a different command against
 * a different aggregate, which the projection does not offer any transition for. A drag that looks
 * available and does nothing is worse than one that is not offered, so the board locks instead and
 * says why.
 */
const canDrag = computed(() => deskGrouping.value === 'status' && isOnline.value && canParticipate.value)
const boardDrag = useBoardDrag<WorkDeskItem, string>({
  columns: () => boardColumns.value.map(column => column.key),
  columnLabel,
  columnOf: columnKeyOf,
  allowEdge: (row, key) => {
    const target = boardColumns.value.find(column => column.key === key)?.status
    if (!target || !canDrag.value) return false
    return allowedWorkItemTransitions[row.status as WorkItemStatus]?.includes(target) ?? false
  },
  findRow: objectId => myWorkRows.value.find(row => row.objectId === objectId) ?? null,
  keyOf: row => row.objectId,
  nameOf: row => deskTitle(row),
  locked: () => !canDrag.value,
  // The Store's row path re-reads the authoritative version and refuses with the server's own
  // wording, so a rejected drop explains itself here instead of failing silently.
  drop: async (row, key) => { await runDeskAction(row, key) },
})
// Destructured so the template unwraps the refs: a ref reached through an object property is not
// unwrapped, and `boardDrag.dragged.value` in a template is a bug waiting to be copy-pasted.
const {
  dragged: draggedRow,
  overColumn,
  announcement: boardAnnouncement,
  allowDrop: allowDropOnColumn,
  start: startRowDrag,
  end: endRowDrag,
  over: markBoardColumn,
  leave: leaveBoardColumn,
  pointerDrop: dropOnColumn,
  onKeydown: onBoardKeydown,
} = boardDrag

function handleBoardKeydown(event: KeyboardEvent): void {
  const target = event.target instanceof Element ? event.target.closest<HTMLElement>('[data-desk-item-id]') : null
  onBoardKeydown(event, target?.dataset.deskItemId)
}

function queryValue(value: unknown): string | null {
  if (typeof value === 'string') return value
  if (Array.isArray(value) && typeof value[0] === 'string') return value[0]
  return null
}

async function updateDeskQuery(key: string, value: string): Promise<void> {
  const query = { ...route.query }
  if (!value || value === 'all' || (key === 'deskAction' && value === 'false')) delete query[key]
  else query[key] = value
  await router.replace({ query })
}

// A row without a title still has to be nameable, so it falls back to a translated object type.
function deskTitle(item: WorkDeskItem): string { return item.title?.trim() || `${enumLabel(item.objectType, workDeskObjectTypeLabels)} · ${item.objectId.slice(0, 8)}` }
function roleLabel(role: string | null): string { return enumLabelOr(role, workDeskResponsibilityRoleLabels, '关联任务') }
function urgencyLabel(urgency: string): string { return enumLabel(urgency, workDeskUrgencyLabels) }
function urgencyTone(urgency: string): SemanticTone { return urgency === 'HIGH' || urgency === 'URGENT' ? 'danger' : urgency === 'NORMAL' ? 'warning' : 'neutral' }
function projectLabel(projectId: string | null): string | null {
  if (!projectId) return null
  const match = scopeStore.state.projects.find(candidate => candidate.id === projectId)
  return match ? `${match.key} · ${match.name}` : null
}
/** A gate, review or execution row is read-only here; only a WorkItem has a status machine. */
function canExecute(item: WorkDeskItem): boolean {
  return item.objectType === 'WORK_ITEM' && item.availableActions.length > 0
}
/** An execution that is not simply running is the one worth looking at. */
const unhealthyExecutions = ['FAILED', 'WAITING', 'MANUAL_TAKEOVER', 'PAUSE_REQUESTED', 'CANCEL_REQUESTED', 'RECOVERING']
function executionTone(status: string): SemanticTone {
  if (status === 'FAILED' || status === 'CANCELLED') return 'danger'
  if (unhealthyExecutions.includes(status)) return 'warning'
  return 'info'
}

/**
 * Runs one action the projection offered for a WorkItem row.
 *
 * The Store owns the command, the authoritative re-read and the verdict; this only decides how the
 * verdict is shown and, on success, re-reads the projection so the row's section comes from the
 * server. Both the board drop and the card's status menu funnel through here, which is what keeps a
 * single undo window and one wording per refusal across the home page and the work board.
 */
async function runDeskAction(item: WorkDeskItem, target: string | WorkItemStatus): Promise<void> {
  const organizationId = team.value?.organizationId
  const teamId = team.value?.id
  if (!organizationId || !teamId || !item.projectId) return
  const result = await workItemStore.transitionFromRow(
    { organizationId, teamId, projectId: item.projectId },
    // A WorkDesk row has no key. Its title names it better than a UUID would, and the refusal only
    // falls back to that name when the server sent no reason of its own.
    { id: item.objectId, key: deskTitle(item), availableActions: item.availableActions },
    target as WorkItemStatus,
  )
  if (result.status === 'executed') {
    offerUndo(deskTitle(item))
    await workDeskStore.load(workDeskFilter.value, true)
    return
  }
  // A refusal and a failure are different news: the first says the row is not in a state that allows
  // the action, the second says we do not know whether the command landed.
  toast.show(result.message, { tone: result.status === 'refused' ? 'warning' : 'danger' })
}

function runCardAction(item: WorkDeskItem, action: WorkDeskItem['availableActions'][number]): void {
  void submitTransition(item.objectId, action, async transition => runDeskAction(item, transition.targetStatus))
}
function openItem(item: WorkDeskItem): void { void router.push(item.route) }

watch(() => [scopeStore.state.selectedTeamId, team.value?.organizationId] as const, async ([teamId, organizationId]) => {
  await scopeStore.loadMembers()
  if (setupStore && teamId && organizationId) {
    setupStore.activateScope({ organizationId, teamId })
    await setupStore.load()
  }
  if (teamId && organizationId) {
    workDeskStore.activateScope({ organizationId, teamId })
    await workDeskStore.load(workDeskFilter.value)
  }
}, { immediate: true })

watch(workDeskFilter, value => { if (team.value) void workDeskStore.load(value) })
// A filter or grouping change re-arms nothing: an action half-confirmed on a row that is no longer
// on screen must not still be waiting for its second click.
watch(() => [myWorkRows.value, deskGrouping.value, workDeskStore.state.summary] as const, resetTransition)

const todayLabel = new Intl.DateTimeFormat('zh-CN', {
  month: 'long',
  day: 'numeric',
  weekday: 'long',
}).format(new Date())
</script>

<template>
  <AppShell eyebrow="今日" title="我的工作台">
    <template #actions>
      <BaseButton v-if="canManageProjects" variant="secondary" size="small" @click="projectCreation.show"><Plus :size="14" />新建项目</BaseButton>
      <RouterLink v-slot="{ navigate }" custom :to="{ name: 'work', query: route.query }">
        <BaseButton size="small" @click="navigate">打开 Work <ArrowRight :size="14" /></BaseButton>
      </RouterLink>
    </template>

    <StatePanel v-if="scopeStore.state.phase === 'loading' || scopeStore.state.phase === 'idle'" state="loading" />
    <StatePanel v-else-if="scopeStore.state.phase === 'error'" state="error" :description="scopeStore.state.errorMessage ?? undefined" @retry="scopeStore.reload" />
    <StatePanel v-else-if="scopeStore.state.phase === 'empty'" state="empty" title="还没有可访问的 Team" description="创建或加入 Team 后，Today 会汇总团队范围内需要关注的工作。"><template #action><RouterLink :to="{ name: 'onboarding' }"><BaseButton size="small">创建或加入 Team</BaseButton></RouterLink></template></StatePanel>

    <div v-else class="today-page page-shell">
      <!--
        The Setup banner is a strip rather than a page: a member whose Team still needs configuring
        can see today's work and the one thing blocking it in the same viewport.
      -->
      <RouterLink v-if="setupReadiness && !setupReadiness.requiredReady" class="setup-strip touch-target" :to="{ name: 'setup', query: route.query }">
        <Settings2 :size="16" aria-hidden="true" />
        <span><strong>配置尚未就绪</strong>Required 能力 {{ setupReadyCount }}/{{ setupRequiredCount }} 项已就绪，其余需要先完成配置。</span>
        <ArrowRight :size="14" aria-hidden="true" />
      </RouterLink>

      <header class="today-head">
        <div>
          <!-- The page title is AppShell's `<h1>`; repeating it here would give the page two headings
               with the same accessible name, so a reader that asks for "我的工作台" is handed a
               choice between two identical answers. -->
          <p class="eyebrow">{{ todayLabel }}</p>
          <p v-if="workDeskStore.state.summary" class="today-head__summary">
            <span v-if="actionRequiredRows.length">需要你行动 {{ actionRequiredRows.length }} 项</span>
            <span v-else>今天没有需要你行动的事项</span>
            <span aria-hidden="true">·</span>
            <span>推进中 {{ myWorkRows.length }} 项</span>
            <template v-if="executionRows.length"><span aria-hidden="true">·</span><span>执行中 {{ executionRows.length }} 项</span></template>
          </p>
        </div>
        <div class="desk-filters" aria-label="工作台筛选">
          <label>项目<select :value="deskProject" @change="updateDeskQuery('deskProject', ($event.target as HTMLSelectElement).value)"><option value="all">全部项目</option><option v-for="item in scopeStore.state.projects" :key="item.id" :value="item.id">{{ item.key }} · {{ item.name }}</option></select></label>
          <label>责任角色<select :value="deskRole" @change="updateDeskQuery('deskRole', ($event.target as HTMLSelectElement).value)"><option value="all">全部角色</option><option v-for="role in workDeskResponsibilityRoles" :key="role" :value="role">{{ workDeskResponsibilityRoleLabels[role] }}</option></select></label>
          <label class="desk-check"><input type="checkbox" :checked="deskOnlyAction" @change="updateDeskQuery('deskAction', ($event.target as HTMLInputElement).checked ? 'true' : 'false')"> 仅看需要我行动</label>
          <BaseTooltip v-if="workDeskStore.state.summary" :text="formatAbsoluteTime(new Date(workDeskStore.state.summary.generatedAt))">
            <small class="desk-updated" :aria-busy="refreshing">{{ refreshing ? '正在刷新' : `更新于 ${formatRelativeTime(new Date(workDeskStore.state.summary.generatedAt))}` }}</small>
          </BaseTooltip>
        </div>
      </header>

      <StatePanel v-if="firstLoad" state="loading" compact title="正在汇总你的工作" description="正在从各个 WorkProject 读取责任、决策与执行事实。" />
      <StatePanel
        v-else-if="failedFirstLoad"
        :state="failurePhase ?? 'error'"
        :description="workDeskStore.state.errorMessage ?? undefined"
        @retry="workDeskStore.load(workDeskFilter, true)"
      />

      <template v-else>
        <!-- A first WorkProject is the one thing the projection cannot help with, and creating it is
             the only next step an empty Team has. -->
        <StatePanel
          v-if="!scopeStore.state.projects.length"
          state="empty"
          title="这个 Team 还没有 WorkProject"
          description="创建第一个 WorkProject 后，即可进入 Work 管理并绑定代码仓库。"
        >
          <template v-if="canManageProjects" #action><BaseButton size="small" @click="projectCreation.show"><Plus :size="14" />创建 WorkProject</BaseButton></template>
        </StatePanel>

        <!-- A refresh that failed keeps the last known rows and says so, rather than blanking a page
             the member was reading. -->
        <p v-if="failurePhase" class="desk-stale-notice" role="alert">
          <TriangleAlert :size="14" aria-hidden="true" />
          {{ failurePhase === 'offline' ? '当前离线，下面是最近一次读取到的内容。' : `刷新失败：${workDeskStore.state.errorMessage ?? '个人工作台暂时不可用'}` }}
          <button type="button" @click="workDeskStore.load(workDeskFilter, true)">重试</button>
        </p>

        <section class="action-required panel" aria-labelledby="action-required-title">
          <div class="panel-heading">
            <div><h2 id="action-required-title">需要我行动</h2><p>等我决策、待我 Review 与被我阻塞的工作，来自全部 WorkProject。</p></div>
            <span v-if="actionRequiredRows.length" class="count-chip">{{ actionRequiredRows.length }} 项</span>
          </div>
          <StatePanel
            v-if="!actionRequiredRows.length"
            state="empty"
            compact
            title="没有需要你行动的事项"
            description="等我决策、待我 Review 与被我阻塞的队列都是空的。你可以查看正在推进的工作，或进入 Work 取一个新任务。"
          >
            <template #action><RouterLink class="desk-empty-link touch-target" :to="{ name: 'work', query: route.query }">进入 Work <ArrowRight :size="13" /></RouterLink></template>
          </StatePanel>
          <div v-else class="action-required__grid">
            <button
              v-for="item in actionRequiredRows"
              :key="`${item.objectType}:${item.objectId}`"
              type="button"
              class="desk-row touch-target"
              @click="openItem(item)"
            >
              <span class="desk-row__main">
                <strong>{{ deskTitle(item) }}</strong>
                <small>{{ enumLabel(item.objectType, workDeskObjectTypeLabels) }} · {{ roleLabel(item.responsibilityRole) }} · {{ formatRelativeTime(new Date(item.updatedAt)) }}</small>
              </span>
              <span class="desk-row__badges">
                <span class="desk-row__status">{{ workDeskStatusLabel(item) }}</span>
                <span class="desk-row__urgency" :class="`desk-row__urgency--${urgencyTone(item.urgency)}`">{{ urgencyLabel(item.urgency) }}</span>
              </span>
              <ArrowRight :size="14" aria-hidden="true" />
            </button>
          </div>
          <!-- A row here can be a WorkItem the member must move; that is what the board below is
               for, and saying so avoids the reader hunting for an action that lives one screen down. -->
          <p v-if="actionRequiredRows.some(canExecute)" class="desk-hint">阻塞的工作项可以直接在下面的看板上推进；决策类事项需要进入对应页面处理。</p>
        </section>

        <section class="my-work panel" aria-labelledby="my-work-title">
          <div class="panel-heading my-work__heading">
            <div><h2 id="my-work-title">我的工作</h2><p>跨 WorkProject 汇总你承担责任的 {{ myWorkRows.length }} 项工作。</p></div>
            <div class="grouping-switcher" role="group" aria-label="我的工作分组方式">
              <button type="button" :class="{ active: deskGrouping === 'status' }" :aria-pressed="deskGrouping === 'status'" @click="updateDeskQuery('deskGroup', 'status')">按状态</button>
              <button type="button" :class="{ active: deskGrouping === 'role' }" :aria-pressed="deskGrouping === 'role'" @click="updateDeskQuery('deskGroup', 'role')">按责任角色</button>
            </div>
          </div>

          <StatePanel
            v-if="!myWorkRows.length"
            state="empty"
            title="没有分配到你的工作项"
            description="当前筛选下，这个 Team 的 WorkProject 里没有需要你承担的工作项。换一个项目或角色筛选，或到 Work 里取一项工作。"
          >
            <template #action><RouterLink class="desk-empty-link touch-target" :to="{ name: 'work', query: route.query }">进入 Work <ArrowRight :size="13" /></RouterLink></template>
          </StatePanel>

          <div v-else class="desk-board" :class="{ 'desk-board--locked': !canDrag }" aria-label="我的工作看板" @keydown="handleBoardKeydown">
            <section
              v-for="column in boardColumns"
              :key="column.key"
              class="desk-column"
              :class="{ 'drop-target': overColumn === column.key, 'drop-rejected': draggedRow && !allowDropOnColumn(column.key) }"
              :aria-label="column.label"
              @dragover.prevent="markBoardColumn(column.key)"
              @dragleave="leaveBoardColumn"
              @drop.prevent="dropOnColumn(column.key)"
            >
              <header><span>{{ column.label }}</span><span class="desk-column__count">{{ rowsFor(column.key).length }}</span></header>
              <div class="desk-column__items">
                <WorkDeskBoardCard
                  v-for="item in rowsFor(column.key)"
                  :key="item.objectId"
                  :item="item"
                  :title="deskTitle(item)"
                  :project-label="projectLabel(item.projectId)"
                  :role-label="roleLabel(item.responsibilityRole)"
                  :show-role="deskGrouping === 'status'"
                  :draggable="canDrag"
                  :confirming-target="confirmingSubject === item.objectId ? confirmingTarget : null"
                  :busy="workItemStore.state.rowActionItemId === item.objectId"
                  @open="openItem(item)"
                  @action="runCardAction(item, $event)"
                  @drag-start="startRowDrag(item)"
                  @drag-end="endRowDrag"
                />
                <p v-if="!rowsFor(column.key).length" class="desk-column__empty">暂无工作项</p>
              </div>
            </section>
          </div>
          <p class="desk-hint" :class="{ 'desk-hint--warn': !canDrag }">
            <template v-if="deskGrouping === 'role'">按责任角色分组时不能拖动：把卡片放到某个角色列意味着改派责任，这是另一条命令，本站不在这块看板上发起。切回「按状态」即可拖动改状态。</template>
            <template v-else-if="!isOnline">当前离线，拖动已停用；联网后可以继续改状态。</template>
            <template v-else-if="!canParticipate">当前身份没有推进工作项的权限，卡片上的动作由服务端判定后可能仍不可用。</template>
            <template v-else>拖动卡片，或聚焦卡片后按空格拾起、方向键选列、Enter 放下，都可以改状态。</template>
          </p>
        </section>

        <div class="today-columns">
          <section class="panel executions" aria-labelledby="executions-title">
            <div class="panel-heading">
              <div><h2 id="executions-title">正在执行</h2><p>Agent 执行进入 Execution Studio 查看阶段与产物。</p></div>
              <span v-if="executionRows.length" class="count-chip">{{ executionRows.length }} 项</span>
            </div>
            <div v-if="executionRows.length" class="desk-list">
              <button v-for="item in executionRows" :key="item.objectId" type="button" class="desk-row" @click="openItem(item)">
                <span class="desk-row__main"><strong>{{ deskTitle(item) }}</strong><small>{{ formatRelativeTime(new Date(item.updatedAt)) }}更新</small></span>
                <span class="desk-row__status" :class="`desk-row__status--${executionTone(item.status)}`">{{ workDeskStatusLabel(item) }}</span>
                <ArrowRight :size="14" aria-hidden="true" />
              </button>
            </div>
            <p v-else class="desk-inline-empty">当前没有进行中的执行。</p>
          </section>

          <section class="panel activity" aria-labelledby="today-activity-title">
            <div class="panel-heading">
              <div><h2 id="today-activity-title">今日动态</h2><p>今天有更新的工作项与执行，按最近更新排序。</p></div>
            </div>
            <ul v-if="todayRows.length" class="today-activity">
              <li v-for="item in todayRows" :key="`${item.objectType}:${item.objectId}`">
                <button type="button" class="today-activity__link" @click="openItem(item)">
                  <span class="today-activity__title">{{ deskTitle(item) }}</span>
                  <small>{{ workDeskStatusLabel(item) }} · {{ formatRelativeTime(new Date(item.updatedAt)) }}</small>
                </button>
              </li>
            </ul>
            <p v-else class="desk-inline-empty">
              今天还没有新的更新。<template v-if="latestUpdate">最近一次更新在 {{ formatRelativeTime(new Date(latestUpdate)) }}。</template>
            </p>
          </section>
        </div>

        <div v-if="inboxSample" class="desk-inbox panel">
          <Inbox :size="16" aria-hidden="true" />
          <span><strong>Inbox</strong> 有 {{ inboxTotal }} 条未读</span>
          <RouterLink :to="{ name: 'inbox', query: route.query }">查看全部 <ArrowRight :size="13" /></RouterLink>
        </div>
      </template>

      <p class="board-announcement sr-only" aria-live="polite">{{ boardAnnouncement }}</p>
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
  </AppShell>
</template>

<style scoped>
.setup-strip { display: flex; min-height: 44px; align-items: center; gap: var(--cs-space-12); padding: var(--cs-space-8) var(--cs-space-16); border: 1px solid var(--cs-warning); border-radius: var(--cs-radius-md); background: var(--cs-warning-soft); color: var(--cs-text); font-size: var(--cs-text-sm); }
.setup-strip span { flex: 1; }.setup-strip strong { margin-right: var(--cs-space-4); }
/* The date and the summary sit at the top of the row rather than on the filter panel's baseline:
   with `flex-end` a two-line block hung off the bottom edge of a 90px panel and left a hole above it. */
.today-head { display: flex; flex-wrap: wrap; align-items: flex-start; justify-content: space-between; gap: var(--cs-space-16); padding-top: var(--cs-space-8); }
.today-head__summary { display: flex; flex-wrap: wrap; gap: var(--cs-space-8); margin: var(--cs-space-4) 0 0; color: var(--cs-text); font-size: var(--cs-text-base); }
.desk-filters { display: flex; flex-wrap: wrap; align-items: end; gap: var(--cs-space-12); padding: var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); }
.desk-filters label { display: grid; gap: var(--cs-space-4); color: var(--cs-text-muted); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.desk-filters select { min-width: 160px; height: var(--cs-density-control-height); padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); font-size: var(--cs-text-base); }
.desk-check { display: flex !important; min-height: var(--cs-density-control-height); align-items: center; font-weight: var(--cs-weight-semibold) !important; }
.desk-check input { accent-color: var(--cs-focus); }
.desk-updated { align-self: center; color: var(--cs-text-muted); font-size: var(--cs-text-sm); white-space: nowrap; }
.desk-stale-notice { display: flex; align-items: center; gap: var(--cs-space-8); margin: 0; padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid var(--cs-warning); border-radius: var(--cs-radius-sm); background: var(--cs-warning-soft); font-size: var(--cs-text-sm); }
.desk-stale-notice button { margin-left: auto; border: 0; background: transparent; color: var(--cs-text-brand); cursor: pointer; font-weight: var(--cs-weight-semibold); }
.count-chip { padding: var(--cs-space-2) var(--cs-space-8); border-radius: 99px; background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }
.action-required { display: grid; gap: var(--cs-space-12); }
.action-required__grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: var(--cs-space-8); }
.desk-row { display: grid; grid-template-columns: minmax(0, 1fr) auto auto; align-items: center; gap: var(--cs-space-12); width: 100%; min-height: var(--cs-density-control-height); padding: var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); text-align: left; cursor: pointer; transition: border-color var(--cs-motion-fast) var(--cs-ease-out); }
.desk-row:hover { border-color: var(--cs-border-accent-strong); }
.desk-row__main { min-width: 0; }
.desk-row__main strong, .desk-row__main small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.desk-row__main strong { font-size: var(--cs-text-sm); }
.desk-row__main small { margin-top: var(--cs-space-4); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.desk-row__badges { display: flex; align-items: center; gap: var(--cs-space-8); }
.desk-row__status { color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); white-space: nowrap; }
.desk-row__status--danger { color: var(--cs-danger); }.desk-row__status--warning { color: var(--cs-warning); }.desk-row__status--info { color: var(--cs-info); }
.desk-row__urgency { font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }
.desk-row__urgency--danger { color: var(--cs-danger); }.desk-row__urgency--warning { color: var(--cs-warning); }.desk-row__urgency--neutral { color: var(--cs-text-muted); }
.my-work { display: grid; gap: var(--cs-space-12); }
.my-work__heading { align-items: flex-start; }
.grouping-switcher { display: flex; flex: 0 0 auto; gap: var(--cs-space-4); padding: var(--cs-space-4); border: 1px solid var(--cs-border); border-radius: 9px; background: var(--cs-surface-subtle); }
.grouping-switcher button { min-height: var(--cs-density-control-height); padding: 0 var(--cs-space-8); border: 0; border-radius: 6px; background: transparent; color: var(--cs-text-muted); font-size: var(--cs-text-sm); cursor: pointer; }
.grouping-switcher button.active { background: var(--cs-surface); box-shadow: var(--cs-shadow-hairline); color: var(--cs-text-brand); font-weight: var(--cs-weight-semibold); }
.desk-board { display: grid; grid-auto-columns: minmax(255px, 1fr); grid-auto-flow: column; gap: var(--cs-space-12); overflow-x: auto; padding-bottom: var(--cs-space-8); scroll-snap-type: x proximity; }
.desk-column { min-width: 0; min-height: 240px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface-subtle); scroll-snap-align: start; }
.desk-column > header { display: flex; min-height: 40px; align-items: center; justify-content: space-between; padding: 0 var(--cs-space-12); border-bottom: 1px solid var(--cs-border); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.desk-column__count { color: var(--cs-text-muted); }
.desk-column__items { display: grid; align-content: start; gap: var(--cs-space-8); padding: var(--cs-space-8); }
.desk-column__empty { padding: var(--cs-space-16) var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-xs); text-align: center; }
.desk-column.drop-target { border-color: var(--cs-border-accent-strong); background: var(--cs-surface-accent); box-shadow: inset 0 0 0 2px var(--cs-ring-brand); }
/* A column that would refuse the row dims, so the highlight is the only bright target. */
.desk-board--locked .desk-column.drop-rejected, .desk-column.drop-rejected { opacity: .62; }
.desk-hint { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.desk-hint--warn { color: var(--cs-warning); }
.today-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--cs-space-16); }
.executions, .activity { display: grid; align-content: start; gap: var(--cs-space-12); }
.desk-list { display: grid; gap: var(--cs-space-8); }
.today-activity { display: grid; gap: var(--cs-space-8); margin: 0; padding: 0; list-style: none; }
.today-activity__link { display: grid; width: 100%; gap: var(--cs-space-2); padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); text-align: left; cursor: pointer; }
.today-activity__link:hover { border-color: var(--cs-border-accent-strong); }
.today-activity__title { overflow: hidden; font-size: var(--cs-text-sm); text-overflow: ellipsis; white-space: nowrap; }
.today-activity__link small { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.desk-inline-empty { margin: 0; padding: var(--cs-space-16); border: 1px dashed var(--cs-border-strong); border-radius: var(--cs-radius-sm); color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
.desk-inbox { display: flex; align-items: center; gap: var(--cs-space-8); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); }
.desk-inbox span { flex: 1; }.desk-inbox strong { color: var(--cs-text); }
.desk-inbox a, .desk-empty-link { display: inline-flex; align-items: center; gap: var(--cs-space-4); color: var(--cs-text-brand); font-weight: var(--cs-weight-semibold); }
.desk-empty-link { font-size: var(--cs-text-sm); }
@media (max-width: 1000px) { .today-columns { grid-template-columns: 1fr; } }
@media (max-width: 767px) {
  .today-head { align-items: stretch; flex-direction: column; }
  .desk-filters { align-items: stretch; flex-direction: column; }
  .desk-filters select { width: 100%; }
  .desk-board { grid-auto-columns: minmax(272px, 84vw); }
  .action-required__grid { grid-template-columns: 1fr; }
  .my-work__heading { align-items: stretch; flex-direction: column; }
  .grouping-switcher button { flex: 1; }
}
</style>
