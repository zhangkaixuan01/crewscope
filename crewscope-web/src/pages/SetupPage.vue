<script setup lang="ts">
import { ArrowRight, Bot, CheckCircle2, CircleAlert, ExternalLink, GitFork, KeyRound, RefreshCw, Settings2, ShieldCheck, UsersRound, WifiOff } from '@lucide/vue'
import { computed, inject, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AUTH_PRINCIPAL, can, permissions } from '../app/auth'
import { useNetworkStatus } from '../app/network'
import BaseButton from '../components/base/BaseButton.vue'
import StatusBadge from '../components/base/StatusBadge.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import SettingsShell from '../components/settings/SettingsShell.vue'
import { useScopeStore } from '../domains/scope/store'
import { useSetupStore } from '../domains/setup/store'
import type { ConfigurationComponent, SetupCapability, SetupReadinessItem } from '../domains/setup/types'
import { usePageRequestScope } from '../composables/usePageRequestScope'

const pageRequests = usePageRequestScope()

const route = useRoute()
const router = useRouter()
const principal = inject(AUTH_PRINCIPAL)
const scopeStore = useScopeStore()
const setupStore = useSetupStore()
const online = useNetworkStatus()
const team = scopeStore.selectedTeam

const readiness = computed(() => setupStore.state.readiness)
const capabilities = computed(() => readiness.value?.capabilities ?? [])
const requiredItems = computed(() => capabilities.value.filter(item => item.required))
const readyRequiredCount = computed(() => requiredItems.value.filter(item => item.status === 'READY').length)
const nextAction = computed(() => capabilities.value.find(item => item.status !== 'READY' && item.canConfigure && item.actionKey) ?? null)
/* 配置健康与就绪度是同一 Scope 的两份投影：各自单飞，互不影响对方的加载状态。 */
const health = computed(() => setupStore.state.health)
const healthNotice = computed(() => {
  if (setupStore.state.healthPhase === 'loading') return '正在读取配置健康…'
  if (setupStore.state.healthPhase === 'offline') return '配置健康当前离线：恢复网络后可继续读取。'
  if (setupStore.state.healthPhase === 'unavailable') return '当前部署没有提供配置健康投影。'
  if (setupStore.state.healthPhase === 'error') return setupStore.state.healthErrorMessage ?? '配置健康暂时不可用，请稍后重试。'
  return ''
})

watch(() => [scopeStore.state.selectedTeamId, team.value?.organizationId] as const, async ([teamId, organizationId]) => {
  const pageOwner = pageRequests.capture()
  if (!teamId || !organizationId) { setupStore.reset(); return }
  setupStore.activateScope({ organizationId, teamId })
  void setupStore.loadHealth()
  await setupStore.load()
  if (!pageOwner.isCurrent()) return
}, { immediate: true })

