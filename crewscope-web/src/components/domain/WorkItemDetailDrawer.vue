<script setup lang="ts">
import {
  ArrowRight,
  Bot,
  CalendarClock,
  Clock3,
  ExternalLink,
  Link2,
  MessageSquare,
  RefreshCw,
  Send,
  ShieldCheck,
  Tag,
  X,
} from '@lucide/vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useTemplateRef, watch } from 'vue'
import type { SemanticTone } from '../base/types'
import type { ResponsibilityCommand, WorkItemDetailCommand, WorkItemPhase } from '../../domains/workitem/store'
import {
  allowedWorkItemTransitions,
  workItemResourceTypes,
  type AddWorkItemCommentInput,
  type LinkWorkItemResourceInput,
  type ResponsibilityAssignment,
  type WorkItemDetails,
  type WorkItemResourceLink,
  type WorkItemResourceType,
  type WorkItemStatus,
  type WorkItemTimelineEvent,
  type WorkItemVersionConflict,
} from '../../domains/workitem/types'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'
import WorkItemResponsibilityPanel, { type ResponsibilityCandidate } from './WorkItemResponsibilityPanel.vue'
import WorkItemTimeline from './WorkItemTimeline.vue'

const props = defineProps<{
  phase: WorkItemPhase
  details: WorkItemDetails | null
  errorMessage: string | null
  commandPending: WorkItemDetailCommand | null
  commandErrorMessage: string | null
  versionConflict: WorkItemVersionConflict | null
  canParticipate: boolean
  canManageResponsibility: boolean
  responsibilityPhase: WorkItemPhase
  responsibilities: ResponsibilityAssignment[]
  responsibilityCandidates: ResponsibilityCandidate[]
  responsibilityErrorMessage: string | null
  responsibilityCommandPending: ResponsibilityCommand | null
  responsibilityCommandErrorMessage: string | null
  timelinePhase: WorkItemPhase
  timeline: WorkItemTimelineEvent[]
  timelineNextCursor: string | null
  timelineLoadingMore: boolean
  timelineErrorMessage: string | null
  onRetry: () => void
  onTransition: (target: WorkItemStatus) => Promise<void>
  onAddComment: (input: AddWorkItemCommentInput) => Promise<void>
  onLinkResource: (input: LinkWorkItemResourceInput) => Promise<void>
  onReplaceOwner: (actorPrincipalId: string) => Promise<void>
  onAssignExecutor: (actorPrincipalId: string) => Promise<void>
  onAssignGateReviewer: (actorPrincipalId: string) => Promise<void>
  onAssignAdvisoryReviewer: (actorPrincipalId: string) => Promise<void>
  onReleaseResponsibility: (assignment: ResponsibilityAssignment) => Promise<void>
  onLoadTimelineMore: () => Promise<void>
}>()

const emit = defineEmits<{ close: []; conversation: [] }>()
const closeButton = useTemplateRef<HTMLButtonElement>('closeButton')
const drawer = useTemplateRef<HTMLElement>('drawer')
const transitionTarget = ref<WorkItemStatus | ''>('')
const comment = ref('')
const resourceType = ref<WorkItemResourceType>('EXTERNAL_URL')
const resourceReference = ref('')
const resourceLabel = ref('')
const commentSubmitted = ref(false)
const resourceSubmitted = ref(false)
const agentPlaceholderNotice = ref(false)
let previousBodyOverflow = ''

const item = computed(() => props.details?.workItem ?? null)
const transitions = computed(() => item.value ? allowedWorkItemTransitions[item.value.status] : [])
const canTransition = computed(() => props.canParticipate && item.value?.source === 'CREWSCOPE' && transitions.value.length > 0)
const canCollaborate = computed(() => props.canParticipate && item.value?.status !== 'ARCHIVED')
const canManageResponsibility = computed(() => props.canManageResponsibility && item.value?.status !== 'ARCHIVED')

watch(
  () => [item.value?.id, item.value?.status] as const,
  () => { transitionTarget.value = transitions.value[0] ?? '' },
  { immediate: true },
)

onMounted(() => {
  document.addEventListener('keydown', closeOnEscape)
  previousBodyOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  void nextTick(() => closeButton.value?.focus())
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', closeOnEscape)
  document.body.style.overflow = previousBodyOverflow
})

