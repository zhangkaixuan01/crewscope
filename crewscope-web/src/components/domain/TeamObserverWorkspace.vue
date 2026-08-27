<script setup lang="ts">
import {
  Activity, AlertTriangle, ArrowUpRight, Bot, CheckCircle2, CircleStop,
  ClipboardCheck, RefreshCw, ShieldCheck, Sparkles,
} from '@lucide/vue'
import { computed, inject, ref, watch } from 'vue'
import { routerKey } from 'vue-router'
import BaseButton from '../base/BaseButton.vue'
import StatusBadge from '../base/StatusBadge.vue'
import StatePanel from '../feedback/StatePanel.vue'
import { TEAM_OBSERVER_STORE, type TeamObserverStore } from '../../domains/teamobserver/store'
import type { TeamObserverScope, TeamSummaryEntry, TeamSummarySection } from '../../domains/teamobserver/types'

const props = defineProps<{
  scope: TeamObserverScope
  teamName: string
  online: boolean
  variant: 'conversation' | 'summary'
  observerStore?: TeamObserverStore
}>()

const router = inject(routerKey, null)
const store = requiredStore(props.observerStore ?? inject(TEAM_OBSERVER_STORE))
const instruction = ref('总结当前团队进展、阻塞、Review、待确认事项和异常，并给出可核验的证据。')
const openingEvidence = ref<number | null>(null)
const busy = computed(() => ['creating-session', 'connecting', 'running', 'reconnecting', 'cancelling'].includes(store.state.phase))
const statusText = computed(() => ({
  idle: '等待生成团队摘要',
  'creating-session': '正在建立只读会话', connecting: '正在连接 Team Observer', running: '正在读取团队事实',
  reconnecting: '连接中断，正在恢复同一次调用', completed: '团队摘要已生成', cancelling: '正在取消本次调用',
  cancelled: '本次调用已取消', error: store.state.errorMessage ?? 'Team Observer 暂时不可用',
})[store.state.phase])
const sections: Array<{ key: TeamSummarySection, label: string, description: string, icon: typeof Activity }> = [
  { key: 'progress', label: '进展', description: '最近推进或完成的团队工作', icon: CheckCircle2 },
  { key: 'blockers', label: '阻塞', description: '正在妨碍交付的事实', icon: CircleStop },
  { key: 'reviewBacklog', label: 'Review', description: '等待 Review 或复核的工作', icon: ClipboardCheck },
  { key: 'pendingConfirmations', label: '待确认', description: '等待成员确认的受控动作', icon: ShieldCheck },
  { key: 'anomalies', label: '异常', description: '需要关注的执行与交付异常', icon: AlertTriangle },
]

watch(
  () => [props.scope.organizationId, props.scope.teamId] as const,
  () => store.activateScope(props.scope),
  { immediate: true },
)

async function submit(): Promise<void> {
  if (!props.online || busy.value) return
  await store.invoke(instruction.value)
}

async function openEvidence(entry: TeamSummaryEntry): Promise<void> {
  if (!props.online || openingEvidence.value !== null) return
  openingEvidence.value = entry.evidenceIndex
  try {
    // Navigation occurs only after the server re-authorizes and the Gateway validates the route.
    const evidence = await store.resolveEvidence(entry.evidenceIndex)
    if (evidence?.authorized && router) await router.push(evidence.navigationPath)
  } finally {
    openingEvidence.value = null
  }
}

function dateTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

function requiredStore(value: TeamObserverStore | undefined): TeamObserverStore {
  if (!value) throw new Error('Team Observer store is not installed')
  return value
}
</script>

