<script setup lang="ts">
import { Activity, Coins, KeyRound, Layers3, Plus, RefreshCw, ShieldCheck } from '@lucide/vue'
import { computed, inject, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AUTH_PRINCIPAL, can, permissions } from '../app/auth'
import BaseButton from '../components/base/BaseButton.vue'
import StatusBadge from '../components/base/StatusBadge.vue'
import ModelConnectionDetail from '../components/domain/ModelConnectionDetail.vue'
import ModelCredentialDialog from '../components/domain/ModelCredentialDialog.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import AppShell from '../components/layout/AppShell.vue'
import { useModelStore } from '../domains/model/store'
import type { CreateModelConnectionInput, ModelConnectionOwnerType, ModelConnectionSummary, ModelProviderSummary } from '../domains/model/types'
import { useScopeStore } from '../domains/scope/store'
import { modelSettingsSelection, withModelSettingsRoute } from '../domains/settings/route'

const route = useRoute()
const router = useRouter()
const principal = inject(AUTH_PRINCIPAL)
const scopeStore = useScopeStore()
const modelStore = useModelStore()
const team = scopeStore.selectedTeam
const selection = computed(() => modelSettingsSelection(route.query))
const canManageTeam = computed(() => Boolean(principal && can(principal, permissions.providerManage)))
const canManageOrganization = computed(() => principal?.role === 'Platform Administrator')
const ownerTypes = computed<ModelConnectionOwnerType[]>(() => canManageOrganization.value
  ? ['USER', 'TEAM', 'ORGANIZATION'] : ['USER', 'TEAM'])
const activeOwnerType = computed(() => ownerTypes.value.includes(selection.value.ownerType ?? 'USER')
  ? selection.value.ownerType ?? 'USER' : 'USER')
const providers = computed(() => modelStore.state.providers.value ?? [])
const selectedProvider = computed(() => providers.value.find(provider => provider.key === selection.value.providerKey) ?? null)
const selectedCatalog = computed(() => selectedProvider.value ? modelStore.state.catalogs[selectedProvider.value.key] : null)
const selectedConnectionResource = computed(() => selection.value.connectionId
  ? modelStore.state.connectionDetails[selection.value.connectionId] ?? null : null)
const selectedConnection = computed(() => selectedConnectionResource.value?.value?.value ?? null)
const connections = computed(() => modelStore.state.connections[activeOwnerType.value]?.value ?? [])
const connectionResource = computed(() => modelStore.state.connections[activeOwnerType.value])
const createOpen = ref(false)
const rotateConnection = ref<ModelConnectionSummary | null>(null)
const createTrigger = ref<HTMLElement | null>(null)
const lifecycleAttemptSignature = ref('')
const lifecycleAttemptKey = ref('')
let activeTeamId: string | null = null

watch(
  () => scopeStore.state.selectedTeamId,
  async teamId => {
    if (!teamId || !team.value?.organizationId) return
    const changed = activeTeamId !== null && activeTeamId !== teamId
    activeTeamId = teamId
    if (changed) {
      await router.replace({
        name: 'model-settings',
        query: withModelSettingsRoute(route.query, { teamId, providerKey: null, connectionId: null, ownerType: 'USER' }),
      })
    }
    modelStore.activateScope({ organizationId: team.value.organizationId, teamId })
    await Promise.all([loadProviders(), ...ownerTypes.value.map(owner => modelStore.loadConnections(owner))])
    await restoreSelection()
  },
  { immediate: true },
)

watch(() => selection.value.providerKey, async providerKey => {
  if (providerKey && providers.value.some(provider => provider.key === providerKey)) await modelStore.loadCatalog(providerKey)
})

watch(() => [selection.value.connectionId, selection.value.ownerType] as const, async ([connectionId, ownerType]) => {
  if (connectionId && ownerType && ownerTypes.value.includes(ownerType)) await modelStore.loadConnection(connectionId)
})

