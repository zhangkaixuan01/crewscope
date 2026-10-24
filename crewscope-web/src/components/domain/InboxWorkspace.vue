<script setup lang="ts">
import { teamMemberUnresolvedDescription } from '../../domains/shared/stateCopy'
import BaseSelect from '../base/BaseSelect.vue'
import {
  Archive,
  ArchiveRestore,
  ArrowUpRight,
  BellRing,
  CheckCheck,
  ChevronRight,
  CircleAlert,
  Clock3,
  Eye,
  RefreshCw,
  ShieldCheck,
  X,
} from '@lucide/vue'
import { computed, useTemplateRef, watch } from 'vue'
import { enumLabel } from '../../domains/shared/labels'
import {
  inboxCloseReasonLabels,
  inboxDispositionStatusLabels,
  inboxPriorityLabels,
  inboxSourceStatusLabels,
  inboxSourceTypeLabels,
} from '../../domains/teamops/labels'
import type { TeamOpsErrorState } from '../../domains/teamops/errors'
import type { TeamOpsCommandState, TeamOpsPhase } from '../../domains/teamops/store'
import {
  inboxDispositionStatuses,
  inboxItemTypes,
  inboxSourceStatuses,
  type Etagged,
  type InboxBatchOutcome,
  type InboxBatchReport,
  type InboxBatchStatus,
  type InboxCounts,
  type InboxDispositionStatus,
  type InboxItem,
  type InboxItemType,
  type InboxSourceStatus,
} from '../../domains/teamops/types'
import BaseButton from '../base/BaseButton.vue'
import ScanToolbar from '../base/ScanToolbar.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'
import { RouterLink } from 'vue-router'
import { permissions } from '../../app/auth'
import RelativeTime from '../base/RelativeTime.vue'
import { useListSort } from '../../composables/useListSort'
import { useSelection } from '../../composables/useSelection'
import { useConfirm } from '../../composables/useConfirm'
import { useDetailPaneFocus } from '../../composables/useDetailPaneFocus'

const props = defineProps<{
  phase: TeamOpsPhase
  items: InboxItem[]
  countsPhase: TeamOpsPhase
  counts: InboxCounts | null
  countsError: TeamOpsErrorState | null
  nextCursor: string | null
  loadingMore: boolean
  error: TeamOpsErrorState | null
  /** When the offline panel shows, how stale the readable facts are (R25「最近同步于」). */
  lastSyncedAt?: string | null
  selectedItemId: string | null
  detailPhase: TeamOpsPhase
  detail: Etagged<InboxItem> | null
  detailError: TeamOpsErrorState | null
  targetPhase: TeamOpsPhase
  targetError: TeamOpsErrorState | null
  command: TeamOpsCommandState
  itemType: InboxItemType | 'ALL'
  sourceStatus: InboxSourceStatus
  /** M13b-F06（R38）：QUEUE 是默认的「待处理队列」视图（UNREAD/READ/ACTED，与 /counts 同口径）。 */
  dispositionStatus: InboxDispositionStatus | 'QUEUE' | 'ALL'
  online: boolean
  batchReport: InboxBatchReport | null
}>()

const emit = defineEmits<{
  select: [itemId: string]
  closeDetail: []
  changeType: [value: InboxItemType | 'ALL']
  changeSourceStatus: [value: InboxSourceStatus]
  changeDispositionStatus: [value: InboxDispositionStatus | 'QUEUE' | 'ALL']
  retry: []
  retryDetail: [itemId: string]
  retryTarget: [itemId: string]
  loadMore: []
  openTarget: [itemId: string]
  changeDisposition: [itemId: string, status: InboxDispositionStatus]
  batchDisposition: [itemIds: string[], status: InboxBatchStatus]
  dismissBatchReport: []
}>()

const detailHeading = useTemplateRef<HTMLElement>('detailHeading')
/** M13b-F11（C4）：详情焦点生命周期——打开聚焦标题、关闭还原触发按钮。 */
const detailFocus = useDetailPaneFocus(detailHeading)

function openDetail(itemId: string): void {
  detailFocus.captureTrigger()
  emit('select', itemId)
}
function closeDetailAndRestore(): void {
  detailFocus.restoreTrigger()
  emit('closeDetail')
}
const hasItems = computed(() => props.items.length > 0)
/** 「全部类型 + 待处理队列之外」是 Inbox 的两个可选筛选；来源状态是成员刻意选择的二值，不在这里重置。 */
const hasOptionalFilter = computed(() => props.itemType !== 'ALL' || props.dispositionStatus !== 'QUEUE')

