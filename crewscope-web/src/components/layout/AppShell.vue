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
  ShieldCheck,
  UsersRound,
  GitFork,
  Inbox,
  ScanSearch,
  KeyRound,
  Send,
  Gauge,
  Settings2,
  PanelLeftClose,
  PanelLeftOpen,
  Sun,
  Moon,
} from '@lucide/vue'
import { computed, inject, nextTick, onBeforeUnmount, ref, useTemplateRef, watch } from 'vue'
import { RouterLink, useRoute, useRouter, type RouteLocationRaw } from 'vue-router'
import { AUTH_PRINCIPAL, can, permissions } from '../../app/auth'
import { useNetworkStatus } from '../../app/network'
import { SCOPE_STORE } from '../../domains/scope/store'
import { IDENTITY_GATEWAY } from '../../domains/identity/gateway'
import { AUTH_STORE } from '../../domains/identity/store'
import { CrewScopeApiError } from '../../api/client'
import crewScopeMark from '../../design/crewscope-mark.svg'
import ScopeSwitcher from '../domain/ScopeSwitcher.vue'
import AppBreadcrumb from './AppBreadcrumb.vue'
import UserAccountMenu from './UserAccountMenu.vue'
import { isDensityPreference, isThemePreference, resolveThemePreference, usePreference } from '../../app/preference'
import { requestCommandPalette } from '../../app/shortcuts'
import { useFocusTrap } from '../../composables/useFocusTrap'

defineProps<{
  title: string
  eyebrow: string
  /**
   * App-height mode for chat-style pages (L03): the body stops growing with content and hands the
   * real remaining viewport height to the workspace, so the page can size its panels from the
   * container instead of a `calc(100vh - guessed chrome)` subtraction.
   */
  fill?: boolean
}>()

const route = useRoute()
const router = useRouter()
const principal = inject(AUTH_PRINCIPAL)
const scopeStore = inject(SCOPE_STORE)
const identityGateway = inject(IDENTITY_GATEWAY, null)
const authStore = inject(AUTH_STORE, null)
const activeMode = computed(() => route.meta.mode)
const activeSection = computed(() => route.meta.section)
const modeTarget = (name: 'conversation' | 'today') => ({ name, query: route.query })
const canReadScope = computed(() => Boolean(principal && can(principal, permissions.scopeRead)))
const isOnline = useNetworkStatus()
let scopeSynchronizationVersion = 0
const signingOut = ref(false)
const signOutError = ref<string | null>(null)
const railCollapsed = usePreference('cs.pref.device.rail-collapsed.v1', false, { version: 1 })
const themePreference = usePreference<'system' | 'light' | 'dark'>('cs.pref.device.theme.v1', 'system', { version: 1, validate: isThemePreference })
const densityPreference = usePreference<'comfortable' | 'compact'>('cs.pref.device.density.v1', 'comfortable', { version: 1, validate: isDensityPreference })
const railCollapsedValue = computed(() => railCollapsed.value.value)
const darkTheme = ref(typeof document !== 'undefined' && document.documentElement.dataset.theme === 'dark')
const isDarkTheme = computed(() => darkTheme.value)
const mobileNavOpen = ref(false)
const mobileNav = useTemplateRef<HTMLElement>('mobileNav')
const mobileNavToggle = useTemplateRef<HTMLButtonElement>('mobileNavToggle')
const mobileTouchStartX = ref<number | null>(null)
useFocusTrap(mobileNav, mobileNavOpen)

function onPreferenceChange(event: Event): void {
  const detail = (event as CustomEvent<{ key?: string, value?: unknown }>).detail
  if (detail?.key === 'cs.pref.device.theme.v1' && isThemePreference(detail.value)) themePreference.value.value = detail.value
  if (detail?.key === 'cs.pref.device.density.v1' && isDensityPreference(detail.value)) densityPreference.value.value = detail.value
}
if (typeof window !== 'undefined') window.addEventListener('crewscope:preference-change', onPreferenceChange)