async function loadProviders(force = false): Promise<void> {
  await modelStore.loadProviders(false, force)
  if (!selection.value.providerKey && providers.value[0] && scopeStore.state.selectedTeamId) {
    await selectProvider(providers.value[0])
  }
}

async function restoreSelection(): Promise<void> {
  const providerKey = selection.value.providerKey
  if (providerKey && providers.value.some(provider => provider.key === providerKey)) {
    await modelStore.loadCatalog(providerKey)
  }
  if (selection.value.connectionId && selection.value.ownerType && ownerTypes.value.includes(selection.value.ownerType)) {
    await modelStore.loadConnection(selection.value.connectionId)
  }
}

async function selectProvider(provider: ModelProviderSummary): Promise<void> {
  const teamId = scopeStore.state.selectedTeamId
  if (!teamId) return
  await router.push({
    name: 'model-settings',
    query: withModelSettingsRoute(route.query, {
      teamId, providerKey: provider.key, connectionId: selection.value.connectionId, ownerType: activeOwnerType.value,
    }),
  })
  await modelStore.loadCatalog(provider.key)
}

async function switchOwner(ownerType: ModelConnectionOwnerType): Promise<void> {
  const teamId = scopeStore.state.selectedTeamId
  if (!teamId || !ownerTypes.value.includes(ownerType)) return
  await router.push({
    name: 'model-settings',
    query: withModelSettingsRoute(route.query, { teamId, providerKey: selection.value.providerKey, connectionId: null, ownerType }),
  })
  await modelStore.loadConnections(ownerType)
}

async function selectConnection(connection: ModelConnectionSummary): Promise<void> {
  const teamId = scopeStore.state.selectedTeamId
  if (!teamId) return
  modelStore.clearCommand()
  lifecycleAttemptSignature.value = ''
  lifecycleAttemptKey.value = ''
  await router.push({
    name: 'model-settings',
    query: withModelSettingsRoute(route.query, {
      teamId, providerKey: connection.providerKey, connectionId: connection.id, ownerType: connection.ownerType,
    }),
  })
  await modelStore.loadConnection(connection.id)
}

async function closeDetail(): Promise<void> {
  const id = selection.value.connectionId
  const teamId = scopeStore.state.selectedTeamId
  if (!teamId) return
  await router.push({
    name: 'model-settings',
    query: withModelSettingsRoute(route.query, { teamId, providerKey: selection.value.providerKey, connectionId: null, ownerType: activeOwnerType.value }),
  })
  await nextTick()
  if (id) document.querySelector<HTMLElement>(`[data-connection-id="${id}"]`)?.focus()
}

function openCreate(event?: MouseEvent): void {
  if (event?.currentTarget instanceof HTMLElement) createTrigger.value = event.currentTarget
  modelStore.clearCommand()
  createOpen.value = true
}

async function closeCredentialDialog(): Promise<void> {
  if (modelStore.state.command.phase === 'pending') return
  createOpen.value = false
  rotateConnection.value = null
  await nextTick(() => createTrigger.value?.focus())
}

async function createConnection(input: CreateModelConnectionInput, idempotencyKey: string): Promise<void> {
  const before = new Set(modelStore.state.connections[input.ownerType]?.value?.map(item => item.id) ?? [])
  const success = await modelStore.createConnection(input, idempotencyKey)
  if (!success) return
  await modelStore.loadConnections(input.ownerType, false, true)
  const created = (modelStore.state.connections[input.ownerType]?.value ?? []).filter(item => !before.has(item.id))
  createOpen.value = false
  if (created.length === 1) await selectConnection(created[0]!)
}

async function rotateCredential(connectionId: string, credentialVersion: number, apiKey: string, idempotencyKey: string): Promise<void> {
  const success = await modelStore.rotateCredential(connectionId, credentialVersion, apiKey, idempotencyKey)
  if (!success) {
    if (modelStore.state.command.phase === 'conflict') {
      await modelStore.loadConnection(connectionId, true)
      rotateConnection.value = modelStore.state.connectionDetails[connectionId]?.value?.value ?? null
    }
    return
  }
  rotateConnection.value = null
  await refreshConnection(connectionId)
}