function statusLabel(status: SetupReadinessItem['status']): string {
  return { READY: '已就绪', ACTION_REQUIRED: '需要配置', BLOCKED: '权限受限', UNAVAILABLE: '暂不可用' }[status]
}
function statusTone(status: SetupReadinessItem['status']): 'success' | 'warning' | 'danger' | 'neutral' {
  return status === 'READY' ? 'success' : status === 'BLOCKED' ? 'danger' : status === 'ACTION_REQUIRED' ? 'warning' : 'neutral'
}
function capabilityLabel(value: SetupCapability): string {
  return {
    PERSONAL_CONVERSATION: 'Personal Conversation', TEAM_TASK: 'Team Task', CODING_REVIEW: 'Coding & Review',
    GITHUB_DRAFT_PR: 'GitHub Draft PR', LARK_NOTIFICATIONS: '飞书通知', TEAM_OBSERVER: 'Team Observer',
  }[value]
}
function capabilityDescription(value: SetupCapability): string {
  return {
    PERSONAL_CONVERSATION: '当前成员的 Personal Agent 与执行模型', TEAM_TASK: 'Team Agent 与共享 Runtime',
    CODING_REVIEW: 'WorkProject、受管仓库和 Coding/Reviewer Agent', GITHUB_DRAFT_PR: '团队 GitHub 连接与可交付仓库',
    LARK_NOTIFICATIONS: '将任务进展发送到团队飞书', TEAM_OBSERVER: '只读团队观察与摘要能力',
  }[value]
}
function reasonLabel(value: string): string {
  return {
    PERSONAL_AGENT_CONFIGURATION_REQUIRED: 'Personal Agent 尚未完成模型配置', TEAM_AGENT_CONFIGURATION_REQUIRED: 'Team Agent 尚未完成模型配置',
    WORKPROJECT_REQUIRED: '需要先创建 WorkProject', MANAGED_REPOSITORY_REQUIRED: '需要先导入并绑定受管仓库', CODING_AGENT_CONFIGURATION_REQUIRED: '需要配置 Coding Agent 或 Reviewer Agent',
    CODING_RUNTIME_UNAVAILABLE: 'Coding Runtime 暂时不可用', GITHUB_CONNECTION_REQUIRED: '需要创建并验证团队 GitHub Connection', GITHUB_REPOSITORY_IMPORT_REQUIRED: '需要从 GitHub Catalog 导入仓库', GITHUB_CATALOG_UNAVAILABLE: 'GitHub Catalog 暂时不可用',
    LARK_CONNECTION_REQUIRED: '需要创建团队飞书 Connection', TEAM_OBSERVER_CONFIGURATION_REQUIRED: '需要配置 Team Observer Agent', RUNTIME_UNAVAILABLE: '共享 Runtime 暂时不可用',
  }[value] ?? '当前配置还未满足该能力的前置条件'
}
function iconFor(value: SetupCapability) {
  return { PERSONAL_CONVERSATION: Bot, TEAM_TASK: UsersRound, CODING_REVIEW: Settings2, GITHUB_DRAFT_PR: GitFork, LARK_NOTIFICATIONS: ExternalLink, TEAM_OBSERVER: ShieldCheck }[value]
}
function healthComponentLabel(value: ConfigurationComponent): string {
  return {
    AGENT_CONFIGURATION: 'Agent 配置', MODEL_CONNECTION: '模型连接', CREDENTIAL: '凭证可用性', INTEGRATION: '集成连接',
  }[value]
}
/** 配置健康的 reasonCode 是另一套词表（A06），与能力就绪度的 reasonLabel 分开维护。 */
function healthReasonLabel(value: string): string {
  return {
    READY: '该组件的配置已满足', AGENT_CONFIGURATION_REQUIRED: '还没有为这个 Team 配置 Agent', MODEL_CONNECTION_REQUIRED: 'Agent 还没有可用的模型连接',
    MODEL_CONNECTION_UNHEALTHY: '模型连接存在，但健康检查未通过', CREDENTIAL_UNAVAILABLE: '模型凭证当前不可用', CREDENTIAL_EXPIRING: '模型凭证即将过期，请尽快轮换',
    INTEGRATION_CONNECTION_REQUIRED: '还没有可用的集成连接',
  }[value] ?? '该组件的配置前置条件还未满足'
}
function actionLabel(action: string | null): string {
  return ({ OPEN_AGENT_SETTINGS: '配置 Agent', OPEN_WORKPROJECT_SETTINGS: '创建 WorkProject', OPEN_GITHUB_SETTINGS: '配置 GitHub', START_GITHUB_IMPORT: '导入仓库', OPEN_LARK_SETTINGS: '配置飞书', OPEN_MODEL_SETTINGS: '配置模型与凭证' } as Record<string, string>)[action ?? ''] ?? '继续配置'
}
/*
 * 两套 actionKey 词表并存：就绪度用 OPEN_WORKPROJECT_SETTINGS / START_GITHUB_IMPORT，A06 配置健康用 OPEN_MODEL_SETTINGS。
 * 这里按并集解析，服务端给出未知 actionKey 时按钮不跳转（保持现状，不回显枚举）。
 */
async function goAction(item: Pick<SetupReadinessItem, 'actionKey'>): Promise<void> {
  const query = { ...route.query, team: scopeStore.state.selectedTeamId ?? undefined }
  const target = ({
    OPEN_AGENT_SETTINGS: 'agent-settings', OPEN_WORKPROJECT_SETTINGS: 'today', OPEN_GITHUB_SETTINGS: 'github-settings',
    START_GITHUB_IMPORT: 'repository-settings', OPEN_LARK_SETTINGS: 'lark-settings', OPEN_MODEL_SETTINGS: 'model-settings',
  } as Record<string, string>)[item.actionKey ?? '']
  if (target) await router.push({ name: target, query })
}
async function refresh(): Promise<void> {
  void setupStore.load(true)
  void setupStore.loadHealth(true)
}
async function goToday(): Promise<void> {
  await router.push({ name: 'today', query: route.query })
}
</script>

