<script setup lang="ts">
import { Bot, ShieldCheck, X } from '@lucide/vue'
import { computed, nextTick, onMounted, reactive, ref, useTemplateRef } from 'vue'
import { isTopmostModal } from '../../app/dialog'
import type { CodingScope, CodingTargetSelection } from '../../domains/coding/types'
import type { CreateTaskInput } from '../../domains/task/types'
import type { ResponsibilityAssignment, WorkItemSummary } from '../../domains/workitem/types'
import BaseButton from '../base/BaseButton.vue'
import CodingTargetFormSection from './CodingTargetFormSection.vue'

const props = defineProps<{
  workItem: WorkItemSummary
  codingScope: CodingScope
  responsibilities: ResponsibilityAssignment[]
  submitting: boolean
  retryable: boolean
  errorMessage: string | null
  conversationSource?: { conversationId: string, messageId: string } | null
  onSubmit: (input: CreateTaskInput) => Promise<void>
  onRetry: () => Promise<void>
}>()

const emit = defineEmits<{ close: [] }>()
const dialog = useTemplateRef<HTMLElement>('dialog')
const objectiveInput = ref<HTMLInputElement | null>(null)
const submitted = ref(false)
const codingSelection = ref<CodingTargetSelection | null>(null)
const codingValid = ref(false)
const form = reactive({
  objective: props.workItem.title,
  acceptanceCriteria: props.workItem.description?.trim() || '完成工作项目标并提供可验证结果',
})
const owner = computed(() => props.responsibilities.find(item => item.role === 'OWNER') ?? null)
const executor = computed(() => props.responsibilities.find(item =>
  item.role === 'EXECUTOR'
  && ['PERSONAL_AGENT', 'TEAM_AGENT'].includes(item.actorType)
  && item.actorAgentProfileId,
) ?? null)
const criteria = computed(() => form.acceptanceCriteria.split('\n').map(value => value.trim()).filter(Boolean))
const valid = computed(() => form.objective.trim().length > 0
  && criteria.value.length > 0
  && Boolean(executor.value)
  && codingValid.value)

onMounted(() => void nextTick(() => (objectiveInput.value ?? dialog.value)?.focus()))

function requestClose(): void {
  if (!props.submitting) emit('close')
}

