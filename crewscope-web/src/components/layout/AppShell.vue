<script setup lang="ts">
import {
  Activity,
  Bell,
  Bot,
  BriefcaseBusiness,
  CalendarDays,
  Command,
  LayoutDashboard,
  MessageSquare,
  Search,
  Settings,
  ShieldCheck,
  UsersRound,
  Workflow,
  GitFork,
} from '@lucide/vue'
import { computed, inject, watch } from 'vue'
import { RouterLink, useRoute, useRouter, type RouteLocationRaw } from 'vue-router'
import { AUTH_PRINCIPAL, can, permissions } from '../../app/auth'
import { useNetworkStatus } from '../../app/network'
import { SCOPE_STORE } from '../../domains/scope/store'
import crewScopeMark from '../../design/crewscope-mark.svg'
import ScopeSwitcher from '../domain/ScopeSwitcher.vue'

defineProps<{
  title: string
  eyebrow: string
}>()

const route = useRoute()
const router = useRouter()
const principal = inject(AUTH_PRINCIPAL)
const scopeStore = inject(SCOPE_STORE)
const activeMode = computed(() => route.meta.mode)
const activeSection = computed(() => route.meta.section)
const modeTarget = (name: 'conversation' | 'today') => ({ name, query: route.query })
const canReadScope = computed(() => Boolean(principal && can(principal, permissions.scopeRead)))
const isOnline = useNetworkStatus()
let scopeSynchronizationVersion = 0

const navigation = [
  { label: 'Today', icon: CalendarDays, name: 'today', section: 'today', permission: permissions.scopeRead },
  { label: 'Work', icon: BriefcaseBusiness, name: 'work', section: 'work', permission: permissions.workRead },
  { label: '团队成员', icon: UsersRound, name: 'team-members', section: 'members', permission: permissions.teamMembersRead },
  { label: '仓库设置', icon: GitFork, name: 'repository-settings', section: 'repositories', permission: permissions.repositoriesManage },
]

const futureNavigation = [
  { label: 'WorkGraph', icon: Workflow },
  { label: 'Agent 与能力', icon: Bot },
  { label: 'Activity', icon: Activity },
  { label: '治理与设置', icon: ShieldCheck },
]

const visibleNavigation = computed(() => navigation.filter(item => principal && can(principal, item.permission)))
const navigationTarget = (name: string): RouteLocationRaw => ({ name, query: route.query })

watch(
  () => [route.query.team, route.query.project] as const,
  async ([team, project]) => {
    if (!scopeStore || !canReadScope.value) return
    const synchronizationVersion = ++scopeSynchronizationVersion
    const selection = await scopeStore.synchronize(queryValue(team), queryValue(project))
    // Route changes can start a newer Scope restoration before the previous request settles.
    if (synchronizationVersion !== scopeSynchronizationVersion) return
    if (scopeStore.state.phase === 'error') return

    const nextQuery = { ...route.query }
    if (selection.teamId) nextQuery.team = selection.teamId
    else delete nextQuery.team
    if (selection.projectId) nextQuery.project = selection.projectId
    else delete nextQuery.project

    const scopeChanged = queryValue(route.query.team) !== selection.teamId || queryValue(route.query.project) !== selection.projectId
    if (scopeChanged) {
      // Object identity belongs to the original Scope and cannot survive URL canonicalization.
      delete nextQuery.workItem
      delete nextQuery.focus
      delete nextQuery.conversation
      await router.replace({ query: nextQuery })
    }
  },
  { immediate: true },
)

function queryValue(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null
}
</script>

