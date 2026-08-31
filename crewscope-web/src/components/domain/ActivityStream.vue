<script setup lang="ts">
import { Activity, ArrowRight, CircleDot, Clock3, Link2, Radio, RefreshCw, UserRound } from '@lucide/vue'
import { computed } from 'vue'
import { RouterLink, useRoute, type RouteLocationRaw } from 'vue-router'
import type { ActivityRealtimePhase } from '../../domains/teamops/activityRealtimeStore'
import type { TeamOpsErrorState } from '../../domains/teamops/errors'
import type { ActivityItem } from '../../domains/teamops/types'
import { principalDisplayName, type PrincipalNameDirectory } from '../../domains/scope/memberDirectory'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'

const props = withDefaults(defineProps<{
  phase: 'idle' | 'loading' | 'ready' | 'empty' | 'error'
  items: ActivityItem[]
  nextCursor: string | null
  loadingMore: boolean
  error: TeamOpsErrorState | null
  realtimePhase?: ActivityRealtimePhase
  online?: boolean
  compact?: boolean
  heading?: string
  description?: string
  principalNames?: PrincipalNameDirectory
}>(), {
  realtimePhase: 'idle',
  online: true,
  compact: false,
  heading: '团队活动',
  description: '责任、执行、Review 与交付事实按团队顺序汇聚。',
  principalNames: () => ({}),
})

const emit = defineEmits<{ retry: [], loadMore: [], select: [item: ActivityItem] }>()
const route = useRoute()
const initialLoading = computed(() => (props.phase === 'idle' || props.phase === 'loading') && props.items.length === 0)
const forbidden = computed(() => props.error?.kind === 'forbidden' || props.realtimePhase === 'forbidden')
const cursorExpired = computed(() => props.error?.kind === 'cursor-expired' || props.realtimePhase === 'cursor-expired')
const offline = computed(() => !props.online || props.error?.kind === 'offline' || props.realtimePhase === 'offline')
const hardError = computed(() => props.phase === 'error' && !forbidden.value && !cursorExpired.value && !offline.value)
const realtimeLabel = computed(() => ({
  idle: '历史模式', connecting: '正在连接', live: '实时', reconnecting: '正在补发',
  offline: '离线', forbidden: '无权限', 'cursor-expired': '游标过期', error: '实时异常',
})[props.realtimePhase])

function actor(item: ActivityItem): string {
  if (!item.actor.principalId) return item.actor.type === 'SYSTEM' ? '系统' : item.actor.type
  return principalDisplayName(props.principalNames, item.actor.principalId, item.actor.type)
}

function subject(item: ActivityItem): string {
  return `${item.subject.type} · ${item.subject.id.slice(0, 8)}`
}

function outcome(item: ActivityItem): string {
  const values = item.payload.values
  return values.outcome ?? values.status ?? values.result ?? values.decision ?? item.eventType
}

function outcomeTone(item: ActivityItem): 'success' | 'danger' | 'warning' | 'info' | 'neutral' {
  const value = outcome(item).toUpperCase()
  if (/(SUCCEEDED|COMPLETED|APPROVED|DONE|ACTIVE)/.test(value)) return 'success'
  if (/(FAILED|REJECTED|CANCELLED|BLOCKED|ERROR)/.test(value)) return 'danger'
  if (/(WAITING|PENDING|REVIEW|UNKNOWN)/.test(value)) return 'warning'
  if (/(RUNNING|STARTED|CREATED|UPDATED)/.test(value)) return 'info'
  return 'neutral'
}

function referenceTarget(reference: ActivityItem['references'][number], eventId: string): RouteLocationRaw {
  const query = { ...route.query }
  if (reference.type === 'WORK_ITEM') return { name: 'work', query: { ...query, workItem: reference.id } }
  if (reference.type === 'CONVERSATION') return { name: 'conversation', query: { ...query, conversation: reference.id } }
  return { name: 'activity', query: { ...query, event: eventId } }
}

function referenceLabel(type: string): string {
  return ({ WORK_ITEM: 'WorkItem', CONVERSATION: 'Conversation', TASK: 'Task', REVIEW_REQUEST: 'Review', PLANNED_ACTION: 'Action', ARTIFACT: 'Evidence' } as Record<string, string>)[type] ?? type
}

function displayTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(new Date(value))
}
</script>

