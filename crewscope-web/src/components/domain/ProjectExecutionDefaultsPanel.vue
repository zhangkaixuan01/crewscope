<script setup lang="ts">
import { computed, inject, onMounted, ref, watch } from 'vue'
import BaseButton from '../base/BaseButton.vue'
import StatePanel from '../feedback/StatePanel.vue'
import { AUTH_PRINCIPAL } from '../../app/auth'
import { useCodingStore } from '../../domains/coding/store'
import type { CodingScope, ExecutionDefaultsInput } from '../../domains/coding/types'
import { useDirtyForm } from '../../composables/useDirtyForm'

const props = defineProps<{ scope: CodingScope | null }>()
const store = useCodingStore()
const principal = inject(AUTH_PRINCIPAL)
const branch = ref('')
const repositoryBindingId = ref<string | null>(null)
const profileKey = ref('')
const saving = ref(false)
const saved = ref(false)
const defaults = computed(() => store.state.executionDefaults.value)
const profiles = computed(() => store.state.projectBuildProfiles.value ?? [])
const repositories = computed(() => (store.state.repositories.value ?? []).filter(item => item.status === 'ACTIVE'))

const formSnapshot = computed(() => ({ branch: branch.value, repositoryBindingId: repositoryBindingId.value, profileKey: profileKey.value }))
const draftScope = computed(() => principal && principal.accountId && props.scope ? {
  accountId: principal.accountId, principalId: principal.id,
  organizationId: props.scope.organizationId, teamId: props.scope.teamId, projectId: props.scope.projectId,
  objectId: 'execution-defaults',
} : undefined)
const dirtyForm = useDirtyForm(formSnapshot, { draftScope })
const draftAvailable = ref(false)

watch(defaults, value => {
  branch.value = value?.branch?.value ?? ''
  repositoryBindingId.value = value?.repositoryBindingId?.value ?? null
  profileKey.value = value?.buildProfile?.value?.key ?? ''
  dirtyForm.markClean(formSnapshot.value)
  // The server value just re-baselined the form, so an older browser draft is recoverable
  // only through the explicit banner rather than silently overwriting (M9b-F05).
  draftAvailable.value = Boolean(dirtyForm.restoreDraft())
}, { immediate: true })

// Edits persist immediately as a browser draft; a refresh or scope switch never eats them.
watch(formSnapshot, snapshot => {
  dirtyForm.sync(snapshot)
  if (dirtyForm.isDirty.value) {
    dirtyForm.saveDraft()
    draftAvailable.value = false
  }
})

watch(() => props.scope, async scope => {
  if (!scope) return
  await Promise.all([
    store.loadExecutionDefaults(scope, true),
    store.loadProjectBuildProfiles(scope, true),
  ])
}, { immediate: true })

onMounted(() => { if (props.scope) store.activateScope(props.scope) })

function restoreDraft(): void {
  const draft = dirtyForm.restoreDraft()
  if (!draft) return
  branch.value = draft.branch
  repositoryBindingId.value = draft.repositoryBindingId
  profileKey.value = draft.profileKey
  dirtyForm.sync(formSnapshot.value)
  draftAvailable.value = false
}

function discardDraft(): void {
  dirtyForm.clearDraft()
  draftAvailable.value = false
}

async function save(): Promise<void> {
  const current = defaults.value
  if (!props.scope || !current) return
  const binding = repositories.value.find(item => item.id === repositoryBindingId.value) ?? null
  const profile = profiles.value.find(item => item.key === profileKey.value) ?? null
  saving.value = true
  saved.value = false
  const input: ExecutionDefaultsInput = {
    repositoryBindingId: binding?.id ?? null,
    repositoryBindingVersion: binding?.version ?? null,
    branch: binding && branch.value.trim() ? branch.value.trim() : null,
    buildProfile: profile ? { key: profile.key, version: profile.version, profileHash: profile.profileHash } : null,
    agentProfileId: current.agentProfileId.value,
    agentProfileRevision: current.agentProfileRevision.value,
  }
  const result = await store.saveExecutionDefaults(input)
  saving.value = false
  saved.value = Boolean(result)
  // The values are server-authoritative now; the browser draft has nothing left to hold.
  if (result) {
    dirtyForm.clearDraft()
    dirtyForm.markClean(formSnapshot.value)
    draftAvailable.value = false
  }
}
</script>

