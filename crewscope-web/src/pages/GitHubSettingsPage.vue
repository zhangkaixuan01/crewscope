<script setup lang="ts">
import { secureId } from '../api/secureId'
import { createFormCommandKeys } from '../api/formCommandKeys'
import { createCommandGateway } from '../api/commandGateway'
import { commandFailureMessage } from '../api/commandIntent'
import { createRequestScope } from '../api/requestScope'
import { AUTH_STORE } from '../domains/identity/store'
import { CheckCircle2, GitFork, HeartPulse, KeyRound, Link2, Plus, RefreshCw, ShieldCheck, TriangleAlert, UploadCloud } from '@lucide/vue'
import { computed, inject, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AUTH_PRINCIPAL, can, permissions } from '../app/auth'
import { useNetworkStatus } from '../app/network'
import { CrewScopeApiError } from '../api/client'
import BaseButton from '../components/base/BaseButton.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import StatusBadge from '../components/base/StatusBadge.vue'
import SettingsShell from '../components/settings/SettingsShell.vue'
import { HttpDeliveryGateway, type CreateGitHubConnectionInput } from '../domains/delivery/gateway'
import type { DeliveryScope, GitHubAuthorizationHealth, GitHubConnection, GitHubProviderBinding, GitHubRepository, GitHubRepositoryImportJob } from '../domains/delivery/types'
import { enumLabel } from '../domains/shared/labels'
import {
  githubAuthenticationTypeLabels,
  githubConnectionOwnerTypeLabels,
  githubImportFailureCodeLabels,
  providerConnectionStatusLabels,
  providerCredentialStatusLabels,
  repositoryVisibilityLabels,
} from '../domains/delivery/labels'
import { useScopeStore } from '../domains/scope/store'
import { useConfirm } from '../composables/useConfirm'

const principal = inject(AUTH_PRINCIPAL)
const authStore = inject(AUTH_STORE, null)
const route = useRoute()
const router = useRouter()
const scopeStore = useScopeStore()
const online = useNetworkStatus()
const commandIntents = createCommandGateway(new HttpDeliveryGateway(), {
  revokeConnection: 3, createRepositoryImport: 3, cancelRepositoryImport: 3, retryRepositoryImport: 3,
})
const gateway = commandIntents.gateway
const credentialKeys = createFormCommandKeys()

/** Connection facts quote four provider enums, so each is read through its own label map. */
function label(value: string | null | undefined, labels: Record<string, string>): string {
  return enumLabel(value, labels)
}

/**
 * A connection's identity line is its ownership and its authentication method together — the two
 * are what decide whether it can push as the team — so it reads them from the connection instead
 * of inferring "GitHub App" from `ownerType === 'TEAM'`.
 */
function connectionIdentity(connection: GitHubConnection): string {
  const owner = label(connection.ownerType, githubConnectionOwnerTypeLabels)
  const authentication = label(connection.authenticationType, githubAuthenticationTypeLabels)
  return `${owner} · ${authentication}`
}

const scope = computed<DeliveryScope | null>(() => {
  const teamId = scopeStore.state.selectedTeamId
  return principal && teamId ? { organizationId: principal.organizationId, teamId } : null
})
const canManage = computed(() => Boolean(principal && can(principal, permissions.providerManage)))
const connections = ref<GitHubConnection[]>([])
const repositories = ref<GitHubRepository[]>([])
const bindings = ref<GitHubProviderBinding[]>([])
const importRepository = ref<GitHubRepository | null>(null)
const importJob = ref<GitHubRepositoryImportJob | null>(null)
const importing = ref(false)
const importCommandPending = ref(false)
const bindingCommandPending = ref(false)
const importReadUnknown = ref(false)
let importPollTimer: ReturnType<typeof setTimeout> | null = null
const selectedId = ref<string | null>(null)
const health = ref<GitHubAuthorizationHealth | null>(null)
const loading = ref(false)
const loadingDetail = ref(false)
const syncing = ref(false)
const verifying = ref(false)
const revoking = ref(false)
const connectionCommandPending = computed(() => syncing.value || verifying.value || revoking.value)
const formOpen = ref(false)
const submitting = ref(false)
const errorMessage = ref<string | null>(null)
const successMessage = ref<string | null>(null)
const formError = ref<string | null>(null)
const { confirm } = useConfirm()

const authenticationType = ref<CreateGitHubConnectionInput['authenticationType']>('OAUTH_USER')
const externalAccountId = ref('')
const accessToken = ref('')
const repositoryAllowlist = ref('')
const expiresAt = ref('')
const importKey = computed(() => {
  const fullName = importRepository.value?.fullName ?? ''
  return fullName.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 63) || 'repository'
})

const selectedConnection = computed(() => connections.value.find(item => item.id === selectedId.value) ?? null)
const selectedProjectName = computed(() => scopeStore.selectedProject.value?.name ?? '未选择项目')
const credentialSubjectType = computed<CreateGitHubConnectionInput['credentialSubjectType']>(() => authenticationType.value === 'APP_INSTALLATION' ? 'TEAM' : 'PRINCIPAL')
const isTeamConnection = computed(() => authenticationType.value === 'APP_INSTALLATION')

