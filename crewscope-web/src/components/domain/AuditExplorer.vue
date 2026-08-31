<script setup lang="ts">
import { ArrowUpRight, Download, Filter, Link2, RefreshCw, ShieldCheck, X } from '@lucide/vue'
import { computed, nextTick, reactive, ref, watch } from 'vue'
import type { TeamOpsCorrelationResource, TeamOpsPhase } from '../../domains/teamops/store'
import {
  auditEventCategories,
  auditOutcomes,
  type AuditEvent,
  type AuditEventCategory,
  type AuditOutcome,
} from '../../domains/teamops/types'
import type { TeamOpsErrorState } from '../../domains/teamops/errors'
import { principalDisplayName, type PrincipalNameDirectory } from '../../domains/scope/memberDirectory'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'

interface AuditFilterForm {
  from: string
  to: string
  category: string
  outcome: string
  initiator: string
  actor: string
  agent: string
  subjectType: string
  subjectId: string
  providerBinding: string
  correlation: string
}

const props = defineProps<{
  phase: TeamOpsPhase
  items: AuditEvent[]
  error: TeamOpsErrorState | null
  nextCursor: string | null
  loadingMore: boolean
  selectedEvent: AuditEvent | null
  correlation: Readonly<TeamOpsCorrelationResource> | null
  correlationId: string
  initialFilter: AuditFilterForm
  online: boolean
  canExport: boolean
  exportPhase: TeamOpsPhase
  exportError: TeamOpsErrorState | null
  principalNames?: PrincipalNameDirectory
}>()

const emit = defineEmits<{
  applyFilter: [filter: AuditFilterForm]
  retry: []
  loadMore: []
  select: [eventId: string]
  closeDetail: []
  openCorrelation: [correlationId: string]
  closeCorrelation: []
  retryCorrelation: [correlationId: string]
  loadMoreCorrelation: [correlationId: string]
  navigate: [href: string]
  export: [maximumRows: number]
}>()

const form = reactive<AuditFilterForm>({ ...props.initialFilter })
const advanced = ref(Object.values(props.initialFilter).slice(4).some(Boolean))
const validation = ref('')
const maximumRows = ref(1000)
const detailHeading = ref<HTMLElement | null>(null)
const initialLoading = computed(() => (props.phase === 'idle' || props.phase === 'loading') && props.items.length === 0)
const forbidden = computed(() => props.error?.kind === 'forbidden')
const offline = computed(() => !props.online || props.error?.kind === 'offline')
const cursorExpired = computed(() => props.error?.kind === 'cursor-expired')
const hardError = computed(() => props.phase === 'error' && !forbidden.value && !offline.value && !cursorExpired.value)
const hasExplicitRange = computed(() => Boolean(form.from && form.to && new Date(form.from) < new Date(form.to)))
const rangeWithinLimit = computed(() => hasExplicitRange.value && new Date(form.to).getTime() - new Date(form.from).getTime() <= 31 * 24 * 60 * 60 * 1000)
const exportDisabledReason = computed(() => {
  if (!props.canExport) return '当前身份没有治理导出权限'
  if (!props.online) return '离线时不能导出'
  if (!hasExplicitRange.value) return '导出需要明确的开始和结束时间'
  if (!rangeWithinLimit.value) return '导出时间范围不能超过 31 天'
  return ''
})

function actorName(actorId: string | null, actorType: string): string {
  if (!actorId) return actorType === 'SYSTEM' ? '系统' : actorType
  return principalDisplayName(props.principalNames ?? {}, actorId, actorType)
}

watch(() => props.initialFilter, value => Object.assign(form, value), { deep: true })
watch(() => props.selectedEvent, async value => {
  if (!value) return
  await nextTick()
  // Move focus after the activating key event completes so keyboard users enter the new detail region.
  if (typeof requestAnimationFrame === 'function') requestAnimationFrame(() => detailHeading.value?.focus())
  else detailHeading.value?.focus()
})

function submit(): void {
  validation.value = validate(form)
  if (!validation.value) emit('applyFilter', { ...form })
}

function reset(): void {
  Object.assign(form, {
    from: '', to: '', category: '', outcome: '', initiator: '', actor: '', agent: '',
    subjectType: '', subjectId: '', providerBinding: '', correlation: '',
  })
  validation.value = ''
  emit('applyFilter', { ...form })
}

