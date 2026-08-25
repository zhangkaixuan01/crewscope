<script setup lang="ts">
import { CheckCircle2, Code2, GitBranch, RefreshCw, ShieldCheck } from '@lucide/vue'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { codingTargetDraftKey } from '../../domains/coding/draft'
import type { CodingScope, CodingTargetSelection } from '../../domains/coding/types'
import { useCodingStore } from '../../domains/coding/store'
import BaseButton from '../base/BaseButton.vue'

const props = withDefaults(defineProps<{
  scope: CodingScope
  workItemId: string
  disabled?: boolean
}>(), { disabled: false })

const emit = defineEmits<{
  change: [selection: CodingTargetSelection | null, valid: boolean]
}>()

const codingStore = useCodingStore()
const form = reactive({
  enabled: true,
  repositoryBindingId: '',
  baselineRef: '',
  allowedPaths: '.',
  buildProfileCoordinate: '',
})
const preflight = ref<{ bindingId: string, baselineRef: string, commit: string } | null>(null)
const submitted = ref(false)

const repositoriesResource = computed(() => codingStore.state.repositories)
const profilesResource = computed(() => codingStore.state.buildProfiles[props.workItemId] ?? null)
const repositories = computed(() => (repositoriesResource.value.value ?? []).filter(item => item.status === 'ACTIVE'))
const profiles = computed(() => profilesResource.value?.value ?? [])
const repository = computed(() => repositories.value.find(item => item.id === form.repositoryBindingId) ?? null)
const profile = computed(() => profiles.value.find(item => profileCoordinate(item) === form.buildProfileCoordinate) ?? null)
const paths = computed(() => form.allowedPaths.split('\n').map(value => value.trim()).filter(Boolean))
const pathsValid = computed(() => paths.value.length >= 1
  && paths.value.length <= 200
  && paths.value.every(path => canonicalPath(path)))
const preflightKey = computed(() => `${props.workItemId}:${form.repositoryBindingId}:${form.baselineRef.trim()}`)
const preflightResource = computed(() => codingStore.state.targetPreflights[preflightKey.value] ?? null)
const preflightCurrent = computed(() => Boolean(
  preflight.value
  && preflight.value.bindingId === form.repositoryBindingId
  && preflight.value.baselineRef === form.baselineRef.trim(),
))
const selectionValid = computed(() => !form.enabled || Boolean(
  repository.value
  && form.baselineRef.trim()
  && pathsValid.value
  && profile.value
  && preflightCurrent.value,
))
const settingsQuery = computed(() => ({ team: props.scope.teamId, project: props.scope.projectId }))

onMounted(async () => {
  restoreDraft()
  codingStore.activateScope(props.scope)
  await Promise.all([
    codingStore.loadRepositories(props.scope),
    codingStore.loadBuildProfiles(props.workItemId),
  ])
  applyDefaults()
})

watch(
  () => [repositories.value.map(item => `${item.id}:${item.version}`).join(','), profiles.value.map(profileCoordinate).join(',')],
  applyDefaults,
)

watch(
  () => [form.enabled, form.repositoryBindingId, form.baselineRef, form.allowedPaths, form.buildProfileCoordinate],
  () => {
    preflight.value = null
    persistDraft()
    publish()
  },
)

watch(selectionValid, publish)

function applyDefaults(): void {
  if (['idle', 'loading'].includes(repositoriesResource.value.phase)
    || !profilesResource.value
    || ['idle', 'loading'].includes(profilesResource.value.phase)) return
  const selectedRepository = repositories.value.find(item => item.id === form.repositoryBindingId)
  if (!selectedRepository) {
    form.repositoryBindingId = repositories.value[0]?.id ?? ''
    form.baselineRef = repositories.value[0]?.defaultBranch ?? ''
  }
  const currentRepository = repositories.value.find(item => item.id === form.repositoryBindingId)
  if (!form.baselineRef.trim()) form.baselineRef = currentRepository?.defaultBranch ?? ''

  const selectedProfile = profiles.value.find(item => profileCoordinate(item) === form.buildProfileCoordinate)
  if (!selectedProfile) form.buildProfileCoordinate = profiles.value[0] ? profileCoordinate(profiles.value[0]) : ''
  publish()
}