/** 交付导入需要一个 ACTIVE 且带 Grant 的 RepositoryBinding；没有时按钮禁用要能说明缺什么。 */
const selectedRepositoryBinding = computed(() => {
  const id = importRepository.value?.externalRepositoryId
  if (!id) return null
  const repository = repositories.value.find(item => item.externalRepositoryId === id)
  if (!repository) return null
  const resource = `github:repository:${repository.fullName.toLowerCase()}`
  return bindings.value.find(binding => binding.status === 'ACTIVE' && binding.grantId
    && (binding.repositoryAllowlist.length === 0
      || binding.repositoryAllowlist.includes(resource)
      || binding.repositoryAllowlist.includes(resource.replace('github:repository:', ''))
      || binding.repositoryAllowlist.includes(`github:repository:${repository.externalRepositoryId}`)
      || binding.repositoryAllowlist.includes(repository.externalRepositoryId))) ?? null
})
const bindingMissingReason = computed(() => (selectedRepositoryBinding.value ? '' : '选择“绑定并导入”后，服务端会只为这个仓库建立 ProviderBinding。'))
const revokedReason = computed(() => (selectedConnection.value?.status === 'REVOKED'
  ? 'Connection 已撤销，验证与撤销不再可用；需要继续使用时请创建新的 Connection。'
  : ''))

const identityKey = () => JSON.stringify([authStore?.state.session?.account?.accountId,
  authStore?.state.session?.account?.securityVersion, principal?.id, principal?.organizationId,
  scope.value?.teamId, canManage.value])
const pageRequests = createRequestScope(identityKey)
const detailRequests = createRequestScope(() => JSON.stringify([identityKey(), selectedId.value]))
const importRequests = createRequestScope(() => JSON.stringify([identityKey(), selectedId.value,
  scopeStore.state.selectedProjectId, importRepository.value?.externalRepositoryId]))
let formEpoch = 0

watch(identityKey, () => {
  pageRequests.invalidate()
  detailRequests.invalidate()
  resetView()
  if (scope.value && canManage.value) void loadConnections()
}, { immediate: true, flush: 'sync' })
watch(() => JSON.stringify([authStore?.state.session?.account?.accountId,
  authStore?.state.session?.account?.securityVersion, principal?.id, principal?.organizationId]),
() => commandIntents.clear(), { flush: 'sync' })
watch(() => scopeStore.state.selectedProjectId, () => closeImport(), { flush: 'sync' })
onBeforeUnmount(() => {
  commandIntents.clear()
  credentialKeys.clear()
  pageRequests.dispose()
  detailRequests.dispose()
  importRequests.dispose()
  stopImportPolling()
  resetForm()
})

async function loadConnections(preferredId?: string): Promise<void> {
  if (!scope.value || !canManage.value) return
  const target = { ...scope.value }
  const request = pageRequests.begin('connections')
  loading.value = true
  errorMessage.value = null
  try {
    const [user, team] = await Promise.all([
      gateway.listConnections(target, 'USER', request.signal),
      gateway.listConnections(target, 'TEAM', request.signal),
    ])
    if (!request.isCurrent()) return
    connections.value = [...user, ...team].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
    const routeConnection = typeof route.query.connection === 'string' ? route.query.connection : null
    const next = routeConnection && connections.value.some(item => item.id === routeConnection)
      ? routeConnection
      : preferredId && connections.value.some(item => item.id === preferredId)
        ? preferredId
        : selectedId.value && connections.value.some(item => item.id === selectedId.value)
          ? selectedId.value : connections.value[0]?.id ?? null
    await selectConnection(next)
    await restoreImportFromRoute()
  } catch (error) {
    if (request.isCurrent()) errorMessage.value = safeError(error, 'GitHub Connection 暂时不可用，请稍后重试。')
  } finally {
    if (request.isCurrent()) loading.value = false
    request.finish()
  }
}

async function selectConnection(id: string | null): Promise<void> {
  detailRequests.invalidate()
  loadingDetail.value = syncing.value = verifying.value = revoking.value = false
  errorMessage.value = successMessage.value = null
  closeImport(false)
  bindings.value = []
  selectedId.value = id
  repositories.value = []
  health.value = null
  if (!id || !scope.value) return
  const target = { ...scope.value }
  const request = detailRequests.begin('details')
  loadingDetail.value = true
  try {
    const connection = connections.value.find(item => item.id === id)
    if (!connection) return
    const [catalog, status, providerBindings] = await Promise.all([
      gateway.listRepositories(target, id, request.signal),
      gateway.health(target, id, request.signal),
      gateway.listBindings(target, id, request.signal),
    ])
    if (!request.isCurrent()) return
    repositories.value = catalog
    health.value = status
    bindings.value = providerBindings
  } catch (error) {
    if (request.isCurrent()) errorMessage.value = safeError(error, 'GitHub Connection 详情暂时不可用，请稍后重试。')
  } finally {
    if (request.isCurrent()) loadingDetail.value = false
    request.finish()
  }
}

function openImport(repository: GitHubRepository): void {
  if (!scope.value || !scopeStore.state.selectedProjectId || selectedConnection.value?.ownerType !== 'TEAM') return
  closeImport()
  importRepository.value = repository
  importJob.value = null
  importReadUnknown.value = false
  void syncImportRoute(repository.externalRepositoryId, null)
}
function stopImportPolling(): void {
  if (importPollTimer) clearTimeout(importPollTimer)
  importPollTimer = null
}
function closeImport(clearRoute = true): void {
  importRequests.invalidate()
  stopImportPolling()
  importRepository.value = null
  importJob.value = null
  importReadUnknown.value = false
  importing.value = false
  importCommandPending.value = false
  if (clearRoute && (route.query.importJob || route.query.connection)) {
    const query = { ...route.query }
    delete query.importJob
    delete query.connection
    void router.replace({ query })
  }
}