<template>
  <section class="activity-stream" :class="{ compact }" aria-labelledby="activity-stream-title">
    <header class="activity-stream__header">
      <div>
        <p>Shared facts</p>
        <h2 id="activity-stream-title">{{ heading }}</h2>
        <span>{{ description }}</span>
      </div>
      <StatusBadge :tone="realtimePhase === 'live' ? 'success' : realtimePhase === 'reconnecting' || realtimePhase === 'connecting' ? 'warning' : 'neutral'" dot>
        <Radio :size="12" aria-hidden="true" />{{ realtimeLabel }}
      </StatusBadge>
    </header>

    <StatePanel v-if="initialLoading" state="loading" :compact="compact" title="正在同步 Activity" description="正在读取权威快照和实时恢复坐标。" />
    <StatePanel v-else-if="forbidden" state="forbidden" :compact="compact" title="无权查看团队活动" description="服务端未授予当前成员这个 Team 的 Activity 读取权限。" />
    <StatePanel v-else-if="cursorExpired" state="error" :compact="compact" title="Activity Cursor 已过期" description="刷新权威快照后将从新的耐久坐标继续补发。" @retry="emit('retry')" />
    <StatePanel v-else-if="offline && items.length === 0" state="offline" :compact="compact" title="离线时没有可用 Activity" description="联网后将读取快照并从耐久 Cursor 恢复。" />
    <StatePanel v-else-if="hardError && items.length === 0" state="error" :compact="compact" :description="error?.message" @retry="emit('retry')" />
    <StatePanel v-else-if="items.length === 0" state="empty" :compact="compact" title="还没有团队活动" description="责任、执行或交付事实产生后会出现在这里。" />

    <template v-else>
      <StatePanel v-if="offline" state="offline" compact title="正在展示最近同步的 Activity" description="恢复网络后会从耐久 Cursor 补齐缺失事件。" />
      <StatePanel v-else-if="realtimePhase === 'reconnecting' || realtimePhase === 'connecting'" state="reconnecting" compact title="正在恢复实时活动" description="历史事实保持可读，新事件会按服务端 Cursor 去重补发。" />
      <StatePanel v-else-if="hardError" state="error" compact :description="error?.message" @retry="emit('retry')" />

      <ol class="activity-list" aria-label="团队活动列表">
        <li v-for="item in items" :key="item.eventId">
          <span class="activity-marker" aria-hidden="true"><CircleDot :size="13" /></span>
          <article>
            <header>
              <div class="activity-kind"><Activity :size="14" aria-hidden="true" /><strong>{{ item.eventType }}</strong><StatusBadge :tone="outcomeTone(item)">{{ outcome(item) }}</StatusBadge></div>
              <time :datetime="item.occurredAt"><Clock3 :size="11" aria-hidden="true" />{{ displayTime(item.occurredAt) }}</time>
            </header>
            <dl>
              <div><dt><UserRound :size="11" aria-hidden="true" />Actor</dt><dd>{{ actor(item) }}</dd></div>
              <div><dt><ArrowRight :size="11" aria-hidden="true" />Subject</dt><dd>{{ subject(item) }}</dd></div>
            </dl>
            <div class="activity-evidence">
              <span><Link2 :size="11" aria-hidden="true" />证据</span>
              <RouterLink v-for="reference in item.references" :key="`${reference.type}:${reference.id}`" :to="referenceTarget(reference, item.eventId)">
                {{ referenceLabel(reference.type) }} · {{ reference.id.slice(0, 8) }}
              </RouterLink>
              <button type="button" @click="emit('select', item)">事件详情</button>
            </div>
          </article>
        </li>
      </ol>

      <footer v-if="nextCursor" class="activity-stream__footer">
        <BaseButton variant="secondary" size="small" :loading="loadingMore" :disabled="!online" @click="emit('loadMore')">
          <RefreshCw :size="13" aria-hidden="true" />加载更早活动
        </BaseButton>
      </footer>
    </template>
  </section>
</template>