function validate(value: AuditFilterForm): string {
  if (value.from && value.to && new Date(value.from) >= new Date(value.to)) return '开始时间必须早于结束时间。'
  if (Boolean(value.subjectType) !== Boolean(value.subjectId)) return 'Subject Type 与 Subject ID 必须同时填写。'
  const identifiers = [value.initiator, value.actor, value.agent, value.subjectId, value.providerBinding, value.correlation].filter(Boolean)
  if (identifiers.some(item => !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(item))) {
    return '身份、对象、Provider 与 Correlation 筛选必须使用有效 UUID。'
  }
  return ''
}

function shortId(value: string | null): string { return value ? value.slice(0, 8) : '—' }
function displayTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}
function outcomeTone(value: AuditOutcome | string | null): 'success' | 'danger' | 'warning' | 'neutral' {
  if (value === 'SUCCEEDED') return 'success'
  if (value === 'FAILED') return 'danger'
  if (value === 'DENIED') return 'warning'
  return 'neutral'
}
function categoryLabel(value: AuditEventCategory): string { return value.replace('_', ' ') }
</script>

<template>
  <section class="audit-explorer" aria-labelledby="audit-title">
    <section class="audit-hero panel">
      <span><ShieldCheck :size="23" aria-hidden="true" /></span>
      <div><p>Team governance</p><h2 id="audit-title">团队审计中心</h2><small>按公开审计事实追踪人员、Agent、对象与 Provider 操作。</small></div>
      <StatusBadge tone="neutral">{{ items.length }} 条已加载</StatusBadge>
    </section>

    <form class="audit-filter panel" aria-label="审计筛选" @submit.prevent="submit">
      <div class="audit-filter__primary">
        <label>开始时间<input v-model="form.from" type="datetime-local"></label>
        <label>结束时间<input v-model="form.to" type="datetime-local"></label>
        <label>Category<select v-model="form.category"><option value="">全部类别</option><option v-for="item in auditEventCategories" :key="item" :value="item">{{ item }}</option></select></label>
        <label>Outcome<select v-model="form.outcome"><option value="">全部结果</option><option v-for="item in auditOutcomes" :key="item" :value="item">{{ item }}</option></select></label>
      </div>
      <button class="advanced-toggle" type="button" :aria-expanded="advanced" @click="advanced = !advanced"><Filter :size="13" />{{ advanced ? '收起高级筛选' : '展开高级筛选' }}</button>
      <div v-if="advanced" class="audit-filter__advanced">
        <label>Initiator UUID<input v-model.trim="form.initiator" autocomplete="off"></label>
        <label>Actor UUID<input v-model.trim="form.actor" autocomplete="off"></label>
        <label>Agent Principal UUID<input v-model.trim="form.agent" autocomplete="off"></label>
        <label>Subject Type<input v-model.trim="form.subjectType" autocomplete="off" placeholder="例如 WORK_ITEM"></label>
        <label>Subject UUID<input v-model.trim="form.subjectId" autocomplete="off"></label>
        <label>Provider Binding UUID<input v-model.trim="form.providerBinding" autocomplete="off"></label>
        <label>Correlation UUID<input v-model.trim="form.correlation" autocomplete="off"></label>
      </div>
      <p v-if="validation" class="filter-error" role="alert">{{ validation }}</p>
      <footer>
        <div class="export-control">
          <label>导出上限<input v-model.number="maximumRows" type="number" min="1" max="10000" step="1"></label>
          <BaseButton variant="secondary" size="small" :loading="exportPhase === 'loading'" :disabled="Boolean(exportDisabledReason) || maximumRows < 1 || maximumRows > 10000" @click="emit('export', maximumRows)"><Download :size="13" />导出 JSON</BaseButton>
          <span v-if="exportDisabledReason">{{ exportDisabledReason }}</span>
          <span v-else-if="exportPhase === 'ready'" class="success" role="status">导出已生成并下载</span>
          <span v-else-if="exportPhase === 'error'" class="failure" role="alert">{{ exportError?.kind === 'forbidden' ? '服务端拒绝导出权限' : exportError?.message }}</span>
        </div>
        <div><BaseButton variant="ghost" size="small" @click="reset">重置</BaseButton><BaseButton size="small" type="submit"><Filter :size="13" />应用筛选</BaseButton></div>
      </footer>
    </form>

    <div class="audit-content" :class="{ 'has-aside': selectedEvent || correlation }">
      <section class="audit-list panel" aria-label="审计事件列表">
        <StatePanel v-if="initialLoading" state="loading" title="正在加载审计事实" />
        <StatePanel v-else-if="forbidden" state="forbidden" title="无权查看团队审计" description="当前成员没有这个 Team 的 Audit 读取权限。" />
        <StatePanel v-else-if="offline && items.length === 0" state="offline" title="离线时没有可用审计事实" />
        <StatePanel v-else-if="hardError && items.length === 0" state="error" :description="error?.message" @retry="emit('retry')" />
        <StatePanel v-else-if="items.length === 0" state="empty" title="当前筛选没有审计事实" description="调整时间或组合筛选后重新查询。" />
        <template v-else>
          <StatePanel v-if="offline" compact state="offline" title="正在展示最近同步的审计事实" description="离线期间筛选续页与导出保持关闭。" />
          <StatePanel v-else-if="cursorExpired" compact state="error" title="审计续页 Cursor 已过期" description="已加载事实保持可读，刷新首屏后可继续。" @retry="emit('retry')" />
          <StatePanel v-else-if="hardError" compact state="error" :description="error?.message" @retry="emit('retry')" />
          <table>
            <thead><tr><th>Category / Outcome</th><th>事件</th><th>Actor</th><th>Subject</th><th>发生时间</th><th><span class="sr-only">操作</span></th></tr></thead>
            <tbody><tr v-for="item in items" :key="item.eventId" :class="{ selected: selectedEvent?.eventId === item.eventId }">
              <td data-label="Category / Outcome"><strong>{{ categoryLabel(item.category) }}</strong><StatusBadge :tone="outcomeTone(item.outcome)">{{ item.outcome }}</StatusBadge></td>
              <td data-label="事件"><span>{{ item.eventType }}</span><small class="mono">{{ shortId(item.eventId) }}</small></td>
              <td data-label="Actor"><span>{{ actorName(item.identity.actorId, item.identity.actorType) }}</span><small class="mono">{{ shortId(item.identity.actorId) }}</small></td>
              <td data-label="Subject"><span>{{ item.subject.type }}</span><small class="mono">{{ shortId(item.subject.id) }}</small></td>
              <td data-label="发生时间"><time :datetime="item.occurredAt">{{ displayTime(item.occurredAt) }}</time></td>
              <td data-label="操作"><button type="button" @click="emit('select', item.eventId)">查看详情</button></td>
            </tr></tbody>
          </table>
          <footer v-if="nextCursor"><BaseButton variant="secondary" size="small" :loading="loadingMore" :disabled="!online" @click="emit('loadMore')"><RefreshCw :size="13" />加载更多</BaseButton></footer>
        </template>
      </section>

      <aside v-if="selectedEvent" class="audit-detail panel" aria-label="审计事件详情">
        <header><div><p>Public audit fact</p><h2 ref="detailHeading" tabindex="-1">审计详情</h2></div><button type="button" aria-label="关闭审计详情" @click="emit('closeDetail')"><X :size="17" /></button></header>
        <section class="audit-detail__hero"><StatusBadge :tone="outcomeTone(selectedEvent.outcome)">{{ selectedEvent.outcome }}</StatusBadge><StatusBadge tone="neutral">{{ selectedEvent.retentionLevel }}</StatusBadge><h3>{{ selectedEvent.eventType }}</h3><span>{{ selectedEvent.category }} · Schema v{{ selectedEvent.sourceSchemaVersion }}</span></section>
        <dl>
          <div><dt>Event ID</dt><dd class="mono">{{ selectedEvent.eventId }}</dd></div>
          <div><dt>Initiator</dt><dd>{{ selectedEvent.identity.initiatorId ? actorName(selectedEvent.identity.initiatorId, 'INITIATOR') : '—' }}<small v-if="selectedEvent.identity.initiatorId" class="mono">{{ selectedEvent.identity.initiatorId }}</small></dd></div>
          <div><dt>Actor</dt><dd>{{ actorName(selectedEvent.identity.actorId, selectedEvent.identity.actorType) }}<small class="mono">{{ selectedEvent.identity.actorId ?? '—' }}</small></dd></div>
          <div><dt>Agent Principal</dt><dd class="mono">{{ selectedEvent.identity.agentPrincipalId ?? '—' }}</dd></div>
          <div><dt>Subject</dt><dd>{{ selectedEvent.subject.type }}<small class="mono">{{ selectedEvent.subject.id }}</small></dd></div>
          <div><dt>发生时间</dt><dd>{{ displayTime(selectedEvent.occurredAt) }}</dd></div>
        </dl>
        <section v-if="selectedEvent.provider" class="safe-reference"><ShieldCheck :size="15" /><div><strong>Provider 安全引用</strong><span class="mono">Binding {{ selectedEvent.provider.providerBindingId }}</span><span class="mono">Connection {{ selectedEvent.provider.connectionId }}</span><span v-if="selectedEvent.provider.externalOperationHash" class="mono">Operation {{ selectedEvent.provider.externalOperationHash }}</span></div></section>
        <section class="audit-summary"><h3>公开摘要</h3><p v-if="Object.keys(selectedEvent.summary).length === 0">此事件没有公开摘要字段。</p><dl v-else><div v-for="(value, key) in selectedEvent.summary" :key="key"><dt>{{ key }}</dt><dd>{{ value }}</dd></div></dl></section>
        <section class="correlation-callout"><Link2 :size="15" /><div><strong>Correlation</strong><span class="mono">{{ selectedEvent.correlation.correlationId }}</span></div><BaseButton variant="secondary" size="small" @click="emit('openCorrelation', selectedEvent.correlation.correlationId)">查看关联链</BaseButton></section>
      </aside>

      <aside v-else-if="correlation" class="correlation-panel panel" aria-label="Correlation 关联链">
        <header><div><p>Cross-object trace</p><h2>Correlation 链</h2></div><button type="button" aria-label="关闭 Correlation 链" @click="emit('closeCorrelation')"><X :size="17" /></button></header>
        <StatePanel v-if="correlation.phase === 'idle' || (correlation.phase === 'loading' && !correlation.value)" compact state="loading" />
        <StatePanel v-else-if="correlation.error?.kind === 'forbidden' && !correlation.value" compact state="forbidden" />
        <StatePanel v-else-if="correlation.phase === 'error' && !correlation.value" compact state="error" :description="correlation.error?.message" @retry="emit('retryCorrelation', correlationId)" />
        <StatePanel v-else-if="correlation.phase === 'empty'" compact state="empty" title="关联链暂无公开事实" />
        <template v-else-if="correlation.value">
          <StatePanel v-if="correlation.error?.kind === 'cursor-expired'" compact state="error" title="关联链 Cursor 已过期" description="已加载节点保持可读，重新打开关联链可恢复。" @retry="emit('retryCorrelation', correlation.value.correlationId)" />
          <p class="correlation-id mono">{{ correlation.value.correlationId }}</p>
          <ol><li v-for="item in correlation.value.events" :key="item.eventId"><span>{{ item.source }}</span><strong>{{ item.eventType }}</strong><small>{{ item.actorType }} · {{ displayTime(item.occurredAt) }}</small><StatusBadge :tone="outcomeTone(item.outcome)">{{ item.outcome ?? 'RECORDED' }}</StatusBadge></li></ol>
          <section class="correlation-objects"><h3>关联对象</h3><button v-for="item in correlation.value.objects" :key="`${item.type}:${item.id}`" type="button" @click="emit('navigate', item.href)"><span><strong>{{ item.type }}</strong><small class="mono">{{ item.id }}</small></span><ArrowUpRight :size="14" /></button></section>
          <footer v-if="correlation.nextCursor"><BaseButton variant="secondary" size="small" :loading="correlation.loadingMore" :disabled="!online" @click="emit('loadMoreCorrelation', correlation.value.correlationId)"><RefreshCw :size="13" />加载更多节点</BaseButton></footer>
        </template>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.audit-explorer { display: grid; max-width: 1280px; gap: 13px; margin: 0 auto; }.audit-hero { display: grid; grid-template-columns: 44px minmax(0,1fr) auto; align-items: center; gap: 13px; padding: 16px 18px; }.audit-hero > span { display: grid; width: 44px; height: 44px; place-items: center; border-radius: 13px; background: var(--cs-brand-100); color: var(--cs-brand-700); }.audit-hero p,.audit-detail header p,.correlation-panel header p { margin: 0; color: var(--cs-brand-700); font-size: 8px; font-weight: 800; letter-spacing: .1em; text-transform: uppercase; }.audit-hero h2 { margin: 2px 0; font-size: 17px; }.audit-hero small { color: var(--cs-text-muted); font-size: 9px; }