<template>
  <section class="execution-defaults panel" aria-labelledby="execution-defaults-title">
    <div class="execution-defaults__header">
      <div>
        <p class="eyebrow">Project execution</p>
        <h2 id="execution-defaults-title">项目默认执行配置</h2>
        <p>日常创建任务会优先复用这里的仓库、分支和受控构建方案；任务仍可在启动前明确覆盖。</p>
      </div>
      <span v-if="defaults" class="muted">版本 {{ defaults.version }}</span>
    </div>
    <StatePanel v-if="store.state.executionDefaults.phase === 'loading'" compact state="loading" />
    <StatePanel v-else-if="store.state.executionDefaults.phase === 'error' && !defaults" compact state="error" :description="store.state.executionDefaults.errorMessage ?? undefined" />
    <form v-else class="execution-defaults__form" @submit.prevent="save">
      <div v-if="draftAvailable" class="draft-recovery" role="status">
        <div><strong>发现未保存的本地草稿</strong><span>此浏览器保留了上次未保存的默认执行配置修改。</span></div>
        <div><BaseButton type="button" variant="secondary" size="small" @click="restoreDraft">恢复草稿</BaseButton><BaseButton type="button" variant="ghost" size="small" @click="discardDraft">丢弃</BaseButton></div>
      </div>
      <label>默认仓库<select v-model="repositoryBindingId"><option :value="null">不设置</option><option v-for="item in repositories" :key="item.id" :value="item.id">{{ item.repositoryKey }} · {{ item.defaultBranch }}</option></select></label>
      <label>默认分支<input v-model="branch" :disabled="!repositoryBindingId" aria-describedby="execution-defaults-branch-reason" placeholder="使用仓库默认分支" /></label>
      <label>构建方案<select v-model="profileKey"><option value="">不设置</option><option v-for="profile in profiles" :key="`${profile.key}:${profile.version}`" :value="profile.key">{{ profile.key }} v{{ profile.version }}</option></select></label>
      <div class="execution-defaults__actions"><BaseButton type="submit" :disabled="saving || !defaults" aria-describedby="execution-defaults-save-reason">{{ saving ? '保存中…' : '保存项目默认' }}</BaseButton><span v-if="saved" class="success">已保存</span></div>
      <p id="execution-defaults-branch-reason" class="sr-only">{{ repositoryBindingId ? '可填写项目默认分支。' : '请先选择默认仓库，才能设置分支。' }}</p>
      <p id="execution-defaults-save-reason" class="sr-only">{{ defaults ? (saving ? '正在保存项目默认配置。' : '保存当前项目默认配置。') : '项目默认配置尚未加载完成。' }}</p>
      <p v-if="store.state.executionDefaults.errorMessage" class="error-text">{{ store.state.executionDefaults.errorMessage }}</p>
    </form>
  </section>
</template>

<style scoped>
.execution-defaults__header { display: flex; justify-content: space-between; gap: 1rem; align-items: flex-start; }
.execution-defaults__form { display: grid; gap: .8rem; max-width: 42rem; margin-top: 1rem; }
.draft-recovery { display: flex; justify-content: space-between; align-items: center; gap: .8rem; padding: .6rem .8rem; border: 1px solid var(--border-subtle); border-radius: .5rem; background: var(--surface); }
.draft-recovery strong { display: block; font-size: .875rem; }
.draft-recovery span { display: block; color: var(--text-secondary); font-size: .8rem; }
.draft-recovery > div:last-child { display: flex; gap: .4rem; }
label { display: grid; gap: .35rem; font-weight: 600; }
/* Control height comes from the density token, never a rem literal: the root font is 14px, so
   2.5rem measured 35px — under the 44px touch floor the narrow viewport enforces through the same
   token (48px there, so the small derived sizes stay compliant too). */
input, select { min-height: var(--cs-density-control-height); padding: .4rem .6rem; border: 1px solid var(--border-subtle); border-radius: .5rem; background: var(--surface); color: var(--text-primary); }
.execution-defaults__actions { display: flex; align-items: center; gap: .8rem; margin-top: .35rem; }
.muted { color: var(--text-secondary); font-size: .875rem; }
.success { color: var(--success-text, #19733c); }
.error-text { color: var(--danger-text, #a12828); }
</style>