async function runConnectionCommand(operation: 'verify' | 'suspend' | 'revoke', connectionId: string, reason?: string): Promise<void> {
  const detail = modelStore.state.connectionDetails[connectionId]?.value
  if (!detail) return
  const signature = JSON.stringify([
    operation, connectionId, reason ?? null, detail.etag, detail.value.credentialVersion,
  ])
  if (signature !== lifecycleAttemptSignature.value) {
    lifecycleAttemptSignature.value = signature
    lifecycleAttemptKey.value = crypto.randomUUID()
  }
  modelStore.clearCommand()
  const key = lifecycleAttemptKey.value
  const success = operation === 'verify'
    ? await modelStore.verifyConnection(connectionId, key)
    : operation === 'suspend'
      ? await modelStore.suspendConnection(connectionId, key)
      : await modelStore.revokeConnection(connectionId, reason ?? 'OWNER_REQUESTED', key)
  if (success) await refreshConnection(connectionId)
  else if (modelStore.state.command.phase === 'conflict') await modelStore.loadConnection(connectionId, true)
}

async function refreshConnection(connectionId = selection.value.connectionId): Promise<void> {
  if (!connectionId) return
  await Promise.all([
    modelStore.loadConnections(activeOwnerType.value, false, true),
    modelStore.loadConnection(connectionId, true),
  ])
}

function canManageConnection(connection: ModelConnectionSummary | null): boolean {
  if (!connection) return false
  if (connection.ownerType === 'USER') return connection.ownerId === principal?.id
  if (connection.ownerType === 'TEAM') return canManageTeam.value
  return canManageOrganization.value
}

function ownerLabel(owner: ModelConnectionOwnerType): string {
  if (owner === 'USER') return '我的连接'
  if (owner === 'TEAM') return '团队连接'
  return '组织连接'
}

function providerTone(status: string): 'success' | 'warning' {
  return status === 'ACTIVE' ? 'success' : 'warning'
}

function connectionTone(status: string): 'success' | 'warning' | 'danger' {
  if (status === 'ACTIVE') return 'success'
  if (status === 'SUSPENDED') return 'warning'
  return 'danger'
}

function healthTone(status: string): 'success' | 'danger' | 'neutral' {
  if (status === 'HEALTHY') return 'success'
  if (status === 'UNHEALTHY') return 'danger'
  return 'neutral'
}

function formatPrice(value: string, currency: string): string {
  return `${currency} ${value}`
}
</script>