<template>
  <div class="app-shell">
    <a class="skip-link" href="#main-workspace">跳到主要内容</a>
    <aside class="app-shell__rail" aria-label="主导航">
      <RouterLink class="brand" :to="modeTarget('conversation')" aria-label="CrewScope 首页">
        <img :src="crewScopeMark" alt="" width="34" height="34">
        <span>CrewScope<small>Team execution</small></span>
      </RouterLink>

      <ScopeSwitcher v-if="scopeStore && canReadScope" />

      <nav class="rail-navigation">
        <p>Workspace</p>
        <RouterLink
          v-for="item in visibleNavigation"
          :key="item.label"
          :class="{ active: activeSection === item.section }"
          :to="navigationTarget(item.name)"
        >
          <component :is="item.icon" :size="17" aria-hidden="true" />
          <span>{{ item.label }}</span>
        </RouterLink>
        <p>Operate</p>
        <button v-for="item in futureNavigation.slice(0, 3)" :key="item.label" type="button" disabled>
          <component :is="item.icon" :size="17" aria-hidden="true" />
          <span>{{ item.label }}</span>
        </button>
        <p>System</p>
        <button v-for="item in futureNavigation.slice(3)" :key="item.label" type="button" disabled>
          <component :is="item.icon" :size="17" aria-hidden="true" />
          <span>{{ item.label }}</span>
        </button>
      </nav>

      <div class="rail-profile">
        <span>{{ principal?.displayName.slice(0, 1) }}</span>
        <div><strong>{{ principal?.displayName }}</strong><small>{{ principal?.role }}</small></div>
        <Settings :size="16" aria-hidden="true" />
      </div>
    </aside>

    <div class="app-shell__body">
      <div v-if="!isOnline" class="network-banner" role="status" aria-live="polite" aria-atomic="true">
        <span aria-hidden="true">●</span>当前离线：已加载事实和草稿已保留，联网后可继续提交。
      </div>
      <header class="topbar">
        <div class="mode-switcher" aria-label="工作模式">
          <RouterLink :class="{ active: activeMode === 'conversation' }" :to="modeTarget('conversation')">
            <MessageSquare :size="16" aria-hidden="true" />对话
          </RouterLink>
          <RouterLink :class="{ active: activeMode === 'control' }" :to="modeTarget('today')">
            <LayoutDashboard :size="16" aria-hidden="true" />工作台
          </RouterLink>
        </div>
        <ScopeSwitcher v-if="scopeStore && canReadScope" class="topbar-scope" />
        <button class="command-search" type="button">
          <Search :size="16" aria-hidden="true" /><span>搜索工作、成员或 Agent</span><kbd><Command :size="11" /> K</kbd>
        </button>
        <button class="icon-button" type="button" aria-label="通知"><Bell :size="18" /></button>
      </header>

      <header class="context-header">
        <div>
          <p>{{ eyebrow }}</p>
          <h1>{{ title }}</h1>
        </div>
        <div class="context-header__actions"><slot name="actions" /></div>
      </header>

      <main id="main-workspace" class="app-shell__workspace" tabindex="-1"><slot /></main>
    </div>

    <nav class="mobile-mode" aria-label="移动端工作模式">
      <RouterLink :class="{ active: activeMode === 'conversation' }" :to="modeTarget('conversation')"><MessageSquare :size="18" />对话</RouterLink>
      <RouterLink :class="{ active: activeMode === 'control' }" :to="modeTarget('today')"><LayoutDashboard :size="18" />工作台</RouterLink>
    </nav>
  </div>
</template>

