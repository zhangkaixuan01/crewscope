<script setup lang="ts">
import { Bot, RefreshCw, ShieldAlert, UserPlus } from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import type { ResponsibilityCommand, WorkItemPhase } from '../../domains/workitem/store'
import type { ResponsibilityAssignment } from '../../domains/workitem/types'
import BaseButton from '../base/BaseButton.vue'
import ResponsibilityChain from './ResponsibilityChain.vue'

export interface ResponsibilityCandidate {
  principalId: string
  displayName: string
}

export interface ResponsibilityAgentCandidate extends ResponsibilityCandidate {
  ownershipType: 'USER' | 'TEAM' | 'ORGANIZATION'
  runtimeRole: string
}

const props = defineProps<{
  phase: WorkItemPhase
  members: ResponsibilityAssignment[]
  candidates: ResponsibilityCandidate[]
  agentCandidates: ResponsibilityAgentCandidate[]
  agentPhase: 'idle' | 'loading' | 'ready' | 'empty' | 'error'
  agentErrorMessage: string | null
  agentLoadingMore: boolean
  agentHasMore: boolean
  errorMessage: string | null
  commandPending: ResponsibilityCommand | null
  commandErrorMessage: string | null
  canManage: boolean
  onRetry: () => void
  onReplaceOwner: (actorPrincipalId: string) => Promise<void>
  onAssignExecutor: (actorPrincipalId: string) => Promise<void>
  onAssignGateReviewer: (actorPrincipalId: string) => Promise<void>
  onAssignAdvisoryReviewer: (actorPrincipalId: string) => Promise<void>
  onRelease: (assignment: ResponsibilityAssignment) => Promise<void>
  onRetryAgents: () => void
  onLoadMoreAgents: () => void
}>()

const ownerPrincipalId = ref('')
const executorPrincipalId = ref('')
const gateReviewerPrincipalId = ref('')
const directoryExecutorPrincipalId = ref('')
const directoryAdvisoryPrincipalId = ref('')
const executorAgentPrincipalId = ref('')
const advisoryAgentPrincipalId = ref('')

const currentOwner = computed(() => props.members.find(member => member.role === 'OWNER') ?? null)
const humanExecutors = computed(() => props.members.filter(member => member.role === 'EXECUTOR' && member.actorType === 'USER'))
const gateReviewerConflict = computed(() => {
  const candidate = gateReviewerPrincipalId.value
  if (!candidate) return false
  return currentOwner.value?.actorPrincipalId === candidate
    || humanExecutors.value.some(member => member.actorPrincipalId === candidate)
})
const executorAgents = computed(() => props.agentCandidates.filter(candidate =>
  !props.members.some(member => member.role === 'EXECUTOR' && member.actorPrincipalId === candidate.principalId),
))
const advisoryAgents = computed(() => props.agentCandidates.filter(candidate =>
  candidate.runtimeRole === 'SPECIALIST'
  && !props.members.some(member => member.role === 'REVIEWER' && member.actorPrincipalId === candidate.principalId),
))

watch(
  () => [props.candidates, props.members] as const,
  () => {
    ownerPrincipalId.value = candidateExcept(currentOwner.value?.actorPrincipalId)
    executorPrincipalId.value = candidateExcept(...props.members.filter(member => member.role === 'EXECUTOR').map(member => member.actorPrincipalId))
    gateReviewerPrincipalId.value = candidateExcept(...props.members.filter(member => member.role === 'REVIEWER' && member.actorType === 'USER').map(member => member.actorPrincipalId))
  },
  { immediate: true, deep: true },
)

watch(
  () => [props.agentCandidates, props.members] as const,
  () => {
    directoryExecutorPrincipalId.value = executorAgents.value[0]?.principalId ?? ''
    directoryAdvisoryPrincipalId.value = advisoryAgents.value[0]?.principalId ?? ''
  },
  { immediate: true, deep: true },
)

