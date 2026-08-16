<script setup lang="ts">
import { Activity, CircleAlert, History, RefreshCw, RotateCcw } from '@lucide/vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { TaskLiveState, TaskPhase } from '../../domains/task/store'
import { latestTaskProgress, taskTimeline } from '../../domains/task/timeline'
import type { TaskEventPage, TaskExecutionStatus } from '../../domains/task/types'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'

const props = defineProps<{
  phase: TaskPhase
  page: TaskEventPage | null
  errorMessage: string | null
  live: TaskLiveState | null
  executionId: string | null
  executionStatus: TaskExecutionStatus | null
  continuityGap: boolean
  onLoadMore: () => void
  onRetry: () => void
}>()

const entries = computed(() => taskTimeline(props.page?.items ?? [], props.executionId))
const visibleEntries = computed(() => [...entries.value].reverse().slice(0, 40))
const progress = computed(() => latestTaskProgress(entries.value))
const recovering = computed(() => props.executionStatus === 'RECOVERING'
  || props.continuityGap
  || Boolean(props.live?.projectionGap))
const liveStatePanel = computed(() => {
  if (recovering.value) return {
    state: 'recovering' as const,
    title: '执行正在恢复',
    description: props.live?.projectionGap || props.continuityGap
      ? '正在从耐久事实补齐连续性缺口。'
      : 'Runtime 正在接管最近检查点。',
  }
  if (props.live?.phase === 'connecting' || props.live?.phase === 'reconnecting') return {
    state: 'reconnecting' as const,
    title: props.live.phase === 'connecting' ? '正在连接实时事实' : '正在重新连接',
    description: props.live.errorMessage ?? 'Timeline 保留已加载事实，并从最新 Cursor 继续追平。',
  }
  if (props.live?.phase === 'error') return {
    state: 'error' as const,
    title: '实时连接不可用',
    description: props.live.errorMessage ?? '耐久历史仍可查看，刷新后会重新建立连接。',
  }
  return null
})
const liveLabel = computed(() => {
  if (!props.live) return '历史'
  if (props.live.phase === 'connected') return '实时'
  if (props.live.phase === 'connecting') return '连接中'
  if (props.live.phase === 'reconnecting') return '重新连接'
  return '实时不可用'
})
const liveTone = computed(() => {
  if (props.live?.phase === 'connected') return 'success' as const
  if (props.live?.phase === 'error') return 'danger' as const
  if (props.live) return 'warning' as const
  return 'neutral' as const
})
const announcement = ref('')
let announcementTimer: ReturnType<typeof setTimeout> | null = null
let announcedEntryId: string | null = null

watch(
  () => entries.value.at(-1) ?? null,
  entry => {
    if (!entry || announcedEntryId === null) {
      announcedEntryId = entry?.id ?? null
      return
    }
    if (entry.id === announcedEntryId) return
    announcedEntryId = entry.id
    if (announcementTimer) clearTimeout(announcementTimer)
    // A short polite delay coalesces bursty Agent progress without making screen readers recite
    // every durable event. Heartbeats and text deltas were removed by the Timeline mapper.
    announcementTimer = setTimeout(() => {
      announcement.value = [entry.title, entry.summary].filter(Boolean).join('：')
      announcementTimer = null
    }, 900)
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  if (announcementTimer) clearTimeout(announcementTimer)
})

function displayTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
    .format(new Date(value))
}
</script>