async function syncImportRoute(repositoryId: string, jobId: string | null): Promise<void> {
  if (!selectedConnection.value) return
  const query: Record<string, string | undefined> = { ...route.query, connection: selectedConnection.value.id }
  if (jobId) query.importJob = jobId
  else delete query.importJob
  await router.replace({ query })
}

async function restoreImportFromRoute(): Promise<void> {
  const jobId = typeof route.query.importJob === 'string' ? route.query.importJob : null
  if (!jobId || !scope.value || !scopeStore.state.selectedProjectId || !selectedConnection.value) return
  try {
    const job = await gateway.getRepositoryImport(
      { ...scope.value }, scopeStore.state.selectedProjectId, jobId,
    )
    if (job.connectionId !== selectedConnection.value.id) return
    const repository = repositories.value.find(item => item.fullName === job.repositoryFullName)
    if (!repository) return
    importRepository.value = repository
    importJob.value = job
    importReadUnknown.value = false
    pollImport()
  } catch {
    importReadUnknown.value = true
    errorMessage.value = '原导入任务暂时无法读取，未创建新任务。请稍后重新读取。'
  }
}
async function startImport(): Promise<void> {
  if (!scope.value || !scopeStore.state.selectedProjectId || !selectedConnection.value || !importRepository.value || importing.value) return
  const target = { ...scope.value }
  // The server remains authoritative for the exact resource intersection. A
  // legacy binding projection may list an external ID instead of fullName;
  // reuse that one before attempting a second bind.
  const selectedBinding = selectedRepositoryBinding.value
  let binding: GitHubProviderBinding | undefined = selectedBinding === null ? undefined : selectedBinding
  if (!binding && gateway.bindConnection) {
    bindingCommandPending.value = true
    try {
      await gateway.bindConnection(target, selectedConnection.value, [importRepository.value.externalRepositoryId])
      bindings.value = await gateway.listBindings(target, selectedConnection.value.id)
      const refreshedBinding = selectedRepositoryBinding.value
      binding = refreshedBinding === null ? undefined : refreshedBinding
    } catch (error) {
      errorMessage.value = safeError(error, '仓库绑定结果尚未确认，请重新读取连接状态。')
    } finally {
      bindingCommandPending.value = false
    }
  }
  if (!binding || !binding.grantId) {
    if (!errorMessage.value) {
      errorMessage.value = '当前仓库尚未建立可用绑定，请重新读取 Connection 后再试。'
    }
    return
  }
  const projectId = scopeStore.state.selectedProjectId
  const request = importRequests.begin('command')
  importing.value = true
  importCommandPending.value = true
  try {
    const job = await gateway.createRepositoryImport(target, projectId, {
      connectionId: selectedConnection.value.id, connectionVersion: selectedConnection.value.version,
      grantId: binding.grantId!, grantVersion: binding.grantVersion ?? 0, externalRepositoryId: importRepository.value.externalRepositoryId,
      defaultBranch: importRepository.value.defaultBranch,
    }, secureId())
    if (!request.isCurrent()) return
    importJob.value = job
    void syncImportRoute(importRepository.value.externalRepositoryId, job.id)
    pollImport()
  } catch (error) {
    if (request.isCurrent()) { errorMessage.value = safeError(error, '仓库导入提交结果尚未确认，请先检查原项目。'); importing.value = false }
  } finally { if (request.isCurrent()) importCommandPending.value = false; request.finish() }
}
async function cancelImport(): Promise<void> {
  if (!scope.value || !scopeStore.state.selectedProjectId || !importJob.value
    || importCommandPending.value || !['REQUESTED', 'PREFLIGHTING'].includes(importJob.value.status)) return
  const target = { ...scope.value }
  const projectId = scopeStore.state.selectedProjectId
  const jobId = importJob.value.id
  importRequests.invalidate()
  stopImportPolling()
  const request = importRequests.begin('command')
  importCommandPending.value = true
  try {
    const job = await gateway.cancelRepositoryImport(
      target, projectId, jobId, secureId(),
    )
    if (!request.isCurrent()) return
    importJob.value = job
    void syncImportRoute(importRepository.value?.externalRepositoryId ?? '', job.id)
    pollImport()
  } catch (error) {
    if (request.isCurrent()) { errorMessage.value = safeError(error, '取消结果尚未确认，请重新读取原导入状态。'); pollImport() }
  } finally { if (request.isCurrent()) importCommandPending.value = false; request.finish() }
}
async function retryImport(): Promise<void> {
  if (!scope.value || !scopeStore.state.selectedProjectId || !importJob.value
    || importing.value || importJob.value.status !== 'FAILED') return
  const target = { ...scope.value }
  const projectId = scopeStore.state.selectedProjectId
  const jobId = importJob.value.id
  const request = importRequests.begin('command')
  importing.value = true
  importCommandPending.value = true
  try {
    const job = await gateway.retryRepositoryImport(
      target, projectId, jobId, secureId(),
    )
    if (!request.isCurrent()) return
    importJob.value = job
    void syncImportRoute(importRepository.value?.externalRepositoryId ?? '', job.id)
    pollImport()
  } catch (error) {
    if (request.isCurrent()) { importing.value = false; errorMessage.value = safeError(error, '重试结果尚未确认，请重新读取原导入状态。') }
  } finally { if (request.isCurrent()) importCommandPending.value = false; request.finish() }
}
function pollImport(): void {
  stopImportPolling()
  if (!scope.value || !scopeStore.state.selectedProjectId || !importJob.value) return
  if (['READY', 'FAILED', 'CANCELLED'].includes(importJob.value.status)) { importing.value = false; return }
  const owner = importRequests.capture()
  importing.value = true
  importPollTimer = setTimeout(() => { if (owner.isCurrent()) void refreshImport() }, 1200)
}

