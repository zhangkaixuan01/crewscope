<script setup lang="ts">
import {
  Activity,
  Bell,
  BookOpen,
  Bot,
  BriefcaseBusiness,
  Building2,
  CalendarDays,
  ChartColumn,
  ChartPie,
  Command,
  LayoutDashboard,
  MessageSquare,
  Search,
  ShieldCheck,
  UsersRound,
  GitFork,
  Inbox,
  Layers,
  ListTree,
  ScanSearch,
  KeyRound,
  Send,
  Gauge,
  Settings2,
  PanelLeftClose,
  PanelLeftOpen,
} from '@lucide/vue'
import { computed, inject, nextTick, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter, type RouteLocationRaw } from 'vue-router'
import { queryValue } from '../../utils/queryParams'
import { AUTH_PRINCIPAL, can, permissions } from '../../app/auth'
import { defaultLoginDestination } from '../../domains/identity/route'
import { useNetworkStatus } from '../../app/network'
import { SCOPE_STORE } from '../../domains/scope/store'
import { IDENTITY_GATEWAY } from '../../domains/identity/gateway'
import { AUTH_STORE } from '../../domains/identity/store'
import { COLLABORATION_STORE } from '../../domains/collaboration/store'
import { collaborationPhaseLabels } from '../../domains/collaboration/labels'
import { TEAM_OPS_STORE } from '../../domains/teamops/store'
import { CrewScopeApiError } from '../../api/client'
import crewScopeMark from '../../design/crewscope-mark.svg'
import ScopeSwitcher from '../domain/ScopeSwitcher.vue'
import AppBreadcrumb from './AppBreadcrumb.vue'
import UserAccountMenu from './UserAccountMenu.vue'
import { usePreference } from '../../app/preference'
import { requestCommandPalette } from '../../app/shortcuts'
import BaseDrawer from '../base/BaseDrawer.vue'

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
// M11-F01: the collaboration channel state rides the topbar as a quiet pill — live is a
// dot, reconnecting pulses, degraded becomes the retry affordance. connecting and idle
// stay unlabeled to avoid a flash on every startup. The store is optional so story and
// spec mounts of the shell render without it.
const collaborationStore = inject(COLLABORATION_STORE, null)
const collaborationPhase = computed(() => collaborationStore?.state.phase ?? 'idle')
const collaborationLabel = computed(() => collaborationPhaseLabels[collaborationPhase.value])
// M13b-F06（R24）：铃铛徽标读 Inbox /counts 的未读数。与 collaboration 同为可选注入——
// story/spec 挂壳不装 teamops store 也能渲染；计数失败静默（无徽标即「未知」，不吵）。
const teamOpsStore = inject(TEAM_OPS_STORE, null)
const inboxUnread = computed(() => teamOpsStore?.state.inboxCounts.value?.unread ?? 0)
const bellLabel = computed(() => inboxUnread.value > 0 ? `打开通知 Inbox，${inboxUnread.value} 条未读` : '打开通知 Inbox')
const activeMode = computed(() => route.meta.mode)
const activeSection = computed(() => route.meta.section)
const modeTarget = (name: 'conversation' | 'today') => ({ name, query: route.query })
const canReadScope = computed(() => Boolean(principal && can(principal, permissions.scopeRead)))
const canConversation = computed(() => Boolean(principal && can(principal, permissions.conversationUse)))
const canWork = computed(() => Boolean(principal && can(principal, permissions.workRead)))
// M13b-F01（R01 复现路径 3）：品牌、模式切换与移动端底栏固定指向 Today/对话/工作项——对没有
// 对应能力的会话（纯组织角色）这是循环回 access-denied 的死链。目标与可见性跟随会话能力。
const brandTarget = computed<RouteLocationRaw>(() => canReadScope.value
  ? modeTarget('today')
  : { path: defaultLoginDestination(principal) })