function clearOptionalFilters(): void {
  emit('changeType', 'ALL')
  emit('changeDispositionStatus', 'QUEUE')
}
const initialLoading = computed(() => (props.phase === 'idle' || props.phase === 'loading') && !hasItems.value)
const forbidden = computed(() => props.error?.kind === 'forbidden')
const cursorExpired = computed(() => props.error?.kind === 'cursor-expired')
const offline = computed(() => !props.online || props.error?.kind === 'offline')
const hardError = computed(() => props.phase === 'error' && !forbidden.value && !cursorExpired.value && !offline.value)
const countsUnavailable = computed(() => props.countsPhase === 'error')
const selected = computed(() => props.detail?.value ?? props.items.find(item => item.inboxItemId === props.selectedItemId) ?? null)
const commandForSelection = computed(() => props.command.targetId === props.selectedItemId ? props.command : null)
const inboxSort = useListSort({ defaultKey: 'openedAt' as const, defaultDirection: 'desc', allowedKeys: ['openedAt'] as const })
const inboxSortLabel = computed(() => inboxSort.direction.value === 'asc' ? '最早优先' : '最新优先')
const selection = useSelection<InboxItem>({ items: computed(() => props.items), getId: item => item.inboxItemId, maxSelected: 100 })
/**
 * The count as a top-level binding, because only those are unwrapped in a template.
 *
 * `selection.selectedCount` reached through the object is the ref itself — always truthy — so the
 * batch bar used to render over an empty selection with 「已选 0 项」 and a live button.
 */
const selectedCount = selection.selectedCount
const orderedItems = computed(() => [...props.items].sort((left, right) => {
  const delta = new Date(left.openedAt).getTime() - new Date(right.openedAt).getTime()
  return inboxSort.direction.value === 'asc' ? delta : -delta
}))
function selectedIdList(): string[] { return [...selection.selectedIds.value] }

watch(
  () => [props.selectedItemId, props.detailPhase] as const,
  async ([itemId, phase]) => {
    if (!itemId || (phase !== 'ready' && phase !== 'error')) return
    await detailFocus.focusHeading()
  },
)

function count(type: InboxItemType): { total: number, unread: number } {
  return props.counts?.byType[type] ?? { total: 0, unread: 0 }
}

/** Contract §5.1: every disposition is reversible; ARCHIVED only steps back to READ first. */
function allowedActions(status: InboxDispositionStatus): InboxDispositionStatus[] {
  if (status === 'UNREAD') return ['READ', 'ACTED', 'ARCHIVED']
  if (status === 'READ') return ['ACTED', 'ARCHIVED', 'UNREAD']
  if (status === 'ACTED') return ['ARCHIVED', 'UNREAD']
  return ['READ', 'UNREAD']
}

function dateTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

function isOverdue(item: InboxItem): boolean {
  return item.sourceStatus === 'OPEN' && Boolean(item.deadline) && new Date(item.deadline!).getTime() < Date.now()
}

function priorityTone(priority: InboxItem['priority']): 'danger' | 'warning' | 'info' | 'neutral' {
  if (priority === 'URGENT') return 'danger'
  if (priority === 'HIGH') return 'warning'
  if (priority === 'NORMAL') return 'info'
  return 'neutral'
}

function dispositionTone(status: InboxDispositionStatus): 'success' | 'info' | 'neutral' {
  if (status === 'ACTED') return 'success'
  if (status === 'READ') return 'info'
  return 'neutral'
}

function dispositionLabel(status: InboxDispositionStatus): string {
  return inboxDispositionStatusLabels[status]
}

/** Inbox rows quote enums owned by five different aggregates, so each reads through its own map. */
function label(value: string | null | undefined, labels: Record<string, string>): string {
  return enumLabel(value, labels)
}

/** The same target reads differently from an archive: it steps back out first (contract §5.1). */
function actionLabel(action: InboxDispositionStatus, current: InboxDispositionStatus): string {
  if (action === 'READ') return current === 'ARCHIVED' ? '恢复（回到已读）' : '标记已读'
  if (action === 'ACTED') return '标记已处理'
  if (action === 'ARCHIVED') return '归档'
  return current === 'ARCHIVED' ? '恢复并标为未读' : '标为未读'
}

const { confirm } = useConfirm()

/** Archiving stays reversible (R26): the dialog explains the archive view, it never warns of loss. */
async function requestDisposition(itemId: string, action: InboxDispositionStatus): Promise<void> {
  if (action === 'ARCHIVED') {
    const agreed = await confirm({
      title: '归档这条 Inbox',
      description: '归档只影响你自己的 Inbox 视图；之后可以从「归档」筛选恢复，来源事实保持独立。',
      confirmLabel: '归档',
      cancelLabel: '取消',
      danger: false,
    })
    if (!agreed) return
  }
  emit('changeDisposition', itemId, action)
}

