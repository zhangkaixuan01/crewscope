<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { Check, ClipboardCheck, Pencil, X } from '@lucide/vue'
import type { TaskIntent, TaskIntentRevisionInput } from '../../domains/conversation/types'
import { principalDisplayName, type PrincipalNameDirectory } from '../../domains/scope/memberDirectory'
import type { TeamMemberSummary, WorkProjectSummary } from '../../domains/scope/types'
import type { PrincipalScope } from '../../domains/principal/types'
import BaseButton from '../base/BaseButton.vue'
import PrincipalPicker from './PrincipalPicker.vue'
import StatusBadge from '../base/StatusBadge.vue'

const props = withDefaults(defineProps<{
  intent: TaskIntent
  currentPrincipalId: string
  pending?: 'revise' | 'reject' | 'confirm' | null
  errorMessage?: string | null
  versionConflict?: boolean
  principalNames?: PrincipalNameDirectory
  /**
   * Directories that turn the revision form into named choices.
   *
   * A full revision has to restate every subject of the proposal, and none of those subjects are
   * things a reviewer can retype from memory. The card therefore never accepts a free-text
   * identifier: members and projects come from the Team scope, and the executor — which may be an
   * Agent — is resolved through the searchable subject directory.
   */
  members?: TeamMemberSummary[]
  projects?: WorkProjectSummary[]
  scope?: PrincipalScope | null
  /** 'summary' is the folded pre-message card: title, objective and status; every action lives in 'full'. */
  variant?: 'full' | 'summary'
}>(), { variant: 'full' })

const emit = defineEmits<{
  revise: [input: TaskIntentRevisionInput]
  reject: [reason: string]
  confirm: []
  expand: []
}>()

const editing = ref(false)
const rejecting = ref(false)
const localError = ref<string | null>(null)
const form = reactive({ objective: '', criteria: '', workProjectId: '', ownerMemberId: '', executorPrincipalId: '' as string | null, gateReviewerMemberId: '' })
const rejectionReason = ref('')
const reviewable = computed(() => props.intent.status === 'READY')
const isOwner = computed(() => props.intent.proposal.owner.principalId === props.currentPrincipalId)
/** 同一张提案卡上一次只允许一个命令：其它命令在提交时，「预检并确认」暂时不可用。 */
const otherCommandPending = computed(() => Boolean(props.pending) && props.pending !== 'confirm')

/**
 * The proposal may already name a member or project that has since left the loaded directory.
 * Dropping it silently would turn an edit into a deletion, so the current fact stays selectable.
 */
const memberOptions = computed(() => withCurrent(
  (props.members ?? []).map(member => ({ value: member.id, label: member.displayName })),
  [form.ownerMemberId, form.gateReviewerMemberId],
  '目录外成员',
))
const projectOptions = computed(() => withCurrent(
  (props.projects ?? []).map(project => ({ value: project.id, label: `${project.key} · ${project.name}` })),
  [form.workProjectId],
  '目录外 WorkProject',
))

function withCurrent(
  options: { value: string, label: string }[],
  current: string[],
  fallbackLabel: string,
): { value: string, label: string }[] {
  const extras = current
    .filter(value => value && !options.some(option => option.value === value))
    .map(value => ({ value, label: `${fallbackLabel} · ${value.slice(0, 8)}` }))
  return [...options, ...extras]
}

watch(() => props.intent, populate, { immediate: true })

function populate(intent: TaskIntent): void {
  form.objective = intent.proposal.objective
  form.criteria = intent.proposal.acceptanceCriteria.join('\n')
  form.workProjectId = intent.proposal.workProjectId
  form.ownerMemberId = intent.proposal.owner.teamMemberId ?? ''
  form.executorPrincipalId = intent.proposal.executor?.principalId ?? null
  form.gateReviewerMemberId = intent.proposal.gateReviewer?.teamMemberId ?? ''
  editing.value = false
  rejecting.value = false
  rejectionReason.value = ''
  localError.value = null
}

function submitRevision(): void {
  const acceptanceCriteria = form.criteria.split('\n').map(value => value.trim()).filter(Boolean)
  if (!form.objective.trim() || acceptanceCriteria.length < 1 || acceptanceCriteria.length > 20) {
    localError.value = '目标不能为空，验收标准需包含 1–20 项'
    return
  }
  if (!form.workProjectId || !form.ownerMemberId) {
    localError.value = 'WorkProject 与 Owner Member 是完整提案的必填事实'
    return
  }
  localError.value = null
  emit('revise', {
    schemaVersion: '1',
    objective: form.objective.trim(),
    acceptanceCriteria,
    workProjectId: form.workProjectId.trim(),
    ownerMemberId: form.ownerMemberId.trim(),
    executorPrincipalId: form.executorPrincipalId || null,
    gateReviewerMemberId: form.gateReviewerMemberId.trim() || null,
  })
}

function submitRejection(): void {
  const reason = rejectionReason.value.trim()
  if (!reason || reason.length > 1_000) {
    localError.value = '拒绝原因需包含 1–1000 个字符'
    return
  }
  localError.value = null
  emit('reject', reason)
}