const isOnline = useNetworkStatus()
let scopeSynchronizationVersion = 0
const signingOut = ref(false)
const signOutError = ref<string | null>(null)
const railCollapsed = usePreference('cs.pref.device.rail-collapsed.v1', false, { version: 1 })
const railCollapsedValue = computed(() => railCollapsed.value.value)
const mobileNavOpen = ref(false)
const mobileTouchStartX = ref<number | null>(null)

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
    { label: '知识库', icon: BookOpen, name: 'knowledge-base', section: 'knowledge', permission: permissions.scopeRead },
    { label: '索引作业', icon: Layers, name: 'knowledge-index', section: 'knowledge', permission: permissions.scopeRead },
    { label: 'Skill 目录', icon: ListTree, name: 'skill-catalog', section: 'skills', permission: permissions.scopeRead },
    { label: '成本质量', icon: ChartColumn, name: 'observability', section: 'observability', permission: permissions.scopeRead },
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
  // M13b-F01（R01）：纯组织角色（ORG_* 投影的 org:* 权限）此前在侧栏没有任何入口——三个
  // team 粒度组全被过滤后，治理页只剩 URL 直达。org:audit-read 是 ORG_AUDITOR/ADMIN/OWNER
  // 共同投影的最小可见集。
  { label: '组织', items: [
    { label: '组织设置', icon: Building2, name: 'organization-settings', section: 'organization', permission: permissions.orgAuditRead },
    { label: '配额与用量', icon: ChartPie, name: 'quota-settings', section: 'organization', permission: permissions.orgAuditRead },
  ] },
]
const visibleNavigationGroups = computed(() => navigationGroups.map(group => ({
  ...group,
  items: group.items.filter(item => principal && can(principal, item.permission)),
})).filter(group => group.items.length > 0))
const navigationTarget = (name: string): RouteLocationRaw => ({ name, query: route.query })
/** M13b-F11（I13）：页面切换后接收键盘焦点的 `<main>`；query/hash 变化不是换页，不在此列。 */
const workspaceEl = ref<HTMLElement | null>(null)

watch(
  // Multi-source form: Vue compares array-getter results by identity, so any query change
  // (a filter, a sort key) would re-run this watcher and flash the whole page through
  // scopeStore's 'loading' phase. Watching the two values as separate sources keeps the
  // scope restoration tied to the team/project actually changing.
  [() => route.query.team, () => route.query.project] as const,
  async ([team, project]) => {
    if (!scopeStore || !canReadScope.value) return
    // synchronize() overwrites the store's selection, so capture the team being left first —
    // it is the only signal that distinguishes a real Team switch from a first entry.
    const previousTeamId = scopeStore.state.selectedTeamId
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

    const leftTeam = previousTeamId !== null && previousTeamId !== selection.teamId
    // A route whose query whitelist cannot carry `team` (the org-grain organization settings,
    // M12-F01) can never hold the selection id: reading its bare URL as a team mismatch would
    // canonicalize on every cold load — deleting the page's own `tab` parameter and bouncing a
    // deep link back to the default view. Such routes keep their query untouched.
    const carriesTeamScope = !(Array.isArray(route.meta.queryWhitelist)
      && !route.meta.queryWhitelist.includes('team'))
    const teamChanged = carriesTeamScope && queryValue(route.query.team) !== selection.teamId
    // A URL that carries no explicit project gets the default project filled in — that alone is
    // canonicalization, not a member-driven scope switch. Deep-link coordinates (conversation,
    // focus, workItem) were authored against the Team as a whole and survive the fill-in; they
    // are invalidated by a Team actually left (ScopeSwitcher clears `project` on that switch)
    // or by a project the URL does carry yet no longer matches the selection. Treating the bare
    // fill-in as a switch swallowed conversation deep links at first entry (M9b-Q02).
    const projectSwitched = queryValue(route.query.project) !== null
      && queryValue(route.query.project) !== selection.projectId
    const scopeChanged = leftTeam || teamChanged || projectSwitched
    if (scopeChanged) {
      // Object identity belongs to the original Scope and cannot survive URL canonicalization.
      delete nextQuery.workItem
      delete nextQuery.focus
      delete nextQuery.conversation
      if (teamChanged || leftTeam) {
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
        delete nextQuery.entry
        delete nextQuery.revision
        delete nextQuery.event
        delete nextQuery.inboxItem
        delete nextQuery.assistant
        delete nextQuery.session
        delete nextQuery.invocation
        delete nextQuery.projection
        delete nextQuery.recovery
      }
      // URL 规范化是尽力而为：与成员自己的导航并发时 vue-router 会拒绝这次 replace
      // （被更新的目标取代；目标是未命名路由时 resolve 直接同步抛出）。Scope 事实此刻
      // 已经同步，下一次路由变化会重新对齐 —— 捕获掉，不让竞态变成未处理的拒绝。
      try { await router.replace({ query: nextQuery }) } catch { /* 竞态下的规范化放弃 */ }
    }
  },
  { immediate: true },
)

