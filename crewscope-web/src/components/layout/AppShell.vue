<script setup lang="ts">
import {
  Activity,
  Bell,
  Bot,
  ChevronDown,
  CircleUserRound,
  Command,
  Gauge,
  Inbox,
  LayoutDashboard,
  MessageSquare,
  Search,
  Settings,
  ShieldCheck,
  Workflow,
} from '@lucide/vue'
import { computed, inject } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { AUTH_PRINCIPAL } from '../../app/auth'
import crewScopeMark from '../../design/crewscope-mark.svg'

defineProps<{
  title: string
  eyebrow: string
}>()

const route = useRoute()
const principal = inject(AUTH_PRINCIPAL)
const activeMode = computed(() => route.meta.mode)
const modeTarget = (name: 'conversation' | 'control') => ({ name, query: route.query })

const navigation = [
  { label: '团队首页', icon: Gauge },
  { label: '我的工作', icon: CircleUserRound },
  { label: '待处理', icon: Inbox, count: 3 },
  { label: 'WorkGraph', icon: Workflow },
  { label: 'Agent 与能力', icon: Bot },
  { label: 'Activity', icon: Activity },
  { label: '治理与设置', icon: ShieldCheck },
]
</script>

<template>
  <div class="app-shell">
    <aside class="app-shell__rail" aria-label="主导航">
      <RouterLink class="brand" :to="modeTarget('conversation')" aria-label="CrewScope 首页">
        <img :src="crewScopeMark" alt="" width="34" height="34">
        <span>CrewScope<small>Team execution</small></span>
      </RouterLink>

      <button class="scope-switcher" type="button">
        <span class="scope-switcher__avatar">P</span>
        <span>Platform Engineering<small>Acme Technology</small></span>
        <ChevronDown :size="14" aria-hidden="true" />
      </button>

      <nav class="rail-navigation">
        <p>Workspace</p>
        <button v-for="item in navigation.slice(0, 3)" :key="item.label" type="button">
          <component :is="item.icon" :size="17" aria-hidden="true" />
          <span>{{ item.label }}</span><em v-if="item.count">{{ item.count }}</em>
        </button>
        <p>Operate</p>
        <button v-for="item in navigation.slice(3, 6)" :key="item.label" type="button">
          <component :is="item.icon" :size="17" aria-hidden="true" />
          <span>{{ item.label }}</span>
        </button>
        <p>System</p>
        <button v-for="item in navigation.slice(6)" :key="item.label" type="button">
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
      <header class="topbar">
        <div class="mode-switcher" aria-label="工作模式">
          <RouterLink :class="{ active: activeMode === 'conversation' }" :to="modeTarget('conversation')">
            <MessageSquare :size="16" aria-hidden="true" />对话
          </RouterLink>
          <RouterLink :class="{ active: activeMode === 'control' }" :to="modeTarget('control')">
            <LayoutDashboard :size="16" aria-hidden="true" />控制台
          </RouterLink>
        </div>
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

      <main class="app-shell__workspace"><slot /></main>
    </div>

    <nav class="mobile-mode" aria-label="移动端工作模式">
      <RouterLink :class="{ active: activeMode === 'conversation' }" :to="modeTarget('conversation')"><MessageSquare :size="18" />对话</RouterLink>
      <RouterLink :class="{ active: activeMode === 'control' }" :to="modeTarget('control')"><LayoutDashboard :size="18" />控制台</RouterLink>
    </nav>
  </div>
</template>