const navigationGroups = [
  { label: '工作', items: [
    { label: '今日', icon: CalendarDays, name: 'today', section: 'today', permission: permissions.scopeRead },
    { label: '对话', icon: MessageSquare, name: 'conversation', section: 'conversation', permission: permissions.conversationUse },
    { label: '工作项', icon: BriefcaseBusiness, name: 'work', section: 'work', permission: permissions.workRead },
    { label: '动态', icon: Activity, name: 'activity', section: 'activity', permission: permissions.scopeRead },
    { label: '我的 Inbox', icon: Inbox, name: 'inbox', section: 'inbox', permission: permissions.scopeRead },
    { label: '运行与发布', icon: Gauge, name: 'operations', section: 'operations', permission: permissions.scopeRead },
  ] },
  { label: '团队', items: [
    { label: '团队观测', icon: ScanSearch, name: 'team-observer', section: 'team-observer', permission: permissions.scopeRead },
    { label: '团队成员', icon: UsersRound, name: 'team-members', section: 'members', permission: permissions.teamMembersRead },
    { label: '审计中心', icon: ShieldCheck, name: 'audit', section: 'audit', permission: permissions.auditRead },
  ] },
  { label: '配置', items: [
    { label: '配置中心', icon: Settings2, name: 'setup', section: 'setup', permission: permissions.scopeRead },
    { label: 'Agent 中心', icon: Bot, name: 'agent-settings', section: 'agents', permission: permissions.scopeRead },
    { label: '模型与凭证', icon: KeyRound, name: 'model-settings', section: 'models', permission: permissions.scopeRead },
    { label: '仓库设置', icon: GitFork, name: 'repository-settings', section: 'repositories', permission: permissions.repositoriesManage },
    { label: '飞书与通知', icon: Send, name: 'lark-settings', section: 'lark', permission: permissions.providerManage },
    { label: 'GitHub 集成', icon: GitFork, name: 'github-settings', section: 'github', permission: permissions.providerManage },
  ] },
]
const visibleNavigationGroups = computed(() => navigationGroups.map(group => ({
  ...group,
  items: group.items.filter(item => principal && can(principal, item.permission)),
})).filter(group => group.items.length > 0))
const navigationTarget = (name: string): RouteLocationRaw => ({ name, query: route.query })

function toggleTheme(): void {
  const next = themePreference.value.value === 'system' ? 'light' : themePreference.value.value === 'light' ? 'dark' : 'system'
  themePreference.value.value = next
  const systemDark = window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false
  const resolved = resolveThemePreference(next, systemDark)
  document.documentElement.dataset.theme = resolved
  darkTheme.value = resolved === 'dark'
}

function toggleDensity(): void { densityPreference.value.value = densityPreference.value.value === 'comfortable' ? 'compact' : 'comfortable' }
function themeLabel(): string { return themePreference.value.value === 'system' ? '跟随系统' : themePreference.value.value === 'dark' ? '深色' : '浅色' }

watch(
  () => [route.query.team, route.query.project] as const,
  async ([team, project]) => {
    if (!scopeStore || !canReadScope.value) return
    const synchronizationVersion = ++scopeSynchronizationVersion
    const selection = await scopeStore.synchronize(queryValue(team), queryValue(project))
    // Route changes can start a newer Scope restoration before the previous request settles.
    if (synchronizationVersion !== scopeSynchronizationVersion) return
    if (scopeStore.state.phase === 'error') return
    authStore?.selectTeam(selection.teamId)

    const nextQuery = { ...route.query }
    if (selection.teamId) nextQuery.team = selection.teamId
    else delete nextQuery.team
    if (selection.projectId) nextQuery.project = selection.projectId
    else delete nextQuery.project

    const teamChanged = queryValue(route.query.team) !== selection.teamId
    const scopeChanged = teamChanged || queryValue(route.query.project) !== selection.projectId
    if (scopeChanged) {
      // Object identity belongs to the original Scope and cannot survive URL canonicalization.
      delete nextQuery.workItem
      delete nextQuery.focus
      delete nextQuery.conversation
      if (teamChanged) {
        // Audit identities and Correlation graphs are Team-bound but independent of WorkProject.
        delete nextQuery.auditEvent
        delete nextQuery.chain
        delete nextQuery.initiator
        delete nextQuery.actor
        delete nextQuery.agent
        delete nextQuery.subjectType
        delete nextQuery.subjectId
        delete nextQuery.providerBinding
        delete nextQuery.correlation
        delete nextQuery.connection
        delete nextQuery.mapping
        delete nextQuery.delivery
        delete nextQuery.mappingStatus
        delete nextQuery.deliveryStatus
        delete nextQuery.deliveryType
        delete nextQuery.recipient
        delete nextQuery.tab
        delete nextQuery.member
        delete nextQuery.event
        delete nextQuery.inboxItem
        delete nextQuery.assistant
        delete nextQuery.session
        delete nextQuery.invocation
        delete nextQuery.projection
        delete nextQuery.recovery
      }
      await router.replace({ query: nextQuery })
    }
  },
  { immediate: true },
)