function repositoryChanged(): void {
  const selected = repositories.value.find(item => item.id === form.repositoryBindingId)
  form.baselineRef = selected?.defaultBranch ?? ''
}

async function retryOptions(): Promise<void> {
  await Promise.all([
    codingStore.loadRepositories(props.scope, true),
    codingStore.loadBuildProfiles(props.workItemId, true),
  ])
  applyDefaults()
}

async function runPreflight(): Promise<void> {
  submitted.value = true
  const baselineRef = form.baselineRef.trim()
  if (!form.repositoryBindingId || !baselineRef || !pathsValid.value || !profile.value) {
    publish()
    return
  }
  const result = await codingStore.preflightTarget(props.workItemId, form.repositoryBindingId, baselineRef)
  if (!result || result.baselineRef !== baselineRef || result.repositoryKey !== repository.value?.repositoryKey) return
  preflight.value = { bindingId: form.repositoryBindingId, baselineRef, commit: result.baselineCommit }
  publish()
}

function publish(): void {
  if (!form.enabled) {
    emit('change', null, true)
    return
  }
  const selectedProfile = profile.value
  if (!selectionValid.value || !selectedProfile) {
    emit('change', null, false)
    return
  }
  emit('change', {
    repositoryBindingId: form.repositoryBindingId,
    baselineRef: form.baselineRef.trim(),
    allowedPaths: paths.value,
    buildProfile: {
      key: selectedProfile.key,
      version: selectedProfile.version,
      profileHash: selectedProfile.profileHash,
    },
  }, true)
}

function restoreDraft(): void {
  try {
    const raw = sessionStorage.getItem(codingTargetDraftKey(props.scope, props.workItemId))
    if (!raw) return
    const value = JSON.parse(raw) as Record<string, unknown>
    if (typeof value.enabled === 'boolean') form.enabled = value.enabled
    if (typeof value.repositoryBindingId === 'string') form.repositoryBindingId = value.repositoryBindingId
    if (typeof value.baselineRef === 'string') form.baselineRef = value.baselineRef
    if (typeof value.allowedPaths === 'string') form.allowedPaths = value.allowedPaths
    if (typeof value.buildProfileCoordinate === 'string') form.buildProfileCoordinate = value.buildProfileCoordinate
  } catch {
    sessionStorage.removeItem(codingTargetDraftKey(props.scope, props.workItemId))
  }
}

function persistDraft(): void {
  try {
    sessionStorage.setItem(codingTargetDraftKey(props.scope, props.workItemId), JSON.stringify({ ...form }))
  } catch {
    // Draft persistence is an interaction aid; browser storage denial must not block delegation.
  }
}

function profileCoordinate(value: { key: string, version: number, profileHash: string }): string {
  return `${value.key}:${value.version}:${value.profileHash}`
}

function canonicalPath(path: string): boolean {
  if (path === '.') return true
  if (!path || path.length > 1_024 || path.startsWith('/') || path.startsWith('\\') || path.includes('\\')) return false
  if (/^[A-Za-z]:/.test(path) || [...path].some(character => character.codePointAt(0)! < 0x20)) return false
  return path.split('/').every(component => component.length > 0 && component !== '.' && component !== '..')
}
</script>