<style scoped>
.activity-stream { overflow: hidden; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-lg); background: var(--cs-surface); box-shadow: var(--cs-shadow-sm); }
.activity-stream__header { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; padding: 20px 22px 17px; border-bottom: 1px solid var(--cs-border); background: linear-gradient(135deg, #f7fbf8, #fff); }
.activity-stream__header p { margin: 0; color: var(--cs-brand-700); font-size: 9px; font-weight: 800; letter-spacing: .11em; text-transform: uppercase; }
.activity-stream__header h2 { margin: 3px 0 4px; font: 21px/1.2 var(--cs-font-display); }
.activity-stream__header span { color: var(--cs-text-muted); font-size: 10px; }
.activity-stream__header :deep(.status-badge) { flex: none; }
.activity-list { margin: 0; padding: 8px 20px 15px; list-style: none; }
.activity-list > li { position: relative; display: grid; grid-template-columns: 25px minmax(0, 1fr); gap: 9px; padding-top: 13px; }
.activity-list > li:not(:last-child)::before { position: absolute; top: 29px; bottom: -13px; left: 12px; width: 1px; background: #d8e6dc; content: ''; }
.activity-marker { position: relative; z-index: 1; display: grid; width: 25px; height: 25px; place-items: center; border: 1px solid #c8ddce; border-radius: 50%; background: #eff8f1; color: var(--cs-brand-700); }
.activity-list article { min-width: 0; padding: 13px 14px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: #fff; }
.activity-list article > header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.activity-kind { display: flex; min-width: 0; align-items: center; gap: 7px; }
.activity-kind > svg { flex: none; color: var(--cs-brand-600); }
.activity-kind strong { overflow: hidden; font: 700 11px var(--cs-font-mono); text-overflow: ellipsis; white-space: nowrap; }
.activity-list time { display: inline-flex; flex: none; align-items: center; gap: 4px; color: var(--cs-text-muted); font: 9px var(--cs-font-mono); }
.activity-list dl { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 7px; margin: 11px 0 0; }
.activity-list dl > div { min-width: 0; padding: 7px 8px; border-radius: 7px; background: var(--cs-surface-subtle); }
.activity-list dt { display: flex; align-items: center; gap: 4px; color: var(--cs-text-muted); font-size: 8px; font-weight: 750; text-transform: uppercase; }
.activity-list dd { overflow: hidden; margin: 3px 0 0; font: 9px var(--cs-font-mono); text-overflow: ellipsis; white-space: nowrap; }
.activity-evidence { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 10px; }
.activity-evidence > span { display: inline-flex; align-items: center; gap: 4px; color: var(--cs-text-muted); font-size: 8px; font-weight: 750; text-transform: uppercase; }
.activity-evidence a, .activity-evidence button { min-height: 27px; padding: 5px 8px; border: 1px solid #d4e4d8; border-radius: 7px; background: #f7fbf8; color: var(--cs-brand-700); font: 8px var(--cs-font-mono); cursor: pointer; }
.activity-evidence a:hover, .activity-evidence a:focus-visible, .activity-evidence button:hover, .activity-evidence button:focus-visible { border-color: var(--cs-brand-600); outline: 2px solid rgb(49 128 78 / 14%); outline-offset: 1px; }
.activity-stream__footer { display: flex; justify-content: center; padding: 12px 20px 18px; border-top: 1px solid var(--cs-border); }
.activity-stream.compact { border: 0; border-radius: 0; box-shadow: none; }
.activity-stream.compact .activity-stream__header { padding: 0 0 12px; background: transparent; }
.activity-stream.compact .activity-stream__header h2 { font: 700 13px var(--cs-font-sans); }
.activity-stream.compact .activity-list { padding: 5px 0 0; }
.activity-stream.compact .activity-list article { padding: 10px; }
.activity-stream.compact .activity-list dl { grid-template-columns: 1fr; }
@media (max-width: 640px) {
  .activity-stream__header { padding: 16px; }
  .activity-stream__header h2 { font-size: 18px; }
  .activity-stream__header span { display: block; max-width: 230px; }
  .activity-list { padding: 5px 10px 13px; }
  .activity-list > li { grid-template-columns: 20px minmax(0, 1fr); gap: 6px; }
  .activity-marker { width: 20px; height: 20px; }
  .activity-list > li:not(:last-child)::before { top: 23px; left: 9px; }
  .activity-list article > header { align-items: flex-start; }
  .activity-kind { flex-wrap: wrap; }
  .activity-list dl { grid-template-columns: 1fr; }
  .activity-evidence a, .activity-evidence button { min-height: 34px; }
}
</style>
