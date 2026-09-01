<script setup lang="ts">
import {
  CheckCircle2,
  CircleOff,
  GitBranch,
  Plus,
  RefreshCw,
  ServerCog,
  ShieldCheck,
  TriangleAlert,
  X,
} from '@lucide/vue'
import { computed, inject, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AUTH_PRINCIPAL, can, permissions } from '../app/auth'
import { useNetworkStatus } from '../app/network'
import BaseButton from '../components/base/BaseButton.vue'
import StatusBadge from '../components/base/StatusBadge.vue'
import WorkProjectCreateDialog from '../components/domain/WorkProjectCreateDialog.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
import AppShell from '../components/layout/AppShell.vue'
import { useCodingStore } from '../domains/coding/store'
import type { CodingScope, RepositoryBinding, RepositoryBindingInput } from '../domains/coding/types'
import { useScopeStore } from '../domains/scope/store'
import { principalDisplayName, principalNameDirectory } from '../domains/scope/memberDirectory'
import { createWorkProjectCreationFlow } from '../domains/scope/workProjectCreation'

const route = useRoute()
const router = useRouter()
const principal = inject(AUTH_PRINCIPAL)
const scopeStore = useScopeStore()
const store = useCodingStore()
const showCreate = ref(false)
const createRepositorySelect = ref<HTMLSelectElement | null>(null)
const repositoryKey = ref('')
const defaultBranch = ref('main')
const submitted = ref(false)
const online = useNetworkStatus()
let createOpenerKey: string | null = null

const scope = computed<CodingScope | null>(() => {
  const teamId = scopeStore.state.selectedTeamId
  const projectId = scopeStore.state.selectedProjectId
  return teamId && projectId
    ? { organizationId: principal?.organizationId ?? '', teamId, projectId }
    : null
})
const project = scopeStore.selectedProject
const repositories = computed(() => store.state.repositories.value ?? [])
const catalog = computed(() => store.state.repositoryCatalog.value ?? [])
const boundRepositoryKeys = computed(() => new Set(repositories.value.map(item => item.repositoryKey)))
const availableCatalog = computed(() => catalog.value.filter(item => (
  item.availability === 'AVAILABLE' && !boundRepositoryKeys.value.has(item.repositoryKey)
)))
const catalogReady = computed(() => store.state.repositoryCatalog.phase === 'ready')
const canCreateRepository = computed(() => online.value && catalogReady.value && availableCatalog.value.length > 0)
const selectedCatalog = computed(() => catalog.value.find(item => item.repositoryKey === repositoryKey.value) ?? null)
const draftKey = computed(() => `draft:${repositoryKey.value}:${defaultBranch.value}`)
const draftPreflight = computed(() => store.state.repositoryPreflights[draftKey.value])
const inputValid = computed(() => repositoryKey.value.length > 0 && defaultBranch.value.trim().length > 0)
const command = computed(() => store.state.repositoryCommand)
const initialLoading = computed(() => ['idle', 'loading'].includes(store.state.repositories.phase)
  || ['idle', 'loading'].includes(store.state.repositoryCatalog.phase))
const forbidden = computed(() => store.state.repositories.errorStatus === 403
  || store.state.repositoryCatalog.errorStatus === 403)
const canManageProjects = computed(() => Boolean(principal && can(principal, permissions.workProjectsManage)))
const projectCreation = createWorkProjectCreationFlow(scopeStore, router, route)
const principalNames = computed(() => principalNameDirectory(scopeStore.state.members))

watch(
  () => [scope.value?.teamId, scope.value?.projectId, route.fullPath] as const,
  async ([teamId, projectId]) => {
    resetDraft()
    createOpenerKey = null
    if (!teamId || !projectId || !scope.value) return
    await Promise.all([
      store.loadRepositories(scope.value, true),
      store.loadRepositoryCatalog(scope.value, true),
      scopeStore.loadMembers(),
    ])
    chooseFirstRepository()
  },
  { immediate: true },
)

watch([repositoryKey, defaultBranch], () => {
  submitted.value = false
  store.clearRepositoryCommand()
})

watch(selectedCatalog, item => {
  if (item?.suggestedDefaultBranch) defaultBranch.value = item.suggestedDefaultBranch
})

async function reload(): Promise<void> {
  if (!scope.value) return
  await Promise.all([
    store.loadRepositories(scope.value, true),
    store.loadRepositoryCatalog(scope.value, true),
  ])
  chooseFirstRepository()
}

async function preflightDraft(): Promise<void> {
  submitted.value = true
  if (!inputValid.value) return
  await store.preflightRepositoryDraft(draftInput())
}

