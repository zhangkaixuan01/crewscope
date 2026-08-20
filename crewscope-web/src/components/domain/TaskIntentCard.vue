<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { Check, ClipboardCheck, Pencil, X } from '@lucide/vue'
import type { TaskIntent, TaskIntentRevisionInput } from '../../domains/conversation/types'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'

const props = defineProps<{
  intent: TaskIntent
  currentPrincipalId: string
  pending?: 'revise' | 'reject' | 'confirm' | null
  errorMessage?: string | null
  versionConflict?: boolean
}>()

const emit = defineEmits<{
  revise: [input: TaskIntentRevisionInput]
  reject: [reason: string]
  confirm: []
}>()

const editing = ref(false)
const rejecting = ref(false)
const localError = ref<string | null>(null)
const form = reactive({ objective: '', criteria: '', workProjectId: '', ownerMemberId: '', executorPrincipalId: '', gateReviewerMemberId: '' })
const rejectionReason = ref('')
const reviewable = computed(() => props.intent.status === 'READY')
const isOwner = computed(() => props.intent.proposal.owner.principalId === props.currentPrincipalId)

watch(() => props.intent, populate, { immediate: true })

function populate(intent: TaskIntent): void {
  form.objective = intent.proposal.objective
  form.criteria = intent.proposal.acceptanceCriteria.join('\n')
  form.workProjectId = intent.proposal.workProjectId
  form.ownerMemberId = intent.proposal.owner.teamMemberId ?? ''
  form.executorPrincipalId = intent.proposal.executor?.principalId ?? ''
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
    executorPrincipalId: form.executorPrincipalId.trim() || null,
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
        <label><span>WorkProject ID</span><input v-model="form.workProjectId" /></label>
        <label><span>Owner Member ID</span><input v-model="form.ownerMemberId" /></label>
        <label><span>Executor Principal ID <small>可选</small></span><input v-model="form.executorPrincipalId" /></label>
        <label><span>Gate Reviewer Member ID <small>可选</small></span><input v-model="form.gateReviewerMemberId" /></label>
      </div>
      <p v-if="localError" class="error" role="alert">{{ localError }}</p>
      <footer><BaseButton variant="ghost" size="small" @click="editing = false">取消</BaseButton><BaseButton type="submit" size="small" :loading="pending === 'revise'">提交完整修订</BaseButton></footer>
    </form>

    <template v-else>
      <div class="objective"><span>目标</span><p>{{ intent.proposal.objective }}</p></div>
      <div class="criteria"><span>验收标准</span><ol><li v-for="criterion in intent.proposal.acceptanceCriteria" :key="criterion">{{ criterion }}</li></ol></div>
      <dl>
        <div><dt>Owner</dt><dd>{{ intent.proposal.owner.principalId }}</dd></div>
        <div><dt>Executor</dt><dd>{{ intent.proposal.executor?.principalId ?? '确认后分配' }}</dd></div>
        <div><dt>Gate Reviewer</dt><dd>{{ intent.proposal.gateReviewer?.principalId ?? '未设置' }}</dd></div>
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
        <BaseButton variant="ghost" size="small" :disabled="Boolean(pending)" @click="editing = true"><template #icon><Pencil :size="13" /></template>修订</BaseButton>
        <BaseButton variant="danger" size="small" :disabled="Boolean(pending)" @click="rejecting = true"><template #icon><X :size="13" /></template>拒绝</BaseButton>
        <BaseButton size="small" :loading="pending === 'confirm'" :disabled="Boolean(pending) && pending !== 'confirm'" @click="emit('confirm')"><template #icon><Check :size="13" /></template>预检并确认</BaseButton>
      </footer>
      <p v-else-if="intent.decision" class="decision">{{ statusText(intent.status) }} · {{ intent.decision.decidedAt }}<template v-if="intent.decision.reason"> · {{ intent.decision.reason }}</template></p>
    </template>
  </section>
</template>

<style scoped>
.intent-card { max-width: 740px; padding: 14px; margin: 0 auto 14px; border: 1px solid #d5e6da; border-radius: var(--cs-radius-md); background: white; box-shadow: 0 6px 18px rgb(27 75 48 / 5%); }.intent-card > header { display: grid; grid-template-columns: 34px 1fr auto; align-items: center; gap: 10px; margin-bottom: 12px; }.intent-card > header > span { display: grid; width: 34px; height: 34px; place-items: center; border-radius: 10px; background: var(--cs-brand-100); color: var(--cs-brand-700); }.intent-card header p { margin: 0 0 2px; color: var(--cs-brand-700); font-size: 8px; font-weight: 800; letter-spacing: .07em; text-transform: uppercase; }.intent-card h3 { margin: 0; font-size: 12px; }.objective, .criteria { padding: 10px; border-top: 1px solid var(--cs-border); }.objective > span, .criteria > span, .revision-form label > span, .reject-form label > span { color: var(--cs-text-muted); font-size: 8px; font-weight: 750; text-transform: uppercase; }.objective p { margin: 5px 0 0; font-size: 11px; line-height: 1.55; }.criteria ol { display: grid; gap: 4px; padding-left: 18px; margin: 6px 0 0; color: var(--cs-text-secondary); font-size: 10px; }.intent-card dl { display: grid; grid-template-columns: 1fr 1fr; gap: 1px; margin: 0; background: var(--cs-border); }.intent-card dl div { min-width: 0; padding: 8px 10px; background: var(--cs-surface-subtle); }.intent-card dt { color: var(--cs-text-muted); font-size: 8px; }.intent-card dd { overflow: hidden; margin: 3px 0 0; color: var(--cs-text-secondary); font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }.intent-card > footer, .revision-form footer { display: flex; justify-content: flex-end; gap: 7px; margin-top: 12px; }.notice { padding: 8px 10px; margin: 10px 0 0; border-radius: var(--cs-radius-sm); background: var(--cs-warning-soft); color: #79511e; font-size: 9px; }.notice.coding-continuation { background: var(--cs-brand-50); color: var(--cs-brand-700); }.notice.conflict { background: var(--cs-info-soft); color: #326597; }.error { margin: 9px 0 0; color: var(--cs-danger); font-size: 9px; }.revision-form { display: grid; gap: 10px; }.revision-form label, .reject-form label { display: grid; gap: 5px; }.revision-form small { color: var(--cs-text-muted); font-size: 7px; text-transform: none; }.revision-form textarea, .revision-form input, .reject-form textarea { width: 100%; padding: 8px 9px; border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); color: var(--cs-text); font: 9px/1.5 var(--cs-font-sans); }.revision-form textarea:focus, .revision-form input:focus, .reject-form textarea:focus { border-color: var(--cs-brand-400); outline: 3px solid var(--cs-brand-100); }.field-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }.reject-form { display: grid; gap: 8px; padding: 10px; margin-top: 10px; border: 1px solid #ecc7c2; border-radius: var(--cs-radius-sm); background: #fff8f7; }.reject-form > div { display: flex; justify-content: flex-end; gap: 7px; }.decision { margin: 10px 0 0; color: var(--cs-text-muted); font-size: 8px; }
@media (max-width: 600px) { .field-grid, .intent-card dl { grid-template-columns: 1fr; }.intent-card > footer { flex-wrap: wrap; } }
</style>