function handleDialogKeydown(event: KeyboardEvent): void {
  if (!isTopmostModal(dialog.value)) return
  event.stopPropagation()
  if (event.key === 'Escape') {
    event.preventDefault()
    requestClose()
    return
  }
  if (event.key !== 'Tab' || !dialog.value) return
  const controls = [...dialog.value.querySelectorAll<HTMLElement>(
    'button:not(:disabled), input:not(:disabled), textarea:not(:disabled)',
  )]
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

async function submit(): Promise<void> {
  submitted.value = true
  if (!valid.value || !executor.value?.actorAgentProfileId) return
  await props.onSubmit({
    objective: form.objective.trim(),
    acceptanceCriteria: criteria.value,
    executorAgentProfileId: executor.value.actorAgentProfileId,
    conversationSource: props.conversationSource ?? null,
    providerBindingIds: [],
    codingTarget: codingSelection.value ? plainCodingTarget(codingSelection.value) : null,
  })
}

function codingChanged(selection: CodingTargetSelection | null, validSelection: boolean): void {
  codingSelection.value = selection
  codingValid.value = validSelection
}

function plainCodingTarget(selection: CodingTargetSelection): CodingTargetSelection {
  // The Task Store snapshots commands with structuredClone; Vue Proxies must not cross that boundary.
  return {
    repositoryBindingId: selection.repositoryBindingId,
    baselineRef: selection.baselineRef,
    allowedPaths: [...selection.allowedPaths],
    buildProfile: { ...selection.buildProfile },
  }
}
</script>

<template>
  <div class="delegate-backdrop" @click.self="requestClose">
    <form ref="dialog" class="delegate-dialog panel" role="dialog" aria-modal="true" aria-labelledby="delegate-title" tabindex="-1" @submit.prevent="submit" @keydown="handleDialogKeydown">
      <header><span class="delegate-icon"><Bot :size="19" /></span><div><p class="eyebrow">Durable Task · {{ workItem.key }}</p><h2 id="delegate-title">交给 Agent 处理</h2><span>确认目标、验收标准和责任链后创建可审计 Task。</span></div><button type="button" aria-label="关闭交给 Agent 对话框" :disabled="submitting" @click="requestClose"><X :size="18" /></button></header>
      <section class="responsibility-preview" aria-label="责任预览"><ShieldCheck :size="17" /><div><strong>Owner · {{ owner?.actorDisplayName ?? '未配置' }}</strong><span>Executor · {{ executor?.actorDisplayName ?? '需要 Personal Agent 或 Team Agent' }}</span></div></section>
      <p v-if="conversationSource" class="conversation-source-note">来源保留为当前 Conversation 消息；创建后可从对话和工作项双向查看 Task。</p>
      <CodingTargetFormSection
        v-if="executor"
        :scope="codingScope"
        :work-item-id="workItem.id"
        :disabled="submitting || retryable"
        @change="codingChanged"
      />
      <div v-if="executor" class="delegate-fields">
        <label><span>执行目标</span><input ref="objectiveInput" v-model="form.objective" maxlength="2000" :disabled="submitting || retryable" :aria-invalid="submitted && !form.objective.trim()"></label>
        <label><span>验收标准 <small>每行一项</small></span><textarea v-model="form.acceptanceCriteria" rows="5" maxlength="8000" :disabled="submitting || retryable" :aria-invalid="submitted && criteria.length === 0" /></label>
        <p>Coding Task 固化当前 Repository、Baseline Commit、Allowed Paths 与精确 BuildProfile；通用任务不创建 CodingTargetSnapshot。</p>
      </div>
      <p v-else class="delegate-unavailable">当前责任链没有 ACTIVE Personal Agent 或 Team Agent Executor。请先在责任链中分配 Agent。</p>
      <p v-if="errorMessage" class="delegate-error" role="alert">{{ errorMessage }}</p>
      <footer><BaseButton type="button" variant="ghost" :disabled="submitting" @click="requestClose">取消</BaseButton><BaseButton v-if="retryable" type="button" :loading="submitting" @click="onRetry">使用原请求重试</BaseButton><BaseButton v-else type="submit" :loading="submitting" :disabled="!valid">创建 Task</BaseButton></footer>
    </form>
  </div>
</template>

<style scoped>
.delegate-backdrop { position: fixed; inset: 0; z-index: 80; display: grid; place-items: center; padding: 18px; background: rgb(21 35 29 / 34%); backdrop-filter: blur(3px); }.delegate-dialog { width: min(720px, 100%); max-height: calc(100vh - 36px); overflow-y: auto; box-shadow: var(--cs-shadow-float); }.delegate-dialog > header { display: grid; grid-template-columns: 42px minmax(0, 1fr) 32px; align-items: start; gap: 11px; padding: 20px; border-bottom: 1px solid var(--cs-border); }.delegate-icon { display: grid; width: 42px; height: 42px; place-items: center; border-radius: 12px; background: var(--cs-agent-soft); color: var(--cs-agent); }.delegate-dialog h2 { margin: 0 0 3px; font-size: 18px; }.delegate-dialog header div > span { color: var(--cs-text-muted); font-size: 10px; }.delegate-dialog header button { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 8px; background: var(--cs-surface-subtle); cursor: pointer; }.responsibility-preview { display: flex; align-items: center; gap: 10px; margin: 16px 20px 0; padding: 12px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-brand-50); color: var(--cs-brand-700); }.responsibility-preview strong, .responsibility-preview span { display: block; }.responsibility-preview strong { font-size: 10px; }.responsibility-preview span { margin-top: 2px; color: var(--cs-text-muted); font-size: 9px; }.delegate-fields { display: grid; gap: 13px; padding: 16px 20px 4px; }.delegate-fields label { display: grid; gap: 5px; color: var(--cs-text-secondary); font-size: 9px; font-weight: 750; }.delegate-fields small { color: var(--cs-text-muted); font-weight: 500; }.delegate-fields input, .delegate-fields textarea { width: 100%; min-height: 36px; padding: 8px 10px; border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-text); font: 10px var(--cs-font-sans); }.delegate-fields textarea { resize: vertical; }.delegate-fields [aria-invalid="true"] { border-color: var(--cs-danger); }.delegate-fields p, .delegate-unavailable { margin: 0; color: var(--cs-text-muted); font-size: 9px; line-height: 1.5; }.delegate-unavailable { margin: 16px 20px 0; padding: 13px; border-radius: var(--cs-radius-md); background: var(--cs-warning-soft); color: var(--cs-warning); }.delegate-error { margin: 12px 20px 0; color: var(--cs-danger); font-size: 10px; }.delegate-dialog > footer { display: flex; justify-content: flex-end; gap: 7px; padding: 17px 20px 20px; }
.conversation-source-note { margin: 9px 20px 0; padding: 8px 10px; border-radius: 8px; background: var(--cs-agent-soft); color: var(--cs-agent); font-size: 9px; }
@media (max-width: 767px) { .delegate-backdrop { align-items: end; padding: 0; }.delegate-dialog { width: 100%; max-height: 92vh; border-radius: 18px 18px 0 0; }.delegate-dialog > header { padding: 17px 16px; }.responsibility-preview, .conversation-source-note { margin-inline: 16px; }.delegate-fields { padding-inline: 16px; }.delegate-dialog > footer { display: grid; padding-inline: 16px; }.delegate-dialog > footer > * { width: 100%; } }
</style>