async function requestBatchDisposition(itemIds: string[], status: InboxBatchStatus): Promise<void> {
  if (status === 'ARCHIVED') {
    const agreed = await confirm({
      title: `归档选中的 ${itemIds.length} 项`,
      description: '归档只影响你自己的 Inbox 视图；之后可以从「归档」筛选逐项恢复，来源事实保持独立。',
      confirmLabel: '归档',
      cancelLabel: '取消',
      danger: false,
    })
    if (!agreed) return
  }
  emit('batchDisposition', itemIds, status)
}

const batchOutcomePresentation: Record<InboxBatchOutcome, { label: string, tone: 'success' | 'danger' | 'warning' | 'neutral' }> = {
  success: { label: '成功', tone: 'success' },
  rejected: { label: '已拒绝', tone: 'danger' },
  conflict: { label: '版本冲突', tone: 'warning' },
  unknown: { label: '结果未知', tone: 'neutral' },
}
const batchSummary = computed(() => {
  const report = props.batchReport
  if (!report) return ''
  const tally = new Map<InboxBatchOutcome, number>()
  for (const entry of report.results) tally.set(entry.outcome, (tally.get(entry.outcome) ?? 0) + 1)
  const tallyOrder: InboxBatchOutcome[] = ['success', 'rejected', 'conflict', 'unknown']
  return report.results.length + ' 项：' + tallyOrder.filter(outcome => tally.has(outcome)).map(outcome => `${batchOutcomePresentation[outcome].label} ${tally.get(outcome)}`).join(' · ')
})
/** Switching filters clears the selection, but a finished batch's own snapshot stays (contract §4.5). */
watch(
  () => [props.itemType, props.sourceStatus, props.dispositionStatus] as const,
  () => selection.clear(),
)

const typePresentation: Record<InboxItemType | 'ALL', { label: string, description: string }> = {
  ALL: { label: '全部', description: '汇总当前成员需要处理的全部事实' },
  OWNERSHIP: { label: '我的负责', description: '需要 Owner 推进或作出决策' },
  EXECUTION: { label: '我的执行', description: '分配给当前成员的执行责任' },
  REVIEW: { label: '待 Review', description: '等待成员完成 Gate Review' },
  CONFIRMATION: { label: '待确认', description: '等待精确外部动作确认' },
  EXCEPTION: { label: '异常', description: '需要成员关注的执行或投递异常' },
}
const inboxViews = ['ALL', ...inboxItemTypes] as const

/**
 * M13b-F10（L02）：行首主文本消费服务端 read-time join 的可读工作上下文——先回答「这条待办
 * 关于哪项工作」。workItemTitle 缺失（纯投递通知、来源对象已不存在）时回退类型标签，行仍渲染。
 */
function rowTitle(item: InboxItem): string {
  return item.sourceContext?.workItemTitle?.trim() || typePresentation[item.itemType].label
}

/** 副行先说任务目标或在等谁；两者皆缺时回退来源事实（类型 · revision）。 */
function rowSubtitle(item: InboxItem): string {
  const context = item.sourceContext
  return context?.taskObjective?.trim()
    || context?.waitingOnDisplayName?.trim()
    || `${label(item.source.type, inboxSourceTypeLabels)} · revision ${item.source.revision}`
}
</script>