<style scoped>
.app-shell { min-height: 100vh; background: var(--cs-canvas); }
.app-shell__rail { position: fixed; inset: 0 auto 0 0; z-index: 10; display: flex; width: 244px; flex-direction: column; padding: 18px 14px 14px; border-right: 1px solid #d8e4db; background: #f5faf6; color: var(--cs-text); }
.brand { display: flex; align-items: center; gap: 10px; padding: 0 5px; font-family: var(--cs-font-display); font-size: 18px; }
.brand img { border: 1px solid rgb(184 239 202 / 24%); border-radius: 11px; }
.brand span, .brand small { display: block; }
.brand small { color: var(--cs-text-muted); font-family: var(--cs-font-sans); font-size: 9px; font-weight: 600; letter-spacing: .07em; text-transform: uppercase; }
.scope-switcher { display: grid; grid-template-columns: 30px 1fr 14px; align-items: center; gap: 8px; width: 100%; margin: 22px 0 18px; padding: 9px; border: 1px solid #d4e2d7; border-radius: var(--cs-radius-md); background: rgb(255 255 255 / 82%); color: inherit; text-align: left; cursor: pointer; }
.scope-switcher__avatar { display: grid; width: 30px; height: 30px; place-items: center; border-radius: 9px; background: var(--cs-brand-200); color: var(--cs-brand-950); font-size: 12px; font-weight: 800; }
.scope-switcher > span:nth-child(2), .scope-switcher small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.scope-switcher > span:nth-child(2) { font-size: 12px; font-weight: 700; }
.scope-switcher small { color: var(--cs-text-muted); font-size: 10px; font-weight: 500; }
.rail-navigation { flex: 1; }
.rail-navigation p { margin: 15px 10px 6px; color: #789080; font-size: 9px; font-weight: 750; letter-spacing: .1em; text-transform: uppercase; }
.rail-navigation button { display: grid; grid-template-columns: 19px 1fr auto; align-items: center; gap: 9px; width: 100%; min-height: 37px; padding: 0 10px; border-radius: var(--cs-radius-sm); background: transparent; color: #4d6256; font-size: 12px; text-align: left; cursor: pointer; }
.rail-navigation button:hover { background: var(--cs-brand-100); color: var(--cs-brand-800); }
.rail-navigation em { min-width: 20px; padding: 2px 5px; border-radius: var(--cs-radius-pill); background: var(--cs-warning); color: white; font-size: 9px; font-style: normal; text-align: center; }
.rail-profile { display: grid; grid-template-columns: 32px 1fr 16px; align-items: center; gap: 9px; padding: 11px 8px 3px; border-top: 1px solid #d8e4db; }
.rail-profile > span { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 50%; background: var(--cs-brand-600); font-size: 11px; font-weight: 750; }
.rail-profile strong, .rail-profile small { display: block; }
.rail-profile strong { font-size: 11px; }.rail-profile small { color: var(--cs-text-muted); font-size: 9px; }
.app-shell__body { min-height: 100vh; margin-left: 244px; }
.topbar { display: grid; height: 58px; grid-template-columns: auto minmax(240px, 440px) auto; align-items: center; justify-content: space-between; gap: 16px; padding: 0 24px; border-bottom: 1px solid var(--cs-border); background: rgb(255 255 255 / 88%); backdrop-filter: blur(12px); }
.mode-switcher { display: flex; gap: 3px; padding: 3px; border: 1px solid var(--cs-border); border-radius: 10px; background: var(--cs-surface-subtle); }
.mode-switcher a { display: flex; min-height: 31px; align-items: center; gap: 6px; padding: 0 10px; border-radius: 7px; color: var(--cs-text-muted); font-size: 11px; font-weight: 700; }
.mode-switcher a.active { background: var(--cs-surface); box-shadow: 0 1px 3px rgb(21 35 29 / 10%); color: var(--cs-text); }
.command-search { display: grid; grid-template-columns: 18px 1fr auto; align-items: center; gap: 8px; width: 100%; min-height: 34px; padding: 0 10px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-text-muted); font-size: 11px; text-align: left; cursor: pointer; }
.command-search kbd { display: flex; align-items: center; gap: 2px; padding: 2px 5px; border: 1px solid var(--cs-border); border-radius: 5px; background: var(--cs-surface); font: 9px var(--cs-font-sans); }
.icon-button { display: grid; width: 34px; height: 34px; place-items: center; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text-secondary); cursor: pointer; }
.context-header { display: flex; min-height: 82px; align-items: center; justify-content: space-between; gap: 18px; padding: 15px 28px; border-bottom: 1px solid var(--cs-border); background: var(--cs-surface); }
.context-header p { margin-bottom: 3px; color: var(--cs-text-muted); font-size: 10px; font-weight: 700; letter-spacing: .06em; text-transform: uppercase; }
.context-header h1 { margin-bottom: 0; font-size: 20px; font-weight: 720; letter-spacing: -.02em; }
.context-header__actions { display: flex; align-items: center; gap: 8px; }
.app-shell__workspace { padding: 18px; }
.mobile-mode { display: none; }
@media (max-width: 1100px) {
  .app-shell__rail { width: 76px; align-items: center; }
  .brand > span, .scope-switcher > span:nth-child(2), .scope-switcher svg, .rail-navigation p, .rail-navigation button span, .rail-navigation em, .rail-profile div, .rail-profile svg { display: none; }
  .scope-switcher { grid-template-columns: 30px; width: auto; padding: 8px; }
  .rail-navigation button { grid-template-columns: 19px; justify-content: center; width: 42px; }
  .rail-profile { grid-template-columns: 32px; padding-inline: 0; }
  .app-shell__body { margin-left: 76px; }
}
@media (max-width: 767px) {
  .app-shell__rail, .mode-switcher, .command-search { display: none; }
  .app-shell__body { margin-left: 0; padding-bottom: 64px; }
  .topbar { height: 48px; grid-template-columns: 1fr auto; justify-items: end; padding: 0 14px; }
  .topbar::before { justify-self: start; content: 'CrewScope'; color: var(--cs-brand-950); font-family: var(--cs-font-display); font-size: 17px; }
  .context-header { min-height: 72px; align-items: flex-start; padding: 13px 16px; }
  .context-header h1 { font-size: 17px; }
  .context-header__actions { display: none; }
  .app-shell__workspace { padding: 12px; }
  .mobile-mode { position: fixed; inset: auto 0 0; z-index: 20; display: grid; height: 60px; grid-template-columns: 1fr 1fr; border-top: 1px solid var(--cs-border); background: rgb(255 255 255 / 96%); }
  .mobile-mode a { display: flex; align-items: center; justify-content: center; gap: 7px; color: var(--cs-text-muted); font-size: 11px; font-weight: 700; }
  .mobile-mode a.active { color: var(--cs-brand-700); }
}
</style>