<style scoped>
.app-shell { min-height: 100vh; background: var(--cs-canvas); }
.skip-link { position: fixed; top: 8px; left: 8px; z-index: 200; padding: 9px 12px; border-radius: var(--cs-radius-sm); background: var(--cs-brand-950); color: var(--cs-text-on-dark); font-size: 11px; transform: translateY(-160%); }.skip-link:focus { transform: translateY(0); }
.network-banner { position: relative; z-index: 40; display: flex; min-height: 36px; align-items: center; justify-content: center; gap: 7px; padding: 7px 16px; border-bottom: 1px solid #d9a8a2; background: #fff4f2; color: #8f332b; font-size: 10px; font-weight: 700; text-align: center; }.network-banner span { color: var(--cs-danger); }
.app-shell__rail { position: fixed; inset: 0 auto 0 0; z-index: 10; display: flex; width: 244px; flex-direction: column; padding: 18px 14px 14px; border-right: 1px solid #d8e4db; background: #f5faf6; color: var(--cs-text); }
.brand { display: flex; align-items: center; gap: 10px; padding: 0 5px; font-family: var(--cs-font-display); font-size: 18px; }
.brand img { border: 1px solid rgb(184 239 202 / 24%); border-radius: 11px; }
.brand span, .brand small { display: block; }
.brand small { color: var(--cs-text-muted); font-family: var(--cs-font-sans); font-size: 9px; font-weight: 600; letter-spacing: .07em; text-transform: uppercase; }
.rail-navigation { flex: 1; }
.rail-navigation p { margin: 15px 10px 6px; color: #50665a; font-size: 9px; font-weight: 750; letter-spacing: .1em; text-transform: uppercase; }
.rail-navigation a, .rail-navigation button { display: grid; grid-template-columns: 19px 1fr auto; align-items: center; gap: 9px; width: 100%; min-height: 37px; padding: 0 10px; border-radius: var(--cs-radius-sm); background: transparent; color: #4d6256; font-size: 12px; text-align: left; cursor: pointer; }
.rail-navigation a:hover, .rail-navigation a.active { background: var(--cs-brand-100); color: var(--cs-brand-800); }.rail-navigation a.active { font-weight: 750; }
.rail-navigation button:disabled { cursor: not-allowed; opacity: .48; }
.rail-profile { display: grid; grid-template-columns: 32px 1fr 16px; align-items: center; gap: 9px; padding: 11px 8px 3px; border-top: 1px solid #d8e4db; }
.rail-profile > span { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 50%; background: var(--cs-brand-600); color: white; font-size: 11px; font-weight: 750; }
.rail-profile strong, .rail-profile small { display: block; }
.rail-profile strong { font-size: 11px; }.rail-profile small { color: var(--cs-text-muted); font-size: 9px; }
.app-shell__body { min-height: 100vh; margin-left: 244px; }
.topbar { position: relative; z-index: 30; display: grid; height: 58px; grid-template-columns: auto minmax(240px, 440px) auto; align-items: center; justify-content: space-between; gap: 16px; padding: 0 24px; border-bottom: 1px solid var(--cs-border); background: rgb(255 255 255 / 88%); backdrop-filter: blur(12px); }
.mode-switcher { display: flex; gap: 3px; padding: 3px; border: 1px solid var(--cs-border); border-radius: 10px; background: var(--cs-surface-subtle); }
.mode-switcher a { display: flex; min-height: 31px; align-items: center; gap: 6px; padding: 0 10px; border-radius: 7px; color: var(--cs-text-muted); font-size: 11px; font-weight: 700; }
.mode-switcher a.active { background: var(--cs-surface); box-shadow: 0 1px 3px rgb(21 35 29 / 10%); color: var(--cs-text); }
.command-search { display: grid; grid-template-columns: 18px 1fr auto; align-items: center; gap: 8px; width: 100%; min-height: 34px; padding: 0 10px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-text-muted); font-size: 11px; text-align: left; cursor: pointer; }
.command-search kbd { display: flex; align-items: center; gap: 2px; padding: 2px 5px; border: 1px solid var(--cs-border); border-radius: 5px; background: var(--cs-surface); font: 9px var(--cs-font-sans); }
.icon-button { display: grid; width: 34px; height: 34px; place-items: center; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text-secondary); cursor: pointer; }
.topbar-scope { display: none; }
.context-header { display: flex; min-height: 82px; align-items: center; justify-content: space-between; gap: 18px; padding: 15px 28px; border-bottom: 1px solid var(--cs-border); background: var(--cs-surface); }
.context-header p { margin-bottom: 3px; color: var(--cs-text-muted); font-size: 10px; font-weight: 700; letter-spacing: .06em; text-transform: uppercase; }
.context-header h1 { margin-bottom: 0; font-size: 20px; font-weight: 720; letter-spacing: -.02em; }
.context-header__actions { display: flex; align-items: center; gap: 8px; }
.app-shell__workspace { padding: 18px; }
.mobile-mode { display: none; }
@media (max-width: 1100px) {
  .app-shell__rail { width: 76px; align-items: center; }
  .brand > span, .rail-navigation p, .rail-navigation a span, .rail-navigation button span, .rail-profile div, .rail-profile svg { display: none; }
  .rail-navigation a, .rail-navigation button { grid-template-columns: 19px; justify-content: center; width: 42px; }
  .rail-profile { grid-template-columns: 32px; padding-inline: 0; }
  .app-shell__body { margin-left: 76px; }
}
@media (max-width: 767px) {
  .app-shell__rail, .mode-switcher, .command-search { display: none; }
  .app-shell__body { margin-left: 0; padding-bottom: 64px; }
  .topbar { height: 52px; grid-template-columns: minmax(0, 1fr) auto; justify-items: end; padding: 0 12px; }
  .topbar-scope { display: block; justify-self: start; max-width: calc(100vw - 70px); }
  .context-header { min-height: 72px; align-items: flex-start; padding: 13px 16px; }
  .context-header h1 { font-size: 17px; }
  .context-header__actions { display: none; }
  .app-shell__workspace { padding: 12px; }
  .mobile-mode { position: fixed; inset: auto 0 0; z-index: 20; display: grid; height: 60px; grid-template-columns: 1fr 1fr; border-top: 1px solid var(--cs-border); background: rgb(255 255 255 / 96%); }
  .mobile-mode a { display: flex; align-items: center; justify-content: center; gap: 7px; color: var(--cs-text-muted); font-size: 11px; font-weight: 700; }
  .mobile-mode a.active { color: var(--cs-brand-700); }
  .network-banner { min-height: 40px; padding-inline: 12px; font-size: 9px; }
}
</style>