function closeMobileNav(): void {
  mobileNavOpen.value = false
  void nextTick(() => mobileNavToggle.value?.focus())
}
function onMobileTouchStart(event: TouchEvent): void { mobileTouchStartX.value = event.changedTouches[0]?.clientX ?? null }
function onMobileTouchEnd(event: TouchEvent): void {
  const start = mobileTouchStartX.value
  mobileTouchStartX.value = null
  const end = event.changedTouches[0]?.clientX ?? start
  if (start !== null && end !== undefined && start - end > 56) closeMobileNav()
}
function onGlobalKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape' && mobileNavOpen.value) { event.preventDefault(); closeMobileNav() }
}
if (typeof window !== 'undefined') window.addEventListener('keydown', onGlobalKeydown)
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onGlobalKeydown)
  window.removeEventListener('crewscope:preference-change', onPreferenceChange)
})
watch(mobileNavOpen, async open => { if (open) await nextTick(() => mobileNav.value?.focus()) })
watch(themePreference.value, value => {
  const systemDark = window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false
  darkTheme.value = resolveThemePreference(value, systemDark) === 'dark'
})

function queryValue(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null
}

async function signOut(): Promise<void> {
  signOutError.value = null
  if (!isOnline.value) {
    signOutError.value = '当前离线，恢复网络后才能退出当前设备。'
    return
  }
  const csrf = authStore?.state.session?.csrf
  if (!identityGateway || !authStore || !csrf) {
    signOutError.value = '当前会话无法完成安全退出，请重新检查页面。'
    return
  }
  signingOut.value = true
  try {
    await identityGateway.logout(csrf)
    authStore.signOutLocally()
  } catch (error) {
    signOutError.value = error instanceof CrewScopeApiError && error.envelope.code === 'network_unavailable'
      ? '网络连接不可用，当前设备尚未退出。'
      : '退出服务暂时不可用，当前设备尚未退出。'
  } finally {
    signingOut.value = false
  }
}
</script>

