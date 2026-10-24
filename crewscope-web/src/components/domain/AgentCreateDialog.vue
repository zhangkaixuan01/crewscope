<script setup lang="ts">
import { Bot, Building2, Code2, ShieldCheck, UserRound } from '@lucide/vue'
import { computed, ref, useId, watch } from 'vue'
import type { AgentOwnershipType, AgentTemplateSummary, CreateAgentInput } from '../../domains/agent/types'
import { enumLabel } from '../../domains/shared/labels'
import BaseButton from '../base/BaseButton.vue'
import BaseDialog from '../base/BaseDialog.vue'
import StatePanel from '../feedback/StatePanel.vue'

const props = defineProps<{
  userTemplates: AgentTemplateSummary[]
  teamTemplates: AgentTemplateSummary[]
  loading: boolean
  canManageTeamAgents: boolean
  initialOwnershipType?: Extract<AgentOwnershipType, 'USER' | 'TEAM'>
  submitting: boolean
  retryable: boolean
  errorMessage: string | null
  templateErrorMessage: string | null
}>()

const emit = defineEmits<{
  close: []
  submit: [input: CreateAgentInput]
  retryTemplates: []
}>()

const ownershipType = ref<Extract<AgentOwnershipType, 'USER' | 'TEAM'>>(
  props.initialOwnershipType === 'TEAM' && props.canManageTeamAgents ? 'TEAM' : 'USER',
)
const templateCoordinate = ref('')
const displayName = ref('')
const submitted = ref(false)
const formId = useId()
const visibleTemplates = computed(() => (ownershipType.value === 'USER' ? props.userTemplates : props.teamTemplates)
  // The same catalog serves existing-profile metadata and creation. Only the server's explicit
  // creation contract belongs in this wizard; platform-managed templates remain discoverable.
  .filter(template => template.creatable))
const selectedTemplate = computed(() => visibleTemplates.value.find(template => coordinate(template) === templateCoordinate.value) ?? null)
const valid = computed(() => Boolean(selectedTemplate.value && displayName.value.trim() && displayName.value.trim().length <= 200))

watch(visibleTemplates, templates => {
  if (!templates.some(template => coordinate(template) === templateCoordinate.value)) {
    templateCoordinate.value = templates[0] ? coordinate(templates[0]) : ''
  }
}, { immediate: true })

function coordinate(template: AgentTemplateSummary): string {
  return `${template.publisherType}:${template.publisherId}:${template.key}:${template.version}`
}

/**
 * A template's publisher decides who may reconfigure the Agents created from it, so the member picking
 * a template is told which of the two scopes published it rather than shown the constant.
 */
const publisherTypeLabels: Record<string, string> = { ORGANIZATION: '组织', TEAM: '团队' }
function publisherLabel(value: string): string { return enumLabel(value, publisherTypeLabels) }

function templateLabel(template: AgentTemplateSummary): string {
  const key = template.key.toLowerCase()
  if (key.includes('coding')) return 'Coding Agent'
  if (key.includes('review')) return 'Reviewer Agent'
  if (key.includes('team-coordinator')) return 'Team Coordinator'
  return template.key
}

function requestClose(): void {
  if (!props.submitting) emit('close')
}

function switchOwnership(value: 'USER' | 'TEAM'): void {
  if (value === 'TEAM' && !props.canManageTeamAgents) return
  ownershipType.value = value
  submitted.value = false
}

function submit(): void {
  submitted.value = true
  const template = selectedTemplate.value
  if (!valid.value || !template) return
  emit('submit', {
    publisherType: template.publisherType,
    templateKey: template.key,
    templateVersion: template.version,
    ownershipType: ownershipType.value,
    displayName: displayName.value.trim(),
  })
}
</script>