<template>
  <AppShell eyebrow="Settings · Model governance" :title="`${team?.name ?? 'Team'} · 模型与凭证`">
    <template #actions><BaseButton size="small" @click="openCreate"><Plus :size="14" />创建连接</BaseButton></template>

    <ModelCredentialDialog
      v-if="createOpen || rotateConnection"
      :mode="rotateConnection ? 'rotate' : 'create'"
      :providers="providers"
      :connection="rotateConnection"
      :team-id="scopeStore.state.selectedTeamId ?? ''"
      :can-manage-team="canManageTeam"
      :can-manage-organization="canManageOrganization"
      :submitting="modelStore.state.command.phase === 'pending'"
      :retryable="modelStore.state.command.retryable"
      :error-message="modelStore.state.command.phase === 'error' || modelStore.state.command.phase === 'conflict' ? modelStore.state.command.errorMessage : null"
      @close="closeCredentialDialog"
      @create="createConnection"
      @rotate="rotateCredential"
    />

    <StatePanel v-if="scopeStore.state.phase === 'loading' || !scopeStore.state.selectedTeamId" state="loading" />
    <div v-else class="model-page page-shell">
      <section class="model-overview panel" aria-labelledby="model-overview-title">
        <div><span class="overview-icon"><Layers3 :size="23" /></span><div><p class="eyebrow">Trusted model plane</p><h2 id="model-overview-title">目录、连接与凭证健康</h2><p>Provider 和价格来自版本化服务端目录；API Key 只在创建与轮换时单向提交。</p></div></div>
        <dl><div><dt>Provider</dt><dd>{{ providers.length }}</dd></div><div><dt>Team Connections</dt><dd>{{ modelStore.state.connections.TEAM?.value?.length ?? 0 }}</dd></div><div><dt>Healthy</dt><dd>{{ Object.values(modelStore.state.connections).flatMap(value => value.value ?? []).filter(value => value.healthStatus === 'HEALTHY').length }}</dd></div></dl>
        <BaseButton class="mobile-create" size="small" @click="openCreate"><Plus :size="14" />创建连接</BaseButton>
      </section>

      <section class="catalog-panel panel" aria-labelledby="provider-catalog-title">
        <header class="panel-heading"><div><p class="eyebrow">Server registry</p><h2 id="provider-catalog-title">Provider 与模型目录</h2><p>目录修订、Region、Retention、Capability 与当前生效价格均为服务端事实。</p></div><BaseButton size="small" variant="ghost" @click="loadProviders(true)"><RefreshCw :size="14" />刷新</BaseButton></header>
        <StatePanel v-if="modelStore.state.providers.phase === 'loading' || modelStore.state.providers.phase === 'idle'" state="loading" compact title="正在加载 Provider" />
        <StatePanel v-else-if="modelStore.state.providers.phase === 'error'" state="error" compact :description="modelStore.state.providers.errorMessage ?? undefined" @retry="loadProviders(true)" />
        <StatePanel v-else-if="modelStore.state.providers.phase === 'empty'" state="empty" compact title="没有可用 Provider" />
        <div v-else class="catalog-layout">
          <nav class="provider-list" aria-label="模型 Provider">
            <button v-for="provider in providers" :key="provider.key" type="button" :class="{ active: selectedProvider?.key === provider.key }" @click="selectProvider(provider)">
              <span><strong>{{ provider.displayName }}</strong><small class="mono">{{ provider.key }}</small></span><StatusBadge :tone="providerTone(provider.status)" dot>{{ provider.status }}</StatusBadge>
              <small>{{ provider.availableRegions.join(' · ') }}</small>
            </button>
          </nav>
          <div class="catalog-content">
            <div v-if="selectedProvider" class="provider-policy"><span>Retention <strong>{{ selectedProvider.retentionMode }}</strong></span><span>Max retention <strong>{{ selectedProvider.maximumRetentionSeconds ?? 'None' }}</strong></span><span>Training <strong>{{ selectedProvider.trainingUsagePolicy }}</strong></span><span>Revision <strong>{{ selectedProvider.version }}</strong></span></div>
            <StatePanel v-if="selectedProvider && (!selectedCatalog || selectedCatalog.phase === 'loading' || selectedCatalog.phase === 'idle')" state="loading" compact title="正在加载模型目录" />
            <StatePanel v-else-if="selectedCatalog?.phase === 'error'" state="error" compact :description="selectedCatalog.errorMessage ?? undefined" @retry="selectedProvider && modelStore.loadCatalog(selectedProvider.key, false, true)" />
            <StatePanel v-else-if="selectedCatalog?.phase === 'empty'" state="empty" compact title="这个 Provider 暂无可选模型" />
            <ul v-else class="model-grid" role="list">
              <li v-for="model in selectedCatalog?.value ?? []" :key="`${model.id}:${model.catalogRevision}`">
                <header><div><h3>{{ model.displayName }}</h3><p class="mono">{{ model.modelId }}</p></div><StatusBadge :tone="model.status === 'ACTIVE' ? 'success' : 'warning'">{{ model.status }}</StatusBadge></header>
                <dl><div><dt>Catalog</dt><dd>r{{ model.catalogRevision }}</dd></div><div><dt>Context</dt><dd>{{ model.contextWindowTokens.toLocaleString() }}</dd></div><div><dt>Output</dt><dd>{{ model.maximumOutputTokens.toLocaleString() }}</dd></div></dl>
                <p class="capabilities">{{ model.capabilities.join(' · ') || '基础文本能力' }}</p>
                <footer v-if="model.effectivePrice"><Coins :size="14" /><span>Input {{ formatPrice(model.effectivePrice.inputPerMillionTokens, model.effectivePrice.currencyCode) }} · Output {{ formatPrice(model.effectivePrice.outputPerMillionTokens, model.effectivePrice.currencyCode) }} / 1M tokens</span></footer>
                <footer v-else><Coins :size="14" /><span>当前没有生效价格修订</span></footer>
              </li>
            </ul>
          </div>
        </div>
      </section>

      <ModelConnectionDetail
        v-if="selection.connectionId"
        :resource="selectedConnectionResource"
        :can-manage="canManageConnection(selectedConnection)"
        :command="modelStore.state.command"
        @close="closeDetail"
        @refresh="modelStore.clearCommand(); refreshConnection()"
        @verify="runConnectionCommand('verify', $event)"
        @rotate="rotateConnection = $event; modelStore.clearCommand()"
        @suspend="runConnectionCommand('suspend', $event)"
        @revoke="(id, reason) => runConnectionCommand('revoke', id, reason)"
      />

      <section class="connections-panel panel" aria-labelledby="connections-title">
        <header class="panel-heading"><div><p class="eyebrow">Owner-scoped credentials</p><h2 id="connections-title">模型连接</h2><p>普通成员管理自己的连接并查看 Team 安全投影；Team 管理操作由 Provider 权限控制。</p></div></header>
        <div class="owner-tabs" role="tablist" aria-label="Connection 归属">
          <button v-for="owner in ownerTypes" :key="owner" type="button" role="tab" :aria-selected="activeOwnerType === owner" :class="{ active: activeOwnerType === owner }" @click="switchOwner(owner)">{{ ownerLabel(owner) }}</button>
        </div>
        <StatePanel v-if="!connectionResource || connectionResource.phase === 'loading' || connectionResource.phase === 'idle'" state="loading" compact title="正在加载模型连接" />
        <StatePanel v-else-if="connectionResource.phase === 'error'" :state="connectionResource.errorStatus === 403 ? 'forbidden' : 'error'" compact :description="connectionResource.errorMessage ?? undefined" @retry="modelStore.loadConnections(activeOwnerType, false, true)" />
        <StatePanel v-else-if="connectionResource.phase === 'empty'" state="empty" compact :title="`${ownerLabel(activeOwnerType)}还没有模型连接`" description="创建后需验证为 HEALTHY，才能进入 Agent 模型选择。" />
        <ul v-else class="connection-list" role="list">
          <li v-for="connection in connections" :key="connection.id">
            <button type="button" :data-connection-id="connection.id" :class="{ selected: selection.connectionId === connection.id }" @click="selectConnection(connection)">
              <span class="connection-provider"><KeyRound :size="17" /><span><strong>{{ connection.providerKey }}</strong><small>{{ connection.region }} · Credential v{{ connection.credentialVersion }}</small></span></span>
              <span class="connection-badges"><StatusBadge :tone="connectionTone(connection.status)" dot>{{ connection.status }}</StatusBadge><StatusBadge :tone="healthTone(connection.healthStatus)" dot>{{ connection.healthStatus }}</StatusBadge></span>
              <span class="connection-meta"><small>Billing {{ connection.billingSubjectType }}</small><small>Failures {{ connection.consecutiveFailures }}</small><small>Version {{ connection.version }}</small></span>
            </button>
          </li>
        </ul>
      </section>

      <section class="governance-panel panel" aria-labelledby="governance-title">
        <header class="panel-heading"><div><p class="eyebrow">Governance delivery</p><h2 id="governance-title">默认、允许列表与预算</h2><p>以下能力等待公开管理 API；页面保持服务端事实边界，不创建仅在本地生效的配置。</p></div></header>
        <div class="governance-grid"><article><ShieldCheck :size="18" /><div><h3>Team 模型默认</h3><p>领域已定义 AgentModelDefault，管理 API 尚未交付。</p></div><StatusBadge tone="neutral">API 待交付</StatusBadge></article><article><Layers3 :size="18" /><div><h3>Provider / Catalog 允许列表</h3><p>Agent Preflight 会执行治理求交集，策略编辑 API 尚未交付。</p></div><StatusBadge tone="neutral">只读边界</StatusBadge></article><article><Coins :size="18" /><div><h3>预算与配额</h3><p>当前没有公开 Budget Policy 目录与编辑契约。</p></div><StatusBadge tone="neutral">API 待交付</StatusBadge></article></div>
      </section>

      <section class="security-note" aria-label="模型凭证安全说明"><ShieldCheck :size="18" /><div><strong>凭证不进入浏览器状态</strong><span>API Key 不进入 Store、URL、Toast、Telemetry、错误详情或可重放闭包；Connection DTO 只展示稳定、安全的公开字段。</span></div></section>
    </div>
  </AppShell>