function closeOnEscape(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    emit('close')
    return
  }
  if (event.key !== 'Tab' || !drawer.value) return
  const controls = [...drawer.value.querySelectorAll<HTMLElement>('button:not(:disabled), select:not(:disabled), input:not(:disabled), textarea:not(:disabled), a[href]')]
    .filter(element => element.offsetParent !== null)
  const first = controls[0]
  const last = controls.at(-1)
  if (!first || !last) return
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

async function submitTransition(): Promise<void> {
  if (!transitionTarget.value) return
  try {
    await props.onTransition(transitionTarget.value)
  } catch {
    // The Store exposes a sanitized error and preserves the refreshed server version on conflict.
  }
}

async function submitComment(): Promise<void> {
  commentSubmitted.value = true
  const content = comment.value.trim()
  if (!content) return
  try {
    await props.onAddComment({ content })
    comment.value = ''
    commentSubmitted.value = false
  } catch {
    // The Store keeps the draft and exposes a sanitized command error.
  }
}

async function submitResource(): Promise<void> {
  resourceSubmitted.value = true
  const reference = resourceReference.value.trim()
  if (!reference) return
  try {
    await props.onLinkResource({
      resourceType: resourceType.value,
      resourceReference: reference,
      label: resourceLabel.value.trim() || null,
    })
    resourceReference.value = ''
    resourceLabel.value = ''
    resourceSubmitted.value = false
  } catch {
    // The Store keeps the draft and exposes a sanitized command error.
  }
}