/*
 * M13b-F11（I13）：换页后键盘焦点落主工作区——键盘/读屏用户不必每页重新跳过侧栏。只在 path
 * 真正变化时执行（query/hash 是页内状态）；首载不抢（登录落地页自身的焦点语义优先）；不滚动
 * （滚动契约归 scrollBehavior，C1）。
 */
watch(() => route.path, async () => {
  await nextTick()
  workspaceEl.value?.focus({ preventScroll: true })
})

// Esc、backdrop、关闭钮与焦点归还都由 BaseDrawer 的焦点陷阱承担（F02 收敛）。
function closeMobileNav(): void {
  mobileNavOpen.value = false
}

// M13b-F06（R24）：Scope 就绪即对齐 teamops store 的激活域并读一次 Inbox 计数——activateScope
// 同域幂等，不会踩掉 Inbox 页已加载的事实；换 Team 时它重置计数，徽标跟随重拉。Inbox 页处置后
// 的强制刷新落进同一个 store，徽标与页面同源联动。
watch(
  [() => scopeStore?.state.phase, () => scopeStore?.state.selectedTeamId, isOnline] as const,
  ([phase, teamId, online]) => {
    if (!teamOpsStore || phase !== 'ready' || !teamId || !online || !canReadScope.value) return
    const scope = { organizationId: principal?.organizationId ?? '', teamId }
    if (!scope.organizationId) return
    teamOpsStore.activateScope(scope)
    // loadResource 内部把失败落进 counts.error（不 reject）——徽标对失败静默。
    void teamOpsStore.loadInboxCounts()
  },
  { immediate: true },
)
function onMobileTouchStart(event: TouchEvent): void { mobileTouchStartX.value = event.changedTouches[0]?.clientX ?? null }
function onMobileTouchEnd(event: TouchEvent): void {
  const start = mobileTouchStartX.value
  mobileTouchStartX.value = null
  const end = event.changedTouches[0]?.clientX ?? start
  if (start !== null && end !== undefined && start - end > 56) closeMobileNav()
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
      <RouterLink class="brand" :to="brandTarget" aria-label="CrewScope 首页">
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
        <button class="mobile-menu-toggle" type="button" aria-label="打开主导航" :aria-expanded="mobileNavOpen" aria-controls="mobile-navigation" @click="mobileNavOpen = true"><PanelLeftOpen :size="18" aria-hidden="true" /></button>
        <div v-if="canConversation || canReadScope" class="mode-switcher" role="group" aria-label="工作模式">
          <RouterLink v-if="canConversation" :class="{ active: activeMode === 'conversation' }" :to="modeTarget('conversation')" :aria-current="activeMode === 'conversation' ? 'page' : undefined">
            <MessageSquare :size="16" aria-hidden="true" />对话
          </RouterLink>
          <RouterLink v-if="canReadScope" :class="{ active: activeMode === 'control' }" :to="modeTarget('today')" :aria-current="activeMode === 'control' ? 'page' : undefined">
            <LayoutDashboard :size="16" aria-hidden="true" />工作台
          </RouterLink>
        </div>
        <ScopeSwitcher v-if="scopeStore && canReadScope" class="topbar-scope" />
        <button class="command-search" type="button" aria-label="打开命令面板，搜索工作、成员或 Agent" aria-haspopup="dialog" aria-keyshortcuts="Meta+K Control+K" @click="requestCommandPalette()">
          <Search :size="16" aria-hidden="true" /><span>搜索工作、成员或 Agent</span><kbd><Command :size="11" /> K</kbd>
        </button>
        <div class="topbar-actions">
          <span v-if="collaborationLabel" class="collab-pill" :class="`collab-pill--${collaborationPhase}`" role="status" aria-live="polite">
            <span class="collab-pill__dot" aria-hidden="true" />
            <button v-if="collaborationPhase === 'degraded'" type="button" class="collab-pill__retry" @click="collaborationStore?.retry()">{{ collaborationLabel }}</button>
            <!-- M13b-F05（R37）: on the ≤767 five-column topbar the pill visually collapses to its
                 dot — the label stays for screen readers (aria-live announces state changes). -->
            <span v-else class="collab-pill__label">{{ collaborationLabel }}</span>
          </span>
          <!-- M13b-F06（R24）：未读徽标并入按钮 aria-label；徽标本体装饰化，不拦截触控。 -->
          <button class="icon-button topbar-bell" type="button" :aria-label="bellLabel" @click="router.push({ name: 'inbox', query: route.query })">
            <Bell :size="18" />
            <span v-if="inboxUnread > 0" class="topbar-bell__badge" aria-hidden="true">{{ inboxUnread > 99 ? '99+' : inboxUnread }}</span>
          </button>
        </div>
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

      <main id="main-workspace" ref="workspaceEl" class="app-shell__workspace" :class="{ 'app-shell__workspace--fill': fill }" tabindex="-1"><slot /></main>
    </div>

    <BaseDrawer :open="mobileNavOpen" title="移动端主导航" side="left" width="min(300px, 86vw)" padding="var(--cs-space-16)" close-label="关闭主导航" @close="closeMobileNav">
      <nav id="mobile-navigation" class="mobile-navigation__links" @touchstart.passive="onMobileTouchStart" @touchend.passive="onMobileTouchEnd">
        <template v-for="group in visibleNavigationGroups" :key="group.label">
          <p>{{ group.label }}</p>
          <RouterLink v-for="item in group.items" :key="item.label" :to="navigationTarget(item.name)" :aria-current="activeSection === item.section ? 'page' : undefined" @click="closeMobileNav"><component :is="item.icon" :size="17" aria-hidden="true" /><span>{{ item.label }}</span></RouterLink>
        </template>
      </nav>
    </BaseDrawer>

    <nav v-if="canReadScope || canWork || canConversation" class="mobile-mode" aria-label="移动端底部导航">
      <RouterLink v-if="canReadScope" :class="{ active: activeSection === 'today' }" :to="modeTarget('today')" :aria-current="activeSection === 'today' ? 'page' : undefined"><CalendarDays :size="18" />今日</RouterLink>
      <RouterLink v-if="canWork" :class="{ active: activeSection === 'work' }" :to="navigationTarget('work')" :aria-current="activeSection === 'work' ? 'page' : undefined"><BriefcaseBusiness :size="18" />工作</RouterLink>
      <RouterLink v-if="canConversation" :class="{ active: activeSection === 'conversation' }" :to="modeTarget('conversation')" :aria-current="activeSection === 'conversation' ? 'page' : undefined"><MessageSquare :size="18" />对话</RouterLink>
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
.skip-link { position: fixed; top: 8px; left: 8px; z-index: var(--cs-z-toast); padding: var(--cs-space-8) var(--cs-space-12); border-radius: var(--cs-radius-sm); background: var(--cs-action-primary); color: var(--cs-action-primary-text); font-size: var(--cs-text-sm); transform: translateY(-160%); }.skip-link:focus { transform: translateY(0); }
.network-banner { position: relative; z-index: var(--cs-z-banner); display: flex; min-height: 36px; align-items: center; justify-content: center; gap: var(--cs-space-8); padding: var(--cs-space-8) var(--cs-space-16); border-bottom: 1px solid var(--cs-danger); background: var(--cs-danger-soft); color: var(--cs-danger); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); text-align: center; }.network-banner span { color: var(--cs-danger); }
.app-shell__rail { position: fixed; inset: 0 auto 0 0; z-index: var(--cs-z-raised); display: flex; width: var(--cs-rail-width); height: 100vh; height: 100dvh; min-height: 0; flex-direction: column; padding: var(--cs-space-20) var(--cs-space-16) var(--cs-space-16); border-right: 1px solid var(--cs-border); background: var(--cs-surface); color: var(--cs-text); }
/* M13b-F01：组织组加入后 nav 溢出转为滚动，flex 收缩曾把 30px 折叠钮压到 23px（WCAG
 * target-size serious）——rail 的固定行全部不参与收缩，溢出只由 .rail-navigation 承担。 */