<template>
  <div class="app-shell" :class="{ 'app-shell--collapsed': railCollapsedValue }">
    <a class="skip-link" href="#main-workspace">跳到主要内容</a>
    <aside class="app-shell__rail" aria-label="主导航">
      <RouterLink class="brand" :to="modeTarget('conversation')" aria-label="CrewScope 首页">
        <img :src="crewScopeMark" alt="" width="34" height="34">
        <span>CrewScope<small>Team execution</small></span>
      </RouterLink>
      <button class="rail-collapse" type="button" :aria-label="railCollapsedValue ? '展开侧栏' : '折叠侧栏'" @click="railCollapsed.value.value = !railCollapsedValue">
        <PanelLeftOpen v-if="railCollapsedValue" :size="16" aria-hidden="true" /><PanelLeftClose v-else :size="16" aria-hidden="true" />
      </button>

      <ScopeSwitcher v-if="scopeStore && canReadScope" />

      <nav class="rail-navigation">
        <template v-for="group in visibleNavigationGroups" :key="group.label">
          <p>{{ group.label }}</p>
          <RouterLink v-for="item in group.items" :key="item.label" :class="{ active: activeSection === item.section }" :to="navigationTarget(item.name)" :aria-current="activeSection === item.section ? 'page' : undefined">
            <component :is="item.icon" :size="17" aria-hidden="true" />
            <span>{{ item.label }}</span>
          </RouterLink>
        </template>
      </nav>

      <UserAccountMenu
        class="rail-profile"
        :display-name="principal?.displayName ?? ''"
        :role="principal?.role ?? ''"
        :pending="signingOut"
        :error="signOutError"
        @sign-out="signOut"
      />
    </aside>

    <div class="app-shell__body" :class="{ 'app-shell__body--fill': fill }">
      <div v-if="!isOnline" class="network-banner" role="status" aria-live="polite" aria-atomic="true">
        <span aria-hidden="true">●</span>当前离线：已加载事实和草稿已保留，联网后可继续提交。
      </div>
      <div class="topbar" role="region" aria-label="全局工具栏">
        <button ref="mobileNavToggle" class="mobile-menu-toggle" type="button" aria-label="打开主导航" :aria-expanded="mobileNavOpen" aria-controls="mobile-navigation" @click="mobileNavOpen = true"><PanelLeftOpen :size="18" aria-hidden="true" /></button>
        <div class="mode-switcher" aria-label="工作模式">
          <RouterLink :class="{ active: activeMode === 'conversation' }" :to="modeTarget('conversation')" :aria-current="activeMode === 'conversation' ? 'page' : undefined">
            <MessageSquare :size="16" aria-hidden="true" />对话
          </RouterLink>
          <RouterLink :class="{ active: activeMode === 'control' }" :to="modeTarget('today')" :aria-current="activeMode === 'control' ? 'page' : undefined">
            <LayoutDashboard :size="16" aria-hidden="true" />工作台
          </RouterLink>
        </div>
        <ScopeSwitcher v-if="scopeStore && canReadScope" class="topbar-scope" />
        <button class="command-search" type="button" aria-label="打开命令面板，搜索工作、成员或 Agent" aria-haspopup="dialog" aria-keyshortcuts="Meta+K Control+K" @click="requestCommandPalette()">
          <Search :size="16" aria-hidden="true" /><span>搜索工作、成员或 Agent</span><kbd><Command :size="11" /> K</kbd>
        </button>
        <button class="icon-button" type="button" :aria-label="`主题：${themeLabel()}，点击切换`" @click="toggleTheme"><Sun v-if="isDarkTheme" :size="18" /><Moon v-else :size="18" /></button>
        <button class="density-button" type="button" :aria-label="`密度：${densityPreference.value.value === 'comfortable' ? '舒适' : '紧凑'}，点击切换`" @click="toggleDensity">{{ densityPreference.value.value === 'comfortable' ? '舒适' : '紧凑' }}</button>
        <button class="icon-button" type="button" aria-label="打开通知 Inbox" @click="router.push({ name: 'inbox', query: route.query })"><Bell :size="18" /></button>
        <UserAccountMenu
          class="mobile-profile"
          compact
          :display-name="principal?.displayName ?? ''"
          :role="principal?.role ?? ''"
          :pending="signingOut"
          :error="signOutError"
          @sign-out="signOut"
        />
      </div>

      <header class="context-header">
        <div>
          <AppBreadcrumb />
          <p>{{ eyebrow }}</p>
          <h1>{{ title }}</h1>
        </div>
        <div class="context-header__actions"><slot name="actions" /></div>
      </header>

      <main id="main-workspace" class="app-shell__workspace" :class="{ 'app-shell__workspace--fill': fill }" tabindex="-1"><slot /></main>
    </div>

    <div v-if="mobileNavOpen" class="mobile-navigation-backdrop" @click.self="closeMobileNav">
      <aside id="mobile-navigation" ref="mobileNav" class="mobile-navigation" aria-label="移动端主导航" role="dialog" aria-modal="true" tabindex="-1" @touchstart.passive="onMobileTouchStart" @touchend.passive="onMobileTouchEnd">
        <header><strong>导航</strong><button type="button" aria-label="关闭主导航" @click="closeMobileNav"><PanelLeftClose :size="18" /></button></header>
        <nav class="mobile-navigation__links">
          <template v-for="group in visibleNavigationGroups" :key="group.label">
            <p>{{ group.label }}</p>
            <RouterLink v-for="item in group.items" :key="item.label" :to="navigationTarget(item.name)" :aria-current="activeSection === item.section ? 'page' : undefined" @click="closeMobileNav"><component :is="item.icon" :size="17" aria-hidden="true" /><span>{{ item.label }}</span></RouterLink>
          </template>
        </nav>
      </aside>
    </div>

    <nav class="mobile-mode" aria-label="移动端工作模式">
      <RouterLink :class="{ active: activeMode === 'conversation' }" :to="modeTarget('conversation')" :aria-current="activeMode === 'conversation' ? 'page' : undefined"><MessageSquare :size="18" />对话</RouterLink>
      <RouterLink :class="{ active: activeMode === 'control' }" :to="modeTarget('today')" :aria-current="activeMode === 'control' ? 'page' : undefined"><LayoutDashboard :size="18" />工作台</RouterLink>
    </nav>
  </div>
