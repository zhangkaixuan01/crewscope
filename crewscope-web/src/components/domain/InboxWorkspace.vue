<script setup lang="ts">
import {
  Archive,
  ArrowUpRight,
  BellRing,
  CheckCheck,
  ChevronRight,
  CircleAlert,
  Clock3,
  Eye,
  Inbox,
  RefreshCw,
  ShieldCheck,
  X,
} from '@lucide/vue'
import { computed, nextTick, useTemplateRef, watch } from 'vue'
import type { TeamOpsErrorState } from '../../domains/teamops/errors'
import type { TeamOpsCommandState, TeamOpsPhase } from '../../domains/teamops/store'
import {
  inboxDispositionStatuses,
  inboxItemTypes,
  inboxSourceStatuses,
  type Etagged,
  type InboxCounts,
  type InboxDispositionStatus,
  type InboxItem,
  type InboxItemType,
  type InboxSourceStatus,
} from '../../domains/teamops/types'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'

const props = defineProps<{
  phase: TeamOpsPhase
  items: InboxItem[]
  countsPhase: TeamOpsPhase
  counts: InboxCounts | null
  countsError: TeamOpsErrorState | null
  nextCursor: string | null
  loadingMore: boolean
  error: TeamOpsErrorState | null
  selectedItemId: string | null
  detailPhase: TeamOpsPhase
  detail: Etagged<InboxItem> | null
  detailError: TeamOpsErrorState | null
  targetPhase: TeamOpsPhase
  targetError: TeamOpsErrorState | null
  command: TeamOpsCommandState
  itemType: InboxItemType
  sourceStatus: InboxSourceStatus
  dispositionStatus: InboxDispositionStatus | 'ALL'
  online: boolean
}>()

const emit = defineEmits<{
  select: [itemId: string]
  closeDetail: []
  changeType: [value: InboxItemType]
  changeSourceStatus: [value: InboxSourceStatus]
  changeDispositionStatus: [value: InboxDispositionStatus | 'ALL']
  retry: []
  retryDetail: [itemId: string]
  loadMore: []
  openTarget: [itemId: string]
  changeDisposition: [itemId: string, status: Exclude<InboxDispositionStatus, 'UNREAD'>]
}>()

const detailHeading = useTemplateRef<HTMLElement>('detailHeading')
const hasItems = computed(() => props.items.length > 0)
const initialLoading = computed(() => (props.phase === 'idle' || props.phase === 'loading') && !hasItems.value)
const forbidden = computed(() => props.error?.kind === 'forbidden')
const cursorExpired = computed(() => props.error?.kind === 'cursor-expired')
const offline = computed(() => !props.online || props.error?.kind === 'offline')
const hardError = computed(() => props.phase === 'error' && !forbidden.value && !cursorExpired.value && !offline.value)
const countsUnavailable = computed(() => props.countsPhase === 'error')
const selected = computed(() => props.detail?.value ?? props.items.find(item => item.inboxItemId === props.selectedItemId) ?? null)
const commandForSelection = computed(() => props.command.targetId === props.selectedItemId ? props.command : null)

watch(
  () => [props.selectedItemId, props.detailPhase] as const,
  async ([itemId, phase]) => {
    if (!itemId || (phase !== 'ready' && phase !== 'error')) return
    await nextTick()
    detailHeading.value?.focus()
  },
)

function count(type: InboxItemType): { total: number, unread: number } {
  return props.counts?.byType[type] ?? { total: 0, unread: 0 }
}

function allowedActions(status: InboxDispositionStatus): Array<Exclude<InboxDispositionStatus, 'UNREAD'>> {
  if (status === 'UNREAD') return ['READ', 'ACTED', 'ARCHIVED']
  if (status === 'READ') return ['ACTED', 'ARCHIVED']
  if (status === 'ACTED') return ['ARCHIVED']
  return []
}

function dateTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

function shortDate(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value))
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
  return ({ UNREAD: '未读', READ: '已读', ACTED: '已处理', ARCHIVED: '已归档' } as const)[status]
}

