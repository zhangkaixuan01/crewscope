<script setup lang="ts">
import { ArrowRight, BriefcaseBusiness, CalendarDays, CircleAlert, Inbox, Layers3, MessageSquare, Plus, Settings2, UsersRound } from '@lucide/vue'
import { computed, inject, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { AUTH_PRINCIPAL, can, permissions } from '../app/auth'
import BaseButton from '../components/base/BaseButton.vue'
import BaseTooltip from '../components/base/BaseTooltip.vue'
import StatusBadge from '../components/base/StatusBadge.vue'
import WorkProjectCreateDialog from '../components/domain/WorkProjectCreateDialog.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import AppShell from '../components/layout/AppShell.vue'
import { useScopeStore } from '../domains/scope/store'
import { createWorkProjectCreationFlow } from '../domains/scope/workProjectCreation'
import { SETUP_STORE } from '../domains/setup/store'
import { WORKDESK_STORE, type WorkDeskStore } from '../domains/workdesk/store'
import { workDeskResponsibilityRoles, type WorkDeskItem, type WorkDeskResponsibilityRole } from '../domains/workdesk/types'
import { formatAbsoluteTime, formatRelativeTime } from '../composables/formatRelativeTime'

const route = useRoute()
const router = useRouter()
const principal = inject(AUTH_PRINCIPAL)
const store = useScopeStore()
const team = store.selectedTeam
const project = store.selectedProject
const canViewMembers = computed(() => Boolean(principal && can(principal, permissions.teamMembersRead)))
const canManageProjects = computed(() => Boolean(principal && can(principal, permissions.workProjectsManage)))
const projectCreation = createWorkProjectCreationFlow(store, router, route)
const setupStore = inject(SETUP_STORE, null)
const setupReadiness = computed(() => setupStore?.state.readiness ?? null)
const setupReadyCount = computed(() => setupReadiness.value?.capabilities.filter(item => item.required && item.status === 'READY').length ?? 0)
const setupRequiredCount = computed(() => setupReadiness.value?.capabilities.filter(item => item.required).length ?? 0)
// Keep the page mountable in isolated route/story tests where the application store is not installed.
const workDeskStore = inject(WORKDESK_STORE, null) ?? ({
  state: { phase: 'idle', scope: null, summary: null, errorMessage: null },
  activateScope: () => undefined,
  load: async () => undefined,
  reset: () => undefined,
} as WorkDeskStore)
const deskProject = computed(() => queryValue(route.query.deskProject) ?? 'all')
const deskRole = computed<WorkDeskResponsibilityRole | 'all'>(() => {
  const value = queryValue(route.query.deskRole)
  return value && workDeskResponsibilityRoles.includes(value as WorkDeskResponsibilityRole) ? value as WorkDeskResponsibilityRole : 'all'
})
const deskOnlyAction = computed(() => queryValue(route.query.deskAction) === 'true')
const workDeskFilter = computed(() => ({
  projectId: deskProject.value === 'all' ? null : deskProject.value,
  responsibilityRole: deskRole.value === 'all' ? null : deskRole.value,
  onlyNeedsAction: deskOnlyAction.value,
}))
const workDeskSections = computed(() => workDeskStore.state.summary?.sections ?? [])
const needsActionItems = computed(() => workDeskSections.value.filter(section => ['HUMAN_GATE', 'REVIEW', 'BLOCKED'].includes(section.key)).flatMap(section => section.items))
const personalItems = computed(() => workDeskSections.value.find(section => section.key === 'WORK_ITEM')?.items ?? [])
const executionItems = computed(() => workDeskSections.value.find(section => section.key === 'TASK_EXECUTION')?.items ?? [])
const inboxItems = computed(() => workDeskSections.value.find(section => section.key === 'INBOX')?.items ?? [])

function queryValue(value: unknown): string | null {
  if (typeof value === 'string') return value
  if (Array.isArray(value) && typeof value[0] === 'string') return value[0]
  return null
}

async function updateDeskQuery(key: string, value: string): Promise<void> {
  const query = { ...route.query }
  if (!value || value === 'all' || (key === 'deskAction' && value === 'false')) delete query[key]
  else query[key] = value
  await router.replace({ query })
}

function itemTitle(item: WorkDeskItem): string { return item.title?.trim() || `${item.objectType} · ${item.objectId.slice(0, 8)}` }
function roleLabel(role: string | null): string { return role === 'OWNER' ? '负责人' : role === 'EXECUTOR' ? '执行人' : role === 'REVIEWER' ? 'Reviewer' : '关联任务' }
function urgencyTone(urgency: string): 'danger' | 'warning' | 'neutral' { return urgency === 'HIGH' || urgency === 'URGENT' ? 'danger' : urgency === 'NORMAL' ? 'warning' : 'neutral' }
function actionLabel(action: string): string { return ({ REVIEW: '去 Review', APPROVE: '去确认', RESPOND: '去处理', OPEN: '打开', RESUME: '继续执行' } as Record<string, string>)[action] ?? '打开' }
function goToItem(item: WorkDeskItem): void { void router.push(item.route) }

watch(() => [store.state.selectedTeamId, team.value?.organizationId] as const, async ([teamId, organizationId]) => {
  await store.loadMembers()
  if (setupStore && teamId && organizationId) {
    setupStore.activateScope({ organizationId, teamId })
    await setupStore.load()
  }
  if (teamId && organizationId) {
    workDeskStore.activateScope({ organizationId, teamId })
    await workDeskStore.load(workDeskFilter.value)
  }
}, { immediate: true })

watch(workDeskFilter, value => { if (team.value) void workDeskStore.load(value) })

const todayLabel = new Intl.DateTimeFormat('zh-CN', {
  month: 'long',
  day: 'numeric',
  weekday: 'long',
}).format(new Date())
</script>

<template>
  <AppShell eyebrow="今日 · 团队工作区" :title="team?.name ?? '团队工作区'">
    <template #actions>
      <BaseButton v-if="canManageProjects" variant="secondary" size="small" @click="projectCreation.show"><Plus :size="14" />新建项目</BaseButton>
      <RouterLink v-slot="{ navigate }" custom :to="{ name: 'conversation', query: route.query }">
        <BaseButton variant="secondary" size="small" @click="navigate"><MessageSquare :size="14" />进入对话</BaseButton>
      </RouterLink>
      <RouterLink v-slot="{ navigate }" custom :to="{ name: 'work', query: route.query }">
        <BaseButton size="small" @click="navigate">打开 Work <ArrowRight :size="14" /></BaseButton>
      </RouterLink>
    </template>

    <StatePanel v-if="store.state.phase === 'loading' || store.state.phase === 'idle'" state="loading" />
    <StatePanel v-else-if="store.state.phase === 'error'" state="error" :description="store.state.errorMessage ?? undefined" @retry="store.reload" />
    <StatePanel v-else-if="store.state.phase === 'empty'" state="empty" title="还没有可访问的 Team" description="创建或加入 Team 后，Today 会汇总团队范围内需要关注的工作。" />

    <div v-else class="today-page page-shell">
      <section class="today-hero">
        <div>
          <p class="eyebrow"><CalendarDays :size="13" />{{ todayLabel }}</p>
          <h2>先确认范围，再推进今天的团队工作。</h2>
          <p>Today 聚合当前 Team 与 WorkProject 的责任、决策和执行入口；工作事实仍由各业务 API 提供。</p>
        </div>
        <div class="scope-fact">
          <span>当前范围</span>
          <strong>{{ team?.name }}</strong>
          <small>{{ project ? `${project.key} · ${project.name}` : '尚未创建 WorkProject' }}</small>
        </div>
      </section>

      <section class="scope-metrics" aria-label="当前范围摘要">
        <article><i><Layers3 :size="18" /></i><div><small>可用 WorkProject</small><strong>{{ store.state.projects.length }}</strong><p>ScopeSwitcher 中可直接切换</p></div></article>
        <article><i class="members"><UsersRound :size="18" /></i><div><small>Active TeamMember</small><strong>{{ store.state.members.filter(member => member.status === 'ACTIVE').length }}</strong><p>{{ store.state.membersLoading ? '正在同步成员事实' : '来自 Team Membership' }}</p></div></article>
        <article><i class="project"><BriefcaseBusiness :size="18" /></i><div><small>当前 WorkProject</small><strong class="project-key">{{ project?.key ?? '—' }}</strong><p>{{ project?.status === 'ACTIVE' ? 'Active · 可进入 Work' : '等待项目范围' }}</p></div></article>
        <article><i class="setup"><Settings2 :size="18" /></i><div><small>Setup Center</small><strong>{{ setupReadiness ? `${setupReadyCount}/${setupRequiredCount}` : '—' }}</strong><p>{{ setupReadiness?.requiredReady ? 'Required ready' : '查看配置进度与下一步' }}</p></div></article>
      </section>

      <section class="work-desk panel" aria-labelledby="work-desk-title">
        <div class="panel-heading work-desk__heading">
          <div><p class="eyebrow">个人工作台</p><h2 id="work-desk-title">我的工作台</h2><p>跨 WorkProject 汇总需要你关注、推进和确认的工作事实。</p></div>
          <BaseTooltip v-if="workDeskStore.state.summary" :text="formatAbsoluteTime(workDeskStore.state.summary.generatedAt)"><small class="desk-updated">更新于 {{ formatRelativeTime(workDeskStore.state.summary.generatedAt) }}</small></BaseTooltip>
        </div>
        <div class="desk-filters" aria-label="工作台筛选">
          <label>项目<select :value="deskProject" @change="updateDeskQuery('deskProject', ($event.target as HTMLSelectElement).value)"><option value="all">全部项目</option><option v-for="item in store.state.projects" :key="item.id" :value="item.id">{{ item.key }} · {{ item.name }}</option></select></label>
          <label>责任角色<select :value="deskRole" @change="updateDeskQuery('deskRole', ($event.target as HTMLSelectElement).value)"><option value="all">全部角色</option><option value="OWNER">负责人</option><option value="EXECUTOR">执行人</option><option value="REVIEWER">Reviewer</option></select></label>
          <label class="desk-check"><input type="checkbox" :checked="deskOnlyAction" @change="updateDeskQuery('deskAction', ($event.target as HTMLInputElement).checked ? 'true' : 'false')"> 仅看需要我行动</label>
        </div>
        <StatePanel v-if="workDeskStore.state.phase === 'loading' || workDeskStore.state.phase === 'idle'" state="loading" compact />
        <StatePanel v-else-if="workDeskStore.state.phase === 'offline'" state="offline" compact :description="workDeskStore.state.errorMessage ?? undefined" />
        <StatePanel v-else-if="workDeskStore.state.phase === 'error'" state="error" compact :description="workDeskStore.state.errorMessage ?? undefined" @retry="workDeskStore.load(workDeskFilter, true)" />
        <StatePanel v-else-if="workDeskStore.state.phase === 'empty'" state="empty" compact title="今天没有待处理事项" description="你可以进入 Work 创建工作项，或打开 Conversation 发起新的任务。">
          <template #action><RouterLink class="desk-empty-link" :to="{ name: 'work', query: route.query }">进入 Work <ArrowRight :size="13" /></RouterLink></template>
        </StatePanel>
        <template v-else>
          <div class="desk-action-block" aria-labelledby="desk-action-title">
            <div class="desk-section-title"><div><h3 id="desk-action-title">需要我行动</h3><span>{{ needsActionItems.length }} 项</span></div><StatusBadge v-if="needsActionItems.length" tone="warning" dot>优先处理</StatusBadge></div>
            <div v-if="needsActionItems.length" class="desk-list">
              <button v-for="item in needsActionItems" :key="`${item.objectType}:${item.objectId}`" type="button" class="desk-item" @click="goToItem(item)">
                <span class="desk-item__icon" :class="`desk-item__icon--${item.objectType.toLowerCase()}`"><CircleAlert :size="16" /></span>
                <span class="desk-item__main"><strong>{{ itemTitle(item) }}</strong><small>{{ roleLabel(item.responsibilityRole) }} · {{ formatRelativeTime(item.updatedAt) }}</small></span>
                <StatusBadge :tone="urgencyTone(item.urgency)" dot>{{ item.urgency === 'URGENT' ? '紧急' : item.urgency === 'HIGH' ? '高优先级' : '待处理' }}</StatusBadge>
                <span class="desk-item__action">{{ actionLabel(item.availableActions[0] ?? 'OPEN') }} <ArrowRight :size="13" /></span>
              </button>
            </div>
            <p v-else class="desk-inline-empty">当前筛选下没有需要你行动的事项。</p>
          </div>
          <div class="desk-columns">
            <section><div class="desk-section-title"><div><h3>我的工作</h3><span>{{ personalItems.length }} 项</span></div></div><div v-if="personalItems.length" class="desk-list desk-list--compact"><button v-for="item in personalItems" :key="`${item.objectType}:${item.objectId}`" type="button" class="desk-item" @click="goToItem(item)"><span class="desk-item__main"><strong>{{ itemTitle(item) }}</strong><small>{{ roleLabel(item.responsibilityRole) }} · {{ formatRelativeTime(item.updatedAt) }}</small></span><span v-if="item.progress !== null" class="desk-progress"><i :style="{ width: `${item.progress}%` }" /><small>{{ item.progress }}%</small></span><ArrowRight :size="13" /></button></div><p v-else class="desk-inline-empty">暂无分配给你的工作项。</p></section>
            <section><div class="desk-section-title"><div><h3>正在执行</h3><span>{{ executionItems.length }} 项</span></div></div><div v-if="executionItems.length" class="desk-list desk-list--compact"><button v-for="item in executionItems" :key="`${item.objectType}:${item.objectId}`" type="button" class="desk-item" @click="goToItem(item)"><span class="desk-item__main"><strong>{{ itemTitle(item) }}</strong><small>{{ item.status }} · {{ formatRelativeTime(item.updatedAt) }}</small></span><StatusBadge tone="agent" dot>Agent</StatusBadge><ArrowRight :size="13" /></button></div><p v-else class="desk-inline-empty">暂无进行中的执行。</p></section>
          </div>
          <div v-if="inboxItems.length" class="desk-inbox"><Inbox :size="16" /><span><strong>Inbox</strong> 有 {{ inboxItems[0]?.title ?? '新的待办消息' }}</span><RouterLink :to="{ name: 'inbox', query: route.query }">查看全部 <ArrowRight :size="13" /></RouterLink></div>
        </template>
      </section>

      <div class="today-grid">
        <section class="panel project-focus">
          <div class="panel-heading"><div><p class="eyebrow">Project focus</p><h2>当前项目范围</h2><p>选择 WorkProject 后，Work、Conversation 和后续详情页共享同一个 URL 上下文。</p></div><StatusBadge :tone="project ? 'success' : 'neutral'" dot>{{ project ? '已锁定范围' : '等待项目' }}</StatusBadge></div>
          <div v-if="project" class="project-focus__body">
            <span class="project-monogram">{{ project.key.slice(0, 2) }}</span>
            <div><small class="mono">{{ project.key }}</small><h3>{{ project.name }}</h3><p>Workspace <span class="mono">{{ project.workspaceId.slice(0, 8) }}…</span></p></div>
            <RouterLink :to="{ name: 'work', query: route.query }">进入 Work <ArrowRight :size="14" /></RouterLink>
          </div>
          <StatePanel v-else state="empty" title="这个 Team 还没有 WorkProject" description="创建第一个 WorkProject 后，即可进入 Work 管理并绑定代码仓库。">
            <template v-if="canManageProjects" #action><BaseButton size="small" @click="projectCreation.show"><Plus :size="14" />创建 WorkProject</BaseButton></template>
          </StatePanel>
        </section>

        <aside class="quick-actions">
          <RouterLink class="quick-card" :to="{ name: 'setup', query: route.query }">
            <i><Settings2 :size="18" /></i><div><strong>Setup Center</strong><span>查看 Team 就绪状态与下一步</span></div><ArrowRight :size="15" />
          </RouterLink>
          <RouterLink class="quick-card" :to="{ name: 'work', query: route.query }">
            <i><BriefcaseBusiness :size="18" /></i><div><strong>Work</strong><span>进入当前项目的工作管理视图</span></div><ArrowRight :size="15" />
          </RouterLink>
          <RouterLink v-if="canViewMembers" class="quick-card" :to="{ name: 'team-members', query: route.query }">
            <i><UsersRound :size="18" /></i><div><strong>团队成员</strong><span>查看 Membership 与加入来源</span></div><ArrowRight :size="15" />
          </RouterLink>
          <RouterLink class="quick-card" :to="{ name: 'conversation', query: route.query }">
            <i class="conversation"><MessageSquare :size="18" /></i><div><strong>Conversation</strong><span>带着当前 Team 与项目范围讨论</span></div><ArrowRight :size="15" />
          </RouterLink>
        </aside>
      </div>
    </div>

    <WorkProjectCreateDialog
      v-if="projectCreation.open.value && team"
      :team-name="team.name"
      :submitting="store.state.projectCommandPending"
      :retryable="store.state.projectCommandRetryable"
      :error-message="store.state.projectCommandErrorMessage"
      :check-key="store.checkWorkProjectKey"
      @close="projectCreation.close"
      @input-changed="store.clearProjectCommand"
      @submit="projectCreation.submit"
    />
  </AppShell>
</template>

<style scoped>
.today-hero { display: flex; min-height: 174px; align-items: flex-end; justify-content: space-between; gap: 28px; padding: 28px; overflow: hidden; border: 1px solid #cfe2d3; border-radius: var(--cs-radius-lg); background: radial-gradient(circle at 88% 4%, rgb(142 213 167 / 28%), transparent 34%), linear-gradient(135deg, #f6fbf7, #e8f4eb); }
.today-hero .eyebrow { display: flex; align-items: center; gap: 6px; }.today-hero h2 { max-width: 650px; margin-bottom: 10px; font: 27px/1.18 var(--cs-font-display); }.today-hero > div:first-child > p:last-child { max-width: 700px; margin: 0; color: var(--cs-text-muted); font-size: 12px; }
.scope-fact { display: grid; min-width: 260px; gap: 3px; padding: 15px 17px; border: 1px solid rgb(255 255 255 / 68%); border-radius: var(--cs-radius-md); background: rgb(255 255 255 / 72%); box-shadow: 0 8px 24px rgb(21 35 29 / 7%); }.scope-fact span { color: var(--cs-text-muted); font-size: 9px; font-weight: 750; letter-spacing: .08em; text-transform: uppercase; }.scope-fact strong { font-size: 14px; }.scope-fact small { color: var(--cs-text-secondary); font-size: 10px; }
.scope-metrics { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }.scope-metrics article { display: grid; grid-template-columns: 40px 1fr; gap: 12px; padding: 16px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }.scope-metrics i { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 11px; background: var(--cs-brand-100); color: var(--cs-brand-700); }.scope-metrics i.members { background: var(--cs-info-soft); color: var(--cs-info); }.scope-metrics i.project { background: var(--cs-agent-soft); color: var(--cs-agent); }.scope-metrics small, .scope-metrics strong, .scope-metrics p { display: block; }.scope-metrics small { color: var(--cs-text-muted); font-size: 9px; font-weight: 700; }.scope-metrics strong { font-size: 22px; }.scope-metrics strong.project-key { font-family: var(--cs-font-mono); font-size: 18px; }.scope-metrics p { margin: 1px 0 0; color: var(--cs-text-muted); font-size: 9px; }
.today-grid { display: grid; grid-template-columns: minmax(0, 1fr) 340px; gap: 14px; }.project-focus { overflow: hidden; }.project-focus__body { display: grid; grid-template-columns: 52px 1fr auto; align-items: center; gap: 14px; min-height: 150px; padding: 24px; }.project-monogram { display: grid; width: 52px; height: 52px; place-items: center; border-radius: 15px; background: var(--cs-agent-soft); color: var(--cs-agent); font-size: 13px; font-weight: 850; }.project-focus__body small { color: var(--cs-brand-600); font-size: 10px; }.project-focus__body h3 { margin: 2px 0 5px; font-size: 17px; }.project-focus__body p { margin: 0; color: var(--cs-text-muted); font-size: 10px; }.project-focus__body > a { display: flex; min-height: 36px; align-items: center; gap: 6px; padding: 0 12px; border-radius: var(--cs-radius-sm); background: var(--cs-brand-800); color: white; font-size: 10px; font-weight: 700; }
.work-desk { display: grid; gap: 14px; }.work-desk__heading { align-items: flex-start; }.work-desk__heading h2 { margin: 2px 0 3px; font-size: 20px; }.desk-updated { color: var(--cs-text-muted); font-size: 10px; }.desk-filters { display: flex; flex-wrap: wrap; align-items: end; gap: 12px; padding: 12px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); }.desk-filters label { display: grid; gap: 4px; color: var(--cs-text-muted); font-size: 10px; font-weight: 700; }.desk-filters select { min-width: 160px; height: 32px; padding: 0 9px; border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); font-size: 11px; }.desk-check { display: flex !important; min-height: 32px; align-items: center; grid-template-columns: auto 1fr; font-weight: 600 !important; }.desk-check input { accent-color: var(--cs-brand-600); }.desk-action-block { display: grid; gap: 9px; }.desk-section-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; }.desk-section-title h3 { display: inline; margin: 0; font-size: 14px; }.desk-section-title span { margin-left: 7px; color: var(--cs-text-muted); font-size: 10px; }.desk-list { display: grid; gap: 7px; }.desk-item { display: grid; grid-template-columns: 34px minmax(0, 1fr) auto auto; align-items: center; gap: 10px; width: 100%; padding: 11px 12px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); text-align: left; cursor: pointer; transition: border-color var(--cs-transition-fast), transform var(--cs-transition-fast); }.desk-item:hover { border-color: var(--cs-brand-300); transform: translateY(-1px); }.desk-item__icon { display: grid; width: 30px; height: 30px; place-items: center; border-radius: 9px; background: var(--cs-warning-soft); color: var(--cs-warning); }.desk-item__icon--human_gate { background: var(--cs-danger-soft); color: var(--cs-danger); }.desk-item__main { min-width: 0; }.desk-item__main strong, .desk-item__main small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.desk-item__main strong { font-size: 11px; }.desk-item__main small { margin-top: 3px; color: var(--cs-text-muted); font-size: 9px; }.desk-item__action { display: flex; align-items: center; gap: 3px; color: var(--cs-brand-700); font-size: 10px; font-weight: 700; white-space: nowrap; }.desk-inline-empty { margin: 0; padding: 14px; border: 1px dashed var(--cs-border-strong); border-radius: var(--cs-radius-sm); color: var(--cs-text-muted); font-size: 10px; }.desk-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }.desk-columns section { display: grid; align-content: start; gap: 9px; }.desk-list--compact .desk-item { grid-template-columns: minmax(0, 1fr) auto; }.desk-progress { display: flex; min-width: 78px; align-items: center; gap: 6px; }.desk-progress i { display: block; width: 42px; height: 5px; overflow: hidden; border-radius: 99px; background: var(--cs-brand-100); }.desk-progress i::after { display: block; width: 100%; height: 100%; background: var(--cs-brand-500); content: ''; }.desk-progress small { color: var(--cs-text-muted); font-size: 9px; }.desk-inbox { display: flex; align-items: center; gap: 8px; padding: 11px 13px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-text-secondary); font-size: 10px; }.desk-inbox span { flex: 1; }.desk-inbox strong { color: var(--cs-text); }.desk-inbox a, .desk-empty-link { display: inline-flex; align-items: center; gap: 4px; color: var(--cs-brand-700); font-weight: 700; }.desk-empty-link { font-size: 10px; }
.quick-actions { display: grid; align-content: start; gap: 10px; }.quick-card { display: grid; min-height: 76px; grid-template-columns: 38px 1fr 16px; align-items: center; gap: 11px; padding: 13px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); transition: border-color var(--cs-transition-fast), transform var(--cs-transition-fast); }.quick-card:hover { border-color: var(--cs-brand-300); transform: translateY(-1px); }.quick-card i { display: grid; width: 38px; height: 38px; place-items: center; border-radius: 10px; background: var(--cs-brand-100); color: var(--cs-brand-700); }.quick-card i.conversation { background: var(--cs-agent-soft); color: var(--cs-agent); }.quick-card strong, .quick-card span { display: block; }.quick-card strong { font-size: 12px; }.quick-card span { margin-top: 2px; color: var(--cs-text-muted); font-size: 9px; }.quick-card > svg { color: var(--cs-text-muted); }
@media (max-width: 1000px) { .today-grid { grid-template-columns: 1fr; }.quick-actions { grid-template-columns: repeat(3, 1fr); }.quick-card { grid-template-columns: 34px 1fr; }.quick-card > svg { display: none; } }
@media (max-width: 767px) { .today-hero { min-height: 210px; align-items: flex-start; flex-direction: column; gap: 18px; padding: 20px; }.today-hero h2 { font-size: 23px; }.scope-fact { width: 100%; min-width: 0; }.scope-metrics { grid-template-columns: 1fr; gap: 8px; }.scope-metrics article { padding: 13px; }.today-grid { gap: 10px; }.project-focus__body { grid-template-columns: 44px 1fr; padding: 18px; }.project-monogram { width: 44px; height: 44px; }.project-focus__body > a { grid-column: 1 / -1; justify-content: center; }.quick-actions { grid-template-columns: 1fr; }.desk-columns { grid-template-columns: 1fr; }.desk-filters { align-items: stretch; flex-direction: column; }.desk-filters select { width: 100%; }.desk-item { grid-template-columns: 30px minmax(0, 1fr) auto; }.desk-item__action { grid-column: 2 / -1; }.desk-item > .status-badge { grid-column: 3; grid-row: 1; } }
</style>