async function refreshImport(): Promise<void> {
  if (!scope.value || !scopeStore.state.selectedProjectId || !importJob.value || importCommandPending.value) return
  stopImportPolling()
  const target = { ...scope.value }
  const projectId = scopeStore.state.selectedProjectId
  const jobId = importJob.value.id
  const request = importRequests.begin('poll')
  try {
    const job = await gateway.getRepositoryImport(target, projectId, jobId, request.signal)
    if (!request.isCurrent() || importJob.value?.id !== jobId) return
    importJob.value = job
    errorMessage.value = null
    pollImport()
  } catch {
    if (request.isCurrent()) { importing.value = false; importReadUnknown.value = true; errorMessage.value = '导入状态暂时无法读取，原任务未被判定失败。请重新读取状态，不要重新创建。' }
  } finally { request.finish() }
}

async function createConnection(): Promise<void> {
  if (!scope.value || !canManage.value || submitting.value) return
  const account = externalAccountId.value.trim()
  const token = accessToken.value.trim()
  const allowlist = [...new Set(repositoryAllowlist.value.split(/[,\n]/).map(item => item.trim()).filter(Boolean))]
  if (!token) {
    formError.value = '请填写 Access Token。GitHub 身份和可用仓库会由服务端验证后自动识别。'
    return
  }
  submitting.value = true
  const target = { ...scope.value }
  const existingConnectionIds = new Set(connections.value.map(connection => connection.id))
  const owner = pageRequests.capture()
  const at = formEpoch
  const current = () => owner.isCurrent() && formEpoch === at
  formError.value = null
  errorMessage.value = null
  try {
    const input: CreateGitHubConnectionInput = {
      authenticationType: authenticationType.value,
      teamId: isTeamConnection.value ? target.teamId : null,
      credentialSubjectType: credentialSubjectType.value,
      ...(account ? { externalAccountId: account } : {}),
      ...(allowlist.length ? { repositoryAllowlist: allowlist } : {}),
      oneShotCredential: token,
      expiresAt: expiresAt.value ? new Date(expiresAt.value).toISOString() : null,
    }
    await gateway.createConnection(target, input, credentialKeys.forInput([target, input]))
    if (!current()) return
    submitting.value = false
    closeForm()
    successMessage.value = 'GitHub Connection 已创建，正在验证 GitHub 身份并读取可用仓库。'
    await loadConnections()
    // The create command intentionally returns only a receipt. Locate the new
    // resource from the refreshed, server-authoritative list without guessing
    // by array position or silently switching to an older connection.
    const createdConnection = connections.value
      .filter(connection => !existingConnectionIds.has(connection.id))
      .sort((left, right) => right.createdAt.localeCompare(left.createdAt))[0]
    if (createdConnection && selectedId.value !== createdConnection.id) {
      await selectConnection(createdConnection.id)
    }
    if (selectedConnection.value && selectedConnection.value.status !== 'REVOKED') {
      await verifyConnection()
      await synchronize()
    }
  } catch (error) {
    if (current()) formError.value = commandFailureMessage(error, 'GitHub Connection 创建结果尚未确认，请检查原范围。')
  } finally {
    if (current()) submitting.value = false
  }
}

async function synchronize(): Promise<void> {
  if (!scope.value || !selectedConnection.value || connectionCommandPending.value || !online.value) return
  const target = { ...scope.value }
  const connection = { ...selectedConnection.value }
  const request = detailRequests.begin('command')
  syncing.value = true
  errorMessage.value = null
  try {
    const catalog = await gateway.synchronizeRepositories(target, connection)
    if (!request.isCurrent()) return
    repositories.value = catalog
    successMessage.value = 'Repository Catalog 已同步。'
  } catch (error) {
    if (request.isCurrent()) errorMessage.value = safeError(error, 'Repository Catalog 同步失败，请稍后重试。')
  } finally {
    if (request.isCurrent()) syncing.value = false
    request.finish()
  }
}

async function verifyConnection(): Promise<void> {
  if (!scope.value || !selectedConnection.value || connectionCommandPending.value || !online.value) return
  const target = { ...scope.value }
  const connection = { ...selectedConnection.value }
  const request = detailRequests.begin('command')
  verifying.value = true
  errorMessage.value = null
  try {
    const verified = await gateway.verifyConnection(target, connection)
    if (!request.isCurrent()) return
    const index = connections.value.findIndex(item => item.id === verified.id)
    if (index >= 0) connections.value[index] = verified
    successMessage.value = 'GitHub Connection 验证成功，正在刷新 Repository Catalog。'
    await selectConnection(verified.id)
  } catch (error) {
    if (request.isCurrent()) errorMessage.value = safeError(error, 'GitHub Connection 验证失败，请检查 Token 和仓库权限。')
  } finally {
    if (request.isCurrent()) verifying.value = false
    request.finish()
  }
}