async function createBinding(): Promise<void> {
  submitted.value = true
  if (!catalogReady.value || !inputValid.value || draftPreflight.value?.phase !== 'ready') return
  if (await store.createRepository(draftInput())) {
    await closeCreate()
  }
}

async function preflightExisting(binding: RepositoryBinding): Promise<void> {
  await store.preflightRepository(binding.id)
}

async function transition(binding: RepositoryBinding): Promise<void> {
  await store.transitionRepository(binding, binding.status === 'ACTIVE' ? 'disable' : 'activate')
}

function draftInput(): RepositoryBindingInput {
  return { repositoryKey: repositoryKey.value, defaultBranch: defaultBranch.value.trim() }
}

function chooseFirstRepository(): void {
  if (availableCatalog.value.some(item => item.repositoryKey === repositoryKey.value)) return
  repositoryKey.value = availableCatalog.value[0]?.repositoryKey ?? ''
}

async function openCreate(event: Event): Promise<void> {
  if (!canCreateRepository.value) return
  createOpenerKey = event.currentTarget instanceof HTMLElement
    ? event.currentTarget.dataset.repositoryCreateTrigger ?? null
    : null
  showCreate.value = true
  await nextTick()
  createRepositorySelect.value?.focus()
}

async function closeCreate(): Promise<void> {
  const openerKey = createOpenerKey
  resetDraft()
  createOpenerKey = null
  await nextTick()
  // Catalog 加载可能重建工具栏按钮；按稳定标识查找当前节点，避免把焦点交还给已脱离 DOM 的旧节点。
  if (openerKey) {
    document.querySelector<HTMLElement>(`[data-repository-create-trigger="${openerKey}"]`)?.focus()
  }
}

function resetDraft(): void {
  showCreate.value = false
  repositoryKey.value = ''
  defaultBranch.value = 'main'
  submitted.value = false
  store.clearRepositoryCommand()
}

function preflightFor(bindingId: string) {
  return store.state.repositoryPreflights[bindingId]
}

function actor(value: string | null): string {
  if (!value) return '系统'
  return principalDisplayName(principalNames.value, value)
}

function updatedAt(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}
</script>

