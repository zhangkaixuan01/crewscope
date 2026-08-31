<script setup lang="ts">
import { ArrowRight, Bot, Boxes, Building2, Cpu, Plus, ShieldCheck, Sparkles, UserRound } from '@lucide/vue'
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
const createOwnership = ref<'USER' | 'TEAM'>('USER')
const createTrigger = ref<HTMLElement | null>(null)
const createKey = ref('')
const createSignature = ref('')
type BindingView = AgentModelBindingSummary | null | 'LOADING' | 'UNAVAILABLE' | 'NOT_CONFIGURED'

const team = scopeStore.selectedTeam
const selection = computed(() => agentSettingsSelection(route.query))
const agents = computed(() => agentStore.state.agents.value ?? [])
const personalAgents = computed(() => agents.value.filter(agent => agent.defaultProfile))
const specialistAgents = computed(() => agents.value.filter(agent => agent.ownershipType === 'USER' && !agent.defaultProfile))
// Built-in Team Observer has a dedicated read-only runtime and is not a WorkItem execution Agent.
const platformManagedAgents = computed(() => agents.value.filter(isPlatformManagedAgent))
const teamAgents = computed(() => agents.value.filter(agent => agent.ownershipType === 'TEAM' && !isPlatformManagedAgent(agent)))
const agentGroups = computed(() => [
  {
    key: 'personal', title: '默认 Personal Agent', directoryId: 'personal-agent-directory',
    description: '每位成员唯一的对话入口，生命周期由平台管理。', agents: personalAgents.value,
    emptyTitle: 'Personal Agent 尚未完成初始化',
    emptyDescription: '完成 Team 初始化后，平台会在这里展示你的默认对话 Agent。',
  },
  {
    key: 'specialist', title: '我的 Specialist', directoryId: 'specialist-agent-directory',
    description: '个人创建的 Coding、Reviewer 与其他受批准专业 Agent。', agents: specialistAgents.value,
    emptyTitle: '还没有个人 Specialist',
    emptyDescription: '从批准 Template 创建 Coding、Reviewer 或其他个人执行 Agent。',
  },
  {
    key: 'team', title: '团队 Agent', directoryId: 'team-agent-directory',
    description: '团队共享的执行身份；配置受管模型后，在 WorkItem 责任链中担任 Executor。', agents: teamAgents.value,
    emptyTitle: '这个 Team 还没有团队 Agent',
    emptyDescription: canManageTeamAgents.value
      ? '创建后配置 TEAM 模型连接，再前往 Work 将它分配为 WorkItem Executor。'
      : 'Team Owner 或 Team Admin 创建后，你可以在 WorkItem 责任链中选择它。',
  },
  {
    key: 'managed', title: '平台托管 Agent', directoryId: 'managed-agent-directory',
    description: '由平台初始化且不可重复创建；管理员可配置受管模型并完成 Preflight。', agents: platformManagedAgents.value,
    emptyTitle: '当前没有平台托管 Agent',
    emptyDescription: '平台托管 Agent 会在相应团队能力初始化后出现。',
  },
].filter(group => group.key !== 'managed' || group.agents.length > 0))
const selectedAgent = computed(() => agents.value.find(agent => agent.id === selection.value.agentId) ?? null)
const teamAgentWorkTarget = computed(() => ({
  name: 'work',
  query: { ...route.query, agent: undefined, configurationRevision: undefined },
}))
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

function openCreate(event?: MouseEvent, ownership: 'USER' | 'TEAM' = 'USER'): void {
  if (event?.currentTarget instanceof HTMLElement) createTrigger.value = event.currentTarget
  createOwnership.value = ownership === 'TEAM' && canManageTeamAgents.value ? 'TEAM' : 'USER'
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
  if (isPlatformManagedAgent(agent)) return 'Team Observer · 平台托管'
  if (agent.defaultProfile) return 'Personal Agent'
  if (agent.runtimeRole === 'CODING') return 'Coding Specialist'
  if (agent.runtimeRole === 'REVIEWER') return 'Reviewer Specialist'
  return agent.runtimeRole.replaceAll('_', ' ')
}