.rail-collapse { display: grid; flex: 0 0 auto; width: 32px; height: 30px; align-items: center; justify-content: center; align-self: flex-end; margin: 0 0 var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text-muted); cursor: pointer; }
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
/* flex-shrink:0：fill 模式的列布局里，固定高度的 chrome 不参与内容溢出的分摊（L03 合同
   「chrome 保持自然高度，workspace 接收剩余视口」）。实测去掉顶栏按钮后内容基线变化，
   58px 的 topbar 一度被压到 42px，整页纵向链全部位移。 */
.topbar { position: relative; z-index: var(--cs-z-sticky); display: grid; flex-shrink: 0; height: 58px; grid-template-columns: auto minmax(240px, 440px) auto; align-items: center; justify-content: space-between; gap: var(--cs-space-12); padding: 0 var(--cs-space-24); border-bottom: 1px solid var(--cs-border); background: var(--cs-surface); backdrop-filter: blur(12px); }
.mode-switcher { display: flex; gap: var(--cs-space-4); padding: var(--cs-space-4); border: 1px solid var(--cs-border); border-radius: 10px; background: var(--cs-surface-subtle); }
.mode-switcher a { display: flex; min-height: 31px; align-items: center; gap: var(--cs-space-8); padding: 0 var(--cs-space-12); border-radius: 7px; color: var(--cs-text-muted); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.mode-switcher a.active { background: var(--cs-surface); box-shadow: var(--cs-shadow-hairline); color: var(--cs-text); }
.command-search { display: grid; grid-template-columns: 18px 1fr auto; align-items: center; gap: var(--cs-space-8); width: 100%; min-height: 34px; padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-text-muted); font-size: var(--cs-text-sm); text-align: left; cursor: pointer; }
.command-search kbd { display: flex; align-items: center; gap: var(--cs-space-2); padding: var(--cs-space-2) var(--cs-space-4); border: 1px solid var(--cs-border); border-radius: 5px; background: var(--cs-surface); font: var(--cs-text-xs) var(--cs-font-sans); }
.topbar-actions { display: flex; align-items: center; gap: var(--cs-space-8); }
.collab-pill { display: inline-flex; align-items: center; gap: var(--cs-space-4); color: var(--cs-text-muted); font-size: var(--cs-text-xs); white-space: nowrap; }
.collab-pill__dot { width: 8px; height: 8px; border-radius: 50%; background: var(--cs-success); }
.collab-pill--reconnecting .collab-pill__dot { background: var(--cs-warning); animation: collab-pulse var(--cs-motion-slow) var(--cs-ease-in-out) infinite alternate; }
.collab-pill--degraded .collab-pill__dot { background: var(--cs-text-muted); }
.collab-pill__label { min-width: 0; }
.collab-pill__retry { border: 0; padding: 0; background: none; color: inherit; font: inherit; text-decoration: underline; cursor: pointer; }
.collab-pill__retry:focus-visible { outline: none; box-shadow: var(--cs-focus-ring); border-radius: 4px; }
@keyframes collab-pulse { from { opacity: .35; } to { opacity: 1; } }
.icon-button { display: grid; width: var(--cs-density-control-height); height: var(--cs-density-control-height); flex: 0 0 var(--cs-density-control-height); place-items: center; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text-secondary); cursor: pointer; }
/* M13b-F06（R24）：铃铛未读徽标——右上角悬浮计数，纯装饰不参与点击。 */
.topbar-bell { position: relative; }
.topbar-bell__badge { position: absolute; top: -7px; right: -7px; display: grid; min-width: 19px; height: 19px; place-items: center; padding: 0 var(--cs-space-4); border-radius: 999px; background: var(--cs-danger); color: var(--cs-text-on-semantic); font: var(--cs-weight-semibold) var(--cs-text-xs) var(--cs-font-sans); pointer-events: none; box-shadow: 0 0 0 2px var(--cs-surface); }
.topbar-scope { display: none; }
.mobile-profile { display: none; }
/* fill 模式下 app-shell__body 是 flex column，header 是文档头而不是弹性区：没有这行
 * flex-shrink:0，767 档一旦 workspace 的内容需求（conversation 的 min-height 链）超过
 * 剩余空间，header 会被压短 ~37px，actions 按钮行溢出 header 底缘、叠进 workspace 顶部
 * 的 mobile-back——触控目标被遮掉一半（M9b-Q01 六视口矩阵顺带揭出的潜伏缺陷）。 */