<template>
  <SettingsShell eyebrow="配置中心 · 团队就绪度" :title="team?.name ? `${team.name} 的配置中心` : '配置中心'">
    <template #actions>
      <BaseButton variant="secondary" size="small" @click="goToday">返回 Today</BaseButton>
      <BaseButton size="small" :disabled="setupStore.state.phase === 'loading' || !online" @click="refresh"><RefreshCw :size="14" />刷新事实</BaseButton>
    </template>

    <StatePanel v-if="setupStore.state.phase === 'idle' || setupStore.state.phase === 'loading'" state="loading" title="正在汇总 Team 配置" description="读取模型、Agent、WorkProject、Repository 与集成事实。" />
    <StatePanel v-else-if="setupStore.state.phase === 'offline'" state="offline" title="Setup Center 当前离线" description="已加载的配置事实仍可查看，恢复网络后可继续配置。" @retry="setupStore.load(true)" />
    <StatePanel v-else-if="setupStore.state.phase === 'error'" state="error" :description="setupStore.state.errorMessage ?? undefined" @retry="setupStore.load(true)" />
    <div v-else-if="readiness" class="setup-page page-shell">
      <section class="setup-hero">
        <div>
          <p class="eyebrow"><CheckCircle2 :size="14" /> Today 就绪摘要</p>
          <h2>{{ readiness.requiredReady ? 'Team 已具备开始工作的条件。' : '完成关键配置，马上开始团队工作。' }}</h2>
          <p>Setup Center 聚合当前 Team 的配置事实；每项操作仍会回到对应领域页面，并由服务端再次校验权限。</p>
        </div>
        <div class="setup-progress" aria-label="必需能力就绪进度"><strong>{{ readyRequiredCount }}/{{ requiredItems.length }}</strong><span>必需能力已就绪</span><div><i :style="{ width: `${requiredItems.length ? readyRequiredCount / requiredItems.length * 100 : 0}%` }" /></div></div>
      </section>

      <section v-if="nextAction" class="next-step panel">
        <div class="next-step__icon"><ArrowRight :size="19" /></div><div><p class="eyebrow">Next step</p><h2>{{ capabilityLabel(nextAction.capability) }}</h2><p>{{ reasonLabel(nextAction.reasonCode) }} · 责任方：{{ nextAction.responsibleParty }}</p></div>
        <BaseButton size="small" :disabled="!online" @click="goAction(nextAction)">{{ actionLabel(nextAction.actionKey) }}<ArrowRight :size="14" /></BaseButton>
      </section>

      <section v-if="health" class="health-panel panel" aria-labelledby="health-heading">
        <div class="panel-heading">
          <div><p class="eyebrow">Configuration health</p><h2 id="health-heading">配置健康 · 四项组件</h2><p>按 Agent 配置、模型连接、凭证与集成四类汇总当前 Team 的配置事实；每项操作回到对应设置页，并由服务端再次校验权限。</p></div>
          <StatusBadge :tone="statusTone(health.overallStatus)" dot>{{ statusLabel(health.overallStatus) }}</StatusBadge>
        </div>
        <div class="health-list" role="list">
          <article v-for="item in health.items" :key="item.component" class="health-row" role="listitem">
            <div class="health-row__body"><h3>{{ healthComponentLabel(item.component) }}</h3><small>{{ healthReasonLabel(item.reasonCode) }} · 责任方：{{ item.responsibleParty }}</small></div>
            <StatusBadge :tone="statusTone(item.status)" dot>{{ statusLabel(item.status) }}</StatusBadge>
            <BaseButton v-if="item.actionKey && item.status !== 'READY'" variant="secondary" size="small" :disabled="!online" @click="goAction(item)">{{ actionLabel(item.actionKey) }}<ArrowRight :size="13" /></BaseButton>
          </article>
        </div>
        <p class="snapshot-note">配置健康观测于 {{ new Date(health.observedAt).toLocaleString('zh-CN') }}</p>
      </section>
      <p v-else-if="healthNotice" class="health-notice" role="status">{{ healthNotice }}</p>

      <section class="capability-panel panel" aria-labelledby="capability-heading">
        <div class="panel-heading"><div><p class="eyebrow">Capability checklist</p><h2 id="capability-heading">能力与前置条件</h2><p>可继续配置的成员看到明确入口；无权限时展示责任方，不暴露内部错误细节。</p></div><StatusBadge :tone="readiness.requiredReady ? 'success' : 'warning'" dot>{{ readiness.requiredReady ? 'Required ready' : '仍需配置' }}</StatusBadge></div>
        <div class="capability-list" role="list">
          <article v-for="item in capabilities" :key="item.capability" class="capability-card" :class="`capability-card--${item.status.toLowerCase()}`" role="listitem">
            <div class="capability-card__icon"><component :is="iconFor(item.capability)" :size="17" aria-hidden="true" /></div>
            <div class="capability-card__body"><div class="capability-card__title"><h3>{{ capabilityLabel(item.capability) }}</h3><span v-if="item.required" class="required-mark">必需</span><StatusBadge :tone="statusTone(item.status)" dot>{{ statusLabel(item.status) }}</StatusBadge></div><p>{{ capabilityDescription(item.capability) }}</p><small>{{ reasonLabel(item.reasonCode) }} · 责任方：{{ item.responsibleParty }}</small></div>
            <BaseButton v-if="item.actionKey && item.canConfigure && item.status === 'ACTION_REQUIRED'" variant="secondary" size="small" :disabled="!online" @click="goAction(item)">{{ actionLabel(item.actionKey) }}<ArrowRight :size="13" /></BaseButton>
            <span v-else-if="item.status === 'BLOCKED'" class="responsibility"><CircleAlert :size="14" />请联系 {{ item.responsibleParty }}</span>
            <span v-else-if="item.status === 'UNAVAILABLE'" class="responsibility"><WifiOff :size="14" />恢复服务后重试</span>
          </article>
        </div>
      </section>
      <p class="snapshot-note">快照 {{ readiness.snapshotVersion }} · 观测于 {{ new Date(readiness.observedAt).toLocaleString('zh-CN') }}</p>
    </div>
  </SettingsShell>