function isPlatformManagedAgent(agent: AgentSummary): boolean {
  return agent.templateKey === 'team-observer'
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
  <AppShell eyebrow="Capabilities · Agent directory" :title="`${team?.name ?? 'Team'} · Agent 中心`">
    <template #actions>
      <BaseButton size="small" :disabled="!scopeStore.state.selectedTeamId" @click="openCreate($event, 'USER')"><Plus :size="14" />创建个人 Agent</BaseButton>
      <BaseButton v-if="canManageTeamAgents" variant="secondary" size="small" :disabled="!scopeStore.state.selectedTeamId" @click="openCreate($event, 'TEAM')"><Building2 :size="14" />创建团队 Agent</BaseButton>
    </template>

    <AgentCreateDialog
      v-if="createOpen"
      :user-templates="userTemplates"
      :team-templates="teamTemplates"
      :loading="templatesLoading"
      :can-manage-team-agents="canManageTeamAgents"
      :initial-ownership-type="createOwnership"
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
          <div><dt>平台托管</dt><dd>{{ platformManagedAgents.length }}</dd></div>
        </dl>
      </section>

      <section class="agent-entry-grid" aria-label="Agent 类型入口">
        <article class="agent-entry agent-entry--personal">
          <span class="agent-entry__icon"><UserRound :size="21" aria-hidden="true" /></span>
          <div><p class="eyebrow">Personal ownership</p><h2>个人 Agent</h2><p>默认 Personal Agent 承接对话；个人 Specialist 执行你的专业任务。</p></div>
          <strong>{{ personalAgents.length + specialistAgents.length }} 个</strong>
          <footer><a href="#personal-agent-directory">查看个人 Agent</a><BaseButton class="entry-create" size="small" variant="ghost" @click="openCreate($event, 'USER')"><Plus :size="13" />创建个人 Agent</BaseButton></footer>
        </article>
        <article class="agent-entry agent-entry--team">
          <span class="agent-entry__icon"><Building2 :size="21" aria-hidden="true" /></span>
          <div><p class="eyebrow">Team ownership</p><h2>团队 Agent</h2><p>使用 TEAM/Organization 连接，通过 WorkItem 责任链承接团队耐久任务。</p></div>
          <strong>{{ teamAgents.length }} 个</strong>
          <footer><a href="#team-agent-directory">查看团队 Agent</a><BaseButton v-if="canManageTeamAgents" class="entry-create" size="small" variant="ghost" @click="openCreate($event, 'TEAM')"><Plus :size="13" />创建团队 Agent</BaseButton></footer>
        </article>
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
        :id="group.directoryId"
        class="agent-group panel"
        :aria-labelledby="`agent-group-${group.key}`"
      >
        <header class="panel-heading">
          <div><p class="eyebrow">{{ group.agents.length }} agents</p><h2 :id="`agent-group-${group.key}`">{{ group.title }}</h2><p>{{ group.description }}</p></div>
          <div v-if="group.key === 'team'" class="team-agent-actions">
            <RouterLink :to="teamAgentWorkTarget">前往 Work 分配<ArrowRight :size="13" /></RouterLink>
          </div>
        </header>
        <div v-if="group.agents.length === 0" class="agent-group-empty" role="status">
          <span><Bot :size="20" aria-hidden="true" /></span>
          <div><strong>{{ group.emptyTitle }}</strong><p>{{ group.emptyDescription }}</p></div>
          <BaseButton v-if="group.key === 'specialist'" size="small" variant="secondary" @click="openCreate($event, 'USER')">创建个人 Agent</BaseButton>
          <BaseButton v-else-if="group.key === 'team' && canManageTeamAgents" size="small" variant="secondary" @click="openCreate($event, 'TEAM')">创建团队 Agent</BaseButton>
        </div>
        <ul v-else class="agent-grid" role="list">
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
              <footer><span>{{ isPlatformManagedAgent(agent) ? '配置模型与预检' : '查看与配置' }}</span><span v-if="selectedAgent?.id === agent.id">已选中</span></footer>
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
.agent-entry-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }.agent-entry { display: grid; grid-template-columns: 42px minmax(0, 1fr) auto; align-items: start; gap: 11px; padding: 17px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }.agent-entry--team { border-color: #d6cbe8; background: linear-gradient(135deg, var(--cs-surface), #fbf8ff); }.agent-entry__icon { display: grid; width: 42px; height: 42px; place-items: center; border-radius: 12px; background: var(--cs-brand-100); color: var(--cs-brand-700); }.agent-entry--team .agent-entry__icon { background: var(--cs-agent-soft); color: var(--cs-agent); }.agent-entry h2 { margin-bottom: 3px; font-size: 15px; }.agent-entry div > p:last-child { margin: 0; color: var(--cs-text-muted); font-size: 10px; line-height: 1.5; }.agent-entry > strong { color: var(--cs-text-secondary); font-size: 12px; }.agent-entry footer { display: flex; grid-column: 2 / -1; align-items: center; justify-content: space-between; gap: 8px; }.agent-entry footer > a { color: var(--cs-brand-700); font-size: 10px; font-weight: 750; }.agent-entry footer > a:hover, .agent-entry footer > a:focus-visible { text-decoration: underline; }.entry-create { display: none; }
.selection-warning { margin: 0; padding: 10px 13px; border: 1px solid #f0d5ad; border-radius: var(--cs-radius-sm); background: var(--cs-warning-soft); color: #765022; font-size: 10px; }
.agent-group { overflow: hidden; scroll-margin-top: 14px; }.team-agent-actions { display: flex; align-items: center; gap: 10px; }.team-agent-actions > a { display: inline-flex; align-items: center; gap: 4px; color: var(--cs-brand-700); font-size: 10px; font-weight: 750; }.agent-group-empty { display: grid; grid-template-columns: 38px minmax(0, 1fr) auto; align-items: center; gap: 11px; padding: 18px; border-top: 1px solid var(--cs-border); background: var(--cs-surface-subtle); }.agent-group-empty > span { display: grid; width: 38px; height: 38px; place-items: center; border-radius: 11px; background: var(--cs-brand-100); color: var(--cs-brand-700); }.agent-group-empty strong { font-size: 11px; }.agent-group-empty p { margin: 3px 0 0; color: var(--cs-text-muted); font-size: 9px; }.agent-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(290px, 1fr)); gap: 12px; margin: 0; padding: 16px; list-style: none; }.agent-card { display: grid; height: 100%; gap: 14px; padding: 16px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); box-shadow: 0 1px 2px rgb(20 43 29 / 3%); color: var(--cs-text); }.agent-card:hover, .agent-card:focus-visible { border-color: var(--cs-brand-400); box-shadow: 0 5px 18px rgb(44 110 67 / 9%); }.agent-card.selected { border-color: var(--cs-brand-500); background: #f7fcf8; box-shadow: 0 0 0 2px rgb(83 173 107 / 12%); }.agent-card.unavailable { background: var(--cs-surface-subtle); }
.agent-card__heading { display: grid; grid-template-columns: 40px minmax(0, 1fr) auto; align-items: center; gap: 10px; }.agent-avatar { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 12px; background: var(--cs-brand-100); color: var(--cs-brand-700); }.agent-card__heading h3 { overflow: hidden; margin-bottom: 2px; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }.agent-card__heading p { margin: 0; color: var(--cs-text-muted); font-size: 9px; }
.agent-facts { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 6px; margin: 0; }.agent-facts div { min-width: 0; padding: 8px; border-radius: 8px; background: var(--cs-surface-subtle); }.agent-facts dt { color: var(--cs-text-muted); font-size: 8px; text-transform: uppercase; }.agent-facts dd { overflow: hidden; margin-top: 3px; color: var(--cs-text-secondary); font-size: 9px; font-weight: 700; text-overflow: ellipsis; white-space: nowrap; }
.binding-list { display: grid; gap: 6px; }.binding-list p { display: grid; grid-template-columns: 64px minmax(0, 1fr); gap: 8px; margin: 0; }.binding-list span { color: var(--cs-text-muted); font-size: 8px; font-weight: 750; letter-spacing: .05em; }.binding-list strong { overflow: hidden; color: var(--cs-text-secondary); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }.agent-card footer { display: flex; justify-content: space-between; padding-top: 10px; border-top: 1px solid var(--cs-border); color: var(--cs-brand-700); font-size: 9px; font-weight: 700; }
.load-more { justify-self: center; min-height: 34px; padding: 0 14px; border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text-secondary); font-size: 10px; font-weight: 700; cursor: pointer; }.load-more:disabled { cursor: wait; opacity: .6; }
.projection-note { display: flex; align-items: flex-start; gap: 10px; padding: 13px 15px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface-subtle); color: var(--cs-text-muted); }.projection-note > svg { flex: 0 0 auto; color: var(--cs-brand-600); }.projection-note strong, .projection-note span { display: block; }.projection-note strong { color: var(--cs-text-secondary); font-size: 10px; }.projection-note span { margin-top: 2px; font-size: 9px; }
@media (max-width: 980px) { .agent-overview { grid-template-columns: 1fr; }.agent-totals { width: 100%; }.agent-entry-grid { grid-template-columns: 1fr; }.agent-grid { grid-template-columns: 1fr; } }
@media (max-width: 767px) { .entry-create { display: inline-flex; }.team-agent-actions > a { white-space: nowrap; }.agent-group-empty { grid-template-columns: 38px minmax(0, 1fr); }.agent-group-empty > button { grid-column: 2; justify-self: start; } }
@media (max-width: 520px) { .agent-overview { padding: 16px; }.overview-copy { align-items: flex-start; }.overview-icon { width: 42px; height: 42px; }.agent-totals { grid-template-columns: 1fr; }.agent-totals div { display: flex; align-items: center; justify-content: space-between; padding: 8px 10px; }.agent-totals dd { margin: 0; font-size: 15px; }.agent-grid { padding: 12px; }.agent-card { padding: 13px; }.agent-card__heading { grid-template-columns: 36px minmax(0, 1fr); }.agent-avatar { width: 36px; height: 36px; }.agent-card__heading .status-badge { grid-column: 2; justify-self: start; }.agent-facts { grid-template-columns: 1fr; }.agent-facts div { display: flex; justify-content: space-between; gap: 12px; }.agent-facts dd { margin: 0; }.panel-heading { padding: 15px; } }
</style>