function displayDate(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

function statusTone(status: WorkItemStatus): SemanticTone {
  if (status === 'BLOCKED') return 'danger'
  if (status === 'DONE') return 'success'
  if (status === 'IN_PROGRESS') return 'info'
  if (status === 'IN_REVIEW') return 'warning'
  return 'neutral'
}

function resourceHref(resource: WorkItemResourceLink): string | undefined {
  if (resource.resourceType !== 'EXTERNAL_URL') return undefined
  try {
    const url = new URL(resource.resourceReference)
    return ['http:', 'https:'].includes(url.protocol) ? url.toString() : undefined
  } catch {
    return undefined
  }
}

const statusLabels: Record<WorkItemStatus, string> = {
  BACKLOG: '待规划', READY: '待执行', IN_PROGRESS: '进行中', IN_REVIEW: '审查中', BLOCKED: '已阻塞', DONE: '已完成', CANCELLED: '已取消', ARCHIVED: '已归档',
}
</script>

<template>
  <div class="detail-backdrop" @mousedown.self="$emit('close')">
    <aside ref="drawer" class="detail-drawer" role="dialog" aria-modal="true" :aria-label="item ? `${item.key} 工作项详情` : '工作项详情'">
      <header class="detail-header">
        <div><p>WorkItem detail</p><strong>{{ item?.key ?? '加载详情' }}</strong></div>
        <button ref="closeButton" type="button" aria-label="关闭工作项详情" @click="$emit('close')"><X :size="18" /></button>
      </header>

      <StatePanel v-if="phase === 'loading' && !details" state="loading" />
      <StatePanel v-else-if="phase === 'error' && !details" state="error" :description="errorMessage ?? undefined" @retry="onRetry" />

      <div v-else-if="details && item" class="detail-content">
        <section class="detail-hero">
          <div class="detail-hero__status"><StatusBadge :tone="statusTone(item.status)" dot>{{ statusLabels[item.status] }}</StatusBadge><span class="mono">v{{ item.version }}</span></div>
          <h2>{{ item.title }}</h2>
          <p v-if="item.description">{{ item.description }}</p>
          <div class="detail-tags"><StatusBadge tone="info">{{ item.type }}</StatusBadge><StatusBadge :tone="item.priority === 'URGENT' ? 'danger' : item.priority === 'HIGH' ? 'warning' : 'neutral'">{{ item.priority }}</StatusBadge><span v-for="label in item.labels" :key="label"><Tag :size="11" />{{ label }}</span></div>
        </section>

        <section v-if="versionConflict" class="conflict-panel" role="alert">
          <RefreshCw :size="17" /><div><strong>检测到并发更新</strong><span>提交基于 v{{ versionConflict.attemptedVersion }}，服务端当前版本为 {{ versionConflict.currentVersion === null ? '未知' : `v${versionConflict.currentVersion}` }}。详情已刷新。</span></div>
        </section>
        <p v-if="commandErrorMessage" class="command-error" role="alert">{{ commandErrorMessage }}</p>
        <p v-if="phase === 'error' && errorMessage" class="command-error" role="alert">{{ errorMessage }} <button type="button" @click="onRetry">重试</button></p>

        <section class="detail-section transition-section">
          <div class="section-heading"><div><p>Workflow</p><h3>状态流转</h3></div><ShieldCheck :size="17" /></div>
          <div v-if="canTransition" class="transition-control"><select v-model="transitionTarget" aria-label="目标状态"><option v-for="status in transitions" :key="status" :value="status">{{ statusLabels[status] }}</option></select><BaseButton size="small" :loading="commandPending === 'transition'" @click="submitTransition">提交流转<ArrowRight :size="13" /></BaseButton></div>
          <p v-else class="section-note">{{ item.source !== 'CREWSCOPE' ? '外部 Provider 工作项由来源系统管理状态。' : item.status === 'ARCHIVED' ? '已归档工作项没有后续状态。' : '当前账号没有参与工作项的界面权限。' }}</p>
        </section>

        <section class="detail-section facts-section">
          <div class="section-heading"><div><p>Facts</p><h3>工作项信息</h3></div></div>
          <dl><div><dt>来源</dt><dd>{{ item.source }}</dd></div><div><dt>更新时间</dt><dd>{{ displayDate(item.updatedAt) }}</dd></div><div><dt>到期时间</dt><dd><CalendarClock :size="12" />{{ item.dueAt ? displayDate(item.dueAt) : '未设置' }}</dd></div><div><dt>创建者</dt><dd class="mono">{{ item.createdByPrincipalId?.slice(0, 8) ?? 'system' }}</dd></div></dl>
        </section>

        <section class="detail-section responsibility-section">
          <div class="section-heading"><div><p>Accountability</p><h3>团队责任链 <span>{{ responsibilities.length }}</span></h3></div><ShieldCheck :size="17" /></div>
          <WorkItemResponsibilityPanel
            :phase="responsibilityPhase"
            :members="responsibilities"
            :candidates="responsibilityCandidates"
            :error-message="responsibilityErrorMessage"
            :command-pending="responsibilityCommandPending"
            :command-error-message="responsibilityCommandErrorMessage"
            :can-manage="canManageResponsibility"
            :on-retry="onRetry"
            :on-replace-owner="onReplaceOwner"
            :on-assign-executor="onAssignExecutor"
            :on-assign-gate-reviewer="onAssignGateReviewer"
            :on-assign-advisory-reviewer="onAssignAdvisoryReviewer"
            :on-release="onReleaseResponsibility"
          />
        </section>

        <section class="detail-section comments-section">
          <div class="section-heading"><div><p>Discussion</p><h3>评论 <span>{{ details.comments.length }}</span></h3></div><MessageSquare :size="17" /></div>
          <div v-if="details.comments.length" class="comment-list">
            <article v-for="entry in details.comments" :key="entry.id"><i>{{ entry.authorPrincipalId.slice(0, 1).toUpperCase() }}</i><div><header><strong class="mono">{{ entry.authorPrincipalId.slice(0, 8) }}</strong><time>{{ displayDate(entry.createdAt) }}</time></header><p>{{ entry.content }}</p></div></article>
          </div>
          <p v-else class="section-note">还没有评论。</p>
          <form v-if="canCollaborate" class="comment-form" @submit.prevent="submitComment"><label for="work-item-comment">添加评论</label><textarea id="work-item-comment" v-model="comment" rows="3" placeholder="记录决策、进展或需要协作的事项" :aria-invalid="commentSubmitted && !comment.trim()" /><BaseButton type="submit" size="small" :loading="commandPending === 'comment'"><Send :size="13" />发送评论</BaseButton></form>
        </section>

        <section class="detail-section resources-section">
          <div class="section-heading"><div><p>WorkGraph nodes</p><h3>关联资源 <span>{{ details.resourceLinks.length }}</span></h3></div><Link2 :size="17" /></div>
          <div v-if="details.resourceLinks.length" class="resource-list">
            <article v-for="resource in details.resourceLinks" :key="resource.id"><i><Link2 :size="14" /></i><div><strong>{{ resource.label ?? resource.resourceType }}</strong><a v-if="resourceHref(resource)" :href="resourceHref(resource)" target="_blank" rel="noopener noreferrer">{{ resource.resourceReference }}<ExternalLink :size="11" /></a><span v-else class="mono">{{ resource.resourceReference }}</span></div><StatusBadge>{{ resource.resourceType }}</StatusBadge></article>
          </div>
          <p v-else class="section-note">还没有关联资源。</p>
          <form v-if="canCollaborate" class="resource-form" @submit.prevent="submitResource"><label><span>资源类型</span><select v-model="resourceType"><option v-for="kind in workItemResourceTypes" :key="kind" :value="kind">{{ kind }}</option></select></label><label><span>引用</span><input v-model="resourceReference" :placeholder="resourceType === 'EXTERNAL_URL' ? 'https://example.com/resource' : '资源的稳定标识'" :aria-invalid="resourceSubmitted && !resourceReference.trim()"></label><label><span>显示名称</span><input v-model="resourceLabel" placeholder="可选"></label><BaseButton type="submit" size="small" variant="secondary" :loading="commandPending === 'resource'">关联资源</BaseButton></form>
        </section>

        <section class="detail-section timeline-section">
          <div class="section-heading"><div><p>Activity</p><h3>业务时间线 <span>{{ timeline.length }}</span></h3></div><Clock3 :size="17" /></div>
          <WorkItemTimeline
            :phase="timelinePhase"
            :events="timeline"
            :next-cursor="timelineNextCursor"
            :loading-more="timelineLoadingMore"
            :error-message="timelineErrorMessage"
            :on-load-more="onLoadTimelineMore"
          />
        </section>
      </div>

      <footer class="detail-footer">
        <p v-if="agentPlaceholderNotice" role="status">Agent 执行将在后续里程碑接入 TaskExecution；当前不会创建虚假执行。</p>
        <div><BaseButton variant="secondary" @click="$emit('conversation')"><MessageSquare :size="14" />与 Personal Agent 讨论</BaseButton><BaseButton variant="ghost" @click="agentPlaceholderNotice = true"><Bot :size="14" />交给 Agent 处理（规划中）</BaseButton></div>
      </footer>
    </aside>
  </div>
</template>

<style scoped>
.detail-backdrop { position: fixed; inset: 0; z-index: 60; background: rgb(21 35 29 / 22%); backdrop-filter: blur(2px); }.detail-drawer { position: absolute; inset: 0 0 0 auto; display: grid; width: min(560px, 92vw); grid-template-rows: auto minmax(0, 1fr) auto; border-left: 1px solid var(--cs-border-strong); background: var(--cs-canvas); box-shadow: -20px 0 55px rgb(21 35 29 / 13%); }.detail-header { display: flex; min-height: 64px; align-items: center; justify-content: space-between; gap: 16px; padding: 11px 16px 11px 20px; border-bottom: 1px solid var(--cs-border); background: var(--cs-surface); }.detail-header p, .detail-header strong { display: block; margin: 0; }.detail-header p { color: var(--cs-text-muted); font-size: 9px; font-weight: 750; letter-spacing: .08em; text-transform: uppercase; }.detail-header strong { margin-top: 2px; color: var(--cs-brand-700); font: 12px var(--cs-font-mono); }.detail-header button { display: grid; width: 34px; height: 34px; place-items: center; border-radius: 9px; background: var(--cs-surface-subtle); cursor: pointer; }.detail-content { overflow-y: auto; padding: 12px; }.detail-hero, .detail-section, .conflict-panel { border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }.detail-hero { padding: 19px; }.detail-hero__status { display: flex; align-items: center; justify-content: space-between; }.detail-hero__status > .mono { color: var(--cs-text-muted); font-size: 9px; }.detail-hero h2 { margin: 13px 0 7px; font-size: 19px; line-height: 1.3; }.detail-hero > p { margin: 0; color: var(--cs-text-muted); font-size: 11px; line-height: 1.6; white-space: pre-wrap; }.detail-tags { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 14px; }.detail-tags > span { display: inline-flex; align-items: center; gap: 4px; padding: 3px 6px; border-radius: 6px; background: var(--cs-surface-subtle); color: var(--cs-text-muted); font-size: 9px; }.detail-section { margin-top: 10px; padding: 16px; }.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }.section-heading p { margin: 0 0 2px; color: var(--cs-brand-600); font-size: 8px; font-weight: 750; letter-spacing: .08em; text-transform: uppercase; }.section-heading h3 { margin: 0; font-size: 12px; }.section-heading h3 span { color: var(--cs-text-muted); font-weight: 500; }.section-heading > svg { color: var(--cs-text-muted); }.transition-control { display: grid; grid-template-columns: 1fr auto; gap: 8px; }.transition-control select, .comment-form textarea, .resource-form input, .resource-form select { width: 100%; min-height: 34px; padding: 0 9px; border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-text); font: 10px var(--cs-font-sans); }.facts-section dl { display: grid; grid-template-columns: 1fr 1fr; gap: 0; margin: 0; }.facts-section dl div { padding: 8px 0; border-bottom: 1px solid var(--cs-border); }.facts-section dl div:nth-last-child(-n+2) { border-bottom: 0; }.facts-section dt { color: var(--cs-text-muted); font-size: 8px; }.facts-section dd { display: flex; align-items: center; gap: 5px; margin: 3px 0 0; font-size: 9px; font-weight: 650; }.section-note { margin: 0; color: var(--cs-text-muted); font-size: 9px; }.comment-list, .resource-list { display: grid; gap: 8px; }.comment-list article { display: grid; grid-template-columns: 28px 1fr; gap: 8px; }.comment-list article > i { display: grid; width: 28px; height: 28px; place-items: center; border-radius: 50%; background: var(--cs-brand-100); color: var(--cs-brand-700); font-size: 9px; font-style: normal; font-weight: 800; }.comment-list header { display: flex; justify-content: space-between; gap: 8px; }.comment-list header strong, .comment-list header time { color: var(--cs-text-muted); font-size: 8px; }.comment-list p { margin: 3px 0 0; color: var(--cs-text-secondary); font-size: 10px; line-height: 1.5; white-space: pre-wrap; }.comment-form { display: grid; justify-items: end; gap: 7px; margin-top: 13px; }.comment-form label { justify-self: start; color: var(--cs-text-secondary); font-size: 9px; font-weight: 700; }.comment-form textarea { min-height: 68px; padding-block: 8px; resize: vertical; }.comment-form textarea[aria-invalid="true"], .resource-form input[aria-invalid="true"] { border-color: var(--cs-danger); }.resource-list article { display: grid; grid-template-columns: 28px minmax(0, 1fr) auto; align-items: center; gap: 8px; padding: 8px; border-radius: 8px; background: var(--cs-surface-subtle); }.resource-list article > i { display: grid; width: 28px; height: 28px; place-items: center; border-radius: 8px; background: var(--cs-agent-soft); color: var(--cs-agent); }.resource-list strong, .resource-list a, .resource-list article div > span { display: flex; min-width: 0; align-items: center; gap: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.resource-list strong { font-size: 10px; }.resource-list a, .resource-list article div > span { margin-top: 2px; color: var(--cs-text-muted); font-size: 8px; }.resource-form { display: grid; grid-template-columns: 120px 1fr; gap: 8px; margin-top: 13px; }.resource-form label { display: grid; gap: 4px; color: var(--cs-text-secondary); font-size: 8px; font-weight: 700; }.resource-form label:nth-child(3) { grid-column: 1 / -1; }.resource-form > button { justify-self: end; grid-column: 1 / -1; }.conflict-panel { display: flex; align-items: flex-start; gap: 9px; margin-top: 10px; padding: 12px; border-color: #f0d5ad; background: var(--cs-warning-soft); color: var(--cs-warning); }.conflict-panel strong, .conflict-panel span { display: block; }.conflict-panel strong { font-size: 10px; }.conflict-panel span { margin-top: 2px; color: var(--cs-text-muted); font-size: 9px; }.command-error { margin: 9px 2px 0; color: var(--cs-danger); font-size: 9px; }.command-error button { color: inherit; text-decoration: underline; cursor: pointer; }.detail-footer { display: flex; justify-content: flex-end; padding: 11px 14px; border-top: 1px solid var(--cs-border); background: var(--cs-surface); }
.detail-footer { display: grid; justify-items: end; gap: 7px; }.detail-footer > p { margin: 0; color: var(--cs-text-muted); font-size: 8px; }.detail-footer > div { display: flex; gap: 7px; }
@media (max-width: 767px) { .detail-drawer { width: 100%; }.detail-content { padding: 9px; }.detail-hero { padding: 16px; }.detail-hero h2 { font-size: 17px; }.detail-section { padding: 14px; }.resource-form { grid-template-columns: 1fr; }.resource-form label:nth-child(3), .resource-form > button { grid-column: 1; }.resource-form > button { justify-self: stretch; }.detail-footer { justify-items: stretch; }.detail-footer > div { display: grid; }.detail-footer > div > * { width: 100%; } }
</style>