<template>
  <section class="inbox-workspace" aria-label="我的 Inbox">
    <section class="summary-strip panel" aria-label="待处理事实概览">
      <h2 v-if="counts">{{ counts.total }} 项待处理事实</h2>
      <h2 v-else-if="countsPhase === 'loading'">正在同步待处理事实</h2>
      <h2 v-else>计数暂不可用</h2>
      <p class="summary-strip__note">
        {{ counts ? '处置状态由当前成员独立维护，未读数以徽标为准' : countsError?.message ?? '等待服务端返回当前成员的权威计数' }}
      </p>
      <StatusBadge :tone="countsUnavailable ? 'danger' : counts?.unread ? 'warning' : counts ? 'success' : 'neutral'" dot>
        {{ countsUnavailable ? '计数同步失败' : countsPhase === 'loading' ? '同步中' : `${counts?.unread ?? 0} 未读` }}
      </StatusBadge>
    </section>

    <nav class="inbox-views" aria-label="Inbox 五类视图">
      <button
        v-for="type in inboxViews"
        :key="type"
        type="button"
        :class="{ active: itemType === type }"
        :aria-current="itemType === type ? 'page' : undefined"
        @click="emit('changeType', type)"
      >
        <span>{{ typePresentation[type].label }}</span>
        <small class="sr-only">{{ typePresentation[type].description }}</small>
        <!-- M13b-F10（L01）：总数/未读总数只在 summary-strip 各出现一次；「全部」视图不再
             重复这两个数字，分类计数是增量信息才留在各自视图上。 -->
        <i v-if="counts && type !== 'ALL'">{{ count(type).total }}<b v-if="count(type).unread">{{ count(type).unread }}</b></i>
        <i v-else-if="!counts" aria-label="计数不可用">—</i>
      </button>
    </nav>

    <ScanToolbar aria-label="Inbox 筛选">
      <template #count>{{ items.length }} 项已加载<span v-if="selectedCount">，已选 {{ selectedCount }}</span></template>
      <template #filters>
        <label>来源状态<BaseSelect :model-value="sourceStatus" @update:model-value="emit('changeSourceStatus', $event as InboxSourceStatus)"><option v-for="status in inboxSourceStatuses" :key="status" :value="status">{{ status === 'OPEN' ? '仍需处理' : '来源已关闭' }}</option></BaseSelect></label>
        <label>我的处置<BaseSelect :model-value="dispositionStatus" @update:model-value="emit('changeDispositionStatus', $event as InboxDispositionStatus | 'QUEUE' | 'ALL')"><option value="QUEUE">待处理队列</option><option value="ALL">全部处置</option><option v-for="status in inboxDispositionStatuses" :key="status" :value="status">{{ dispositionLabel(status) }}</option></BaseSelect></label>
      </template>
      <template #side>
        <BaseButton variant="ghost" size="small" :aria-label="`按打开时间${inboxSortLabel}`" @click="inboxSort.toggleSort('openedAt')">{{ inboxSortLabel }}</BaseButton>
      </template>
    </ScanToolbar>
    <div v-if="selectedCount" class="selection-actions" role="toolbar" aria-label="批量处置">
      <span>已选 {{ selectedCount }} 项（跨页保留）</span>
      <BaseButton variant="secondary" size="small" :disabled="!online" @click="requestBatchDisposition(selectedIdList(), 'READ')">批量标记已读</BaseButton>
      <BaseButton variant="secondary" size="small" :disabled="!online" @click="requestBatchDisposition(selectedIdList(), 'ACTED')">批量标记已处理</BaseButton>
      <BaseButton variant="ghost" size="small" :disabled="!online" @click="requestBatchDisposition(selectedIdList(), 'ARCHIVED')">批量归档</BaseButton>
      <BaseButton variant="ghost" size="small" @click="selection.clear">清除选择</BaseButton>
    </div>
    <section v-if="batchReport" class="batch-report panel" aria-label="批量处置结果">
      <header>
        <div><strong>{{ batchReport.status === 'READ' ? '批量标记已读' : batchReport.status === 'ACTED' ? '批量标记已处理' : '批量归档' }}结果</strong><span>{{ batchSummary }}</span></div>
        <button type="button" aria-label="关闭批量结果" @click="emit('dismissBatchReport')"><X :size="15" /></button>
      </header>
      <ul>
        <li v-for="entry in batchReport.results" :key="entry.itemId">
          <StatusBadge :tone="batchOutcomePresentation[entry.outcome].tone">{{ batchOutcomePresentation[entry.outcome].label }}</StatusBadge>
          <span>{{ entry.title }}</span>
          <small>{{ entry.outcome === 'conflict' ? '处置版本已更新，回读列表后请基于当前状态重新确认。' : entry.outcome === 'unknown' ? '结果未知：先刷新确认本次结果，不要直接重试。' : entry.outcome === 'rejected' ? (entry.message ?? '命令被服务端拒绝。') : (entry.message ?? '处置已提交。') }}</small>
        </li>
      </ul>
      <footer v-if="batchReport.results.some(entry => entry.outcome === 'conflict' || entry.outcome === 'unknown')">
        <BaseButton variant="secondary" size="small" @click="emit('retry')">回读最新列表</BaseButton>
      </footer>
    </section>

    <div class="inbox-content" :class="{ 'has-detail': selectedItemId }">
      <section class="inbox-list panel" aria-label="Inbox 项目列表">
        <StatePanel v-if="initialLoading" state="loading" title="正在同步我的 Inbox" description="正在读取当前 Team 的成员专属投影。" />
        <StatePanel v-else-if="forbidden" state="forbidden" title="无权读取 Inbox" :description="teamMemberUnresolvedDescription"><template #action><RouterLink :to="{ name: 'access-denied', query: { requiredPermission: permissions.scopeRead } }"><BaseButton variant="secondary" size="small">查看权限说明</BaseButton></RouterLink></template></StatePanel>
        <StatePanel v-else-if="offline && !hasItems" state="offline" title="离线时没有可用 Inbox" description="联网后会回读当前成员的权威投影。" />
        <StatePanel v-else-if="cursorExpired && !hasItems" state="error" title="Inbox Cursor 已过期" description="刷新首屏后会从当前投影代际继续读取。" @retry="emit('retry')" />
        <StatePanel v-else-if="hardError && !hasItems" state="error" :description="error?.message" @retry="emit('retry')" />
        <!-- M13b-F06（R38）：默认队列视图的空态是「清空队列」的收官反馈，不是筛选无果——
             回看归档与历史处置的出口写在描述里。 -->
        <StatePanel
          v-else-if="!hasItems"
          state="empty"
          :title="dispositionStatus === 'QUEUE' && sourceStatus === 'OPEN' ? `${typePresentation[itemType].label}队列已清空` : `${typePresentation[itemType].label}暂无项目`"
          :description="dispositionStatus === 'QUEUE' && sourceStatus === 'OPEN' ? '当前类别没有待处理的 Inbox 事实；归档与历史处置可在「我的处置」筛选里回看。' : '当前筛选下没有需要展示的成员 Inbox 事实。'"
        ><template #action><BaseButton v-if="hasOptionalFilter" variant="secondary" size="small" @click="clearOptionalFilters">清除类型与处置筛选</BaseButton></template></StatePanel>

        <template v-else>
          <StatePanel v-if="offline" compact state="offline" title="正在展示最近同步的 Inbox" description="离线期间处置命令保持关闭。">
            <template v-if="lastSyncedAt" #description>离线期间处置命令保持关闭。最近同步于 <RelativeTime :value="lastSyncedAt" />。</template>
          </StatePanel>
          <StatePanel v-else-if="cursorExpired" compact state="error" title="续页 Cursor 已过期" description="刷新首屏可进入当前投影代际。" @retry="emit('retry')" />
          <StatePanel v-else-if="hardError" compact state="error" :description="error?.message" @retry="emit('retry')" />

          <ol>
            <li v-for="item in orderedItems" :key="item.inboxItemId" :class="{ selected: selectedItemId === item.inboxItemId, unread: item.dispositionStatus === 'UNREAD' }">
              <article>
                <header>
                  <div><input type="checkbox" :checked="selection.isSelected(item.inboxItemId)" :aria-label="`选择${typePresentation[item.itemType].label}`" @change="selection.toggle(item.inboxItemId)"><BellRing v-if="item.dispositionStatus === 'UNREAD'" :size="14" aria-label="未读" /><CheckCheck v-else :size="14" aria-hidden="true" /><strong>{{ rowTitle(item) }}</strong></div>
                  <RelativeTime :value="item.openedAt" />
                </header>
                <p>{{ rowSubtitle(item) }}</p>
                <div class="inbox-card__facts">
                  <StatusBadge tone="neutral">{{ typePresentation[item.itemType].label }}</StatusBadge>
                  <StatusBadge :tone="priorityTone(item.priority)">{{ inboxPriorityLabels[item.priority] }}</StatusBadge>
                  <StatusBadge :tone="dispositionTone(item.dispositionStatus)">{{ dispositionLabel(item.dispositionStatus) }}</StatusBadge>
                  <span v-if="item.deadline" :class="{ overdue: isOverdue(item) }"><Clock3 :size="11" aria-hidden="true" /><RelativeTime :value="item.deadline" /></span>
                  <span v-else :class="{ overdue: isOverdue(item) }"><Clock3 :size="11" aria-hidden="true" />无截止时间</span>
                </div>
                <footer><span class="inbox-card__source" :class="{ closed: item.sourceStatus !== 'OPEN' }">{{ item.sourceStatus === 'OPEN' ? '来源仍需处理' : '来源已关闭' }}</span><button type="button" :aria-label="`查看 ${typePresentation[item.itemType].label} 详情`" @click="openDetail(item.inboxItemId)">查看详情<ChevronRight :size="13" aria-hidden="true" /></button></footer>
              </article>
            </li>
          </ol>

          <footer v-if="nextCursor"><BaseButton variant="secondary" size="small" :loading="loadingMore" @click="emit('loadMore')"><RefreshCw :size="13" />加载更多</BaseButton></footer>
        </template>
      </section>

      <aside v-if="selectedItemId" class="inbox-detail panel" aria-label="Inbox 详情">
        <header><div><p>Member disposition</p><h2 ref="detailHeading" tabindex="-1">Inbox 详情</h2></div><button type="button" aria-label="关闭 Inbox 详情" @click="closeDetailAndRestore()"><X :size="17" /></button></header>
        <StatePanel v-if="detailPhase === 'idle' || detailPhase === 'loading'" state="loading" compact />
        <StatePanel v-else-if="detailPhase === 'error' && !detail" :state="detailError?.kind === 'forbidden' ? 'forbidden' : 'error'" compact :description="detailError?.message" @retry="emit('retryDetail', selectedItemId)" />

        <template v-else-if="selected">
          <StatePanel v-if="commandForSelection?.phase === 'conflict'" compact state="conflict" title="处置版本已更新" description="详情已回读，请基于当前处置重新确认操作。" @retry="emit('retryDetail', selectedItemId)" />
          <!-- M13b-F11（C2）：命令失败不盲目重放——恢复动作是回读最新处置，确认事实后再决定。 -->
          <StatePanel v-else-if="commandForSelection?.phase === 'error'" compact state="error" title="处置命令失败" :description="commandForSelection.error?.message" retry-label="回读最新处置" @retry="emit('retryDetail', selectedItemId)" />
          <StatePanel v-if="targetError" compact state="error" title="来源解析失败" :description="targetError.message" retry-label="重新加载来源" @retry="emit('retryTarget', selectedItemId)" />

          <section class="inbox-detail__hero">
            <div><StatusBadge :tone="priorityTone(selected.priority)">{{ inboxPriorityLabels[selected.priority] }}</StatusBadge><StatusBadge :tone="dispositionTone(selected.dispositionStatus)">{{ dispositionLabel(selected.dispositionStatus) }}</StatusBadge></div>
            <h3>{{ typePresentation[selected.itemType].label }}</h3>
            <p>{{ typePresentation[selected.itemType].description }}</p>
          </section>

          <dl>
            <div><dt>来源</dt><dd>{{ label(selected.source.type, inboxSourceTypeLabels) }}</dd></div>
            <div><dt>来源 Revision</dt><dd>{{ selected.source.revision }}</dd></div>
            <div><dt>来源状态</dt><dd>{{ label(selected.sourceStatus, inboxSourceStatusLabels) }}</dd></div>
            <div><dt>处置版本</dt><dd>v{{ selected.dispositionVersion }}</dd></div>
            <div><dt>打开时间</dt><dd>{{ dateTime(selected.openedAt) }}</dd></div>
            <div><dt>截止时间</dt><dd :class="{ overdue: isOverdue(selected) }">{{ selected.deadline ? dateTime(selected.deadline) : '未设置' }}</dd></div>
            <div v-if="selected.closedAt"><dt>关闭时间</dt><dd>{{ dateTime(selected.closedAt) }}</dd></div>
            <div v-if="selected.closeReason"><dt>关闭原因</dt><dd>{{ label(selected.closeReason, inboxCloseReasonLabels) }}</dd></div>
          </dl>

          <section class="inbox-detail__source"><ShieldCheck :size="16" aria-hidden="true" /><div><strong>服务端授权来源</strong><span class="mono">{{ selected.source.id }}</span></div><BaseButton size="small" variant="secondary" :loading="targetPhase === 'loading'" :disabled="!online" @click="emit('openTarget', selectedItemId)">打开来源<ArrowUpRight :size="13" /></BaseButton></section>

          <section class="inbox-detail__actions">
            <header><div><CircleAlert :size="15" aria-hidden="true" /><strong>我的处置</strong></div><span>强 ETag · 可逆处置</span></header>
            <p>处置只影响当前成员的 Inbox 视图，来源业务事实保持独立。</p>
            <div>
              <BaseButton
                v-for="status in allowedActions(selected.dispositionStatus)"
                :key="status"
                size="small"
                :variant="status === 'ARCHIVED' ? 'ghost' : status === 'ACTED' ? 'primary' : 'secondary'"
                :loading="commandForSelection?.phase === 'pending' && commandForSelection.operation === 'inbox-disposition'"
                :disabled="!online || commandForSelection?.phase === 'pending'"
                @click="requestDisposition(selectedItemId, status)"
              ><ArchiveRestore v-if="(status === 'READ' || status === 'UNREAD') && selected.dispositionStatus === 'ARCHIVED'" :size="13" /><Eye v-else-if="status === 'READ'" :size="13" /><CheckCheck v-else-if="status === 'ACTED'" :size="13" /><Archive v-else-if="status === 'ARCHIVED'" :size="13" /><BellRing v-else :size="13" />{{ actionLabel(status, selected.dispositionStatus) }}</BaseButton>
            </div>
          </section>
        </template>
      </aside>
    </div>
  </section>