function statusText(status: TaskIntent['status']): string {
  return ({ DRAFT: '草拟中', READY: '待确认', CONFIRMED: '已确认', REJECTED: '已拒绝', EXPIRED: '已过期' })[status]
}

function principalName(principalId: string): string {
  return principalDisplayName(props.principalNames ?? {}, principalId)
}
</script>

<template>
  <section class="intent-card" aria-labelledby="task-intent-title">
    <header>
      <span><ClipboardCheck :size="17" aria-hidden="true" /></span>
      <div><p>Task intent · Revision {{ intent.proposalRevision }}</p><h3 id="task-intent-title">结构化任务提案</h3></div>
      <StatusBadge :tone="intent.status === 'READY' ? 'warning' : intent.status === 'CONFIRMED' ? 'success' : intent.status === 'REJECTED' ? 'danger' : 'neutral'">{{ statusText(intent.status) }}</StatusBadge>
    </header>

    <form v-if="editing && isOwner && reviewable" class="revision-form" @submit.prevent="submitRevision">
      <label><span>目标</span><textarea v-model="form.objective" maxlength="5000" rows="3" /></label>
      <label><span>验收标准 <small>每行一项</small></span><textarea v-model="form.criteria" maxlength="20000" rows="4" /></label>
      <div class="field-grid">
        <label>
          <span>WorkProject</span>
          <select v-model="form.workProjectId">
            <option value="">请选择 WorkProject</option>
            <option v-for="option in projectOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
          </select>
        </label>
        <label>
          <span>Owner</span>
          <select v-model="form.ownerMemberId">
            <option value="">请选择 Owner</option>
            <option v-for="option in memberOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
          </select>
        </label>
        <PrincipalPicker
          v-if="scope"
          v-model="form.executorPrincipalId"
          :scope="scope"
          label="Executor（可选）"
          placeholder="按姓名搜索成员或 Agent"
          help-text="留空表示确认后再分配执行者。"
        />
        <label v-else><span>Executor <small>可选</small></span><select v-model="form.executorPrincipalId"><option :value="null">确认后分配</option></select></label>
        <label>
          <span>Gate Reviewer <small>可选</small></span>
          <select v-model="form.gateReviewerMemberId">
            <option value="">不设置 Gate Reviewer</option>
            <option v-for="option in memberOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
          </select>
        </label>
      </div>
      <p v-if="localError" class="error" role="alert">{{ localError }}</p>
      <footer><BaseButton variant="ghost" size="small" @click="editing = false">取消</BaseButton><BaseButton type="submit" size="small" :loading="pending === 'revise'">提交完整修订</BaseButton></footer>
    </form>

    <template v-else-if="variant === 'summary'">
      <div class="objective"><span>目标</span><p>{{ intent.proposal.objective }}</p></div>
      <footer class="intent-summary-footer">
        <BaseButton variant="secondary" size="small" @click="emit('expand')">展开提案</BaseButton>
      </footer>
    </template>

    <template v-else>
      <div class="objective"><span>目标</span><p>{{ intent.proposal.objective }}</p></div>
      <div class="criteria"><span>验收标准</span><ol><li v-for="criterion in intent.proposal.acceptanceCriteria" :key="criterion">{{ criterion }}</li></ol></div>
      <dl>
        <div><dt>Owner</dt><dd>{{ principalName(intent.proposal.owner.principalId) }}</dd></div>
        <div><dt>Executor</dt><dd>{{ intent.proposal.executor ? principalName(intent.proposal.executor.principalId) : '确认后分配' }}</dd></div>
        <div><dt>Gate Reviewer</dt><dd>{{ intent.proposal.gateReviewer ? principalName(intent.proposal.gateReviewer.principalId) : '未设置' }}</dd></div>
        <div><dt>WorkProject</dt><dd>{{ intent.proposal.workProjectId }}</dd></div>
      </dl>
      <p v-if="!isOwner && reviewable" class="notice">只有提案 Owner 可以修订、确认或拒绝；当前成员可继续观察事实变化。</p>
      <p v-else-if="isOwner && reviewable" class="notice coding-continuation">确认将原子创建 WorkItem；确认结果随后使用统一委托表单选择 Repository、Ref、Allowed Paths 与 BuildProfile。</p>
      <p v-if="versionConflict" class="notice conflict">提案版本已刷新，请重新检查当前内容。</p>
      <p v-if="errorMessage || localError" class="error" role="alert">{{ errorMessage || localError }}</p>
      <div v-if="rejecting" class="reject-form">
        <label><span>拒绝原因</span><textarea v-model="rejectionReason" maxlength="1000" rows="2" autofocus /></label>
        <div><BaseButton variant="ghost" size="small" @click="rejecting = false">取消</BaseButton><BaseButton variant="danger" size="small" :loading="pending === 'reject'" @click="submitRejection">确认拒绝</BaseButton></div>
      </div>
      <footer v-if="reviewable && isOwner && !rejecting">
        <p v-if="otherCommandPending" id="intent-confirm-reason" class="sr-only">正在提交上一次提案操作，完成前无法再次确认。</p>
        <BaseButton variant="ghost" size="small" :disabled="Boolean(pending)" @click="editing = true"><template #icon><Pencil :size="13" /></template>修订</BaseButton>
        <BaseButton variant="danger" size="small" :disabled="Boolean(pending)" @click="rejecting = true"><template #icon><X :size="13" /></template>拒绝</BaseButton>
        <BaseButton size="small" :loading="pending === 'confirm'" :disabled="otherCommandPending" :aria-describedby="otherCommandPending ? 'intent-confirm-reason' : undefined" @click="emit('confirm')"><template #icon><Check :size="13" /></template>预检并确认</BaseButton>
      </footer>
      <p v-else-if="intent.decision" class="decision">{{ statusText(intent.status) }} · {{ intent.decision.decidedAt }}<template v-if="intent.decision.reason"> · {{ intent.decision.reason }}</template></p>
    </template>
  </section>