/* M13b-F09（L15）：面包屑退出一级页面后，eyebrow+h1 两行 51px 就是全部内容——垂直 padding
 * 收到 4px，min-height 82→64，全站每页首屏多出 18px 给真实内容（契约 §7 首条数据 ≤200px）。 */
.context-header { display: flex; min-height: 64px; flex-shrink: 0; align-items: center; justify-content: space-between; gap: var(--cs-space-20); padding: var(--cs-space-4) var(--cs-space-32); border-bottom: 1px solid var(--cs-border); background: var(--cs-surface); }
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
  .app-shell__rail, .mode-switcher { display: none; }
  /*
   * 手机顶栏（L02）：导航开关 / 范围 / 搜索 / 通知。搜索不再整块隐藏，而是收成图标按钮
   * 打开命令面板——触屏没有 Meta+K，这个图标是唯一的入口。
   */
  .command-search { grid-template-columns: 18px; width: var(--cs-touch-min); min-height: var(--cs-touch-min); justify-items: center; flex: 0 0 auto; }
  .command-search span, .command-search kbd { display: none; }
  .mobile-menu-toggle { display: grid !important; }
  .app-shell__body { margin-left: 0; padding-bottom: var(--cs-space-64); }
  .topbar { height: 52px; grid-template-columns: auto minmax(0, 1fr) auto auto auto; justify-items: end; gap: var(--cs-space-8); padding: 0 var(--cs-space-12); }
  /* M13b-F05（R37）: the pill shrinks to its dot — the label survives for screen readers inside
     the aria-live status, and the degraded retry keeps a capped, ellipsized text of its own. */
  .collab-pill__label { position: absolute; width: 1px; height: 1px; min-width: 0; padding: 0; overflow: hidden; clip: rect(0 0 0 0); clip-path: inset(50%); white-space: nowrap; }
  .collab-pill__retry { max-width: 12em; overflow: hidden; text-overflow: ellipsis; }
  .topbar-scope { display: block; justify-self: stretch; min-width: 0; }
  .mobile-profile { display: block; }
  .context-header { min-height: 72px; align-items: flex-start; padding: var(--cs-space-12) var(--cs-space-16); }
  .context-header h1 { font-size: var(--cs-text-lg); }
  .context-header { display: grid; grid-template-columns: 1fr; gap: var(--cs-space-12); }
  .context-header__actions { display: flex; flex-wrap: wrap; width: 100%; }
  /* 半宽基准而不是 auto：三个操作按钮的 min-content 总宽恰好卡在 358px 容器的临界点，
   * 换不换行取决于 webfont 加载竞速——header 高度在 132/180 间抖动，mobile-back 的落位
   * 跟着抖（M9b-Q01 axe 定位 target-size 时的伴生发现）。固定 2+1 两行布局，高度确定。 */
  .context-header__actions :deep(.base-button) { flex: 1 1 calc(50% - var(--cs-space-8)); }
  .mobile-mode { position: fixed; inset: auto 0 0; z-index: var(--cs-z-sticky); display: grid; height: calc(60px + env(safe-area-inset-bottom)); max-width: 100vw; grid-template-columns: repeat(3, minmax(0, 1fr)); grid-template-rows: 1fr auto; padding-bottom: env(safe-area-inset-bottom); border-top: 1px solid var(--cs-border); background: var(--cs-surface); }
  .mobile-mode a { display: flex; align-items: center; justify-content: center; gap: var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
  .mobile-mode a.active { color: var(--cs-text-brand); }
  .network-banner { min-height: 40px; padding-inline: var(--cs-space-12); font-size: var(--cs-text-xs); }
}
.mobile-menu-toggle { display: none; width: var(--cs-touch-min); height: var(--cs-touch-min); place-items: center; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text-secondary); cursor: pointer; }
.mobile-navigation__links { display: grid; gap: var(--cs-space-4); padding-top: var(--cs-space-8); }.mobile-navigation__links p { margin: var(--cs-space-12) var(--cs-space-8) var(--cs-space-4); color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.mobile-navigation__links a { display: flex; min-height: 42px; align-items: center; gap: var(--cs-space-8); padding: 0 var(--cs-space-12); border-radius: var(--cs-radius-sm); color: var(--cs-text-secondary); font-size: var(--cs-text-base); text-decoration: none; }.mobile-navigation__links a:hover, .mobile-navigation__links a[aria-current='page'] { background: var(--cs-surface-accent); color: var(--cs-text-brand-strong); font-weight: var(--cs-weight-semibold); }
</style>
