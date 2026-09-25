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
import { computed, inject, nextTick, onBeforeUnmount, onMounted, ref, useTemplateRef, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { AUTH_PRINCIPAL } from '../../app/auth'
import { isTopmostModal } from '../../app/dialog'
import {
  clearWorkItemCommentDraft,
  clearWorkItemCommentDraftIfRevision,
  readWorkItemCommentDraft,
  writeWorkItemCommentDraft,
} from '../../domains/workitem/commentDraft'
import type { SemanticTone } from '../base/types'
import type { ResponsibilityCommand, WorkItemDetailCommand, WorkItemPhase } from '../../domains/workitem/store'
import {
  workItemResourceTypes,
  type AddWorkItemCommentInput,
  type LinkWorkItemResourceInput,
  type ResponsibilityAssignment,
  type WorkItemAvailableTransition,
  type WorkItemDetails,
  type WorkItemResourceLink,
  type WorkItemResourceType,
  type WorkItemStatus,
  type WorkItemTimelineEvent,
  type WorkItemVersionConflict,
} from '../../domains/workitem/types'
import { enumLabel } from '../../domains/shared/labels'
import { useTransitionConfirm } from '../../domains/workitem/useTransitionConfirm'
import {
  workItemPriorityLabels,
  workItemResourceTypeLabels,
  workItemSourceLabels,
  workItemStatusLabels,
  workItemTransitionVariants,
  workItemTypeLabels,
} from '../../domains/workitem/labels'
import BaseButton from '../base/BaseButton.vue'
import BaseTooltip from '../base/BaseTooltip.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'
import WorkItemResponsibilityPanel, { type ResponsibilityAgentCandidate, type ResponsibilityCandidate } from './WorkItemResponsibilityPanel.vue'
import WorkItemTimeline from './WorkItemTimeline.vue'
import WorkItemContentEditor from './WorkItemContentEditor.vue'
import ConversationWorkItemLinks from './ConversationWorkItemLinks.vue'
import type { PrincipalScope } from '../../domains/principal/types'
import type { ConversationWorkItemAssociation } from '../../domains/conversation/workItemLinkGateway'
import type { ConversationWorkItemLinkPhase } from '../../domains/conversation/workItemLinkStore'

const props = defineProps<{
  scope: PrincipalScope | null
  phase: WorkItemPhase
  details: WorkItemDetails | null
  errorMessage: string | null
  commandPending: WorkItemDetailCommand | null
  commandErrorMessage: string | null
  versionConflict: WorkItemVersionConflict | null
  availabilityPhase: WorkItemPhase
  availableTransitions: WorkItemAvailableTransition[]
  availabilityErrorMessage: string | null
  canParticipate: boolean
  canDelegate: boolean
  canManageResponsibility: boolean
  responsibilityPhase: WorkItemPhase
  responsibilities: ResponsibilityAssignment[]
  responsibilityCandidates: ResponsibilityCandidate[]
  responsibilityAgentCandidates: ResponsibilityAgentCandidate[]
  responsibilityAgentPhase: 'idle' | 'loading' | 'ready' | 'empty' | 'error'
  responsibilityAgentErrorMessage: string | null
  responsibilityAgentLoadingMore: boolean
  responsibilityAgentHasMore: boolean
  responsibilityErrorMessage: string | null
  responsibilityCommandPending: ResponsibilityCommand | null
  responsibilityCommandErrorMessage: string | null
  timelinePhase: WorkItemPhase
  timeline: WorkItemTimelineEvent[]
  timelineNextCursor: string | null
  timelineLoadingMore: boolean
  timelineErrorMessage: string | null
  associationPhase: ConversationWorkItemLinkPhase
  associations: ConversationWorkItemAssociation[]
  associationErrorMessage: string | null
  onRetry: () => void
  onContentSaved?: () => void
  onTransition: (target: WorkItemStatus) => Promise<void>
  onRetryAvailability: () => void
  onAddComment: (input: AddWorkItemCommentInput) => Promise<void>
  onLinkResource: (input: LinkWorkItemResourceInput) => Promise<void>
  onReplaceOwner: (actorPrincipalId: string) => Promise<void>
  onAssignExecutor: (actorPrincipalId: string) => Promise<void>
  onAssignGateReviewer: (actorPrincipalId: string) => Promise<void>
  onAssignAdvisoryReviewer: (actorPrincipalId: string) => Promise<void>
  onReleaseResponsibility: (assignment: ResponsibilityAssignment) => Promise<void>
  onRetryResponsibilityAgents: () => void
  onLoadMoreResponsibilityAgents: () => void
  onLoadTimelineMore: () => Promise<void>
  onRetryAssociations: () => void
}>()

const emit = defineEmits<{
  close: []
  conversation: []
  openConversation: [association: ConversationWorkItemAssociation]
  delegate: []
}>()
const closeButton = useTemplateRef<HTMLButtonElement>('closeButton')
const drawer = useTemplateRef<HTMLElement>('drawer')
const principal = inject(AUTH_PRINCIPAL)
const comment = ref('')
const resourceType = ref<WorkItemResourceType>('EXTERNAL_URL')
const resourceReference = ref('')
const resourceLabel = ref('')
const commentSubmitted = ref(false)
const resourceSubmitted = ref(false)
let previousBodyOverflow = ''

const item = computed(() => props.details?.workItem ?? null)
const commentDraftScope = computed(() => props.scope && item.value
  ? { organizationId: props.scope.organizationId, teamId: props.scope.teamId, projectId: item.value.projectId }
  : null)
let restoredCommentForItem: string | null = null

// Details arrive asynchronously, so restore when the item lands — only into an untouched
// composer, and only once per drawer instance (WorkPage keys the drawer by work item).
watch(item, value => {
  if (!value || !commentDraftScope.value || restoredCommentForItem === value.id) return
  restoredCommentForItem = value.id
  if (!comment.value.trim()) comment.value = readWorkItemCommentDraft(commentDraftScope.value, value.id, principal)?.content ?? ''
}, { immediate: true })

// Closing or escaping the drawer keeps the comment as a draft; a successful submit clears it.
watch(comment, value => {
  if (!commentDraftScope.value || !item.value) return
  if (value.trim()) writeWorkItemCommentDraft(commentDraftScope.value, item.value.id, item.value.version, value, principal)
  else clearWorkItemCommentDraft(commentDraftScope.value, item.value.id, principal)
})

/**
 * The action list is whatever the server returned, never wider.
 *
 * Earlier revisions derived it from the generated state machine and then guessed the member's
 * permission locally, which is how a button could look live and die on submit. The generated
 * machine still says which edges exist; only `availableTransitions` says which of them this
 * member may execute right now, so it is the only input here.
 */
const transitions = computed(() => props.availableTransitions)
const hasActionableTransition = computed(() => transitions.value.some(transition => transition.enabled))
const busyTransition = computed(() => props.commandPending === 'transition' || props.commandPending === 'undo')
const {
  confirmingTarget: confirmingTransition,
  submit: submitConfirmedTransition,
} = useTransitionConfirm(() => busyTransition.value)
const canCollaborate = computed(() => props.canParticipate && item.value?.status !== 'ARCHIVED')
const canManageResponsibility = computed(() => props.canManageResponsibility && item.value?.status !== 'ARCHIVED')

function humanName(principalId: string | null): string {
  if (!principalId) return '系统'
  return props.responsibilityCandidates.find(candidate => candidate.principalId === principalId)?.displayName
    ?? props.responsibilityAgentCandidates.find(candidate => candidate.principalId === principalId)?.displayName
    ?? `${principalId.slice(0, 8)}…`
}

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
  if (!isTopmostModal(drawer.value)) return
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

/**
 * The two-step rule for irreversible actions lives in `useTransitionConfirm`, shared with the cards.
 *
 * The drawer keeps its own *presentation* — a row of labelled buttons rather than a menu — because
 * these are the primary actions of a detail surface: a `menu` role would take them out of the
 * button list a screen reader offers and make Tab skip past them. What it does not keep is a second
 * copy of the rule about which actions need confirming, which is the part that would drift.
 */
async function submitTransition(transition: WorkItemAvailableTransition): Promise<void> {
  await submitConfirmedTransition(transition.actionId, transition, async selected => {
    try {
      await props.onTransition(selected.targetStatus)
    } catch {
      // The Store exposes a sanitized error and preserves the refreshed server version on conflict.
    }
  })
}

function transitionDomId(transition: WorkItemAvailableTransition): string {
  return `transition-reason-${transition.actionId}`
}

async function submitComment(): Promise<void> {
  commentSubmitted.value = true
  const content = comment.value.trim()
  if (!content) return
  const draftScope = commentDraftScope.value
  const itemId = item.value?.id
  const itemVersion = item.value?.version
  try {
    await props.onAddComment({ content })
    // Clear the stored draft only when it still holds the version this submit read, so a
    // comment re-drafted against a newer version is never lost.
    if (draftScope && itemId && itemVersion !== undefined) clearWorkItemCommentDraftIfRevision(draftScope, itemId, itemVersion, principal)
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

</script>

<template>
  <div class="detail-backdrop" @mousedown.self="$emit('close')">
    <aside ref="drawer" class="detail-drawer" role="dialog" aria-modal="true" tabindex="-1" :aria-label="item ? `${item.key} 工作项详情` : '工作项详情'">
      <header class="detail-header">
        <div><p>WorkItem detail</p><strong>{{ item?.key ?? '加载详情' }}</strong></div>
        <button ref="closeButton" type="button" aria-label="关闭工作项详情" @click="$emit('close')"><X :size="18" /></button>
      </header>

      <StatePanel v-if="phase === 'loading' && !details" state="loading" />
      <StatePanel v-else-if="phase === 'error' && !details" state="error" :description="errorMessage ?? undefined" @retry="onRetry" />

      <div v-else-if="details && item" class="detail-content">
        <section class="detail-hero">
          <div class="detail-hero__status"><StatusBadge :tone="statusTone(item.status)" dot>{{ workItemStatusLabels[item.status] }}</StatusBadge><span class="mono">v{{ item.version }}</span></div>
          <h2>{{ item.title }}</h2>
          <p v-if="item.description">{{ item.description }}</p>
          <div class="detail-tags"><StatusBadge tone="info">{{ workItemTypeLabels[item.type] }}</StatusBadge><StatusBadge :tone="item.priority === 'URGENT' ? 'danger' : item.priority === 'HIGH' ? 'warning' : 'neutral'">{{ workItemPriorityLabels[item.priority] }}优先级</StatusBadge><span v-for="label in item.labels" :key="label"><Tag :size="11" />{{ label }}</span></div>
        </section>

        <WorkItemContentEditor :key="item.id" :item="item" :can-participate="canParticipate" @saved="onContentSaved ? onContentSaved() : onRetry()" />
        <section v-if="versionConflict" class="conflict-panel" role="alert">
          <RefreshCw :size="17" /><div><strong>检测到并发更新</strong><span>提交基于 v{{ versionConflict.attemptedVersion }}，服务端当前版本为 {{ versionConflict.currentVersion === null ? '未知' : `v${versionConflict.currentVersion}` }}。详情已刷新。</span></div>
        </section>
        <p v-if="commandErrorMessage" class="command-error" role="alert">{{ commandErrorMessage }}</p>
        <p v-if="phase === 'error' && errorMessage" class="command-error" role="alert">{{ errorMessage }} <button type="button" @click="onRetry">重试</button></p>

        <section class="detail-section transition-section">
          <div class="section-heading"><div><p>Workflow</p><h3>状态流转</h3></div><ShieldCheck :size="17" /></div>
          <StatePanel v-if="availabilityPhase === 'loading' && !transitions.length" state="loading" description="正在确认可执行动作" />
          <p v-else-if="availabilityPhase === 'error'" class="command-error" role="alert">
            {{ availabilityErrorMessage ?? '暂时无法加载可执行动作' }}
            <button type="button" @click="onRetryAvailability">重试</button>
          </p>
          <div v-else-if="transitions.length" class="transition-control" role="group" aria-label="可执行工作项动作">
            <p id="transition-help" class="transition-help">
              {{ hasActionableTransition
                ? '可撤销的动作执行后会提供 10 秒撤销入口；不可撤销的动作需要再次点击确认。'
                : '下列动作当前都不可执行，原因见每个动作的说明。' }}
            </p>
            <div class="transition-actions">
              <div v-for="transition in transitions" :key="transition.actionId" class="transition-action">
                <BaseTooltip v-if="!transition.enabled" :text="transition.reasonMessage ?? '当前不可执行'">
                  <BaseButton
                    size="small"
                    :variant="workItemTransitionVariants[transition.strength]"
                    disabled
                    :aria-describedby="transitionDomId(transition)"
                  >{{ transition.label }}</BaseButton>
                </BaseTooltip>
                <BaseButton
                  v-else
                  size="small"
                  :variant="workItemTransitionVariants[transition.strength]"
                  :loading="busyTransition"
                  aria-describedby="transition-help"
                  @click="submitTransition(transition)"
                >{{ confirmingTransition === transition.targetStatus ? `再次点击确认${transition.label}` : transition.label }}<ArrowRight :size="13" /></BaseButton>
                <p v-if="!transition.enabled" :id="transitionDomId(transition)" class="transition-reason">
                  {{ transition.reasonMessage ?? '当前不可执行' }}
                  <RouterLink v-if="transition.remedyRoute" :to="transition.remedyRoute">{{ transition.remedyLabel ?? '前往处理' }}</RouterLink>
                </p>
              </div>
            </div>
          </div>
          <p v-else class="section-note">当前状态没有后续动作。</p>
        </section>

        <section class="detail-section facts-section">
          <div class="section-heading"><div><p>Facts</p><h3>工作项信息</h3></div></div>
          <dl><div><dt>来源</dt><dd>{{ enumLabel(item.source, workItemSourceLabels) }}</dd></div><div><dt>更新时间</dt><dd>{{ displayDate(item.updatedAt) }}</dd></div><div><dt>到期时间</dt><dd><CalendarClock :size="12" />{{ item.dueAt ? displayDate(item.dueAt) : '未设置' }}</dd></div><div><dt>创建者</dt><dd>{{ humanName(item.createdByPrincipalId) }}</dd></div></dl>
        </section>

        <ConversationWorkItemLinks
          :phase="associationPhase"
          :associations="associations"
          :error-message="associationErrorMessage"
          direction="work-item"
          @open="emit('openConversation', $event)"
          @retry="onRetryAssociations"
        />

        <section class="detail-section responsibility-section">
          <div class="section-heading"><div><p>Accountability</p><h3>团队责任链 <span>{{ responsibilities.length }}</span></h3></div><ShieldCheck :size="17" /></div>
          <WorkItemResponsibilityPanel
            :scope="scope"
            :phase="responsibilityPhase"
            :members="responsibilities"
            :candidates="responsibilityCandidates"
            :agent-candidates="responsibilityAgentCandidates"
            :agent-phase="responsibilityAgentPhase"
            :agent-error-message="responsibilityAgentErrorMessage"
            :agent-loading-more="responsibilityAgentLoadingMore"
            :agent-has-more="responsibilityAgentHasMore"
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
            :on-retry-agents="onRetryResponsibilityAgents"
            :on-load-more-agents="onLoadMoreResponsibilityAgents"
          />
        </section>

        <section class="detail-section comments-section">
          <div class="section-heading"><div><p>Discussion</p><h3>评论 <span>{{ details.comments.length }}</span></h3></div><MessageSquare :size="17" /></div>
          <div v-if="details.comments.length" class="comment-list">
            <article v-for="entry in details.comments" :key="entry.id"><i>{{ humanName(entry.authorPrincipalId).slice(0, 1).toUpperCase() }}</i><div><header><strong>{{ humanName(entry.authorPrincipalId) }}</strong><time>{{ displayDate(entry.createdAt) }}</time></header><p>{{ entry.content }}</p></div></article>
          </div>
          <p v-else class="section-note">还没有评论。</p>
          <form v-if="canCollaborate" class="comment-form" @submit.prevent="submitComment"><label for="work-item-comment">添加评论</label><textarea id="work-item-comment" v-model="comment" rows="3" placeholder="记录决策、进展或需要协作的事项" :aria-invalid="commentSubmitted && !comment.trim()" /><BaseButton type="submit" size="small" :loading="commandPending === 'comment'"><Send :size="13" />发送评论</BaseButton></form>
        </section>

        <section class="detail-section resources-section">
          <div class="section-heading"><div><p>WorkGraph nodes</p><h3>关联资源 <span>{{ details.resourceLinks.length }}</span></h3></div><Link2 :size="17" /></div>
          <div v-if="details.resourceLinks.length" class="resource-list">
            <article v-for="resource in details.resourceLinks" :key="resource.id"><i><Link2 :size="14" /></i><div><strong>{{ resource.label ?? workItemResourceTypeLabels[resource.resourceType] }}</strong><a v-if="resourceHref(resource)" :href="resourceHref(resource)" target="_blank" rel="noopener noreferrer">{{ resource.resourceReference }}<ExternalLink :size="11" /></a><span v-else class="mono">{{ resource.resourceReference }}</span></div><StatusBadge>{{ workItemResourceTypeLabels[resource.resourceType] }}</StatusBadge></article>
          </div>
          <p v-else class="section-note">还没有关联资源。</p>
          <form v-if="canCollaborate" class="resource-form" @submit.prevent="submitResource"><label><span>资源类型</span><select v-model="resourceType"><option v-for="kind in workItemResourceTypes" :key="kind" :value="kind">{{ workItemResourceTypeLabels[kind] }}</option></select></label><label><span>引用</span><input v-model="resourceReference" :placeholder="resourceType === 'EXTERNAL_URL' ? 'https://example.com/resource' : '资源的稳定标识'" :aria-invalid="resourceSubmitted && !resourceReference.trim()"></label><label><span>显示名称</span><input v-model="resourceLabel" placeholder="可选"></label><BaseButton type="submit" size="small" variant="secondary" :loading="commandPending === 'resource'">关联资源</BaseButton></form>
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

        <section v-if="$slots.activity" class="detail-section activity-projection-section">
          <slot name="activity" />
        </section>
      </div>

      <footer class="detail-footer">
        <div><BaseButton variant="secondary" @click="$emit('conversation')"><MessageSquare :size="14" />与 Personal Agent 讨论</BaseButton><BaseButton v-if="canDelegate" variant="ghost" @click="$emit('delegate')"><Bot :size="14" />交给 Agent 处理</BaseButton></div>
      </footer>
    </aside>
  </div>
</template>

<style scoped>
.detail-backdrop { position: fixed; inset: 0; z-index: var(--cs-z-drawer); background: var(--cs-scrim); backdrop-filter: blur(2px); }.detail-drawer { position: absolute; inset: 0 0 0 auto; display: grid; width: min(560px, 92vw); grid-template-rows: auto minmax(0, 1fr) auto; border-left: 1px solid var(--cs-border-strong); background: var(--cs-canvas); box-shadow: var(--cs-shadow-drawer); }.detail-header { display: flex; min-height: 64px; align-items: center; justify-content: space-between; gap: var(--cs-space-16); padding: var(--cs-space-12) var(--cs-space-16) var(--cs-space-12) var(--cs-space-20); border-bottom: 1px solid var(--cs-border); background: var(--cs-surface); }.detail-header p, .detail-header strong { display: block; margin: 0; }.detail-header p { color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); letter-spacing: .08em; text-transform: uppercase; }.detail-header strong { margin-top: var(--cs-space-2); color: var(--cs-text-brand); font: var(--cs-text-base) var(--cs-font-mono); }.detail-header button { display: grid; width: 34px; height: 34px; place-items: center; border-radius: 9px; background: var(--cs-surface-subtle); cursor: pointer; }.detail-content { overflow-y: auto; padding: var(--cs-space-12); }.detail-hero, .detail-section, .conflict-panel { border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }.detail-hero { padding: var(--cs-space-20); }.detail-hero__status { display: flex; align-items: center; justify-content: space-between; }.detail-hero__status > .mono { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.detail-hero h2 { margin: var(--cs-space-12) 0 var(--cs-space-8); font-size: var(--cs-text-lg); line-height: var(--cs-leading-tight); }.detail-hero > p { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-sm); line-height: var(--cs-leading-normal); white-space: pre-wrap; }.detail-tags { display: flex; flex-wrap: wrap; gap: var(--cs-space-8); margin-top: var(--cs-space-16); }.detail-tags > span { display: inline-flex; align-items: center; gap: var(--cs-space-4); padding: var(--cs-space-4) var(--cs-space-8); border-radius: 6px; background: var(--cs-surface-subtle); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.detail-section { margin-top: var(--cs-space-12); padding: var(--cs-space-16); }.section-heading { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-12); margin-bottom: var(--cs-space-12); }.section-heading p { margin: 0 0 var(--cs-space-2); color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); letter-spacing: .08em; text-transform: uppercase; }.section-heading h3 { margin: 0; font-size: var(--cs-text-base); }.section-heading h3 span { color: var(--cs-text-muted); font-weight: var(--cs-weight-medium); }.section-heading > svg { color: var(--cs-text-muted); }.transition-control { display: grid; grid-template-columns: 1fr auto; gap: var(--cs-space-8); }.transition-control select, .comment-form textarea, .resource-form input, .resource-form select { width: 100%; min-height: 34px; padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-text); font: var(--cs-text-base) var(--cs-font-sans); }.facts-section dl { display: grid; grid-template-columns: 1fr 1fr; gap: 0; margin: 0; }.facts-section dl div { padding: var(--cs-space-8) 0; border-bottom: 1px solid var(--cs-border); }.facts-section dl div:nth-last-child(-n+2) { border-bottom: 0; }.facts-section dt { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.facts-section dd { display: flex; align-items: center; gap: var(--cs-space-4); margin: var(--cs-space-4) 0 0; font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.section-note { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.comment-list, .resource-list { display: grid; gap: var(--cs-space-8); }.comment-list article { display: grid; grid-template-columns: 28px 1fr; gap: var(--cs-space-8); }.comment-list article > i { display: grid; width: 28px; height: 28px; place-items: center; border-radius: 50%; background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-style: normal; font-weight: var(--cs-weight-semibold); }.comment-list header { display: flex; justify-content: space-between; gap: var(--cs-space-8); }.comment-list header strong, .comment-list header time { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.comment-list p { margin: var(--cs-space-4) 0 0; color: var(--cs-text-secondary); font-size: var(--cs-text-sm); line-height: var(--cs-leading-normal); white-space: pre-wrap; }.comment-form { display: grid; justify-items: end; gap: var(--cs-space-8); margin-top: var(--cs-space-12); }.comment-form label { justify-self: start; color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.comment-form textarea { min-height: 68px; padding-block: var(--cs-space-8); resize: vertical; }.comment-form textarea[aria-invalid="true"], .resource-form input[aria-invalid="true"] { border-color: var(--cs-danger); }.resource-list article { display: grid; grid-template-columns: 28px minmax(0, 1fr) auto; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-8); border-radius: 8px; background: var(--cs-surface-subtle); }.resource-list article > i { display: grid; width: 28px; height: 28px; place-items: center; border-radius: 8px; background: var(--cs-agent-soft); color: var(--cs-agent); }.resource-list strong, .resource-list a, .resource-list article div > span { display: flex; min-width: 0; align-items: center; gap: var(--cs-space-4); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.resource-list strong { font-size: var(--cs-text-sm); }.resource-list a, .resource-list article div > span { margin-top: var(--cs-space-2); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.resource-form { display: grid; grid-template-columns: 120px 1fr; gap: var(--cs-space-8); margin-top: var(--cs-space-12); }.resource-form label { display: grid; gap: var(--cs-space-4); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.resource-form label:nth-child(3) { grid-column: 1 / -1; }.resource-form > button { justify-self: end; grid-column: 1 / -1; }.conflict-panel { display: flex; align-items: flex-start; gap: var(--cs-space-8); margin-top: var(--cs-space-12); padding: var(--cs-space-12); border-color: var(--cs-warning-border); background: var(--cs-warning-soft); color: var(--cs-warning); }.conflict-panel strong, .conflict-panel span { display: block; }.conflict-panel strong { font-size: var(--cs-text-sm); }.conflict-panel span { margin-top: var(--cs-space-2); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.command-error { margin: var(--cs-space-8) var(--cs-space-2) 0; color: var(--cs-danger); font-size: var(--cs-text-xs); }.command-error button { color: inherit; text-decoration: underline; cursor: pointer; }.detail-footer { display: flex; justify-content: flex-end; padding: var(--cs-space-12) var(--cs-space-16); border-top: 1px solid var(--cs-border); background: var(--cs-surface); }
.detail-footer { display: grid; justify-items: end; gap: var(--cs-space-8); }.detail-footer > p { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.detail-footer > div { display: flex; gap: var(--cs-space-8); }
@media (max-width: 767px) { .detail-drawer { width: 100%; }.detail-content { padding: var(--cs-space-8); }.detail-hero { padding: var(--cs-space-16); }.detail-hero h2 { font-size: var(--cs-text-lg); }.detail-section { padding: var(--cs-space-16); }.resource-form { grid-template-columns: 1fr; }.resource-form label:nth-child(3), .resource-form > button { grid-column: 1; }.resource-form > button { justify-self: stretch; }.detail-footer { justify-items: stretch; }.detail-footer > div { display: grid; }.detail-footer > div > * { width: 100%; } }
.transition-control { display: grid; gap: var(--cs-space-8); }
.transition-help { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.transition-actions { display: flex; flex-wrap: wrap; gap: var(--cs-space-8); }
.transition-action { display: grid; gap: var(--cs-space-4); }
.transition-reason { max-width: 220px; margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }
.transition-reason a { color: var(--cs-text-brand); font-weight: var(--cs-weight-semibold); text-decoration: underline; }
</style>