function candidateExcept(...excluded: Array<string | undefined>): string {
  return props.candidates.find(candidate => !excluded.includes(candidate.principalId))?.principalId ?? ''
}

function agentLabel(candidate: ResponsibilityAgentCandidate): string {
  const ownership = candidate.ownershipType === 'USER' ? '个人' : candidate.ownershipType === 'TEAM' ? '团队' : '组织'
  const role = candidate.runtimeRole === 'PERSONAL_ASSISTANT'
    ? 'Personal Agent'
    : candidate.runtimeRole === 'TEAM_COORDINATOR'
      ? 'Team Agent'
      : 'Specialist'
  return `${candidate.displayName} · ${ownership} ${role}`
}

async function submit(action: () => Promise<void>, clear?: () => void): Promise<void> {
  try {
    await action()
    clear?.()
  } catch {
    // Policy and concurrency errors are sanitized by the Store after refreshing the chain.
  }
}
</script>

<template>
  <div class="responsibility-panel">
    <div v-if="phase === 'loading' && members.length === 0" class="responsibility-state" aria-live="polite">正在加载责任链…</div>
    <div v-else-if="phase === 'error' && members.length === 0" class="responsibility-state error" role="alert">
      <span>{{ errorMessage }}</span><button type="button" @click="onRetry"><RefreshCw :size="12" />重试</button>
    </div>
    <p v-else-if="phase === 'empty'" class="responsibility-state">当前还没有有效责任。</p>
    <ResponsibilityChain
      v-else
      :members="members"
      :can-manage="canManage"
      :command-pending="commandPending"
      :on-release="onRelease"
    />

    <p v-if="commandErrorMessage" class="responsibility-error" role="alert">{{ commandErrorMessage }}</p>

    <div v-if="canManage" class="responsibility-actions">
      <form @submit.prevent="submit(() => onReplaceOwner(ownerPrincipalId))">
        <label><span>替换 Owner</span><select v-model="ownerPrincipalId" aria-label="新 Owner"><option value="" disabled>选择团队成员</option><option v-for="candidate in candidates" :key="candidate.principalId" :value="candidate.principalId">{{ candidate.displayName }}</option></select></label>
        <BaseButton type="submit" size="small" variant="secondary" :disabled="!ownerPrincipalId || ownerPrincipalId === currentOwner?.actorPrincipalId" :loading="commandPending === 'owner'">替换</BaseButton>
      </form>

      <form @submit.prevent="submit(() => onAssignExecutor(executorPrincipalId))">
        <label><span>添加团队 Executor</span><select v-model="executorPrincipalId" aria-label="团队 Executor"><option value="" disabled>选择团队成员</option><option v-for="candidate in candidates" :key="candidate.principalId" :value="candidate.principalId">{{ candidate.displayName }}</option></select></label>
        <BaseButton type="submit" size="small" variant="secondary" :disabled="!executorPrincipalId" :loading="commandPending === 'executor'">添加</BaseButton>
      </form>

      <form @submit.prevent="submit(() => onAssignGateReviewer(gateReviewerPrincipalId))">
        <label><span>添加 Gate Reviewer</span><select v-model="gateReviewerPrincipalId" aria-label="Gate Reviewer"><option value="" disabled>选择团队成员</option><option v-for="candidate in candidates" :key="candidate.principalId" :value="candidate.principalId">{{ candidate.displayName }}</option></select></label>
        <BaseButton type="submit" size="small" variant="secondary" :disabled="!gateReviewerPrincipalId" :loading="commandPending === 'gate-reviewer'">添加</BaseButton>
        <p v-if="gateReviewerConflict" class="policy-warning"><ShieldAlert :size="12" />该成员已是 Owner 或 Executor，默认职责分离策略会拒绝；最终资格由服务端 PolicyPack 裁决。</p>
      </form>

      <section class="agent-directory" aria-labelledby="responsibility-agent-directory-title">
        <header><div><Bot :size="14" /><strong id="responsibility-agent-directory-title">Agent 目录</strong></div><span>来自当前 Team 与 Workspace 的 ACTIVE Agent</span></header>
        <div v-if="agentPhase === 'loading' || agentPhase === 'idle'" class="agent-directory-state" aria-live="polite">正在加载可分配 Agent…</div>
        <div v-else-if="agentPhase === 'error'" class="agent-directory-state error" role="alert"><span>{{ agentErrorMessage ?? 'Agent 目录暂时不可用' }}</span><button type="button" @click="onRetryAgents"><RefreshCw :size="12" />重试</button></div>
        <div v-else-if="agentCandidates.length === 0" class="agent-directory-state">当前已加载目录页没有可分配的 ACTIVE Agent。{{ agentHasMore ? '可继续加载下一页。' : '请先在 Agent 设置中创建或启用 Agent。' }}</div>
        <template v-else>
          <form v-if="executorAgents.length > 0" @submit.prevent="submit(() => onAssignExecutor(directoryExecutorPrincipalId))">
            <label><span>Agent Executor</span><select v-model="directoryExecutorPrincipalId" aria-label="Agent Executor"><option value="" disabled>选择 Agent</option><option v-for="candidate in executorAgents" :key="candidate.principalId" :value="candidate.principalId">{{ agentLabel(candidate) }}</option></select></label>
            <BaseButton type="submit" size="small" variant="secondary" :disabled="!directoryExecutorPrincipalId" :loading="commandPending === 'executor'">添加</BaseButton>
          </form>
          <p v-else class="agent-directory-state">已加载的 Agent 均已承担 Executor；{{ agentHasMore ? '可继续加载下一页。' : '可在 Agent 设置中创建或启用其他 Agent。' }}</p>
          <form v-if="advisoryAgents.length > 0" @submit.prevent="submit(() => onAssignAdvisoryReviewer(directoryAdvisoryPrincipalId))">
            <label><span>Advisory Reviewer</span><select v-model="directoryAdvisoryPrincipalId" aria-label="Advisory Reviewer Agent"><option value="" disabled>选择 Specialist</option><option v-for="candidate in advisoryAgents" :key="candidate.principalId" :value="candidate.principalId">{{ agentLabel(candidate) }}</option></select></label>
            <BaseButton type="submit" size="small" variant="secondary" :disabled="!directoryAdvisoryPrincipalId" :loading="commandPending === 'advisory-reviewer'">添加</BaseButton>
          </form>
          <p v-else class="agent-directory-state">已加载目录页没有可担任 Advisory Reviewer 的 Specialist Agent。{{ agentHasMore ? '可继续加载下一页。' : '' }}</p>
        </template>
        <button v-if="!['idle', 'loading', 'error'].includes(agentPhase) && agentHasMore" type="button" class="agent-directory-more" :disabled="agentLoadingMore" @click="onLoadMoreAgents">{{ agentLoadingMore ? '正在加载…' : '加载更多 Agent' }}</button>
      </section>

      <details class="agent-assignment">
        <summary><Bot :size="13" />高级：手动使用 Agent Principal ID</summary>
        <form @submit.prevent="submit(() => onAssignExecutor(executorAgentPrincipalId.trim()), () => { executorAgentPrincipalId = '' })">
          <label><span>Agent Executor</span><input v-model="executorAgentPrincipalId" aria-label="Executor Agent Principal ID" placeholder="Team Agent Principal ID"></label>
          <BaseButton type="submit" size="small" variant="secondary" :disabled="!executorAgentPrincipalId.trim()" :loading="commandPending === 'executor'">添加</BaseButton>
        </form>
        <form @submit.prevent="submit(() => onAssignAdvisoryReviewer(advisoryAgentPrincipalId.trim()), () => { advisoryAgentPrincipalId = '' })">
          <label><span>Advisory Reviewer</span><input v-model="advisoryAgentPrincipalId" aria-label="Advisory Agent Principal ID" placeholder="Specialist Agent Principal ID"></label>
          <BaseButton type="submit" size="small" variant="secondary" :disabled="!advisoryAgentPrincipalId.trim()" :loading="commandPending === 'advisory-reviewer'">添加</BaseButton>
        </form>
        <p>仅在目标 Agent 未出现在当前目录页时使用。服务端仍校验 Principal 类型、Team Scope、Workspace 和 Agent 状态。</p>
      </details>
    </div>

    <p v-else class="responsibility-policy"><UserPlus :size="13" />当前账号可查看责任链；责任调整需要 Responsibility Manage 权限。</p>
  </div>