function actionLabel(status: Exclude<InboxDispositionStatus, 'UNREAD'>): string {
  return ({ READ: '标记已读', ACTED: '标记已处理', ARCHIVED: '归档' } as const)[status]
}

const typePresentation: Record<InboxItemType, { label: string, description: string }> = {
  OWNERSHIP: { label: '我的负责', description: '需要 Owner 推进或作出决策' },
  EXECUTION: { label: '我的执行', description: '分配给当前成员的执行责任' },
  REVIEW: { label: '待 Review', description: '等待成员完成 Gate Review' },
  CONFIRMATION: { label: '待确认', description: '等待精确外部动作确认' },
  EXCEPTION: { label: '异常', description: '需要成员关注的执行或投递异常' },
}
</script>

<template>
  <section class="inbox-workspace" aria-label="我的 Inbox">
    <section class="inbox-overview panel">
      <span><Inbox :size="23" aria-hidden="true" /></span>
      <div>
        <p>Member-owned queue</p>
        <h2 v-if="counts">{{ counts.total }} 项待处理事实</h2>
        <h2 v-else-if="countsPhase === 'loading'">正在同步待处理事实</h2>
        <h2 v-else>计数暂不可用</h2>
        <small v-if="counts">{{ counts.unread }} 项未读，处置状态由当前成员独立维护。</small>
        <small v-else>{{ countsError?.message ?? '等待服务端返回当前成员的权威计数。' }}</small>
      </div>
      <StatusBadge :tone="countsUnavailable ? 'danger' : counts?.unread ? 'warning' : counts ? 'success' : 'neutral'" dot>
        {{ countsUnavailable ? '计数同步失败' : countsPhase === 'loading' ? '同步中' : `${counts?.unread ?? 0} 未读` }}
      </StatusBadge>
    </section>

    <nav class="inbox-views" aria-label="Inbox 五类视图">
      <button
        v-for="type in inboxItemTypes"
        :key="type"
        type="button"
        :class="{ active: itemType === type }"
        :aria-current="itemType === type ? 'page' : undefined"
        @click="emit('changeType', type)"
      >
        <span>{{ typePresentation[type].label }}</span>
        <small>{{ typePresentation[type].description }}</small>
        <i v-if="counts">{{ count(type).total }}<b v-if="count(type).unread">{{ count(type).unread }}</b></i>
        <i v-else aria-label="计数不可用">—</i>
      </button>
    </nav>

    <section class="inbox-toolbar panel" aria-label="Inbox 筛选">
      <label>来源状态<select :value="sourceStatus" @change="emit('changeSourceStatus', ($event.target as HTMLSelectElement).value as InboxSourceStatus)"><option v-for="status in inboxSourceStatuses" :key="status" :value="status">{{ status === 'OPEN' ? '仍需处理' : '来源已关闭' }}</option></select></label>
      <label>我的处置<select :value="dispositionStatus" @change="emit('changeDispositionStatus', ($event.target as HTMLSelectElement).value as InboxDispositionStatus | 'ALL')"><option value="ALL">全部处置</option><option v-for="status in inboxDispositionStatuses" :key="status" :value="status">{{ dispositionLabel(status) }}</option></select></label>
      <span>{{ items.length }} 项已加载</span>
    </section>

    <div class="inbox-content" :class="{ 'has-detail': selectedItemId }">
      <section class="inbox-list panel" aria-label="Inbox 项目列表">
        <StatePanel v-if="initialLoading" state="loading" title="正在同步我的 Inbox" description="正在读取当前 Team 的成员专属投影。" />
        <StatePanel v-else-if="forbidden" state="forbidden" title="无权读取 Inbox" description="服务端没有为当前身份解析出这个 Team 的活动成员。" />
        <StatePanel v-else-if="offline && !hasItems" state="offline" title="离线时没有可用 Inbox" description="联网后会回读当前成员的权威投影。" />
        <StatePanel v-else-if="cursorExpired && !hasItems" state="error" title="Inbox Cursor 已过期" description="刷新首屏后会从当前投影代际继续读取。" @retry="emit('retry')" />
        <StatePanel v-else-if="hardError && !hasItems" state="error" :description="error?.message" @retry="emit('retry')" />
        <StatePanel v-else-if="!hasItems" state="empty" :title="`${typePresentation[itemType].label}暂无项目`" description="当前筛选下没有需要展示的成员 Inbox 事实。" />

        <template v-else>
          <StatePanel v-if="offline" compact state="offline" title="正在展示最近同步的 Inbox" description="离线期间处置命令保持关闭。" />
          <StatePanel v-else-if="cursorExpired" compact state="error" title="续页 Cursor 已过期" description="刷新首屏可进入当前投影代际。" @retry="emit('retry')" />
          <StatePanel v-else-if="hardError" compact state="error" :description="error?.message" @retry="emit('retry')" />

          <ol>
            <li v-for="item in items" :key="item.inboxItemId" :class="{ selected: selectedItemId === item.inboxItemId, unread: item.dispositionStatus === 'UNREAD' }">
              <article>
                <header>
                  <div><BellRing v-if="item.dispositionStatus === 'UNREAD'" :size="14" aria-label="未读" /><CheckCheck v-else :size="14" aria-hidden="true" /><strong>{{ typePresentation[item.itemType].label }}</strong></div>
                  <time :datetime="item.openedAt">{{ shortDate(item.openedAt) }}</time>
                </header>
                <p>{{ item.source.type }} · revision {{ item.source.revision }}</p>
                <div class="inbox-card__facts">
                  <StatusBadge :tone="priorityTone(item.priority)">{{ item.priority }}</StatusBadge>
                  <StatusBadge :tone="dispositionTone(item.dispositionStatus)">{{ dispositionLabel(item.dispositionStatus) }}</StatusBadge>
                  <span :class="{ overdue: isOverdue(item) }"><Clock3 :size="11" aria-hidden="true" />{{ item.deadline ? shortDate(item.deadline) : '无截止时间' }}</span>
                </div>
                <footer><span class="mono">{{ item.source.id.slice(0, 8) }}</span><button type="button" :aria-label="`查看 ${typePresentation[item.itemType].label} 详情`" @click="emit('select', item.inboxItemId)">查看详情<ChevronRight :size="13" aria-hidden="true" /></button></footer>
              </article>
            </li>
          </ol>

          <footer v-if="nextCursor"><BaseButton variant="secondary" size="small" :loading="loadingMore" @click="emit('loadMore')"><RefreshCw :size="13" />加载更多</BaseButton></footer>
        </template>
      </section>

      <aside v-if="selectedItemId" class="inbox-detail panel" aria-label="Inbox 详情">
        <header><div><p>Member disposition</p><h2 ref="detailHeading" tabindex="-1">Inbox 详情</h2></div><button type="button" aria-label="关闭 Inbox 详情" @click="emit('closeDetail')"><X :size="17" /></button></header>
        <StatePanel v-if="detailPhase === 'idle' || detailPhase === 'loading'" state="loading" compact />
        <StatePanel v-else-if="detailPhase === 'error' && !detail" :state="detailError?.kind === 'forbidden' ? 'forbidden' : 'error'" compact :description="detailError?.message" @retry="emit('retryDetail', selectedItemId)" />

        <template v-else-if="selected">
          <StatePanel v-if="commandForSelection?.phase === 'conflict'" compact state="conflict" title="处置版本已更新" description="详情已回读，请基于当前处置重新确认操作。" @retry="emit('retryDetail', selectedItemId)" />
          <StatePanel v-else-if="commandForSelection?.phase === 'error'" compact state="error" title="处置命令失败" :description="commandForSelection.error?.message" />
          <StatePanel v-if="targetError" compact state="error" title="来源解析失败" :description="targetError.message" />

          <section class="inbox-detail__hero">
            <div><StatusBadge :tone="priorityTone(selected.priority)">{{ selected.priority }}</StatusBadge><StatusBadge :tone="dispositionTone(selected.dispositionStatus)">{{ dispositionLabel(selected.dispositionStatus) }}</StatusBadge></div>
            <h3>{{ typePresentation[selected.itemType].label }}</h3>
            <p>{{ typePresentation[selected.itemType].description }}</p>
          </section>

          <dl>
            <div><dt>来源</dt><dd>{{ selected.source.type }}</dd></div>
            <div><dt>来源 Revision</dt><dd>{{ selected.source.revision }}</dd></div>
            <div><dt>来源状态</dt><dd>{{ selected.sourceStatus }}</dd></div>
            <div><dt>处置版本</dt><dd>v{{ selected.dispositionVersion }}</dd></div>
            <div><dt>打开时间</dt><dd>{{ dateTime(selected.openedAt) }}</dd></div>
            <div><dt>截止时间</dt><dd :class="{ overdue: isOverdue(selected) }">{{ selected.deadline ? dateTime(selected.deadline) : '未设置' }}</dd></div>
            <div v-if="selected.closedAt"><dt>关闭时间</dt><dd>{{ dateTime(selected.closedAt) }}</dd></div>
            <div v-if="selected.closeReason"><dt>关闭原因</dt><dd>{{ selected.closeReason }}</dd></div>
          </dl>

          <section class="inbox-detail__source"><ShieldCheck :size="16" aria-hidden="true" /><div><strong>服务端授权来源</strong><span class="mono">{{ selected.source.id }}</span></div><BaseButton size="small" variant="secondary" :loading="targetPhase === 'loading'" :disabled="!online" @click="emit('openTarget', selectedItemId)">打开来源<ArrowUpRight :size="13" /></BaseButton></section>

          <section class="inbox-detail__actions">
            <header><div><CircleAlert :size="15" aria-hidden="true" /><strong>我的处置</strong></div><span>强 ETag · 单调状态</span></header>
            <p>处置只影响当前成员的 Inbox 视图，来源业务事实保持独立。</p>
            <div v-if="allowedActions(selected.dispositionStatus).length">
              <BaseButton
                v-for="status in allowedActions(selected.dispositionStatus)"
                :key="status"
                size="small"
                :variant="status === 'ARCHIVED' ? 'ghost' : status === 'ACTED' ? 'primary' : 'secondary'"
                :loading="commandForSelection?.phase === 'pending' && commandForSelection.operation === 'inbox-disposition'"
                :disabled="!online || commandForSelection?.phase === 'pending'"
                @click="emit('changeDisposition', selectedItemId, status)"
              ><Eye v-if="status === 'READ'" :size="13" /><CheckCheck v-else-if="status === 'ACTED'" :size="13" /><Archive v-else :size="13" />{{ actionLabel(status) }}</BaseButton>
            </div>
            <StatusBadge v-else tone="neutral">处置已归档</StatusBadge>
          </section>
        </template>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.inbox-workspace { display: grid; max-width: 1240px; gap: 13px; margin: 0 auto; }.inbox-overview { display: grid; grid-template-columns: 44px minmax(0, 1fr) auto; align-items: center; gap: 13px; padding: 16px 18px; }.inbox-overview > span { display: grid; width: 44px; height: 44px; place-items: center; border-radius: 13px; background: var(--cs-brand-100); color: var(--cs-brand-700); }.inbox-overview p, .inbox-detail > header p { margin: 0; color: var(--cs-brand-700); font-size: 8px; font-weight: 800; letter-spacing: .1em; text-transform: uppercase; }.inbox-overview h2 { margin: 2px 0; font-size: 17px; }.inbox-overview small { color: var(--cs-text-muted); font-size: 9px; }