<template>
  <section class="coding-target" :class="{ disabled: !form.enabled }" aria-labelledby="coding-target-title">
    <div class="coding-header">
      <span><Code2 :size="17" aria-hidden="true" /></span>
      <div><strong id="coding-target-title">Coding Agent</strong><small>在受管 Worktree 与 Sandbox 中执行代码任务</small></div>
      <label class="coding-switch"><input v-model="form.enabled" type="checkbox" :disabled="disabled"><span>{{ form.enabled ? '已启用' : '通用任务' }}</span></label>
    </div>

    <p v-if="!form.enabled" class="coding-note">本次创建通用 Agent Task，不固化 Repository 与 CodingTargetSnapshot。</p>
    <div v-else-if="repositoriesResource.phase === 'loading' || profilesResource?.phase === 'loading'" class="coding-state" role="status" aria-live="polite" aria-atomic="true"><RefreshCw :size="14" class="spin" />正在读取仓库与构建配置…</div>
    <div v-else-if="repositoriesResource.phase === 'error' || profilesResource?.phase === 'error'" class="coding-state error" role="alert"><span>{{ repositoriesResource.errorMessage ?? profilesResource?.errorMessage }}</span><BaseButton type="button" size="small" variant="ghost" @click="retryOptions">重试</BaseButton></div>
    <div v-else-if="repositories.length === 0" class="coding-state empty">
      <span>当前 WorkProject 没有 ACTIVE RepositoryBinding。</span>
      <RouterLink :to="{ name: 'repository-settings', query: settingsQuery }">前往仓库设置</RouterLink>
    </div>
    <div v-else-if="profiles.length === 0" class="coding-state empty">当前 WorkItem 没有可用 BuildProfile，暂时不能创建 Coding Task。</div>
    <div v-else class="coding-fields">
      <label><span>Repository</span><select v-model="form.repositoryBindingId" :disabled="disabled" @change="repositoryChanged"><option v-for="item in repositories" :key="item.id" :value="item.id">{{ item.repositoryKey }}</option></select></label>
      <label><span>基线 Ref</span><div class="ref-field"><GitBranch :size="14" /><input v-model="form.baselineRef" maxlength="255" autocomplete="off" :disabled="disabled" placeholder="main"></div></label>
      <label class="wide"><span>Allowed Paths <small>每行一个仓库相对路径，`.` 表示整个仓库</small></span><textarea v-model="form.allowedPaths" rows="3" maxlength="20000" :disabled="disabled" :aria-invalid="submitted && !pathsValid" placeholder="src/main&#10;pom.xml" /></label>
      <label class="wide"><span>BuildProfile</span><select v-model="form.buildProfileCoordinate" :disabled="disabled"><option v-for="item in profiles" :key="profileCoordinate(item)" :value="profileCoordinate(item)">{{ item.key }} · v{{ item.version }} · {{ item.buildTool }} / Java {{ item.javaRelease }}</option></select><small v-if="profile">允许命令：{{ profile.commandKinds.join('、') }}</small></label>
      <div class="preflight-row wide" aria-live="polite" aria-atomic="true">
        <BaseButton type="button" size="small" variant="secondary" :loading="preflightResource?.phase === 'loading'" :disabled="disabled || !form.baselineRef.trim() || !pathsValid" @click="runPreflight"><ShieldCheck :size="14" />验证 Ref</BaseButton>
        <span v-if="preflightCurrent" class="preflight-ok"><CheckCircle2 :size="14" />Preflight 通过 · <code>{{ preflight?.commit.slice(0, 12) }}</code></span>
        <span v-else-if="preflightResource?.phase === 'error'" class="preflight-error" role="alert">{{ preflightResource.errorMessage }}</span>
        <span v-else>创建前解析并固定完整 Baseline Commit。</span>
      </div>
      <p v-if="submitted && !pathsValid" class="field-error wide" role="alert">Allowed Paths 需包含 1–200 个 canonical 仓库相对路径，不能使用绝对路径或 `..`。</p>
    </div>
  </section>
</template>