<template>
  <section class="observer-workspace" :class="`observer-workspace--${variant}`" aria-labelledby="team-observer-heading">
    <header class="observer-hero panel">
      <div class="observer-identity" aria-hidden="true"><Bot :size="24" /></div>
      <div>
        <p class="eyebrow">Built-in team agent · team-observer@1</p>
        <h2 id="team-observer-heading">Team Observer</h2>
        <p>读取 {{ teamName }} 的团队事实，生成面向成员的可核验摘要。</p>
      </div>
      <div class="observer-guardrail">
        <StatusBadge tone="success"><ShieldCheck :size="12" />固定只读</StatusBadge>
        <small>不能修改任务、Review、配置或外部系统</small>
      </div>
    </header>

    <StatePanel
      v-if="!online"
      compact
      state="offline"
      title="当前离线"
      description="已生成的摘要仍可阅读；生成、恢复和证据跳转暂时关闭。"
    />

    <div class="observer-status" :class="{ error: store.state.phase === 'error' }" :role="store.state.phase === 'error' ? 'alert' : 'status'" aria-live="polite" aria-atomic="true">
      <span><Sparkles :size="14" aria-hidden="true" />{{ statusText }}</span>
      <span v-if="store.state.invocationId" class="mono">Invocation {{ store.state.invocationId.slice(0, 8) }}</span>
      <BaseButton v-if="busy && store.state.invocationId" size="small" variant="ghost" :disabled="!online || store.state.phase === 'cancelling'" @click="store.cancel()">取消</BaseButton>
      <BaseButton v-else-if="store.state.phase === 'error' && store.state.retryable" size="small" variant="secondary" :disabled="!online" @click="store.retry()"><RefreshCw :size="13" />恢复调用</BaseButton>
    </div>

    <form v-if="variant === 'conversation'" class="observer-composer panel" @submit.prevent="submit">
      <label for="observer-instruction">向 Team Observer 提问</label>
      <textarea id="observer-instruction" v-model="instruction" maxlength="4000" rows="3" :disabled="busy" placeholder="例如：本周有哪些阻塞和待 Review 工作？" />
      <footer>
        <span>{{ instruction.length }} / 4000 · 仅发送查询文本与摘要条数上限</span>
        <BaseButton type="submit" :loading="busy" :disabled="!online || !instruction.trim()">生成只读摘要</BaseButton>
      </footer>
    </form>

    <div v-if="store.state.summary" class="observer-summary">
      <header>
        <div><p class="eyebrow">Authorized team summary</p><h3>团队态势</h3></div>
        <div><span>生成于 {{ dateTime(store.state.summary.generatedAt) }}</span><BaseButton size="small" variant="ghost" :disabled="!online || busy" @click="store.refreshSummary()"><RefreshCw :size="13" />刷新事实</BaseButton></div>
      </header>
      <div class="observer-section-grid">
        <section v-for="section in sections" :key="section.key" class="observer-section panel" :aria-labelledby="`observer-${section.key}`">
          <header><span><component :is="section.icon" :size="15" aria-hidden="true" /></span><div><h4 :id="`observer-${section.key}`">{{ section.label }}</h4><p>{{ section.description }}</p></div><strong>{{ store.state.summary[section.key].length }}</strong></header>
          <p v-if="store.state.summary[section.key].length === 0" class="observer-empty">当前没有可披露的{{ section.label }}事实</p>
          <ol v-else>
            <li v-for="entry in store.state.summary[section.key]" :key="entry.evidenceIndex">
              <!-- Summary remains plain text so model-produced markup cannot become executable UI. -->
              <p>{{ entry.summary }}</p>
              <footer><span>{{ entry.dataScope }}</span><button type="button" :disabled="!online || openingEvidence !== null" :aria-label="`打开${section.label}证据：${entry.summary}`" @click="openEvidence(entry)">查看证据<ArrowUpRight :size="12" aria-hidden="true" /></button></footer>
            </li>
          </ol>
        </section>
      </div>
    </div>

    <StatePanel v-else-if="store.state.phase === 'idle'" state="empty" title="尚未生成团队摘要" description="Team Observer 会从当前 Team 的授权投影读取事实，并提供可重新授权的站内证据链接。">
      <template v-if="variant === 'summary'" #action><BaseButton size="small" :disabled="!online" @click="submit">生成团队摘要</BaseButton></template>
    </StatePanel>
    <StatePanel v-else-if="store.state.phase === 'cancelled'" state="empty" title="本次调用已取消" description="可以调整问题后重新生成；取消不会修改任何团队事实。" />
  </section>
</template>