<template>
  <AppShell eyebrow="WorkProject · Settings" :title="`${project?.name ?? 'WorkProject'} 仓库设置`">
    <template #actions>
      <BaseButton v-if="!scope && canManageProjects" size="small" @click="projectCreation.show">
        <Plus :size="14" />新建项目
      </BaseButton>
      <BaseButton v-else-if="scope" data-repository-create-trigger="header" size="small" :disabled="!canCreateRepository" @click="openCreate">
        <Plus :size="14" />绑定仓库
      </BaseButton>
    </template>

    <StatePanel v-if="!scope" state="empty" title="这个 Team 还没有 WorkProject" description="先创建 WorkProject，再为它绑定受管代码仓库。">
      <template v-if="canManageProjects" #action><BaseButton size="small" @click="projectCreation.show"><Plus :size="14" />创建 WorkProject</BaseButton></template>
    </StatePanel>
    <StatePanel v-else-if="initialLoading" state="loading" />
    <StatePanel v-else-if="forbidden" state="forbidden" title="需要 Team 管理员权限" description="Team Owner、Team Admin 或平台管理员可以管理 RepositoryBinding。" />
    <StatePanel
      v-else-if="store.state.repositories.phase === 'error'"
      state="error"
      :description="store.state.repositories.errorMessage ?? undefined"
      @retry="reload"
    />

    <div v-else class="repository-page page-shell">
      <StatePanel v-if="!online" compact state="offline" title="仓库写操作已暂停" description="已加载的 RepositoryBinding 保持可读，联网后可继续 Preflight、绑定和启停。" />

      <section class="repository-overview panel">
        <i><ServerCog :size="23" /></i>
        <div>
          <p class="eyebrow">Managed repository boundary</p>
          <h2>{{ repositories.length }} 个 RepositoryBinding</h2>
          <p>仓库由受信 Worker 管理。这里仅列出受管本地 Git 源仓库，页面只持有稳定 Repository Key、Branch、Commit 与审计摘要。</p>
        </div>
        <StatusBadge :tone="store.state.repositoryCatalog.phase === 'ready' ? 'success' : 'warning'" dot>
          {{ store.state.repositoryCatalog.phase === 'ready' ? `${availableCatalog.length} 可绑定` : 'Catalog 不可用' }}
        </StatusBadge>
      </section>

      <section class="source-note panel" aria-labelledby="repository-source-title">
        <div class="source-note__icon"><GitBranch :size="18" /></div>
        <div>
          <h3 id="repository-source-title">仓库来源说明</h3>
          <p><strong>受管本地仓库</strong>用于 Coding Agent 的 Worktree、代码修改和测试；<strong>GitHub Connection Repository Catalog</strong>用于远程授权、Push 和 Draft PR。两者是独立的安全边界，GitHub 仓库不会直接出现在这里的绑定候选中。</p>
          <p class="source-note__hint">要使用 GitHub 仓库，请先将它部署到 Worker 的受管仓库目录，再回到本页刷新 Catalog；GitHub 连接和远程仓库授权在 GitHub 集成页管理。</p>
        </div>
        <BaseButton variant="secondary" size="small" @click="router.push({ name: 'github-settings', query: route.query })">管理 GitHub Connection</BaseButton>
      </section>

      <StatePanel
        v-if="store.state.repositoryCatalog.phase === 'error'"
        state="error"
        compact
        title="Repository Catalog 暂时不可用"
        :description="store.state.repositoryCatalog.errorMessage ?? '本机受管仓库 Catalog 当前不可用；请检查 Worker 的受管仓库目录和运行状态。GitHub Connection 仓库请在 GitHub 集成页管理。'"
        @retry="reload"
      ><template #action><BaseButton variant="secondary" size="small" @click="router.push({ name: 'github-settings', query: route.query })">前往 GitHub 集成</BaseButton></template></StatePanel>

      <section v-if="showCreate" class="create-binding panel" aria-labelledby="create-binding-title" @keydown.esc="closeCreate">
        <div class="panel-heading">
          <div><p class="eyebrow">New binding</p><h2 id="create-binding-title">绑定受管本地仓库</h2><p>Repository Key 来自 Worker 的受管本地 Catalog，默认 Branch 在创建前完成 Preflight。</p></div>
          <button type="button" aria-label="关闭绑定仓库面板" @click="closeCreate"><X :size="17" /></button>
        </div>
        <form class="binding-form" @submit.prevent="createBinding">
          <label>Repository Key
            <select ref="createRepositorySelect" v-model="repositoryKey" :aria-invalid="submitted && !repositoryKey">
              <option value="" disabled>选择受管本地仓库</option>
              <option v-for="item in availableCatalog" :key="item.repositoryKey" :value="item.repositoryKey">
                {{ item.repositoryKey }}
              </option>
            </select>
          </label>
          <label>Default Branch
            <input v-model="defaultBranch" maxlength="255" autocomplete="off" placeholder="main" :aria-invalid="submitted && !defaultBranch.trim()">
          </label>
          <div class="form-actions">
            <BaseButton type="button" variant="secondary" :loading="draftPreflight?.phase === 'loading'" :disabled="!catalogReady || !inputValid || !online" @click="preflightDraft">
              <ShieldCheck :size="15" />运行 Preflight
            </BaseButton>
            <BaseButton type="submit" :loading="command.phase === 'pending' && command.operation === 'create'" :disabled="!catalogReady || draftPreflight?.phase !== 'ready' || !online">
              <Plus :size="15" />确认绑定
            </BaseButton>
          </div>
          <p v-if="submitted && !inputValid" class="field-message error" role="alert">请选择 Repository Key 并填写默认 Branch。</p>
          <p v-else-if="draftPreflight?.phase === 'ready'" class="field-message success" role="status"><CheckCircle2 :size="14" />Preflight 已通过，基线 Commit {{ draftPreflight.value?.baselineCommit.slice(0, 10) }}</p>
          <p v-else-if="draftPreflight?.phase === 'error'" class="field-message error" role="alert"><TriangleAlert :size="14" />{{ draftPreflight.errorMessage }}</p>
        </form>
      </section>

      <StatePanel
        v-if="command.phase === 'error' || command.phase === 'conflict'"
        :state="command.phase === 'conflict' ? 'conflict' : 'error'"
        compact
        :description="command.errorMessage ?? undefined"
        @retry="command.phase === 'conflict' || !command.retryable ? reload() : store.retryRepositoryCommand()"
      />

      <section class="binding-directory panel">
        <div class="panel-heading">
          <div><p class="eyebrow">Project bindings</p><h2>已绑定仓库</h2><p>启用状态决定仓库能否用于新的 CodingTarget；历史执行事实保持可审计。</p></div>
          <div class="directory-actions">
            <BaseButton data-repository-create-trigger="directory" size="small" :disabled="!canCreateRepository" @click="openCreate"><Plus :size="14" />绑定仓库</BaseButton>
            <BaseButton variant="secondary" size="small" :disabled="!online" @click="reload"><RefreshCw :size="14" />刷新</BaseButton>
          </div>
        </div>

        <StatePanel v-if="repositories.length === 0" state="empty" title="还没有 RepositoryBinding" description="从服务端 Repository Catalog 选择一个可用仓库并完成 Preflight。">
          <template v-if="availableCatalog.length > 0" #action><BaseButton data-repository-create-trigger="empty" size="small" :disabled="!canCreateRepository" @click="openCreate"><Plus :size="14" />绑定第一个仓库</BaseButton></template>
        </StatePanel>

        <div v-else class="binding-list" role="list" aria-label="RepositoryBinding 列表">
          <article v-for="binding in repositories" :key="binding.id" class="binding-card" role="listitem">
            <div class="binding-identity">
              <i><GitBranch :size="18" /></i>
              <div><strong class="mono">{{ binding.repositoryKey }}</strong><span class="mono">{{ binding.defaultBranch }}</span></div>
            </div>
            <div class="binding-facts">
              <span><small>状态</small><StatusBadge :tone="binding.status === 'ACTIVE' ? 'success' : 'neutral'" dot>{{ binding.status }}</StatusBadge></span>
              <span><small>版本</small><strong class="mono">v{{ binding.version }}</strong></span>
              <span><small>最近更新</small><strong>{{ updatedAt(binding.updatedAt) }}</strong></span>
              <span><small>操作人</small><strong>{{ actor(binding.updatedByPrincipalId) }}</strong></span>
            </div>
            <p v-if="preflightFor(binding.id)?.phase === 'ready'" class="preflight-result success" role="status" aria-live="polite"><CheckCircle2 :size="13" />{{ preflightFor(binding.id)?.value?.baselineCommit.slice(0, 10) }} · Preflight 通过</p>
            <p v-else-if="preflightFor(binding.id)?.phase === 'error'" class="preflight-result error" role="alert"><TriangleAlert :size="13" />{{ preflightFor(binding.id)?.errorMessage }}</p>
            <div class="binding-actions">
              <BaseButton variant="secondary" size="small" :loading="preflightFor(binding.id)?.phase === 'loading'" :disabled="!online" @click="preflightExisting(binding)"><ShieldCheck :size="14" />Preflight</BaseButton>
              <BaseButton
                :variant="binding.status === 'ACTIVE' ? 'ghost' : 'primary'"
                size="small"
                :loading="command.phase === 'pending' && command.bindingId === binding.id"
                :disabled="!online"
                @click="transition(binding)"
              >
                <CircleOff v-if="binding.status === 'ACTIVE'" :size="14" /><CheckCircle2 v-else :size="14" />{{ binding.status === 'ACTIVE' ? '停用' : '启用' }}
              </BaseButton>
            </div>
          </article>
        </div>
      </section>

      <section class="security-note"><ShieldCheck :size="17" /><div><strong>浏览器披露边界</strong><span>Canonical Path、Managed Root、文件系统用户和原始 Git 输出停留在受信基础设施内。</span></div></section>
    </div>

    <WorkProjectCreateDialog
      v-if="projectCreation.open.value && scopeStore.selectedTeam.value"
      :team-name="scopeStore.selectedTeam.value.name"
      :submitting="scopeStore.state.projectCommandPending"
      :retryable="scopeStore.state.projectCommandRetryable"
      :error-message="scopeStore.state.projectCommandErrorMessage"
      :check-key="scopeStore.checkWorkProjectKey"
      @close="projectCreation.close"
      @input-changed="scopeStore.clearProjectCommand"
      @submit="projectCreation.submit"
    />
  </AppShell>