async function revokeConnection(): Promise<void> {
  if (!scope.value || !selectedConnection.value || connectionCommandPending.value || !online.value) return
  const target = { ...scope.value }
  const connection = { ...selectedConnection.value }
  const request = detailRequests.begin('command')
  revoking.value = true
  try {
    const confirmed = await confirm({
      title: '撤销 GitHub Connection？',
      description: `「${connection.externalAccountLogin || connection.id.slice(0, 8)}」撤销后需要重新创建连接。`,
      confirmLabel: '确认撤销',
      cancelLabel: '返回',
      danger: true,
    })
    if (!confirmed || !request.isCurrent()) return
    errorMessage.value = null
    await gateway.revokeConnection(target, connection, 'OWNER_REQUESTED', secureId())
    if (!request.isCurrent()) return
    revoking.value = false
    selectedId.value = null
    repositories.value = []
    health.value = null
    successMessage.value = 'GitHub Connection 已撤销。旧凭证不会再用于新的执行。'
    await loadConnections()
  } catch (error) {
    if (request.isCurrent()) errorMessage.value = safeError(error, 'GitHub Connection 撤销结果尚未确认，请重新读取。')
  } finally {
    if (request.isCurrent()) revoking.value = false
    request.finish()
  }
}

function resetView(): void {
  loading.value = loadingDetail.value = syncing.value = verifying.value = revoking.value = submitting.value = false
  closeForm()
  closeImport()
  connections.value = []
  repositories.value = []
  bindings.value = []
  selectedId.value = null
  health.value = null
  errorMessage.value = null
  successMessage.value = null
  formError.value = null
}

function resetForm(): void {
  authenticationType.value = 'OAUTH_USER'
  externalAccountId.value = ''
  accessToken.value = ''
  repositoryAllowlist.value = ''
  expiresAt.value = ''
}

function closeForm(): void {
  credentialKeys.clear()
  formEpoch += 1
  formOpen.value = false
  submitting.value = false
  resetForm()
  formError.value = null
}

function safeError(error: unknown, fallback: string): string {
  return error instanceof CrewScopeApiError ? error.envelope.message : fallback
}