<style scoped>
.coding-target { margin: 15px 20px 0; overflow: hidden; border: 1px solid #cfe2d5; border-radius: var(--cs-radius-md); background: linear-gradient(145deg, #fff 0%, #f7fbf8 100%); }.coding-target > header { display: grid; grid-template-columns: 34px minmax(0, 1fr) auto; align-items: center; gap: 9px; padding: 11px 12px; border-bottom: 1px solid var(--cs-border); }.coding-target > header > span { display: grid; width: 34px; height: 34px; place-items: center; border-radius: 10px; background: var(--cs-brand-100); color: var(--cs-brand-700); }.coding-target strong, .coding-target small { display: block; }.coding-target strong { font-size: 10px; }.coding-target small { margin-top: 2px; color: var(--cs-text-muted); font-size: 8px; font-weight: 500; }.coding-switch { display: flex; align-items: center; gap: 6px; color: var(--cs-text-secondary); font-size: 9px; font-weight: 750; cursor: pointer; }.coding-switch input { accent-color: var(--cs-brand-600); }.coding-note, .coding-state { min-height: 52px; margin: 0; padding: 14px; color: var(--cs-text-muted); font-size: 9px; line-height: 1.5; }.coding-state { display: flex; align-items: center; justify-content: center; gap: 8px; }.coding-state.error { color: var(--cs-danger); }.coding-state.empty { flex-wrap: wrap; background: var(--cs-warning-soft); color: #79511e; }.coding-state a { color: inherit; font-weight: 800; }.coding-fields { display: grid; grid-template-columns: 1fr 1fr; gap: 11px; padding: 13px; }.coding-fields label { display: grid; min-width: 0; gap: 5px; color: var(--cs-text-secondary); font-size: 9px; font-weight: 750; }.coding-fields label > span { display: flex; align-items: baseline; gap: 5px; }.coding-fields label small { display: inline; }.coding-fields input, .coding-fields select, .coding-fields textarea { width: 100%; min-height: 35px; padding: 7px 9px; border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: #fff; color: var(--cs-text); font: 9px/1.45 var(--cs-font-sans); }.coding-fields textarea { resize: vertical; }.coding-fields [aria-invalid="true"] { border-color: var(--cs-danger); }.wide { grid-column: 1 / -1; }.ref-field { position: relative; }.ref-field svg { position: absolute; top: 10px; left: 9px; color: var(--cs-text-muted); }.ref-field input { padding-left: 29px; }.preflight-row { display: flex; min-height: 34px; align-items: center; gap: 9px; color: var(--cs-text-muted); font-size: 8px; }.preflight-row > span { display: flex; min-width: 0; align-items: center; gap: 5px; }.preflight-ok { color: var(--cs-success); }.preflight-error, .field-error { color: var(--cs-danger); }.preflight-row code { font-size: 8px; }.field-error { margin: -4px 0 0; font-size: 8px; }.spin { animation: spin 1s linear infinite; }@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 600px) { .coding-target { margin-inline: 16px; }.coding-target > header { grid-template-columns: 34px minmax(0, 1fr); }.coding-switch { grid-column: 2; }.coding-fields { grid-template-columns: 1fr; padding: 12px; }.wide { grid-column: 1; }.preflight-row { align-items: stretch; flex-direction: column; }.preflight-row > button { width: 100%; }.preflight-row > span { min-height: 28px; }.coding-state { align-items: flex-start; flex-direction: column; } }
.coding-header { display: grid; grid-template-columns: 34px minmax(0, 1fr) auto; align-items: center; gap: 9px; padding: 11px 12px; border-bottom: 1px solid var(--cs-border); }.coding-header > span { display: grid; width: 34px; height: 34px; place-items: center; border-radius: 10px; background: var(--cs-brand-100); color: var(--cs-brand-700); }
@media (max-width: 600px) { .coding-header { grid-template-columns: 34px minmax(0, 1fr); }.coding-header .coding-switch { grid-column: 2; } }
@media (prefers-reduced-motion: reduce) { .spin { animation: none; } }
</style>