</template>

<style scoped>
/*
 * 导航栏宽度是布局尺寸而不是间距节奏，所以它是一个自定义属性而不是 --cs-space-* 档位。
 * 只写一次：收起态与 1100px 断点各自改写这个属性，主体区的 margin-left 自动跟随，
 * 不会再出现「改了栏宽忘了改偏移」的错位。
 */
.app-shell { --cs-rail-width: 244px; min-height: 100vh; background: var(--cs-canvas); }
.skip-link { position: fixed; top: 8px; left: 8px; z-index: var(--cs-z-toast); padding: var(--cs-space-8) var(--cs-space-12); border-radius: var(--cs-radius-sm); background: var(--cs-brand-950); color: var(--cs-text-on-dark); font-size: var(--cs-text-sm); transform: translateY(-160%); }.skip-link:focus { transform: translateY(0); }
.network-banner { position: relative; z-index: var(--cs-z-banner); display: flex; min-height: 36px; align-items: center; justify-content: center; gap: var(--cs-space-8); padding: var(--cs-space-8) var(--cs-space-16); border-bottom: 1px solid var(--cs-danger); background: var(--cs-danger-soft); color: var(--cs-danger); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); text-align: center; }.network-banner span { color: var(--cs-danger); }
.app-shell__rail { position: fixed; inset: 0 auto 0 0; z-index: var(--cs-z-raised); display: flex; width: var(--cs-rail-width); height: 100vh; height: 100dvh; min-height: 0; flex-direction: column; padding: var(--cs-space-20) var(--cs-space-16) var(--cs-space-16); border-right: 1px solid var(--cs-border); background: var(--cs-surface); color: var(--cs-text); }
.rail-collapse { display: grid; width: 32px; height: 30px; align-items: center; justify-content: center; align-self: flex-end; margin: 0 0 var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text-muted); cursor: pointer; }
.brand { display: flex; min-height: var(--cs-density-control-height); flex: 0 0 auto; align-items: center; gap: var(--cs-space-12); padding: 0 var(--cs-space-4); font-family: var(--cs-font-display); font-size: var(--cs-text-lg); }
.brand img { border: 1px solid var(--cs-border); border-radius: 11px; }
.brand span, .brand small { display: block; }
.brand small { color: var(--cs-text-muted); font-family: var(--cs-font-sans); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); letter-spacing: .07em; text-transform: uppercase; }
.app-shell__rail > :deep(.scope-switcher-root) { flex: 0 0 auto; }
.rail-navigation { min-height: 0; flex: 1 1 auto; overflow-x: hidden; overflow-y: auto; overscroll-behavior: contain; scrollbar-color: var(--cs-border-strong) transparent; scrollbar-width: thin; }
.rail-navigation::-webkit-scrollbar { width: 6px; }.rail-navigation::-webkit-scrollbar-track { background: transparent; }.rail-navigation::-webkit-scrollbar-thumb { border-radius: 999px; background: var(--cs-border-strong); }
.rail-navigation p { margin: var(--cs-space-16) var(--cs-space-12) var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); letter-spacing: .1em; text-transform: uppercase; }
.rail-navigation a, .rail-navigation button { display: grid; grid-template-columns: 19px 1fr auto; align-items: center; gap: var(--cs-space-8); width: 100%; min-height: 37px; padding: 0 var(--cs-space-12); border-radius: var(--cs-radius-sm); background: transparent; color: var(--cs-text-secondary); font-size: var(--cs-text-base); text-align: left; cursor: pointer; }
.rail-navigation a:hover, .rail-navigation a.active { background: var(--cs-surface-selected); color: var(--cs-nav-active-text); }.rail-navigation a.active { font-weight: var(--cs-weight-semibold); }
.rail-navigation button:disabled { cursor: not-allowed; opacity: .48; }
.rail-profile { z-index: 1; flex: 0 0 auto; margin-top: var(--cs-space-8); background: var(--cs-surface); }
.app-shell__body { min-height: 100vh; margin-left: var(--cs-rail-width); }
.app-shell--collapsed { --cs-rail-width: 76px; }
.app-shell--collapsed .app-shell__rail { align-items: center; }
.app-shell--collapsed .brand > span, .app-shell--collapsed .rail-navigation p, .app-shell--collapsed .rail-navigation a span { display: none; }
.app-shell--collapsed .rail-navigation a { grid-template-columns: 19px; justify-content: center; width: 42px; }
.app-shell--collapsed .rail-collapse { align-self: center; }
.topbar { position: relative; z-index: var(--cs-z-sticky); display: grid; height: 58px; grid-template-columns: auto minmax(240px, 440px) auto auto; align-items: center; justify-content: space-between; gap: var(--cs-space-12); padding: 0 var(--cs-space-24); border-bottom: 1px solid var(--cs-border); background: var(--cs-surface); backdrop-filter: blur(12px); }
.mode-switcher { display: flex; gap: var(--cs-space-4); padding: var(--cs-space-4); border: 1px solid var(--cs-border); border-radius: 10px; background: var(--cs-surface-subtle); }
.mode-switcher a { display: flex; min-height: 31px; align-items: center; gap: var(--cs-space-8); padding: 0 var(--cs-space-12); border-radius: 7px; color: var(--cs-text-muted); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.mode-switcher a.active { background: var(--cs-surface); box-shadow: var(--cs-shadow-hairline); color: var(--cs-text); }
.command-search { display: grid; grid-template-columns: 18px 1fr auto; align-items: center; gap: var(--cs-space-8); width: 100%; min-height: 34px; padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-text-muted); font-size: var(--cs-text-sm); text-align: left; cursor: pointer; }
.command-search kbd { display: flex; align-items: center; gap: var(--cs-space-2); padding: var(--cs-space-2) var(--cs-space-4); border: 1px solid var(--cs-border); border-radius: 5px; background: var(--cs-surface); font: var(--cs-text-xs) var(--cs-font-sans); }
.icon-button { display: grid; width: var(--cs-density-control-height); height: var(--cs-density-control-height); flex: 0 0 var(--cs-density-control-height); place-items: center; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text-secondary); cursor: pointer; }
.density-button { min-height: 30px; padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); cursor: pointer; }
.topbar-scope { display: none; }
.mobile-profile { display: none; }
.context-header { display: flex; min-height: 82px; align-items: center; justify-content: space-between; gap: var(--cs-space-20); padding: var(--cs-space-16) var(--cs-space-32); border-bottom: 1px solid var(--cs-border); background: var(--cs-surface); }
.context-header p { margin-bottom: var(--cs-space-4); color: var(--cs-text-muted); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); letter-spacing: .06em; text-transform: uppercase; }
.context-header h1 { margin-bottom: 0; font-size: var(--cs-text-lg); font-weight: var(--cs-weight-semibold); letter-spacing: -.02em; }
.context-header__actions { display: flex; align-items: center; gap: var(--cs-space-8); }
.app-shell__workspace { padding: var(--cs-density-workspace-padding); }
/* Fill mode: the chrome (banner, topbar, context header) keeps its natural height and the workspace
 * receives the real remaining viewport height; the page then flexes inside it (L03, contract §4.4). */