.audit-filter { padding: 13px; }.audit-filter label { display: grid; gap: 4px; color: var(--cs-text-muted); font-size: 8px; font-weight: 750; }.audit-filter input,.audit-filter select { min-width: 0; min-height: 35px; padding: 0 9px; border: 1px solid var(--cs-border); border-radius: 8px; background: #fff; color: var(--cs-text); font-size: 10px; }.audit-filter__primary { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap: 9px; }.advanced-toggle { display: inline-flex; min-height: 30px; align-items: center; gap: 5px; margin-top: 7px; color: var(--cs-brand-700); font-size: 9px; font-weight: 750; cursor: pointer; }.audit-filter__advanced { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap: 9px; padding-top: 8px; border-top: 1px solid var(--cs-border); }.filter-error { margin: 8px 0 0; color: var(--cs-danger); font-size: 9px; }.audit-filter > footer { display: flex; align-items: flex-end; justify-content: space-between; gap: 12px; margin-top: 10px; padding-top: 10px; border-top: 1px solid var(--cs-border); }.audit-filter > footer > div,.export-control { display: flex; align-items: flex-end; gap: 7px; }.export-control label { width: 100px; }.export-control > span { align-self: center; max-width: 180px; color: var(--cs-text-muted); font-size: 8px; }.export-control .success { color: var(--cs-success); }.export-control .failure { color: var(--cs-danger); }
.audit-content { display: grid; grid-template-columns: minmax(0,1fr); align-items: start; gap: 13px; }.audit-content.has-aside { grid-template-columns: minmax(0,1fr) 370px; }.audit-list { overflow: hidden; }.audit-list table { width: 100%; border-collapse: collapse; }.audit-list th { padding: 9px 12px; border-bottom: 1px solid var(--cs-border); background: var(--cs-surface-subtle); color: var(--cs-text-muted); font-size: 8px; text-align: left; text-transform: uppercase; }.audit-list td { padding: 11px 12px; border-bottom: 1px solid var(--cs-border); vertical-align: middle; font-size: 9px; }.audit-list tr.selected { background: #f2faf4; }.audit-list td > strong,.audit-list td > span,.audit-list td > small { display: block; }.audit-list td > strong,.audit-list td > span { margin-bottom: 3px; font-size: 9px; }.audit-list td > small { color: var(--cs-text-muted); font-size: 7px; }.audit-list td:first-child :deep(.status-badge) { margin-top: 3px; }.audit-list td button { min-height: 30px; padding: 0 7px; border-radius: 7px; color: var(--cs-brand-700); font-size: 8px; font-weight: 750; cursor: pointer; }.audit-list td button:hover,.audit-list td button:focus-visible { background: var(--cs-brand-100); }.audit-list > footer,.correlation-panel > footer { display: flex; justify-content: center; padding: 12px; }
.audit-detail,.correlation-panel { position: sticky; top: 12px; overflow: hidden; }.audit-detail > header,.correlation-panel > header { display: flex; align-items: center; justify-content: space-between; padding: 13px 14px; border-bottom: 1px solid var(--cs-border); }.audit-detail header h2,.correlation-panel header h2 { margin: 2px 0 0; font-size: 15px; }.audit-detail header button,.correlation-panel header button { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 8px; background: var(--cs-surface-subtle); cursor: pointer; }.audit-detail__hero { padding: 14px; border-bottom: 1px solid var(--cs-border); }.audit-detail__hero :deep(.status-badge) { margin-right: 5px; }.audit-detail__hero h3 { margin: 9px 0 2px; font-size: 14px; }.audit-detail__hero > span { color: var(--cs-text-muted); font-size: 8px; }.audit-detail > dl { display: grid; grid-template-columns: 1fr 1fr; gap: 0 11px; margin: 0; padding: 7px 14px; }.audit-detail > dl > div { padding: 7px 0; border-bottom: 1px solid var(--cs-border); }.audit-detail dt,.audit-summary dt { color: var(--cs-text-muted); font-size: 7px; font-weight: 750; text-transform: uppercase; }.audit-detail dd,.audit-summary dd { overflow-wrap: anywhere; margin: 2px 0 0; font-size: 8px; }.audit-detail dd small { display: block; margin-top: 2px; color: var(--cs-text-muted); font-size: 7px; }.safe-reference,.correlation-callout { display: grid; grid-template-columns: 18px minmax(0,1fr) auto; gap: 7px; align-items: start; padding: 12px 14px; border-top: 1px solid var(--cs-border); }.safe-reference > svg,.correlation-callout > svg { color: var(--cs-brand-700); }.safe-reference strong,.safe-reference span,.correlation-callout strong,.correlation-callout span { display: block; }.safe-reference strong,.correlation-callout strong { font-size: 9px; }.safe-reference span,.correlation-callout span { overflow-wrap: anywhere; margin-top: 3px; color: var(--cs-text-muted); font-size: 7px; }.audit-summary { padding: 12px 14px; border-top: 1px solid var(--cs-border); }.audit-summary h3,.correlation-objects h3 { margin: 0 0 8px; font-size: 10px; }.audit-summary > p { margin: 0; color: var(--cs-text-muted); font-size: 8px; }.audit-summary dl { display: grid; gap: 7px; margin: 0; }
.correlation-id { margin: 0; padding: 10px 14px; border-bottom: 1px solid var(--cs-border); color: var(--cs-text-muted); font-size: 8px; }.correlation-panel > ol { display: grid; gap: 0; margin: 0; padding: 0 14px; list-style: none; }.correlation-panel > ol li { display: grid; grid-template-columns: 1fr auto; gap: 3px 8px; padding: 10px 0; border-bottom: 1px solid var(--cs-border); }.correlation-panel > ol li > span { color: var(--cs-brand-700); font-size: 7px; font-weight: 750; }.correlation-panel > ol li > strong { grid-column: 1; font-size: 9px; }.correlation-panel > ol li > small { grid-column: 1; color: var(--cs-text-muted); font-size: 7px; }.correlation-panel > ol li :deep(.status-badge) { grid-column: 2; grid-row: 1 / 4; align-self: center; }.correlation-objects { padding: 12px 14px; background: var(--cs-surface-subtle); }.correlation-objects button { display: flex; width: 100%; min-height: 43px; align-items: center; justify-content: space-between; gap: 8px; padding: 7px 9px; border-top: 1px solid var(--cs-border); text-align: left; cursor: pointer; }.correlation-objects button:hover,.correlation-objects button:focus-visible { background: var(--cs-brand-100); }.correlation-objects strong,.correlation-objects small { display: block; }.correlation-objects strong { font-size: 8px; }.correlation-objects small { margin-top: 2px; color: var(--cs-text-muted); font-size: 7px; }
@media (max-width: 1020px) { .audit-filter__advanced { grid-template-columns: repeat(3,minmax(0,1fr)); }.audit-content.has-aside { grid-template-columns: minmax(0,1fr) 330px; } }
@media (max-width: 780px) { .audit-filter__primary,.audit-filter__advanced { grid-template-columns: 1fr 1fr; }.audit-filter > footer { align-items: stretch; flex-direction: column; }.audit-filter > footer > div { justify-content: flex-end; }.export-control { flex-wrap: wrap; }.audit-content.has-aside { grid-template-columns: 1fr; }.audit-detail,.correlation-panel { position: static; grid-row: 1; }.audit-list thead { position: absolute; overflow: hidden; width: 1px; height: 1px; clip: rect(0 0 0 0); }.audit-list tbody,.audit-list tr,.audit-list td { display: block; }.audit-list tr { padding: 9px 12px; border-bottom: 1px solid var(--cs-border); }.audit-list td { display: grid; grid-template-columns: 105px minmax(0,1fr); padding: 5px 0; border: 0; }.audit-list td::before { grid-row: 1 / 4; content: attr(data-label); color: var(--cs-text-muted); font-size: 7px; font-weight: 750; text-transform: uppercase; }.audit-list td button { justify-self: start; padding-left: 0; }.audit-list td button:hover { padding-left: 7px; } }
@media (max-width: 480px) { .audit-hero { grid-template-columns: 38px 1fr; padding: 13px; }.audit-hero > span { width: 38px; height: 38px; }.audit-hero > :last-child { grid-column: 1 / -1; justify-self: start; }.audit-filter__primary,.audit-filter__advanced { grid-template-columns: 1fr; }.audit-filter input,.audit-filter select { min-height: 42px; }.audit-filter > footer > div { display: grid; justify-content: stretch; }.export-control label { width: auto; }.audit-detail > dl { grid-template-columns: 1fr; }.safe-reference,.correlation-callout { grid-template-columns: 18px minmax(0,1fr); }.safe-reference > button,.correlation-callout > button { grid-column: 1 / -1; width: 100%; } }
</style>