</template>

<style scoped>
.repository-overview { display: grid; grid-template-columns: 50px 1fr auto; align-items: center; gap: 15px; padding: 21px 23px; background: linear-gradient(135deg, var(--cs-surface), var(--cs-brand-50)); }.repository-overview > i { display: grid; width: 50px; height: 50px; place-items: center; border-radius: 15px; background: var(--cs-brand-100); color: var(--cs-brand-700); }.repository-overview h2 { margin-bottom: 5px; font-size: 18px; }.repository-overview p:last-child { margin: 0; color: var(--cs-text-muted); font-size: 10px; }
.source-note { display: flex; align-items: flex-start; gap: 12px; }.source-note__icon { display: grid; width: 32px; height: 32px; flex: 0 0 auto; place-items: center; border-radius: 9px; background: var(--cs-brand-50); color: var(--cs-brand-700); }.source-note h3 { margin: 0 0 5px; font-size: 12px; }.source-note p { margin: 0; color: var(--cs-text-muted); font-size: 10px; line-height: 1.55; }.source-note__hint { margin-top: 5px !important; }.source-note > :last-child { margin-left: auto; flex: 0 0 auto; }
.create-binding { overflow: hidden; }.panel-heading > button { display: grid; width: 30px; height: 30px; place-items: center; border-radius: 8px; background: var(--cs-surface-subtle); cursor: pointer; }.binding-form { display: grid; grid-template-columns: minmax(180px, 1fr) minmax(180px, 1fr) auto; align-items: end; gap: 14px; padding: 18px 20px; }.binding-form label { display: grid; gap: 6px; color: var(--cs-text-secondary); font-size: 10px; font-weight: 750; }.binding-form select, .binding-form input { min-width: 0; min-height: 38px; padding: 0 11px; border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); font-size: 11px; }.binding-form [aria-invalid="true"] { border-color: var(--cs-danger); }.form-actions { display: flex; gap: 8px; }.field-message { display: flex; grid-column: 1 / -1; align-items: center; gap: 6px; margin: 0; font-size: 10px; }.success { color: var(--cs-success); }.error { color: var(--cs-danger); }
.binding-directory { overflow: hidden; }.binding-list { display: grid; }.binding-card { display: grid; grid-template-columns: minmax(210px, .8fr) minmax(460px, 2fr) auto; align-items: center; gap: 18px; min-height: 92px; padding: 15px 20px; border-bottom: 1px solid var(--cs-border); }.binding-card:last-child { border-bottom: 0; }.binding-identity { display: flex; align-items: center; gap: 11px; min-width: 0; }.binding-identity > i { display: grid; width: 38px; height: 38px; flex: 0 0 auto; place-items: center; border-radius: 11px; background: var(--cs-brand-50); color: var(--cs-brand-700); }.binding-identity strong, .binding-identity span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.binding-identity strong { font-size: 11px; }.binding-identity span { margin-top: 3px; color: var(--cs-text-muted); font-size: 9px; }.binding-facts { display: grid; grid-template-columns: 100px 60px minmax(130px, 1fr) minmax(110px, 1fr); gap: 12px; }.binding-facts > span { min-width: 0; }.binding-facts small, .binding-facts strong { display: block; }.binding-facts small { margin-bottom: 5px; color: var(--cs-text-muted); font-size: 8px; font-weight: 700; text-transform: uppercase; }.binding-facts strong { overflow: hidden; color: var(--cs-text-secondary); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }.binding-actions { display: flex; justify-content: flex-end; gap: 6px; }.preflight-result { display: flex; grid-column: 2 / -1; align-items: center; gap: 5px; margin: -8px 0 0; font-size: 9px; }
.directory-actions { display: flex; gap: 7px; }
.security-note { display: flex; align-items: flex-start; gap: 10px; padding: 13px 15px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface-subtle); color: var(--cs-text-muted); }.security-note > svg { flex: 0 0 auto; color: var(--cs-brand-600); }.security-note strong, .security-note span { display: block; }.security-note strong { color: var(--cs-text-secondary); font-size: 10px; }.security-note span { margin-top: 2px; font-size: 9px; }
@media (max-width: 1050px) { .binding-card { grid-template-columns: minmax(200px, .8fr) 1fr; }.binding-facts { grid-column: 1 / -1; order: 3; }.binding-actions { grid-column: 2; grid-row: 1; }.preflight-result { grid-column: 1 / -1; order: 4; } }
@media (max-width: 767px) { .repository-overview { grid-template-columns: 44px 1fr; padding: 17px; }.repository-overview > i { width: 44px; height: 44px; }.repository-overview > :last-child { grid-column: 1 / -1; justify-self: start; }.source-note { flex-wrap: wrap; }.source-note > :last-child { width: 100%; margin-left: 44px; }.binding-form { grid-template-columns: 1fr; padding: 16px; }.form-actions { display: grid; grid-template-columns: 1fr 1fr; }.field-message { grid-column: 1; }.binding-card { grid-template-columns: 1fr; gap: 13px; padding: 16px; }.binding-actions { grid-column: 1; grid-row: auto; justify-content: stretch; }.binding-actions > * { flex: 1; }.binding-facts { grid-column: 1; grid-template-columns: 1fr 1fr; }.preflight-result { grid-column: 1; margin: 0; }.panel-heading { flex-wrap: wrap; }.directory-actions { width: 100%; }.directory-actions > * { flex: 1; } }
</style>