</template>

<style scoped>
.responsibility-panel { display: grid; gap: 12px; }.responsibility-state { margin: 0; padding: 10px; border-radius: 8px; background: var(--cs-surface-subtle); color: var(--cs-text-muted); font-size: 9px; }.responsibility-state.error { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: var(--cs-danger); }.responsibility-state button { display: inline-flex; align-items: center; gap: 3px; color: inherit; cursor: pointer; }.responsibility-error { margin: 0; color: var(--cs-danger); font-size: 9px; }.responsibility-actions { display: grid; gap: 8px; padding-top: 11px; border-top: 1px solid var(--cs-border); }.responsibility-actions > form, .agent-directory form, .agent-assignment form { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: end; gap: 7px; }.responsibility-actions label, .agent-directory label, .agent-assignment label { display: grid; gap: 4px; color: var(--cs-text-secondary); font-size: 8px; font-weight: 700; }.responsibility-actions select, .agent-directory select, .agent-assignment input { width: 100%; min-height: 34px; padding: 0 9px; border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-text); font: 10px var(--cs-font-sans); }.policy-warning { display: flex; grid-column: 1 / -1; align-items: flex-start; gap: 5px; margin: 0; padding: 7px 8px; border-radius: 7px; background: var(--cs-warning-soft); color: var(--cs-warning); font-size: 8px; line-height: 1.45; }.policy-warning svg { flex: 0 0 auto; }.agent-directory { display: grid; gap: 8px; padding: 9px; border: 1px solid color-mix(in srgb, var(--cs-agent) 30%, var(--cs-border)); border-radius: 9px; background: color-mix(in srgb, var(--cs-agent) 4%, var(--cs-surface-subtle)); }.agent-directory header { display: flex; align-items: center; justify-content: space-between; gap: 8px; }.agent-directory header > div { display: flex; align-items: center; gap: 5px; color: var(--cs-agent); }.agent-directory header span, .agent-directory-state { color: var(--cs-text-muted); font-size: 8px; }.agent-directory-state { padding: 7px; border-radius: 7px; background: var(--cs-surface); }.agent-directory-state.error { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: var(--cs-danger); }.agent-directory-state button, .agent-directory-more { display: inline-flex; align-items: center; gap: 4px; color: inherit; font-size: 8px; cursor: pointer; }.agent-directory-more { justify-self: start; color: var(--cs-agent); }.agent-assignment { padding: 9px; border: 1px dashed var(--cs-border-strong); border-radius: 9px; background: var(--cs-surface-subtle); }.agent-assignment summary { display: flex; align-items: center; gap: 5px; color: var(--cs-agent); font-size: 9px; font-weight: 750; cursor: pointer; }.agent-assignment[open] summary { margin-bottom: 9px; }.agent-assignment form + form { margin-top: 7px; }.agent-assignment > p { margin: 8px 0 0; color: var(--cs-text-muted); font-size: 8px; line-height: 1.45; }.responsibility-policy { display: flex; align-items: center; gap: 5px; margin: 0; color: var(--cs-text-muted); font-size: 9px; }
@media (max-width: 480px) { .responsibility-actions > form, .agent-directory form, .agent-assignment form { grid-template-columns: 1fr; }.responsibility-actions button, .agent-directory button, .agent-assignment button { width: 100%; }.agent-directory header { align-items: flex-start; flex-direction: column; }.policy-warning { grid-column: 1; } }
</style>