<template>
  <section class="detail-card timeline-card" aria-labelledby="task-timeline-title">
    <p class="sr-only" role="status" aria-live="polite" aria-atomic="true">{{ announcement }}</p>
    <div class="timeline-heading">
      <div><p>Durable activity</p><h3 id="task-timeline-title">Timeline <span>{{ entries.length }}</span></h3></div>
      <StatusBadge :tone="liveTone" dot>{{ liveLabel }}</StatusBadge>
    </div>

    <StatePanel
      v-if="liveStatePanel"
      class="timeline-state"
      compact
      :state="liveStatePanel.state"
      :title="liveStatePanel.title"
      :description="liveStatePanel.description"
      @retry="onRetry"
    />

    <div v-if="progress" class="progress-card">
      <div>
        <span>{{ progress.source === 'AGENT_RUN' ? 'AgentRun Progress' : 'Worker Progress' }}</span>
        <strong>{{ progress.percent === null ? '进行中' : `${progress.percent}%` }}</strong>
      </div>
      <div v-if="progress.percent !== null" class="progress-track" role="progressbar" aria-label="Task 执行进度" aria-valuemin="0" aria-valuemax="100" :aria-valuenow="progress.percent">
        <i class="progress-fill" :style="{ width: `${progress.percent}%` }" />
      </div>
      <p v-if="progress.summary">{{ progress.summary }}</p>
    </div>

    <StatePanel v-if="(phase === 'loading' || phase === 'idle') && !page" state="loading" title="正在追平 Task 历史" />
    <StatePanel v-else-if="phase === 'error' && !page" state="error" :description="errorMessage ?? undefined" @retry="onRetry" />
    <template v-else>
      <div v-if="visibleEntries.length" class="timeline-list" role="list" aria-label="Task durable timeline">
        <article v-for="entry in visibleEntries" :key="entry.id" role="listitem" :class="{ recovery: entry.recovery }">
          <i><History v-if="!entry.recovery" :size="13" /><RotateCcw v-else :size="13" /></i>
          <div><strong>{{ entry.title }}</strong><p v-if="entry.summary">{{ entry.summary }}</p><span>{{ displayTime(entry.occurredAt) }}<template v-if="entry.meta"> · {{ entry.meta }}</template></span></div>
          <StatusBadge :tone="entry.tone">{{ entry.progressPercent === null ? '事实' : `${entry.progressPercent}%` }}</StatusBadge>
        </article>
      </div>
      <p v-else class="empty-note"><Activity :size="14" />当前 attempt 还没有可展示的执行事件。</p>
      <div v-if="page?.hasMore" class="timeline-more"><BaseButton variant="secondary" size="small" @click="onLoadMore">继续追平历史</BaseButton></div>
      <p v-if="errorMessage" class="timeline-error"><CircleAlert :size="12" />{{ errorMessage }} <button type="button" @click="onRetry"><RefreshCw :size="11" />刷新</button></p>
    </template>
  </section>
</template>

<style scoped>
.timeline-card { padding: 16px; }.timeline-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 12px; }.timeline-heading p { margin: 0 0 2px; color: var(--cs-brand-600); font-size: 8px; font-weight: 750; letter-spacing: .08em; text-transform: uppercase; }.timeline-heading h3 { margin: 0; font-size: 12px; }.timeline-heading h3 span { color: var(--cs-text-muted); font-weight: 500; }.timeline-state { margin-bottom: 9px; }.progress-card { margin-bottom: 11px; padding: 11px; border: 1px solid var(--cs-brand-100); border-radius: 10px; background: var(--cs-brand-50); }.progress-card > div:first-child { display: flex; align-items: center; justify-content: space-between; gap: 8px; }.progress-card span { color: var(--cs-brand-700); font-size: 8px; font-weight: 750; }.progress-card strong { color: var(--cs-brand-700); font-size: 13px; }.progress-track { height: 5px; margin-top: 8px; overflow: hidden; border-radius: 999px; background: var(--cs-brand-100); }.progress-fill { display: block; height: 100%; border-radius: inherit; background: var(--cs-brand-500); transition: width .35s ease; }.progress-card p { margin: 7px 0 0; color: var(--cs-text-secondary); font-size: 9px; line-height: 1.45; }.timeline-list { display: grid; }.timeline-list article { position: relative; display: grid; grid-template-columns: 24px minmax(0, 1fr) auto; align-items: start; gap: 8px; padding: 9px 0; border-top: 1px solid var(--cs-border); }.timeline-list article > i { display: grid; width: 24px; height: 24px; place-items: center; border-radius: 50%; background: var(--cs-surface-subtle); color: var(--cs-text-muted); }.timeline-list article.recovery > i { background: var(--cs-warning-soft); color: var(--cs-warning); }.timeline-list strong, .timeline-list p, .timeline-list span { display: block; }.timeline-list strong { font-size: 9px; line-height: 1.4; }.timeline-list p { margin: 3px 0 0; color: var(--cs-text-secondary); font-size: 8px; line-height: 1.45; }.timeline-list span { margin-top: 4px; color: var(--cs-text-muted); font-size: 7px; }.empty-note { display: flex; align-items: center; gap: 6px; margin: 0; color: var(--cs-text-muted); font-size: 9px; }.timeline-more { display: flex; justify-content: center; padding-top: 9px; border-top: 1px solid var(--cs-border); }.timeline-error { display: flex; align-items: center; gap: 4px; margin: 8px 0 0; color: var(--cs-danger); font-size: 8px; }.timeline-error button { display: inline-flex; align-items: center; gap: 3px; color: inherit; text-decoration: underline; cursor: pointer; }.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; clip-path: inset(50%); }
@media (prefers-reduced-motion: reduce) { .progress-fill { transition: none !important; } }
</style>