.app-shell__body--fill { display: flex; height: 100vh; height: 100dvh; min-height: 0; flex-direction: column; overflow: hidden; }
.app-shell__workspace--fill { display: flex; flex: 1 1 auto; flex-direction: column; min-height: 0; overflow: hidden; }
.mobile-mode { display: none; }
.mobile-menu-toggle { display: none; }
@media (max-width: 1100px) {
  .app-shell { --cs-rail-width: 76px; }
  .app-shell__rail { align-items: center; }
  .brand > span, .rail-navigation p, .rail-navigation a span, .rail-navigation button span { display: none; }
  .rail-navigation a, .rail-navigation button { grid-template-columns: 19px; justify-content: center; width: 42px; }
  .rail-profile { width: 34px; }
  .rail-profile :deep(.user-menu__trigger) { min-height: 40px; grid-template-columns: 32px; padding: var(--cs-space-4) 0; border: 0; }
  .rail-profile :deep(.user-menu__identity), .rail-profile :deep(.user-menu__trigger > svg) { display: none; }
}
@media (max-width: 767px) {
  .app-shell__rail, .mode-switcher, .command-search { display: none; }
  .mobile-menu-toggle { display: grid !important; }
  .app-shell__body { margin-left: 0; padding-bottom: var(--cs-space-64); }
  .topbar { height: 52px; grid-template-columns: minmax(0, 1fr) auto auto; justify-items: end; padding: 0 var(--cs-space-12); }
  .topbar-scope { display: block; justify-self: start; max-width: calc(100vw - 70px); }
  .mobile-profile { display: block; }
  .density-button { display: none; }
  .context-header { min-height: 72px; align-items: flex-start; padding: var(--cs-space-12) var(--cs-space-16); }
  .context-header h1 { font-size: var(--cs-text-lg); }
  .context-header { display: grid; grid-template-columns: 1fr; gap: var(--cs-space-12); }
  .context-header__actions { display: flex; flex-wrap: wrap; width: 100%; }
  .context-header__actions :deep(.base-button) { flex: 1 1 auto; }
  .mobile-mode { position: fixed; inset: auto 0 0; z-index: var(--cs-z-sticky); display: grid; height: 60px; grid-template-columns: 1fr 1fr; border-top: 1px solid var(--cs-border); background: var(--cs-surface); }
  .mobile-mode a { display: flex; align-items: center; justify-content: center; gap: var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
  .mobile-mode a.active { color: var(--cs-text-brand); }
  .network-banner { min-height: 40px; padding-inline: var(--cs-space-12); font-size: var(--cs-text-xs); }
}
.mobile-menu-toggle { display: none; width: var(--cs-touch-min); height: var(--cs-touch-min); place-items: center; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text-secondary); cursor: pointer; }
.mobile-navigation-backdrop { position: fixed; inset: 0; z-index: var(--cs-z-drawer); background: var(--cs-scrim); }
.mobile-navigation { width: min(300px, 86vw); height: 100%; padding: var(--cs-space-16); background: var(--cs-surface); box-shadow: var(--cs-shadow-float); overflow-y: auto; }
.mobile-navigation header { display: flex; align-items: center; justify-content: space-between; padding-bottom: var(--cs-space-12); border-bottom: 1px solid var(--cs-border); font-size: var(--cs-text-md); }.mobile-navigation header button { display: grid; width: 32px; height: 32px; place-items: center; border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); cursor: pointer; }
.mobile-navigation__links { display: grid; gap: var(--cs-space-4); padding-top: var(--cs-space-12); }.mobile-navigation__links p { margin: var(--cs-space-12) var(--cs-space-8) var(--cs-space-4); color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.mobile-navigation__links a { display: flex; min-height: 42px; align-items: center; gap: var(--cs-space-8); padding: 0 var(--cs-space-12); border-radius: var(--cs-radius-sm); color: var(--cs-text-secondary); font-size: var(--cs-text-base); text-decoration: none; }.mobile-navigation__links a:hover, .mobile-navigation__links a[aria-current='page'] { background: var(--cs-surface-accent); color: var(--cs-text-brand-strong); font-weight: var(--cs-weight-semibold); }
</style>