</template>

<style scoped>
/* M13b-F09（契约 §7）：strip/五类视图/工具栏是同一组扫读条，gap 收到元素级 8px——
 * 首条数据距 context header 的 200px 预算在 12px panel gap 下装不下（实测 207）。 */
.inbox-workspace { display: grid; grid-template-columns: minmax(0, 1fr); max-width: var(--cs-content-max); gap: var(--cs-space-8); margin: 0 auto; }.inbox-detail > header p { margin: 0; color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); letter-spacing: .1em; text-transform: uppercase; }
/* M13b-F09（契约 §7）：五类视图从 82px 竖排卡收敛为单行 pill——首条数据距 context header ≤200px
 * 的预算放不下第二层大卡；类别说明退到 sr-only，计数内联到标签右侧，密度与工具栏控件对齐。 */
.inbox-views { display: flex; flex-wrap: wrap; gap: var(--cs-space-8); }.inbox-views button { display: inline-flex; min-height: var(--cs-density-control-height); align-items: center; gap: var(--cs-space-8); padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: 999px; background: var(--cs-surface); color: var(--cs-text-secondary); cursor: pointer; }.inbox-views button:hover, .inbox-views button:focus-visible { border-color: var(--cs-border-accent-strong); }.inbox-views button.active { border-color: var(--cs-border-accent); background: var(--cs-surface-accent); box-shadow: inset 0 0 0 1px var(--cs-ring-brand); color: var(--cs-text-brand-strong); }.inbox-views span { font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); white-space: nowrap; }.inbox-views i { position: relative; display: inline-grid; min-width: 22px; height: 22px; place-items: center; border-radius: 8px; background: var(--cs-surface-subtle); font: var(--cs-weight-semibold) var(--cs-text-xs) var(--cs-font-mono); font-style: normal; }.inbox-views i b { position: absolute; top: -6px; right: -6px; display: grid; min-width: 15px; height: 15px; place-items: center; padding: 0 var(--cs-space-4); border-radius: 8px; background: var(--cs-warning); color: var(--cs-text-on-semantic); font-size: var(--cs-text-xs); }
.selection-actions { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid var(--cs-border-accent); border-radius: var(--cs-radius-md); background: var(--cs-surface-accent); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); }
.batch-report { padding: var(--cs-space-12) var(--cs-space-16); }.batch-report > header { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--cs-space-12); }.batch-report > header > div { display: grid; gap: var(--cs-space-2); }.batch-report > header strong { font-size: var(--cs-text-sm); }.batch-report > header span { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.batch-report > header button { display: grid; width: 28px; height: 28px; place-items: center; border-radius: 7px; background: var(--cs-surface-subtle); cursor: pointer; }.batch-report > ul { display: grid; gap: var(--cs-space-4); margin: var(--cs-space-8) 0 0; padding: 0; list-style: none; }.batch-report > ul > li { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cs-space-8); }.batch-report > ul > li .mono { color: var(--cs-text-muted); font: var(--cs-text-xs) var(--cs-font-mono); }.batch-report > ul > li small { flex-basis: 100%; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.batch-report > footer { display: flex; justify-content: flex-end; margin-top: var(--cs-space-8); }
.inbox-content { display: grid; grid-template-columns: minmax(0, 1fr); align-items: start; gap: var(--cs-space-12); }.inbox-content.has-detail { grid-template-columns: minmax(0, 1fr) 355px; }.inbox-list { overflow: hidden; }.inbox-list > ol { display: grid; gap: 0; margin: 0; padding: 0; list-style: none; }.inbox-list > ol > li { border-bottom: 1px solid var(--cs-border); }.inbox-list > ol > li:last-child { border-bottom: 0; }.inbox-list > ol > li.selected { background: var(--cs-surface-accent); }.inbox-list > ol > li.unread { box-shadow: inset 3px 0 var(--cs-warning); }.inbox-list article { padding: var(--cs-space-16) var(--cs-space-16); }.inbox-list article > header, .inbox-list article > footer, .inbox-detail__actions > header { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-12); }.inbox-list article > header > div { display: flex; align-items: center; gap: var(--cs-space-8); }.inbox-list article > header svg { color: var(--cs-text-brand); }.inbox-list article > header strong { font-size: var(--cs-text-base); font-weight: var(--cs-weight-semibold); }.inbox-list article > header time { color: var(--cs-text-muted); font: var(--cs-text-xs) var(--cs-font-mono); }.inbox-list article > p { margin: var(--cs-space-8) 0 var(--cs-space-8); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); }.inbox-card__facts { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cs-space-8); }.inbox-card__facts > span { display: inline-flex; align-items: center; gap: var(--cs-space-4); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.overdue { color: var(--cs-danger) !important; font-weight: var(--cs-weight-semibold); }.inbox-list article > footer { margin-top: var(--cs-space-12); }.inbox-list article > footer > .inbox-card__source { color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.inbox-list article > footer > .inbox-card__source.closed { color: var(--cs-text-muted); font-weight: var(--cs-weight-medium); }.inbox-list article > footer button { display: inline-flex; min-height: 30px; align-items: center; gap: var(--cs-space-4); padding: 0 var(--cs-space-8); border-radius: 7px; color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); cursor: pointer; }.inbox-list article > footer button:hover, .inbox-list article > footer button:focus-visible { background: var(--cs-surface-accent-strong); }.inbox-list > footer { display: flex; justify-content: center; padding: var(--cs-space-12); border-top: 1px solid var(--cs-border); }
.inbox-detail { position: sticky; top: 12px; overflow: hidden; }.inbox-detail > header { display: flex; align-items: center; justify-content: space-between; padding: var(--cs-space-12) var(--cs-space-16); border-bottom: 1px solid var(--cs-border); }.inbox-detail > header h2 { margin: var(--cs-space-2) 0 0; font-size: var(--cs-text-md); }.inbox-detail > header button { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 8px; background: var(--cs-surface-subtle); cursor: pointer; }.inbox-detail__hero { padding: var(--cs-space-16); border-bottom: 1px solid var(--cs-border); }.inbox-detail__hero > div { display: flex; gap: var(--cs-space-8); }.inbox-detail__hero h3 { margin: var(--cs-space-12) 0 var(--cs-space-4); font-size: var(--cs-text-md); }.inbox-detail__hero p { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.inbox-detail > dl { display: grid; grid-template-columns: 1fr 1fr; gap: 0 var(--cs-space-12); margin: 0; padding: var(--cs-space-8) var(--cs-space-16); }.inbox-detail > dl > div { padding: var(--cs-space-8) 0; border-bottom: 1px solid var(--cs-border); }.inbox-detail dt { color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); text-transform: uppercase; }.inbox-detail dd { overflow-wrap: anywhere; margin: var(--cs-space-2) 0 0; font-size: var(--cs-text-xs); }.inbox-detail__source { display: grid; grid-template-columns: 18px minmax(0, 1fr) auto; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-12) var(--cs-space-16); border-top: 1px solid var(--cs-border); }.inbox-detail__source > svg { color: var(--cs-text-brand); }.inbox-detail__source strong, .inbox-detail__source span { display: block; }.inbox-detail__source strong { font-size: var(--cs-text-xs); }.inbox-detail__source span { overflow: hidden; margin-top: var(--cs-space-2); color: var(--cs-text-muted); font-size: var(--cs-text-xs); text-overflow: ellipsis; white-space: nowrap; }.inbox-detail__actions { padding: var(--cs-space-16) var(--cs-space-16); border-top: 1px solid var(--cs-border); background: var(--cs-surface-subtle); }.inbox-detail__actions > header > div { display: flex; align-items: center; gap: var(--cs-space-4); }.inbox-detail__actions > header strong { font-size: var(--cs-text-sm); }.inbox-detail__actions > header span { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.inbox-detail__actions > p { margin: var(--cs-space-8) 0 var(--cs-space-12); color: var(--cs-text-muted); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }.inbox-detail__actions > div { display: flex; flex-wrap: wrap; gap: var(--cs-space-8); }
@media (max-width: 980px) { .inbox-content.has-detail { grid-template-columns: minmax(0, 1fr) 330px; } }
@media (max-width: 760px) { .inbox-views { flex-wrap: nowrap; overflow-x: auto; padding-bottom: var(--cs-space-2); scroll-snap-type: x proximity; }.inbox-views button { flex: 0 0 auto; scroll-snap-align: start; }.inbox-content.has-detail { grid-template-columns: 1fr; }.inbox-detail { position: static; grid-row: 1; }.inbox-list article { padding: var(--cs-space-12); } }
@media (max-width: 460px) { .inbox-detail > dl { grid-template-columns: 1fr; }.inbox-detail__source { grid-template-columns: 18px minmax(0, 1fr); }.inbox-detail__source > button { grid-column: 1 / -1; width: 100%; }.inbox-detail__actions > div { display: grid; }.inbox-detail__actions > div > * { width: 100%; } }
</style>