function formatDate(value: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

function statusTone(status: string): 'success' | 'warning' | 'danger' | 'neutral' {
  if (['ACTIVE', 'VERIFIED', 'USABLE'].includes(status)) return 'success'
  if (['REVOKED', 'INVALID', 'EXPIRED', 'UNUSABLE'].includes(status)) return 'danger'
  if (['PENDING', 'UNKNOWN', 'DEGRADED'].includes(status)) return 'warning'
  return 'neutral'
}
</script>

<template>
  <SettingsShell title="GitHub 集成" eyebrow="设置 · Provider 连接">
    <template #actions>
      <BaseButton variant="secondary" size="small" :disabled="!scope || loading || !online" :aria-describedby="scope ? undefined : 'github-settings-scope-reason'" @click="loadConnections"><RefreshCw :size="14" />刷新</BaseButton>
      <BaseButton v-if="canManage" size="small" :disabled="!scope || !online" :aria-describedby="scope ? undefined : 'github-settings-scope-reason'" @click="formOpen ? closeForm() : formOpen = true"><Plus :size="14" />创建 Connection</BaseButton>
    </template>

    <StatePanel v-if="!scope" id="github-settings-scope-reason" state="empty" title="请选择 Team" description="GitHub Connection 属于 Organization 与 Team 的明确范围。" />
    <StatePanel v-else-if="loading && connections.length === 0" state="loading" title="正在加载 GitHub Connection" />
    <StatePanel v-else-if="!canManage" state="forbidden" title="需要 Provider 管理权限" description="只有具备 Provider 管理权限的成员可以查看和创建 GitHub Connection。"><template #action><BaseButton variant="secondary" size="small" @click="router.push({ name: 'access-denied', query: { requiredPermission: permissions.providerManage } })">查看权限说明</BaseButton></template></StatePanel>
    <div v-else class="github-page page-shell">
      <StatePanel v-if="errorMessage" state="error" compact :description="errorMessage" @retry="loadConnections" />
      <p v-if="successMessage" class="command-message" role="status"><CheckCircle2 :size="15" />{{ successMessage }}</p>

      <section v-if="formOpen" class="panel connection-form-panel">
        <div class="panel-heading"><div><p class="eyebrow">New provider connection</p><h2>创建 GitHub Connection</h2><p>Token 仅随本次请求发送，服务端只保存安全凭证引用和授权投影。</p></div></div>
        <form class="connection-form" @submit.prevent="createConnection">
          <label>认证方式
            <select v-model="authenticationType">
              <option value="OAUTH_USER">OAuth User（个人执行）</option>
              <option value="APP_INSTALLATION">GitHub App Installation（团队执行）</option>
            </select>
          </label>
          <label>Access Token
            <input v-model="accessToken" required type="password" autocomplete="new-password" placeholder="只在提交时发送，不会回显">
          </label>
          <label>凭证过期时间（可选）
            <input v-model="expiresAt" type="datetime-local">
          </label>
          <details class="advanced-connection-options">
            <summary>高级：使用已有固定仓库范围（可选）</summary>
            <p class="form-hint">普通路径不需要这些字段。保留它们是为了兼容已有部署；留空时服务端先识别身份，再从 Catalog 选择仓库。</p>
            <label>GitHub Account ID（兼容字段）
              <input v-model="externalAccountId" maxlength="100" autocomplete="off" placeholder="留空，由 GitHub 凭据自动识别">
            </label>
            <label>Repository Allowlist（兼容字段）
              <textarea v-model="repositoryAllowlist" rows="2" placeholder="留空，验证后从 Catalog 选择" />
            </label>
          </details>
          <p class="form-hint"><ShieldCheck :size="14" />{{ isTeamConnection ? '团队 App Connection 将以当前 Team 身份执行。' : '个人 OAuth Connection 仅用于当前成员授权的个人任务。' }} GitHub 身份和可用仓库由服务端验证，不需要填写 Account ID 或 Allowlist。</p>
          <div class="form-actions"><BaseButton type="button" variant="secondary" :disabled="submitting" @click="closeForm">取消</BaseButton><BaseButton type="submit" :loading="submitting"><Plus :size="15" />创建</BaseButton></div>
          <p v-if="formError && !submitting" class="field-message error" role="alert"><TriangleAlert :size="14" />{{ formError }}</p>
        </form>
      </section>

      <section class="panel connection-directory">
        <div class="panel-heading"><div><p class="eyebrow">Authorized connections</p><h2>{{ connections.length }} 个 GitHub Connection</h2><p>Connection 是远程授权事实；GitHub Repository Catalog 用于 Push 和 Draft PR。Coding Agent 使用的 RepositoryBinding 仍需在 WorkProject 仓库设置中单独配置。</p></div><GitFork :size="24" /></div>
        <StatePanel v-if="connections.length === 0" state="empty" title="还没有 GitHub Connection" description="创建个人 OAuth 或团队 GitHub App Connection 后，才能同步 Repository Catalog。"><template #action><BaseButton size="small" :disabled="!online" @click="formOpen = true"><Plus :size="14" />创建第一个 Connection</BaseButton></template></StatePanel>
        <div v-else class="connection-list" role="list" aria-label="GitHub Connection 列表">
          <button v-for="connection in connections" :key="connection.id" class="connection-card" :class="{ selected: selectedId === connection.id }" type="button" role="listitem" @click="selectConnection(connection.id)">
            <span class="connection-card__icon"><KeyRound :size="17" /></span><span class="connection-card__body"><strong>{{ connection.externalAccountLogin || 'GitHub account' }}</strong><small>{{ connectionIdentity(connection) }} · {{ connection.id.slice(0, 8) }}</small></span><StatusBadge :tone="statusTone(connection.status)" dot>{{ label(connection.status, providerConnectionStatusLabels) }}</StatusBadge>
          </button>
        </div>
      </section>

      <section v-if="selectedConnection" class="panel connection-detail">
        <div class="panel-heading"><div><p class="eyebrow">Connection detail</p><h2>{{ selectedConnection.externalAccountLogin || 'GitHub account' }}</h2><p>{{ selectedConnection.ownerType === 'TEAM' ? '团队连接' : '个人连接' }} · 最后更新 {{ formatDate(selectedConnection.updatedAt) }}</p></div><div class="detail-actions"><BaseButton size="small" :loading="verifying" :disabled="!online || connectionCommandPending || selectedConnection.status === 'REVOKED'" :aria-describedby="revokedReason ? 'github-revoked-reason' : undefined" @click="verifyConnection"><ShieldCheck :size="14" />验证 Connection</BaseButton><BaseButton variant="secondary" size="small" :disabled="loadingDetail || !online" @click="selectConnection(selectedConnection.id)"><RefreshCw :size="14" />重新检查</BaseButton><BaseButton variant="danger" size="small" :loading="revoking" :disabled="!online || connectionCommandPending || selectedConnection.status === 'REVOKED'" :aria-describedby="revokedReason ? 'github-revoked-reason' : undefined" @click="revokeConnection">撤销</BaseButton><span id="github-revoked-reason" class="sr-only">{{ revokedReason }}</span></div></div>
        <div class="fact-grid"><div><span>授权状态</span><StatusBadge :tone="statusTone(selectedConnection.status)">{{ label(selectedConnection.status, providerConnectionStatusLabels) }}</StatusBadge></div><div><span>凭证状态</span><StatusBadge :tone="statusTone(selectedConnection.credentialStatus ?? 'UNKNOWN')">{{ selectedConnection.credentialStatus ? label(selectedConnection.credentialStatus, providerCredentialStatusLabels) : '未记录' }}</StatusBadge></div><div><span>验证时间</span><strong>{{ formatDate(selectedConnection.verifiedAt) }}</strong></div><div><span>Allowlist</span><strong>{{ selectedConnection.repositoryAllowlist.length }} 个仓库</strong></div></div>
        <div v-if="health" class="health-strip"><HeartPulse :size="16" /><span>可交付仓库 {{ health.deliverableRepositoryCount }} 个 · Rate limit {{ health.rateLimit?.remaining ?? '—' }}/{{ health.rateLimit?.limit ?? '—' }}</span><StatusBadge :tone="health.connectionUsable ? 'success' : 'danger'">{{ health.connectionUsable ? '可用' : '不可用' }}</StatusBadge></div>
        <p v-if="selectedConnection.status !== 'REVOKED'" class="edit-hint"><KeyRound :size="14" />凭证和 Allowlist 不支持原地编辑；需要变更时创建新 Connection，验证成功后再撤销旧连接。</p>
        <div class="catalog-heading"><div><h3>Repository Catalog</h3><p>来自该 Connection 的远程可访问仓库，用于 GitHub Delivery（Push / Draft PR）。选择团队连接下的仓库，可导入当前 WorkProject 形成受管 RepositoryBinding。</p></div><BaseButton variant="secondary" size="small" :loading="syncing" :disabled="!online" @click="synchronize"><RefreshCw :size="14" />同步 Catalog</BaseButton></div>
        <p id="github-binding-reason" class="sr-only">{{ bindingMissingReason }}</p>
        <StatePanel v-if="loadingDetail" state="loading" compact title="正在读取 Connection 事实" />
        <StatePanel v-else-if="repositories.length === 0" state="empty" compact title="暂无可访问仓库" description="检查 Token 权限和 Repository Allowlist 后重新同步。"><template #action><BaseButton variant="secondary" size="small" :loading="syncing" :disabled="!online" @click="synchronize">重新同步 Catalog</BaseButton></template></StatePanel>
        <ul v-else class="repository-list"><li v-for="repository in repositories" :key="repository.externalRepositoryId"><Link2 :size="15" /><span><strong>{{ repository.fullName }}</strong><small>{{ label(repository.visibility, repositoryVisibilityLabels) }} · 默认分支 {{ repository.defaultBranch }}</small></span><BaseButton v-if="selectedConnection.ownerType === 'TEAM' && scopeStore.state.selectedProjectId" variant="secondary" size="small" :disabled="!online || !bindings.some(binding => binding.status === 'ACTIVE' && binding.grantId)" :aria-describedby="bindingMissingReason ? 'github-binding-reason' : undefined" @click="openImport(repository)"><UploadCloud :size="13" />导入</BaseButton></li></ul>
        <section v-if="importRepository" class="import-panel" aria-labelledby="import-title">
          <div><p class="eyebrow">Managed repository import</p><h3 id="import-title">导入 {{ importRepository.fullName }}</h3><p>导入到当前 WorkProject：{{ selectedProjectName }}。Worker 负责 Remote URL、凭证和本地仓库路径。</p></div>
          <label>受管仓库代号
            <input :value="importKey" disabled aria-describedby="github-import-key-reason">
          </label>
          <p id="github-import-key-reason" class="form-hint">系统根据已验证的仓库名称自动生成并处理冲突，不需要填写内部 Key。{{ importJob ? '导入已提交，仓库代号固定在原任务中。' : '' }}</p>
          <div v-if="importJob" class="import-progress" role="status" aria-live="polite"><strong>{{ importJob.status === 'READY' ? '导入完成' : importJob.status === 'FAILED' ? '导入失败' : importJob.status === 'CANCELLED' ? '导入已取消' : '正在导入' }}</strong><span>{{ importJob.progressPercent }}%</span><div><i :style="{ width: `${importJob.progressPercent}%` }" /></div><small v-if="importJob.failureCode">失败原因：{{ label(importJob.failureCode, githubImportFailureCodeLabels) }}</small><small v-if="importReadUnknown" class="unknown-state">状态读取未知：不会创建新任务，也未将原任务判定失败。</small><span v-if="importJob.bindingId" class="import-ready"><small>RepositoryBinding 已创建。</small><BaseButton variant="secondary" size="small" @click="router.push({ name: 'repository-settings', query: { team: scopeStore.state.selectedTeamId ?? undefined, project: scopeStore.state.selectedProjectId ?? undefined } })">前往仓库设置</BaseButton></span></div>
          <div class="form-actions">
            <BaseButton v-if="importJob" variant="secondary" size="small" :disabled="!online || importCommandPending" :aria-describedby="!online || importCommandPending ? 'github-import-command-reason' : undefined" @click="refreshImport"><RefreshCw :size="14" />重新读取状态</BaseButton>
            <BaseButton v-if="importJob && ['REQUESTED', 'PREFLIGHTING'].includes(importJob.status)" variant="secondary" size="small" :disabled="!online || importCommandPending" :aria-describedby="!online || importCommandPending ? 'github-import-command-reason' : undefined" @click="cancelImport">取消导入</BaseButton>
            <BaseButton v-else variant="secondary" size="small" :disabled="importing" @click="closeImport">关闭</BaseButton>
            <BaseButton v-if="importJob?.status === 'FAILED'" size="small" :loading="importing" :disabled="!online" @click="retryImport"><RefreshCw :size="14" />重试</BaseButton>
            <BaseButton v-else-if="!importJob" size="small" :loading="importing || bindingCommandPending" :disabled="!online || bindingCommandPending" :aria-describedby="bindingMissingReason ? 'github-binding-reason' : undefined" @click="startImport"><UploadCloud :size="14" />开始导入</BaseButton>
          </div>
          <p v-if="!online || importCommandPending" id="github-import-command-reason" class="form-hint">{{ !online ? '当前离线，联网后可重新读取或取消导入。' : '导入命令正在提交，请等待本次响应后再操作。' }}</p>
        </section>
      </section>
    </div>
  </SettingsShell>
</template>

<style scoped>
.github-page { display: grid; gap: var(--cs-space-16); }
.detail-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: var(--cs-space-8); }
.edit-hint { display: flex; align-items: center; gap: var(--cs-space-8); margin: var(--cs-space-12) 0 0; color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
.panel { padding: var(--cs-space-20); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); box-shadow: var(--cs-shadow-raised); }
.panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--cs-space-16); margin-bottom: var(--cs-space-16); }.panel-heading h2 { margin: var(--cs-space-4) 0 var(--cs-space-4); font-size: var(--cs-text-lg); }.panel-heading p:not(.eyebrow) { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-sm); }.eyebrow { margin: 0; color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); letter-spacing: .08em; text-transform: uppercase; }
.connection-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--cs-space-12); }.connection-form label { display: grid; gap: var(--cs-space-8); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }.connection-form label:nth-child(3), .connection-form label:nth-child(4), .connection-form label:nth-child(5), .form-hint, .form-actions, .field-message { grid-column: 1 / -1; }.connection-form input, .connection-form select, .connection-form textarea { width: 100%; border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); font: inherit; padding: var(--cs-space-8) var(--cs-space-12); }.connection-form textarea { resize: vertical; }.form-hint { display: flex; align-items: center; gap: var(--cs-space-8); margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-sm); }.form-actions { display: flex; justify-content: flex-end; gap: var(--cs-space-8); }.field-message, .command-message { display: flex; align-items: center; gap: var(--cs-space-8); margin: 0; color: var(--cs-danger); font-size: var(--cs-text-sm); }.command-message { padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid var(--cs-border-accent); border-radius: var(--cs-radius-sm); background: var(--cs-surface-accent); color: var(--cs-success); }
.advanced-connection-options { grid-column: 1 / -1; display: grid; gap: var(--cs-space-8); padding: var(--cs-space-12); border: 1px dashed var(--cs-border); border-radius: var(--cs-radius-sm); }.advanced-connection-options summary { color: var(--cs-text-secondary); cursor: pointer; font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }.advanced-connection-options label { font-weight: var(--cs-weight-medium); }
.connection-list { display: grid; gap: var(--cs-space-8); }.connection-card { display: flex; width: 100%; align-items: center; gap: var(--cs-space-12); padding: var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: inherit; text-align: left; cursor: pointer; }.connection-card:hover, .connection-card.selected { border-color: var(--cs-border-accent-strong); background: var(--cs-surface-accent); }.connection-card__icon { display: grid; width: 30px; height: 30px; place-items: center; border-radius: 8px; background: var(--cs-surface-accent); color: var(--cs-text-brand); }.connection-card__body { display: grid; min-width: 0; flex: 1; gap: var(--cs-space-4); }.connection-card__body strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: var(--cs-text-base); }.connection-card__body small, .repository-list small { color: var(--cs-text-muted); font-size: var(--cs-text-sm); }
.fact-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--cs-space-8); }.fact-grid > div { display: grid; gap: var(--cs-space-4); padding: var(--cs-space-12); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); }.fact-grid span { color: var(--cs-text-muted); font-size: var(--cs-text-sm); }.fact-grid strong { font-size: var(--cs-text-sm); }.health-strip { display: flex; align-items: center; gap: var(--cs-space-8); margin-top: var(--cs-space-12); padding: var(--cs-space-8) var(--cs-space-12); border-radius: var(--cs-radius-sm); background: var(--cs-surface-accent); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); }.health-strip span { flex: 1; }.catalog-heading { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-12); margin-top: var(--cs-space-20); margin-bottom: var(--cs-space-8); }.catalog-heading h3 { margin: 0 0 var(--cs-space-4); font-size: var(--cs-text-base); }.catalog-heading p { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-sm); }.repository-list { display: grid; gap: var(--cs-space-8); margin: 0; padding: 0; list-style: none; }.repository-list li { display: flex; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); }.repository-list li > span { display: grid; min-width: 0; flex: 1; gap: var(--cs-space-2); }
.import-panel { display:grid; gap:var(--cs-space-12); margin-top:var(--cs-space-16); padding:var(--cs-space-16); border: 1px solid var(--cs-border); border-radius:var(--cs-radius-sm); background: var(--cs-surface-accent); }.import-panel h3 { margin:var(--cs-space-4) 0 var(--cs-space-4); font-size:var(--cs-text-base); }.import-panel p:not(.eyebrow) { margin:0; color: var(--cs-text-muted); font-size:var(--cs-text-sm); }.import-panel label { display:grid; gap:var(--cs-space-4); color: var(--cs-text-secondary); font-size:var(--cs-text-sm); font-weight:var(--cs-weight-semibold); }.import-panel input { min-height:34px; padding:0 var(--cs-space-8); border: 1px solid var(--cs-border-strong); border-radius:var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); font:inherit; }.import-progress { display:grid; grid-template-columns:1fr auto; gap:var(--cs-space-4) var(--cs-space-12); align-items:center; font-size:var(--cs-text-sm); }.import-progress div { grid-column:1/-1; height:5px; overflow:hidden; border-radius:99px; background: var(--cs-border); }.import-progress i { display:block; height:100%; border-radius:inherit; background: var(--cs-brand-600); }.import-progress small { grid-column:1/-1; color: var(--cs-text-muted); }.import-panel .form-actions { justify-content:flex-end; }
@media (max-width: 700px) { .connection-form, .fact-grid { grid-template-columns: 1fr; }.connection-form label:nth-child(n), .form-hint, .form-actions, .field-message, .advanced-connection-options { grid-column: 1; }.catalog-heading { align-items: flex-start; flex-direction: column; } }
.unknown-state { color: var(--cs-warning) !important; }
</style>