</template>

<style scoped>
.model-overview { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 22px; padding: 21px; background: linear-gradient(135deg, var(--cs-surface), #f1faf4); }.model-overview > div:first-child { display: flex; align-items: center; gap: 13px; }.overview-icon { display: grid; width: 48px; height: 48px; place-items: center; border-radius: 14px; background: var(--cs-brand-100); color: var(--cs-brand-700); }.model-overview h2 { margin-bottom: 3px; font-size: 17px; }.model-overview p:last-child { margin: 0; color: var(--cs-text-muted); font-size: 10px; }.model-overview dl { display: grid; grid-template-columns: repeat(3, minmax(85px, 1fr)); gap: 7px; margin: 0; }.model-overview dl div { padding: 10px 11px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: rgb(255 255 255 / 75%); }.model-overview dt { color: var(--cs-text-muted); font-size: 8px; }.model-overview dd { margin-top: 3px; font: 750 17px var(--cs-font-display); }.mobile-create { display: none; }.catalog-layout { display: grid; grid-template-columns: 220px minmax(0, 1fr); min-height: 250px; border-top: 1px solid var(--cs-border); }.provider-list { padding: 10px; border-right: 1px solid var(--cs-border); background: var(--cs-surface-subtle); }.provider-list button { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 6px; width: 100%; padding: 10px; border: 1px solid transparent; border-radius: var(--cs-radius-sm); background: transparent; text-align: left; cursor: pointer; }.provider-list button.active { border-color: var(--cs-brand-200); background: var(--cs-surface); box-shadow: 0 1px 3px rgb(21 35 29 / 6%); }.provider-list strong, .provider-list small { display: block; }.provider-list strong { font-size: 10px; }.provider-list small { color: var(--cs-text-muted); font-size: 8px; }.provider-list button > small { grid-column: 1 / -1; }.catalog-content { min-width: 0; padding: 13px; }.provider-policy { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 10px; }.provider-policy span { padding: 5px 7px; border-radius: 6px; background: var(--cs-brand-50); color: var(--cs-text-muted); font-size: 8px; }.provider-policy strong { color: var(--cs-text-secondary); }.model-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 9px; margin: 0; padding: 0; list-style: none; }.model-grid li { display: grid; gap: 10px; padding: 12px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); }.model-grid header { display: flex; justify-content: space-between; gap: 8px; }.model-grid h3 { margin: 0; font-size: 11px; }.model-grid header p { margin: 2px 0 0; color: var(--cs-text-muted); font-size: 8px; }.model-grid dl { display: grid; grid-template-columns: repeat(3, 1fr); gap: 5px; margin: 0; }.model-grid dl div { padding: 6px; border-radius: 6px; background: var(--cs-surface-subtle); }.model-grid dt { color: var(--cs-text-muted); font-size: 7px; text-transform: uppercase; }.model-grid dd { margin-top: 2px; color: var(--cs-text-secondary); font-size: 9px; font-weight: 700; }.capabilities { margin: 0; color: var(--cs-brand-700); font-size: 8px; font-weight: 700; }.model-grid footer { display: flex; gap: 6px; padding-top: 8px; border-top: 1px solid var(--cs-border); color: var(--cs-text-muted); font-size: 8px; }.model-grid footer svg { flex: 0 0 auto; }.owner-tabs { display: flex; gap: 4px; padding: 0 16px 12px; }.owner-tabs button { min-height: 31px; padding: 0 11px; border: 1px solid var(--cs-border); border-radius: 8px; background: var(--cs-surface-subtle); color: var(--cs-text-muted); font-size: 9px; font-weight: 700; cursor: pointer; }.owner-tabs button.active { border-color: var(--cs-brand-300); background: var(--cs-brand-50); color: var(--cs-brand-800); }.connection-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 9px; margin: 0; padding: 0 16px 16px; list-style: none; }.connection-list li { min-width: 0; }.connection-list button { display: grid; width: 100%; height: 100%; gap: 11px; padding: 13px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); color: var(--cs-text); text-align: left; cursor: pointer; }.connection-list button:hover, .connection-list button:focus-visible, .connection-list button.selected { border-color: var(--cs-brand-400); background: var(--cs-brand-50); }.connection-provider { display: flex; align-items: center; gap: 8px; color: var(--cs-brand-700); }.connection-provider strong, .connection-provider small { display: block; }.connection-provider strong { color: var(--cs-text); font-size: 11px; }.connection-provider small { margin-top: 2px; color: var(--cs-text-muted); font-size: 8px; }.connection-badges { display: flex; flex-wrap: wrap; gap: 5px; }.connection-meta { display: flex; flex-wrap: wrap; gap: 9px; padding-top: 8px; border-top: 1px solid var(--cs-border); color: var(--cs-text-muted); font-size: 8px; }.governance-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 9px; padding: 0 16px 16px; }.governance-grid article { display: grid; grid-template-columns: 20px minmax(0, 1fr) auto; align-items: start; gap: 8px; padding: 12px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface-subtle); color: var(--cs-brand-700); }.governance-grid h3 { margin: 0; color: var(--cs-text); font-size: 10px; }.governance-grid p { margin: 3px 0 0; color: var(--cs-text-muted); font-size: 8px; line-height: 1.45; }.security-note { display: flex; gap: 9px; padding: 12px 14px; border: 1px solid var(--cs-brand-200); border-radius: var(--cs-radius-md); background: var(--cs-brand-50); color: var(--cs-brand-700); }.security-note svg { flex: 0 0 auto; }.security-note strong, .security-note span { display: block; }.security-note strong { font-size: 10px; }.security-note span { margin-top: 2px; color: var(--cs-text-secondary); font-size: 9px; }
@media (max-width: 980px) { .model-overview { grid-template-columns: 1fr; }.catalog-layout { grid-template-columns: 1fr; }.provider-list { display: flex; gap: 6px; overflow-x: auto; border-right: 0; border-bottom: 1px solid var(--cs-border); }.provider-list button { min-width: 180px; }.governance-grid { grid-template-columns: 1fr; } }
@media (max-width: 767px) { .mobile-create { display: inline-flex; grid-column: 1 / -1; width: 100%; }.model-overview dl { width: 100%; }.connection-list { grid-template-columns: 1fr; padding-inline: 12px; }.owner-tabs { overflow-x: auto; padding-inline: 12px; }.governance-grid { padding-inline: 12px; } }
@media (max-width: 520px) { .model-overview { padding: 16px; }.model-overview > div:first-child { align-items: flex-start; }.model-overview dl { grid-template-columns: 1fr; }.model-overview dl div { display: flex; justify-content: space-between; align-items: center; padding: 7px 9px; }.model-overview dd { margin: 0; font-size: 14px; }.model-grid { grid-template-columns: 1fr; }.provider-policy { display: grid; grid-template-columns: 1fr 1fr; } }
</style>