</template>

<style scoped>
.setup-hero { display:flex; align-items:flex-end; justify-content:space-between; gap:var(--cs-space-32); min-height:180px; padding:var(--cs-space-32); border: 1px solid var(--cs-border); border-radius:var(--cs-radius-lg); background: radial-gradient(circle at 88% 4%,var(--cs-hero-glow),transparent 34%),linear-gradient(135deg,var(--cs-surface-accent),var(--cs-surface-accent-strong)); }.setup-hero h2{max-width:680px;margin:var(--cs-space-8) 0 var(--cs-space-12);font:var(--cs-text-xl)/var(--cs-leading-tight) var(--cs-font-display)}.setup-hero p:last-child{max-width:700px;margin:0;color: var(--cs-text-muted);font-size:var(--cs-text-base)}.eyebrow{display:flex;align-items:center;gap:var(--cs-space-8);color: var(--cs-text-muted);font-size:var(--cs-text-sm);font-weight:var(--cs-weight-semibold);letter-spacing:.08em;text-transform:uppercase}.setup-progress{display:grid;min-width:190px;gap:var(--cs-space-4);padding:var(--cs-space-16) var(--cs-space-16);border: 1px solid var(--cs-border-translucent);border-radius:var(--cs-radius-md);background: var(--cs-surface-glass)}.setup-progress strong{font:var(--cs-text-2xl) var(--cs-font-display)}.setup-progress span{color: var(--cs-text-muted);font-size:var(--cs-text-sm)}.setup-progress div{height:6px;margin-top:var(--cs-space-8);overflow:hidden;border-radius:999px;background: var(--cs-border)}.setup-progress i{display:block;height:100%;border-radius:inherit;background: var(--cs-brand-600);transition:width var(--cs-motion-slow) var(--cs-ease-out)}.next-step{display:grid;grid-template-columns:40px 1fr auto;align-items:center;gap:var(--cs-space-12);padding:var(--cs-space-16) var(--cs-space-20);border-color: var(--cs-border);background: var(--cs-surface-accent)}.next-step__icon,.capability-card__icon{display:grid;place-items:center;border-radius:11px;background: var(--cs-surface-accent-strong);color: var(--cs-text-brand)}.next-step__icon{width:40px;height:40px}.next-step h2{margin:var(--cs-space-2) 0 var(--cs-space-2);font-size:var(--cs-text-md)}.next-step p:last-child{margin:0;color: var(--cs-text-muted);font-size:var(--cs-text-sm)}.panel-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:var(--cs-space-16);padding:var(--cs-space-20) var(--cs-space-20) var(--cs-space-16);border-bottom: 1px solid var(--cs-border)}.panel-heading h2{margin:var(--cs-space-4) 0 var(--cs-space-4);font-size:var(--cs-text-lg)}.panel-heading p:last-child{margin:0;color: var(--cs-text-muted);font-size:var(--cs-text-sm)}.capability-list{display:grid}.capability-card{display:grid;grid-template-columns:38px minmax(0,1fr) auto;align-items:center;gap:var(--cs-space-12);padding:var(--cs-space-16) var(--cs-space-20);border-bottom: 1px solid var(--cs-border)}.capability-card:last-child{border-bottom: 0}.capability-card__icon{width:38px;height:38px}.capability-card--ready .capability-card__icon{background: var(--cs-success-soft);color: var(--cs-success)}.capability-card--blocked .capability-card__icon{background: var(--cs-danger-soft);color: var(--cs-danger)}.capability-card__title{display:flex;align-items:center;gap:var(--cs-space-8);flex-wrap:wrap}.capability-card h3{margin:0;font-size:var(--cs-text-base)}.capability-card p{margin:var(--cs-space-4) 0;color: var(--cs-text-secondary);font-size:var(--cs-text-sm)}.capability-card small{color: var(--cs-text-muted);font-size:var(--cs-text-xs)}.required-mark{padding:var(--cs-space-2) var(--cs-space-4);border-radius:4px;background: var(--cs-surface-accent-strong);color: var(--cs-text-brand);font-size:var(--cs-text-xs);font-weight:var(--cs-weight-semibold)}.responsibility{display:flex;align-items:center;gap:var(--cs-space-4);color: var(--cs-text-muted);font-size:var(--cs-text-xs);white-space:nowrap}.snapshot-note{margin:var(--cs-space-12) var(--cs-space-2) 0;color: var(--cs-text-muted);font:var(--cs-text-xs) var(--cs-font-mono)}.health-panel{overflow:hidden}.health-list{display:grid}.health-row{display:grid;grid-template-columns:minmax(0,1fr) auto auto;align-items:center;gap:var(--cs-space-12);min-height:var(--cs-density-control-height);padding:var(--cs-space-12) var(--cs-space-20);border-bottom: 1px solid var(--cs-border)}.health-row:last-child{border-bottom:0}.health-row h3{margin:0;font-size:var(--cs-text-base)}.health-row small{display:block;margin-top:var(--cs-space-4);color: var(--cs-text-muted);font-size:var(--cs-text-xs)}.health-notice{margin:0;padding:var(--cs-space-12) var(--cs-space-16);border: 1px solid var(--cs-border);border-radius:var(--cs-radius-md);background: var(--cs-surface-subtle);color: var(--cs-text-muted);font-size:var(--cs-text-sm)}
@media (max-width:767px){.setup-hero{min-height:220px;align-items:flex-start;flex-direction:column;gap:var(--cs-space-20);padding:var(--cs-space-20)}.setup-hero h2{font-size:var(--cs-text-xl)}.setup-progress{width:100%;min-width:0}.next-step{grid-template-columns:34px 1fr;padding:var(--cs-space-16)}.next-step>button{grid-column:1/-1;justify-content:center}.capability-card{grid-template-columns:34px minmax(0,1fr);padding:var(--cs-space-12) var(--cs-space-16)}.capability-card__icon{width:34px;height:34px}.capability-card>:last-child:not(.capability-card__body){grid-column:2}.panel-heading{padding:var(--cs-space-16) var(--cs-space-16)}.panel-heading>.status-badge{display:none}.health-row{grid-template-columns:minmax(0,1fr);padding:var(--cs-space-12) var(--cs-space-16)}.health-row>:last-child{justify-self:start}}
</style>