<template>
  <BaseDialog
    open
    title="创建执行 Agent"
    eyebrow="Approved template"
    subtitle="从服务端批准的 Template 创建稳定身份，随后再配置模型与受控偏好。"
    width="min(690px, 100%)"
    :close-disabled="submitting"
    close-label="关闭创建 Agent"
    @close="requestClose"
  >
    <template #icon><Bot :size="20" aria-hidden="true" /></template>
    <form :id="formId" class="agent-create-content" @submit.prevent="submit">
      <fieldset class="ownership-picker">
        <legend>所有权</legend>
        <button type="button" :class="{ active: ownershipType === 'USER' }" :aria-pressed="ownershipType === 'USER'" @click="switchOwnership('USER')">
          <UserRound :size="18" /><span><strong>个人 Agent</strong><small>执行你的个人任务，可使用当前授权的 USER/TEAM/Organization 模型连接。</small></span>
        </button>
        <button type="button" :disabled="!canManageTeamAgents" aria-label="团队 Agent" aria-describedby="agent-ownership-team-reason" :class="{ active: ownershipType === 'TEAM' }" :aria-pressed="ownershipType === 'TEAM'" @click="switchOwnership('TEAM')">
          <Building2 :size="18" /><span><strong>团队 Agent</strong><small id="agent-ownership-team-reason">{{ canManageTeamAgents ? '团队共享身份，只使用 TEAM/Organization 连接。' : '需要 Team Agent 管理权限。' }}</small></span>
        </button>
      </fieldset>

      <StatePanel v-if="loading" state="loading" compact title="正在读取批准模板" />
      <StatePanel
        v-else-if="templateErrorMessage"
        state="error"
        compact
        title="批准模板暂时不可用"
        :description="templateErrorMessage"
        @retry="emit('retryTemplates')"
      />
      <StatePanel
        v-else-if="visibleTemplates.length === 0"
        state="empty"
        compact
        title="没有可用模板"
        description="当前所有权下没有 ACTIVE 且允许实例化的 AgentTemplate。"
      />
      <fieldset v-else class="template-picker">
        <legend>Agent Template</legend>
        <label v-for="template in visibleTemplates" :key="coordinate(template)" :class="{ active: templateCoordinate === coordinate(template) }">
          <input v-model="templateCoordinate" type="radio" :value="coordinate(template)" />
          <Code2 :size="19" aria-hidden="true" />
          <span><strong>{{ templateLabel(template) }}</strong><small class="mono">{{ template.key }}@{{ template.version }}</small><small>{{ publisherLabel(template.publisherType) }}发布</small><small>{{ template.declaredCapabilities.join(' · ') || '固定受控能力' }}</small></span>
        </label>
      </fieldset>

      <label class="name-field">
        <span>显示名称</span>
        <input v-model="displayName" maxlength="200" autocomplete="off" placeholder="例如：我的 Java Coding Agent" autofocus :aria-invalid="submitted && !displayName.trim()" :disabled="submitting" />
        <small>名称用于团队识别；Principal、Ownership、RuntimeRole 与 Workspace 由服务端从 Template 固化。</small>
      </label>

      <section v-if="selectedTemplate" class="template-boundary" aria-label="Template 安全边界">
        <ShieldCheck :size="17" aria-hidden="true" />
        <div><strong>固定边界</strong><span>需要模型能力：{{ selectedTemplate.requiredModelCapabilities.join('、') || '无额外要求' }}。System Prompt、Tool 和 Structured Output Schema 不由浏览器提交。</span></div>
      </section>
      <p v-if="errorMessage" class="command-error" role="alert">{{ errorMessage }}</p>
    </form>

    <template #footer>
      <BaseButton type="button" variant="ghost" :disabled="submitting" @click="requestClose">取消</BaseButton>
      <BaseButton type="submit" :form="formId" :loading="submitting" :disabled="!valid">{{ retryable ? '使用原请求重试' : '创建 Agent' }}</BaseButton>
    </template>
  </BaseDialog>
</template>

<style scoped>
.agent-create-content { display: grid; gap: var(--cs-space-16); }
.ownership-picker, .template-picker { display: grid; grid-template-columns: repeat(2, 1fr); gap: var(--cs-space-8); padding: 0; border: 0; margin: 0; }.ownership-picker legend, .template-picker legend, .name-field > span { grid-column: 1 / -1; margin-bottom: var(--cs-space-2); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }.ownership-picker button, .template-picker label { display: grid; grid-template-columns: 20px 1fr; align-items: start; gap: var(--cs-space-8); padding: var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); color: var(--cs-text); text-align: left; cursor: pointer; }.ownership-picker button.active, .template-picker label.active { border-color: var(--cs-border-accent-strong); background: var(--cs-surface-accent); box-shadow: 0 0 0 2px var(--cs-ring-brand); }.ownership-picker button:disabled { cursor: not-allowed; opacity: .55; }.ownership-picker strong, .ownership-picker small, .template-picker strong, .template-picker small { display: block; }.ownership-picker strong, .template-picker strong { font-size: var(--cs-text-sm); }.ownership-picker small, .template-picker small { margin-top: var(--cs-space-4); color: var(--cs-text-muted); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); }.template-picker label { grid-template-columns: 16px 20px 1fr; }.template-picker label > input { margin-top: var(--cs-space-2); }.name-field { display: grid; gap: var(--cs-space-8); }.name-field input { width: 100%; min-height: 40px; padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); }.name-field input[aria-invalid='true'] { border-color: var(--cs-danger); }.name-field small { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.template-boundary { display: flex; align-items: flex-start; gap: var(--cs-space-8); padding: var(--cs-space-12) var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-text-brand); }.template-boundary svg { flex: 0 0 auto; }.template-boundary strong, .template-boundary span { display: block; }.template-boundary strong { font-size: var(--cs-text-sm); }.template-boundary span { margin-top: var(--cs-space-2); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.command-error { margin: 0; color: var(--cs-danger); font-size: var(--cs-text-sm); }
@media (max-width: 767px) { .ownership-picker, .template-picker { grid-template-columns: 1fr; }.ownership-picker legend, .template-picker legend { grid-column: 1; }.name-field input { font-size: var(--cs-text-md); } }

/* M12b-F01：悬停反馈补齐——此前这些可点元素对指针移动完全沉默。 */
.ownership-picker button:hover:not(:disabled) { background: var(--cs-surface-accent); }
.ownership-picker button:active:not(:disabled) { transform: scale(.97); }
.ownership-picker button { transition: background-color var(--cs-motion-fast) var(--cs-ease-out), border-color var(--cs-motion-fast) var(--cs-ease-out), transform var(--cs-motion-fast) var(--cs-ease-out); }
.template-picker label:hover:not(:disabled) { background: var(--cs-surface-accent); }
.template-picker label:active:not(:disabled) { transform: scale(.97); }
.template-picker label { transition: background-color var(--cs-motion-fast) var(--cs-ease-out), border-color var(--cs-motion-fast) var(--cs-ease-out), transform var(--cs-motion-fast) var(--cs-ease-out); }
</style>