<style scoped>
.observer-workspace { display: grid; gap: 14px; max-width: 1240px; margin: 0 auto; }.observer-hero { display: grid; grid-template-columns: 52px 1fr auto; align-items: center; gap: 14px; padding: 18px; }.observer-identity { display: grid; width: 50px; height: 50px; place-items: center; border: 1px solid #d9cfeb; border-radius: 16px; background: var(--cs-agent-soft); color: var(--cs-agent); }.observer-hero h2 { margin: 2px 0 4px; font-size: 20px; }.observer-hero p:last-child { margin: 0; color: var(--cs-text-secondary); font-size: 11px; }.observer-guardrail { display: grid; justify-items: end; gap: 5px; }.observer-guardrail small { max-width: 220px; color: var(--cs-text-muted); font-size: 9px; text-align: right; }.observer-status { display: flex; min-height: 40px; align-items: center; gap: 10px; padding: 7px 10px; border: 1px solid var(--cs-brand-200); border-radius: var(--cs-radius-sm); background: var(--cs-brand-50); color: var(--cs-brand-800); font-size: 10px; }.observer-status > span:first-child { display: inline-flex; align-items: center; gap: 6px; font-weight: 700; }.observer-status > .mono { margin-left: auto; color: var(--cs-text-muted); font-size: 8px; }.observer-status.error { border-color: #ecc7c2; background: #fff6f5; color: var(--cs-danger); }.observer-composer { display: grid; gap: 8px; padding: 16px; }.observer-composer label { font-size: 11px; font-weight: 750; }.observer-composer textarea { width: 100%; min-height: 84px; resize: vertical; padding: 11px; border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: white; color: var(--cs-text); font: 12px/1.55 var(--cs-font-sans); }.observer-composer footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }.observer-composer footer span { color: var(--cs-text-muted); font-size: 9px; }.observer-summary { display: grid; gap: 10px; }.observer-summary > header { display: flex; align-items: end; justify-content: space-between; gap: 12px; padding: 4px 2px; }.observer-summary h3 { margin: 2px 0 0; font-size: 17px; }.observer-summary > header > div:last-child { display: flex; align-items: center; gap: 8px; color: var(--cs-text-muted); font-size: 9px; }.observer-section-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }.observer-section:last-child { grid-column: 1 / -1; }.observer-section { overflow: hidden; }.observer-section > header { display: grid; grid-template-columns: 32px 1fr auto; align-items: center; gap: 9px; padding: 12px 14px; border-bottom: 1px solid var(--cs-border); background: var(--cs-surface-subtle); }.observer-section > header > span { display: grid; width: 30px; height: 30px; place-items: center; border-radius: 9px; background: var(--cs-brand-100); color: var(--cs-brand-700); }.observer-section h4 { margin: 0; font-size: 12px; }.observer-section header p { margin: 2px 0 0; color: var(--cs-text-muted); font-size: 8px; }.observer-section header strong { color: var(--cs-brand-700); font-size: 14px; }.observer-section ol { display: grid; gap: 0; padding: 0; margin: 0; list-style: none; }.observer-section li { padding: 11px 14px; border-bottom: 1px solid var(--cs-border); }.observer-section li:last-child { border: 0; }.observer-section li > p { margin: 0; color: var(--cs-text-secondary); font-size: 10px; line-height: 1.55; white-space: pre-wrap; overflow-wrap: anywhere; }.observer-section li footer { display: flex; align-items: center; justify-content: space-between; margin-top: 7px; }.observer-section li footer span { color: var(--cs-text-muted); font-size: 8px; font-weight: 700; }.observer-section li button { display: inline-flex; align-items: center; gap: 3px; border: 0; background: transparent; color: var(--cs-brand-700); font-size: 9px; font-weight: 750; cursor: pointer; }.observer-section li button:disabled { cursor: wait; opacity: .55; }.observer-empty { padding: 24px 14px; margin: 0; color: var(--cs-text-muted); font-size: 10px; text-align: center; }
@media (max-width: 767px) { .observer-hero { grid-template-columns: 44px 1fr; padding: 14px; }.observer-identity { width: 42px; height: 42px; border-radius: 13px; }.observer-guardrail { grid-column: 1 / -1; justify-items: start; }.observer-guardrail small { text-align: left; }.observer-section-grid { grid-template-columns: 1fr; }.observer-section:last-child { grid-column: auto; }.observer-summary > header, .observer-composer footer { align-items: flex-start; flex-direction: column; }.observer-summary > header > div:last-child { width: 100%; justify-content: space-between; }.observer-status > .mono { display: none; }.observer-status { flex-wrap: wrap; }.observer-status :deep(.base-button) { margin-left: auto; } }
</style>
