<script setup lang="ts">
import { ArrowRight, BriefcaseBusiness, CalendarDays, Layers3, MessageSquare, Plus, UsersRound } from '@lucide/vue'
import { computed, inject, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { AUTH_PRINCIPAL, can, permissions } from '../app/auth'
import BaseButton from '../components/base/BaseButton.vue'
import StatusBadge from '../components/base/StatusBadge.vue'
import WorkProjectCreateDialog from '../components/domain/WorkProjectCreateDialog.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import AppShell from '../components/layout/AppShell.vue'
import { useScopeStore } from '../domains/scope/store'
import { createWorkProjectCreationFlow } from '../domains/scope/workProjectCreation'

const route = useRoute()
const router = useRouter()
const principal = inject(AUTH_PRINCIPAL)
const store = useScopeStore()
const team = store.selectedTeam
const project = store.selectedProject
const canViewMembers = computed(() => Boolean(principal && can(principal, permissions.teamMembersRead)))
const canManageProjects = computed(() => Boolean(principal && can(principal, permissions.workProjectsManage)))
const projectCreation = createWorkProjectCreationFlow(store, router, route)

watch(() => store.state.selectedTeamId, () => store.loadMembers(), { immediate: true })

const todayLabel = new Intl.DateTimeFormat('zh-CN', {
  month: 'long',
  day: 'numeric',
  weekday: 'long',
}).format(new Date())
</script>

<template>
  <AppShell eyebrow="Today · Team workspace" :title="team?.name ?? '团队工作区'">
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
.quick-actions { display: grid; align-content: start; gap: 10px; }.quick-card { display: grid; min-height: 76px; grid-template-columns: 38px 1fr 16px; align-items: center; gap: 11px; padding: 13px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); transition: border-color var(--cs-transition-fast), transform var(--cs-transition-fast); }.quick-card:hover { border-color: var(--cs-brand-300); transform: translateY(-1px); }.quick-card i { display: grid; width: 38px; height: 38px; place-items: center; border-radius: 10px; background: var(--cs-brand-100); color: var(--cs-brand-700); }.quick-card i.conversation { background: var(--cs-agent-soft); color: var(--cs-agent); }.quick-card strong, .quick-card span { display: block; }.quick-card strong { font-size: 12px; }.quick-card span { margin-top: 2px; color: var(--cs-text-muted); font-size: 9px; }.quick-card > svg { color: var(--cs-text-muted); }
@media (max-width: 1000px) { .today-grid { grid-template-columns: 1fr; }.quick-actions { grid-template-columns: repeat(3, 1fr); }.quick-card { grid-template-columns: 34px 1fr; }.quick-card > svg { display: none; } }
@media (max-width: 767px) { .today-hero { min-height: 210px; align-items: flex-start; flex-direction: column; gap: 18px; padding: 20px; }.today-hero h2 { font-size: 23px; }.scope-fact { width: 100%; min-width: 0; }.scope-metrics { grid-template-columns: 1fr; gap: 8px; }.scope-metrics article { padding: 13px; }.today-grid { gap: 10px; }.project-focus__body { grid-template-columns: 44px 1fr; padding: 18px; }.project-monogram { width: 44px; height: 44px; }.project-focus__body > a { grid-column: 1 / -1; justify-content: center; }.quick-actions { grid-template-columns: 1fr; } }
</style>
