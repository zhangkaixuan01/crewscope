<script setup lang="ts">
import { Bot, Boxes, Cpu, Plus, ShieldCheck, Sparkles } from '@lucide/vue'
import { computed, inject, nextTick, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { AUTH_PRINCIPAL, can, permissions } from '../app/auth'
import BaseButton from '../components/base/BaseButton.vue'
import StatusBadge from '../components/base/StatusBadge.vue'
import AgentConfigurationPanel from '../components/domain/AgentConfigurationPanel.vue'
import AgentCreateDialog from '../components/domain/AgentCreateDialog.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import AppShell from '../components/layout/AppShell.vue'
import { useAgentStore } from '../domains/agent/store'
import type { AgentModelBindingSummary, AgentSummary, AgentTemplateSummary, CreateAgentInput } from '../domains/agent/types'
import { useScopeStore } from '../domains/scope/store'
import { agentSettingsSelection, withAgentSettingsRoute } from '../domains/settings/route'

const route = useRoute()
const router = useRouter()
const principal = inject(AUTH_PRINCIPAL)
const scopeStore = useScopeStore()
const agentStore = useAgentStore()
let activeTeamId: string | null = null
const createOpen = ref(false)
const createTrigger = ref<HTMLElement | null>(null)
const createKey = ref('')
const createSignature = ref('')
type BindingView = AgentModelBindingSummary | null | 'LOADING' | 'UNAVAILABLE' | 'NOT_CONFIGURED'

const team = scopeStore.selectedTeam
const selection = computed(() => agentSettingsSelection(route.query))
const agents = computed(() => agentStore.state.agents.value ?? [])
const personalAgents = computed(() => agents.value.filter(agent => agent.defaultProfile))
const specialistAgents = computed(() => agents.value.filter(agent => agent.ownershipType === 'USER' && !agent.defaultProfile))
const teamAgents = computed(() => agents.value.filter(agent => agent.ownershipType === 'TEAM'))
const agentGroups = computed(() => [
  {
    key: 'personal', title: '默认 Personal Agent',
    description: '每位成员唯一的对话入口，生命周期由平台管理。', agents: personalAgents.value,
  },
  {
    key: 'specialist', title: '我的 Specialist',
    description: '个人创建的 Coding、Reviewer 与其他受批准专业 Agent。', agents: specialistAgents.value,
  },
  {
    key: 'team', title: '团队 Agent',
    description: '团队共享的执行身份，只使用 TEAM 或 Organization 受管连接。', agents: teamAgents.value,
  },
].filter(group => group.agents.length > 0))
const selectedAgent = computed(() => agents.value.find(agent => agent.id === selection.value.agentId) ?? null)
const forbidden = computed(() => agentStore.state.agents.phase === 'error' && agentStore.state.agents.errorStatus === 403)
const canManageTeamAgents = computed(() => Boolean(principal && can(principal, permissions.agentManage)))
const userTemplates = computed(() => agentStore.state.templates.USER?.value ?? [])
const teamTemplates = computed(() => agentStore.state.templates.TEAM?.value ?? [])
const templatesLoading = computed(() => {
  const phases = [agentStore.state.templates.USER?.phase]
  if (canManageTeamAgents.value) phases.push(agentStore.state.templates.TEAM?.phase)
  return phases.some(phase => phase === undefined || phase === 'idle' || phase === 'loading')
})
const templateErrorMessage = computed(() => {
  const resources = [agentStore.state.templates.USER]
  if (canManageTeamAgents.value) resources.push(agentStore.state.templates.TEAM)
  return resources.find(resource => resource?.phase === 'error')?.errorMessage ?? null
})
const selectedTemplate = computed(() => findExactTemplate(selectedAgent.value))
const canConfigureSelected = computed(() => Boolean(selectedAgent.value
  && (selectedAgent.value.ownershipType === 'USER' || canManageTeamAgents.value)))
const createCommand = computed(() => agentStore.state.command.operation === 'create' ? agentStore.state.command : null)

watch(
  () => scopeStore.state.selectedTeamId,
  async teamId => {
    if (!teamId) return
    const teamChanged = activeTeamId !== null && activeTeamId !== teamId
    activeTeamId = teamId
    if (teamChanged) {
      // Agent and Configuration coordinates belong to the previous Team and cannot cross Scope restoration.
      await router.replace({
        name: 'agent-settings',
        query: withAgentSettingsRoute(route.query, { teamId, agentId: null, configurationRevision: null }),
      })
    }
    agentStore.activateScope({ organizationId: team.value?.organizationId ?? '', teamId })
    await Promise.all([loadAgentsAndConfigurations(), loadTemplates()])
  },
  { immediate: true },
)

async function loadAgentsAndConfigurations(force = false, more = false): Promise<void> {
  await agentStore.loadAgents(more, force)
  if (agentStore.state.agents.phase !== 'ready') return
  // Configuration requests remain independently recoverable so one unavailable summary cannot hide the Agent directory.
  await Promise.all((agentStore.state.agents.value ?? [])
    .filter(agent => agent.currentConfigurationRevision !== null)
    .map(agent => agentStore.loadCurrentConfiguration(agent.id, force)))
}

async function loadTemplates(force = false): Promise<void> {
  const requests = [agentStore.loadTemplates('USER', false, force)]
  if (canManageTeamAgents.value) requests.push(agentStore.loadTemplates('TEAM', false, force))
  await Promise.all(requests)
}

function findExactTemplate(agent: AgentSummary | null): AgentTemplateSummary | null {
  if (!agent) return null
  // AgentProfile does not expose publisher coordinates, so ambiguous key/version matches fail closed.
  const matches = [...userTemplates.value, ...teamTemplates.value]
    .filter(template => template.key === agent.templateKey && template.version === agent.templateVersion)
    .filter((template, index, all) => all.findIndex(candidate =>
      candidate.publisherType === template.publisherType
      && candidate.publisherId === template.publisherId
      && candidate.key === template.key
      && candidate.version === template.version) === index)
  return matches.length === 1 ? matches[0]! : null
}

function openCreate(event?: MouseEvent): void {
  if (event?.currentTarget instanceof HTMLElement) createTrigger.value = event.currentTarget
  agentStore.clearCommand()
  createSignature.value = ''
  createKey.value = ''
  createOpen.value = true
}

async function closeCreate(): Promise<void> {
  if (createCommand.value?.phase === 'pending') return
  createOpen.value = false
  await nextTick()
  createTrigger.value?.focus()
}

async function createAgent(input: CreateAgentInput): Promise<void> {
  const signature = JSON.stringify(input)
  if (signature !== createSignature.value) {
    createSignature.value = signature
    createKey.value = crypto.randomUUID()
  }
  const before = new Set(agents.value.map(agent => agent.id))
  const success = await agentStore.createAgent(input, createKey.value)
  if (!success) return
  await loadAgentsAndConfigurations(true)
  const created = agents.value.filter(agent => !before.has(agent.id))
  createOpen.value = false
  if (created.length === 1) await selectAgent(created[0]!)
  else await nextTick(() => createTrigger.value?.focus())
}

async function selectAgent(agent: AgentSummary): Promise<void> {
  await router.push(agentTarget(agent))
}

async function closeConfiguration(): Promise<void> {
  const closedId = selectedAgent.value?.id
  const teamId = scopeStore.state.selectedTeamId
  if (!teamId) return
  await router.push({
    name: 'agent-settings',
    query: withAgentSettingsRoute(route.query, { teamId, agentId: null, configurationRevision: null }),
  })
  await nextTick()
  if (closedId) document.querySelector<HTMLElement>(`[data-agent-id="${closedId}"]`)?.focus()
}

async function selectRevision(revision: number): Promise<void> {
  if (!selectedAgent.value || !scopeStore.state.selectedTeamId) return
  await router.push({
    name: 'agent-settings',
    query: withAgentSettingsRoute(route.query, {
      teamId: scopeStore.state.selectedTeamId,
      agentId: selectedAgent.value.id,
      configurationRevision: revision,
    }),
  })
}

async function refreshSelectedAgent(): Promise<void> {
  const profileId = selection.value.agentId
  await loadAgentsAndConfigurations(true)
  const refreshed = agents.value.find(agent => agent.id === profileId)
  const teamId = scopeStore.state.selectedTeamId
  if (!teamId) return
  await router.replace({
    name: 'agent-settings',
    query: withAgentSettingsRoute(route.query, {
      teamId,
      agentId: refreshed?.id ?? null,
      configurationRevision: refreshed?.currentConfigurationRevision ?? null,
    }),
  })
}

function agentTarget(agent: AgentSummary) {
  return {
    name: 'agent-settings',
    query: withAgentSettingsRoute(route.query, {
      teamId: agent.teamId!,
      agentId: agent.id,
      configurationRevision: agent.currentConfigurationRevision,
    }),
  }
}

function statusTone(agent: AgentSummary): 'success' | 'warning' | 'neutral' {
  if (agent.status === 'ACTIVE' && agent.principalStatus === 'ACTIVE') return 'success'
  if (agent.status === 'DISABLED' || agent.principalStatus === 'DISABLED') return 'warning'
  return 'neutral'
}

function statusLabel(agent: AgentSummary): string {
  if (agent.status === 'ACTIVE' && agent.principalStatus === 'ACTIVE') return '运行中'
  if (agent.status === 'DISABLED' || agent.principalStatus === 'DISABLED') return '已禁用'
  if (agent.status === 'ARCHIVED' || agent.principalStatus === 'ARCHIVED') return '已归档'
  return agent.status
}

function roleLabel(agent: AgentSummary): string {
  if (agent.defaultProfile) return 'Personal Agent'
  if (agent.runtimeRole === 'CODING') return 'Coding Specialist'
  if (agent.runtimeRole === 'REVIEWER') return 'Reviewer Specialist'
  return agent.runtimeRole.replaceAll('_', ' ')
}

function modelBinding(agent: AgentSummary, scope: 'PERSONAL' | 'TEAM'): BindingView {
  if (agent.currentConfigurationRevision === null) return 'NOT_CONFIGURED'
  const resource = agentStore.state.currentConfigurations[agent.id]
  if (!resource || resource.phase === 'idle' || resource.phase === 'loading') return 'LOADING'
  if (resource.phase !== 'ready' || !resource.value) return 'UNAVAILABLE'
  return scope === 'PERSONAL' ? resource.value.value.personalBinding : resource.value.value.teamBinding
}

function bindingLabel(binding: BindingView): string {
  if (binding === 'LOADING') return '正在读取…'
  if (binding === 'UNAVAILABLE') return '配置摘要暂不可用'
  if (binding === 'NOT_CONFIGURED') return '尚未配置'
  if (!binding?.primary) return '继承受管默认'
  const fallback = binding.fallback?.modelId ? ` · Fallback ${binding.fallback.modelId}` : ''
  return `${binding.primary.modelId}${fallback}`
}
</script>

<template>
  <AppShell eyebrow="Settings · Agent directory" :title="`${team?.name ?? 'Team'} · 我的 Agent`">
    <template #actions>
      <BaseButton size="small" :disabled="!scopeStore.state.selectedTeamId" @click="openCreate"><Plus :size="14" />创建 Agent</BaseButton>
    </template>

    <AgentCreateDialog
      v-if="createOpen"
      :user-templates="userTemplates"
      :team-templates="teamTemplates"
      :loading="templatesLoading"
      :can-manage-team-agents="canManageTeamAgents"
      :submitting="createCommand?.phase === 'pending'"
      :retryable="Boolean(createCommand?.retryable)"
      :error-message="createCommand?.errorMessage ?? null"
      :template-error-message="templateErrorMessage"
      @close="closeCreate"
      @retry-templates="loadTemplates(true)"
      @submit="createAgent"
    />
    <StatePanel v-if="scopeStore.state.phase === 'loading' || !scopeStore.state.selectedTeamId" state="loading" />
    <StatePanel
      v-else-if="forbidden"
      state="forbidden"
      title="无权查看 Agent"
      description="当前成员无权查看这个 Team 的 Agent 目录。"
    />
    <StatePanel
      v-else-if="agentStore.state.agents.phase === 'loading' || agentStore.state.agents.phase === 'idle'"
      state="loading"
      title="正在加载 Agent"
    />
    <StatePanel
      v-else-if="agentStore.state.agents.phase === 'error'"
      state="error"
      :description="agentStore.state.agents.errorMessage ?? undefined"
      @retry="loadAgentsAndConfigurations(true)"
    />
    <StatePanel
      v-else-if="agentStore.state.agents.phase === 'empty'"
      state="empty"
      title="还没有可访问的 Agent"
      description="Team 完成 Personal Agent 初始化后，它会出现在这里。"
    />
    <BaseButton
      v-if="agentStore.state.agents.phase === 'empty'"
      class="empty-mobile-create"
      size="small"
      @click="openCreate"
    ><Plus :size="14" />创建第一个 Agent</BaseButton>

    <div v-else class="agent-page page-shell">
      <section class="agent-overview panel" aria-labelledby="agent-overview-title">
        <div class="overview-copy">
          <span class="overview-icon"><Sparkles :size="23" aria-hidden="true" /></span>
          <div>
            <p class="eyebrow">Agent roster</p>
            <h2 id="agent-overview-title">{{ agents.length }} 个可协作 Agent</h2>
            <p>Personal Agent 承接对话，Specialist 执行专业任务，团队 Agent 使用稳定的 Team 身份和配置。</p>
          </div>
        </div>
        <dl class="agent-totals" aria-label="Agent 分类汇总">
          <div><dt>默认 Personal</dt><dd>{{ personalAgents.length }}</dd></div>
          <div><dt>我的 Specialist</dt><dd>{{ specialistAgents.length }}</dd></div>
          <div><dt>团队 Agent</dt><dd>{{ teamAgents.length }}</dd></div>
        </dl>
        <BaseButton class="mobile-create" size="small" @click="openCreate"><Plus :size="14" />创建 Agent</BaseButton>
      </section>

      <p v-if="selection.agentId && !selectedAgent" class="selection-warning" role="status">
        深链接指向的 Agent 不在当前 Team 可见范围内，列表已保持在安全范围。
      </p>

      <AgentConfigurationPanel
        v-if="selectedAgent"
        :agent="selectedAgent"
        :template="selectedTemplate"
        :can-configure="canConfigureSelected"
        :selected-revision="selection.configurationRevision"
        @close="closeConfiguration"
        @refreshed="refreshSelectedAgent"
        @select-revision="selectRevision"
      />

      <section
        v-for="group in agentGroups"
        :key="group.key"
        class="agent-group panel"
        :aria-labelledby="`agent-group-${group.key}`"
      >
        <header class="panel-heading">
          <div><p class="eyebrow">{{ group.agents.length }} agents</p><h2 :id="`agent-group-${group.key}`">{{ group.title }}</h2><p>{{ group.description }}</p></div>
        </header>
        <ul class="agent-grid" role="list">
          <li v-for="agent in group.agents" :key="agent.id">
            <RouterLink
              class="agent-card"
              :data-agent-id="agent.id"
              :class="{ selected: selectedAgent?.id === agent.id, unavailable: agent.status !== 'ACTIVE' }"
              :to="agentTarget(agent)"
              :aria-current="selectedAgent?.id === agent.id ? 'page' : undefined"
              :aria-label="`${agent.displayName}，${statusLabel(agent)}`"
            >
              <div class="agent-card__heading">
                <span class="agent-avatar"><Bot v-if="agent.defaultProfile" :size="21" /><Cpu v-else-if="agent.runtimeRole === 'CODING'" :size="21" /><Boxes v-else :size="21" /></span>
                <div><h3>{{ agent.displayName }}</h3><p>{{ roleLabel(agent) }}</p></div>
                <StatusBadge :tone="statusTone(agent)" dot>{{ statusLabel(agent) }}</StatusBadge>
              </div>
              <dl class="agent-facts">
                <div><dt>Ownership</dt><dd>{{ agent.defaultProfile ? 'USER · DEFAULT' : agent.ownershipType }}</dd></div>
                <div><dt>Template</dt><dd class="mono">{{ agent.templateKey }}@{{ agent.templateVersion }}</dd></div>
                <div><dt>Configuration</dt><dd>{{ agent.currentConfigurationRevision ? `Revision ${agent.currentConfigurationRevision}` : '待配置' }}</dd></div>
              </dl>
              <div class="binding-list">
                <p v-if="agent.ownershipType === 'USER'"><span>PERSONAL</span><strong>{{ bindingLabel(modelBinding(agent, 'PERSONAL')) }}</strong></p>
                <p v-if="!agent.defaultProfile"><span>TEAM</span><strong>{{ bindingLabel(modelBinding(agent, 'TEAM')) }}</strong></p>
              </div>
              <footer><span>查看与配置</span><span v-if="selectedAgent?.id === agent.id">已选中</span></footer>
            </RouterLink>
          </li>
        </ul>
      </section>

      <button
        v-if="agentStore.state.agents.nextOffset !== null"
        class="load-more"
        type="button"
        :disabled="agentStore.state.agents.loadingMore"
        @click="loadAgentsAndConfigurations(false, true)"
      >
        {{ agentStore.state.agents.loadingMore ? '正在加载…' : '加载更多 Agent' }}
      </button>

      <section class="projection-note" aria-label="任务与成本数据说明">
        <ShieldCheck :size="18" aria-hidden="true" />
        <div><strong>任务与成本等待 Agent 聚合投影</strong><span>当前 Task Delivery Summary 按 Task 或 Conversation 授权查询。页面不扫描 Task 来推导 Agent 统计，避免不完整、跨币种或越权的成本数字。</span></div>
      </section>
    </div>
  </AppShell>
</template>

<style scoped>
.agent-overview { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 24px; padding: 22px; background: linear-gradient(135deg, var(--cs-surface), #f1faf4); }
.overview-copy { display: flex; align-items: center; gap: 14px; }.overview-icon { display: grid; width: 50px; height: 50px; flex: 0 0 auto; place-items: center; border-radius: 15px; background: var(--cs-brand-100); color: var(--cs-brand-700); }.overview-copy h2 { margin-bottom: 4px; font-size: 18px; }.overview-copy p:last-child { max-width: 670px; margin: 0; color: var(--cs-text-muted); font-size: 10px; }
.agent-totals { display: grid; grid-template-columns: repeat(3, minmax(94px, 1fr)); gap: 7px; margin: 0; }.agent-totals div { min-width: 94px; padding: 11px 12px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: rgb(255 255 255 / 75%); }.agent-totals dt { color: var(--cs-text-muted); font-size: 9px; }.agent-totals dd { margin-top: 4px; color: var(--cs-text); font: 750 18px var(--cs-font-display); }
.mobile-create { display: none; }
.empty-mobile-create { display: none; margin: 0 16px 16px; }
.selection-warning { margin: 0; padding: 10px 13px; border: 1px solid #f0d5ad; border-radius: var(--cs-radius-sm); background: var(--cs-warning-soft); color: #765022; font-size: 10px; }
.agent-group { overflow: hidden; }.agent-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(290px, 1fr)); gap: 12px; margin: 0; padding: 16px; list-style: none; }.agent-card { display: grid; height: 100%; gap: 14px; padding: 16px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); box-shadow: 0 1px 2px rgb(20 43 29 / 3%); color: var(--cs-text); }.agent-card:hover, .agent-card:focus-visible { border-color: var(--cs-brand-400); box-shadow: 0 5px 18px rgb(44 110 67 / 9%); }.agent-card.selected { border-color: var(--cs-brand-500); background: #f7fcf8; box-shadow: 0 0 0 2px rgb(83 173 107 / 12%); }.agent-card.unavailable { background: var(--cs-surface-subtle); }
.agent-card__heading { display: grid; grid-template-columns: 40px minmax(0, 1fr) auto; align-items: center; gap: 10px; }.agent-avatar { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 12px; background: var(--cs-brand-100); color: var(--cs-brand-700); }.agent-card__heading h3 { overflow: hidden; margin-bottom: 2px; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }.agent-card__heading p { margin: 0; color: var(--cs-text-muted); font-size: 9px; }
.agent-facts { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 6px; margin: 0; }.agent-facts div { min-width: 0; padding: 8px; border-radius: 8px; background: var(--cs-surface-subtle); }.agent-facts dt { color: var(--cs-text-muted); font-size: 8px; text-transform: uppercase; }.agent-facts dd { overflow: hidden; margin-top: 3px; color: var(--cs-text-secondary); font-size: 9px; font-weight: 700; text-overflow: ellipsis; white-space: nowrap; }
.binding-list { display: grid; gap: 6px; }.binding-list p { display: grid; grid-template-columns: 64px minmax(0, 1fr); gap: 8px; margin: 0; }.binding-list span { color: var(--cs-text-muted); font-size: 8px; font-weight: 750; letter-spacing: .05em; }.binding-list strong { overflow: hidden; color: var(--cs-text-secondary); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }.agent-card footer { display: flex; justify-content: space-between; padding-top: 10px; border-top: 1px solid var(--cs-border); color: var(--cs-brand-700); font-size: 9px; font-weight: 700; }
.load-more { justify-self: center; min-height: 34px; padding: 0 14px; border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text-secondary); font-size: 10px; font-weight: 700; cursor: pointer; }.load-more:disabled { cursor: wait; opacity: .6; }
.projection-note { display: flex; align-items: flex-start; gap: 10px; padding: 13px 15px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface-subtle); color: var(--cs-text-muted); }.projection-note > svg { flex: 0 0 auto; color: var(--cs-brand-600); }.projection-note strong, .projection-note span { display: block; }.projection-note strong { color: var(--cs-text-secondary); font-size: 10px; }.projection-note span { margin-top: 2px; font-size: 9px; }
@media (max-width: 980px) { .agent-overview { grid-template-columns: 1fr; }.agent-totals { width: 100%; }.agent-grid { grid-template-columns: 1fr; } }
@media (max-width: 767px) { .mobile-create, .empty-mobile-create { display: inline-flex; justify-self: stretch; width: calc(100% - 32px); }.mobile-create { width: 100%; }.empty-mobile-create { justify-self: center; } }
@media (max-width: 520px) { .agent-overview { padding: 16px; }.overview-copy { align-items: flex-start; }.overview-icon { width: 42px; height: 42px; }.agent-totals { grid-template-columns: 1fr; }.agent-totals div { display: flex; align-items: center; justify-content: space-between; padding: 8px 10px; }.agent-totals dd { margin: 0; font-size: 15px; }.agent-grid { padding: 12px; }.agent-card { padding: 13px; }.agent-card__heading { grid-template-columns: 36px minmax(0, 1fr); }.agent-avatar { width: 36px; height: 36px; }.agent-card__heading .status-badge { grid-column: 2; justify-self: start; }.agent-facts { grid-template-columns: 1fr; }.agent-facts div { display: flex; justify-content: space-between; gap: 12px; }.agent-facts dd { margin: 0; }.panel-heading { padding: 15px; } }
</style>