</template>

<style scoped>
.intent-card { max-width: 740px; padding: var(--cs-space-16); margin: 0 auto var(--cs-space-16); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); box-shadow: var(--cs-shadow-raised); }.intent-card > header { display: grid; grid-template-columns: 34px 1fr auto; align-items: center; gap: var(--cs-space-12); margin-bottom: var(--cs-space-12); }.intent-card > header > span { display: grid; width: 34px; height: 34px; place-items: center; border-radius: 10px; background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); }.intent-card header p { margin: 0 0 var(--cs-space-2); color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); letter-spacing: .07em; text-transform: uppercase; }.intent-card h3 { margin: 0; font-size: var(--cs-text-base); }.objective, .criteria { padding: var(--cs-space-12); border-top: 1px solid var(--cs-border); }.objective > span, .criteria > span, .revision-form label > span, .reject-form label > span { color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); text-transform: uppercase; }.objective p { margin: var(--cs-space-4) 0 0; font-size: var(--cs-text-sm); line-height: var(--cs-leading-normal); }.criteria ol { display: grid; gap: var(--cs-space-4); padding-left: var(--cs-space-20); margin: var(--cs-space-8) 0 0; color: var(--cs-text-secondary); font-size: var(--cs-text-sm); }.intent-card dl { display: grid; grid-template-columns: 1fr 1fr; gap: var(--cs-space-2); margin: 0; background: var(--cs-border); }.intent-card dl div { min-width: 0; padding: var(--cs-space-8) var(--cs-space-12); background: var(--cs-surface-subtle); }.intent-card dt { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.intent-card dd { overflow: hidden; margin: var(--cs-space-4) 0 0; color: var(--cs-text-secondary); font-size: var(--cs-text-xs); text-overflow: ellipsis; white-space: nowrap; }.intent-card > footer, .revision-form footer { display: flex; justify-content: flex-end; gap: var(--cs-space-8); margin-top: var(--cs-space-12); }.notice { padding: var(--cs-space-8) var(--cs-space-12); margin: var(--cs-space-12) 0 0; border-radius: var(--cs-radius-sm); background: var(--cs-warning-soft); color: var(--cs-warning); font-size: var(--cs-text-xs); }.notice.coding-continuation { background: var(--cs-surface-accent); color: var(--cs-text-brand); }.notice.conflict { background: var(--cs-info-soft); color: var(--cs-info); }.error { margin: var(--cs-space-8) 0 0; color: var(--cs-danger); font-size: var(--cs-text-xs); }.revision-form { display: grid; gap: var(--cs-space-12); }.revision-form label, .reject-form label { display: grid; gap: var(--cs-space-4); }.revision-form small { color: var(--cs-text-muted); font-size: var(--cs-text-xs); text-transform: none; }.revision-form textarea, .revision-form input, .revision-form select, .reject-form textarea { width: 100%; padding: var(--cs-space-8) var(--cs-space-8); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); color: var(--cs-text); font: var(--cs-text-base)/var(--cs-leading-normal) var(--cs-font-sans); }.revision-form textarea:focus, .revision-form input:focus, .revision-form select:focus, .reject-form textarea:focus { border-color: var(--cs-border-accent-strong); outline: 3px solid var(--cs-ring-brand); }.field-grid { display: grid; grid-template-columns: 1fr 1fr; align-items: start; gap: var(--cs-space-8); }.reject-form { display: grid; gap: var(--cs-space-8); padding: var(--cs-space-12); margin-top: var(--cs-space-12); border: 1px solid var(--cs-danger-border); border-radius: var(--cs-radius-sm); background: var(--cs-danger-soft); }.reject-form > div { display: flex; justify-content: flex-end; gap: var(--cs-space-8); }.decision { margin: var(--cs-space-12) 0 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
@media (max-width: 600px) { .field-grid, .intent-card dl { grid-template-columns: 1fr; }.intent-card > footer { flex-wrap: wrap; } }
.intent-summary-footer { display: flex; justify-content: flex-end; margin-top: var(--cs-space-12); }
</style>