.inbox-views { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 7px; }.inbox-views button { position: relative; display: grid; min-width: 0; min-height: 82px; align-content: center; gap: 3px; padding: 11px 38px 11px 12px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); color: var(--cs-text-secondary); text-align: left; cursor: pointer; }.inbox-views button:hover, .inbox-views button:focus-visible { border-color: var(--cs-brand-300); }.inbox-views button.active { border-color: #9cc7a8; background: #f2faf4; box-shadow: inset 0 0 0 1px rgb(49 128 78 / 8%); color: var(--cs-brand-800); }.inbox-views span { font-size: 11px; font-weight: 780; }.inbox-views small { overflow: hidden; color: var(--cs-text-muted); font-size: 8px; line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; }.inbox-views i { position: absolute; top: 11px; right: 10px; display: grid; min-width: 22px; height: 22px; place-items: center; border-radius: 8px; background: var(--cs-surface-subtle); font: 700 9px var(--cs-font-mono); font-style: normal; }.inbox-views i b { position: absolute; top: -6px; right: -6px; display: grid; min-width: 15px; height: 15px; place-items: center; padding: 0 3px; border-radius: 8px; background: var(--cs-warning); color: white; font-size: 7px; }
.inbox-toolbar { display: grid; grid-template-columns: minmax(170px, 220px) minmax(170px, 220px) 1fr; align-items: end; gap: 11px; padding: 11px 13px; }.inbox-toolbar label { display: grid; gap: 4px; color: var(--cs-text-muted); font-size: 8px; font-weight: 750; }.inbox-toolbar select { min-height: 35px; padding: 0 9px; border: 1px solid var(--cs-border); border-radius: 8px; background: #fff; color: var(--cs-text); font-size: 10px; }.inbox-toolbar > span { justify-self: end; padding-bottom: 9px; color: var(--cs-text-muted); font: 9px var(--cs-font-mono); }
.inbox-content { display: grid; grid-template-columns: minmax(0, 1fr); align-items: start; gap: 13px; }.inbox-content.has-detail { grid-template-columns: minmax(0, 1fr) 355px; }.inbox-list { overflow: hidden; }.inbox-list > ol { display: grid; gap: 0; margin: 0; padding: 0; list-style: none; }.inbox-list > ol > li { border-bottom: 1px solid var(--cs-border); }.inbox-list > ol > li:last-child { border-bottom: 0; }.inbox-list > ol > li.selected { background: #f4faf5; }.inbox-list > ol > li.unread { box-shadow: inset 3px 0 var(--cs-warning); }.inbox-list article { padding: 14px 16px; }.inbox-list article > header, .inbox-list article > footer, .inbox-detail__actions > header { display: flex; align-items: center; justify-content: space-between; gap: 10px; }.inbox-list article > header > div { display: flex; align-items: center; gap: 6px; }.inbox-list article > header svg { color: var(--cs-brand-700); }.inbox-list article > header strong { font-size: 11px; }.inbox-list article > header time { color: var(--cs-text-muted); font: 8px var(--cs-font-mono); }.inbox-list article > p { margin: 6px 0 9px; color: var(--cs-text-secondary); font: 9px var(--cs-font-mono); }.inbox-card__facts { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }.inbox-card__facts > span { display: inline-flex; align-items: center; gap: 4px; color: var(--cs-text-muted); font-size: 8px; }.overdue { color: var(--cs-danger) !important; font-weight: 750; }.inbox-list article > footer { margin-top: 10px; }.inbox-list article > footer > span { color: var(--cs-text-muted); font-size: 8px; }.inbox-list article > footer button { display: inline-flex; min-height: 30px; align-items: center; gap: 4px; padding: 0 8px; border-radius: 7px; color: var(--cs-brand-700); font-size: 9px; font-weight: 750; cursor: pointer; }.inbox-list article > footer button:hover, .inbox-list article > footer button:focus-visible { background: var(--cs-brand-100); }.inbox-list > footer { display: flex; justify-content: center; padding: 13px; border-top: 1px solid var(--cs-border); }
.inbox-detail { position: sticky; top: 12px; overflow: hidden; }.inbox-detail > header { display: flex; align-items: center; justify-content: space-between; padding: 13px 14px; border-bottom: 1px solid var(--cs-border); }.inbox-detail > header h2 { margin: 2px 0 0; font-size: 15px; }.inbox-detail > header button { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 8px; background: var(--cs-surface-subtle); cursor: pointer; }.inbox-detail__hero { padding: 15px; border-bottom: 1px solid var(--cs-border); }.inbox-detail__hero > div { display: flex; gap: 6px; }.inbox-detail__hero h3 { margin: 10px 0 3px; font-size: 15px; }.inbox-detail__hero p { margin: 0; color: var(--cs-text-muted); font-size: 9px; }.inbox-detail > dl { display: grid; grid-template-columns: 1fr 1fr; gap: 0 12px; margin: 0; padding: 8px 15px; }.inbox-detail > dl > div { padding: 7px 0; border-bottom: 1px solid var(--cs-border); }.inbox-detail dt { color: var(--cs-text-muted); font-size: 7px; font-weight: 750; text-transform: uppercase; }.inbox-detail dd { overflow-wrap: anywhere; margin: 2px 0 0; font-size: 8px; }.inbox-detail__source { display: grid; grid-template-columns: 18px minmax(0, 1fr) auto; align-items: center; gap: 8px; padding: 13px 15px; border-top: 1px solid var(--cs-border); }.inbox-detail__source > svg { color: var(--cs-brand-700); }.inbox-detail__source strong, .inbox-detail__source span { display: block; }.inbox-detail__source strong { font-size: 9px; }.inbox-detail__source span { overflow: hidden; margin-top: 2px; color: var(--cs-text-muted); font-size: 7px; text-overflow: ellipsis; white-space: nowrap; }.inbox-detail__actions { padding: 14px 15px; border-top: 1px solid var(--cs-border); background: var(--cs-surface-subtle); }.inbox-detail__actions > header > div { display: flex; align-items: center; gap: 5px; }.inbox-detail__actions > header strong { font-size: 10px; }.inbox-detail__actions > header span { color: var(--cs-text-muted); font-size: 7px; }.inbox-detail__actions > p { margin: 7px 0 10px; color: var(--cs-text-muted); font-size: 8px; line-height: 1.45; }.inbox-detail__actions > div { display: flex; flex-wrap: wrap; gap: 6px; }
@media (max-width: 980px) { .inbox-views { grid-template-columns: repeat(3, minmax(0, 1fr)); }.inbox-content.has-detail { grid-template-columns: minmax(0, 1fr) 330px; } }
@media (max-width: 760px) { .inbox-overview { grid-template-columns: 38px 1fr; padding: 13px; }.inbox-overview > span { width: 38px; height: 38px; }.inbox-overview > :last-child { grid-column: 1 / -1; justify-self: start; }.inbox-views { display: flex; overflow-x: auto; padding-bottom: 2px; scroll-snap-type: x proximity; }.inbox-views button { flex: 0 0 160px; scroll-snap-align: start; }.inbox-toolbar { grid-template-columns: 1fr 1fr; }.inbox-toolbar > span { grid-column: 1 / -1; justify-self: start; padding: 0; }.inbox-content.has-detail { grid-template-columns: 1fr; }.inbox-detail { position: static; grid-row: 1; }.inbox-list article { padding: 13px; } }
@media (max-width: 460px) { .inbox-toolbar { grid-template-columns: 1fr; }.inbox-toolbar > span { grid-column: auto; }.inbox-toolbar select { min-height: 42px; }.inbox-detail > dl { grid-template-columns: 1fr; }.inbox-detail__source { grid-template-columns: 18px minmax(0, 1fr); }.inbox-detail__source > button { grid-column: 1 / -1; width: 100%; }.inbox-detail__actions > div { display: grid; }.inbox-detail__actions > div > * { width: 100%; } }
</style>
