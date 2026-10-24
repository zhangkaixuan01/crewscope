<script setup lang="ts">
import { GitBranchPlus } from '@lucide/vue'
import { computed, ref, useId, watch } from 'vue'
import { useDirtyForm } from '../../composables/useDirtyForm'
import { useCodingStore } from '../../domains/coding/store'
import type { CodingScope } from '../../domains/coding/types'
import { COMMIT_PATTERN, type RepositoryBuildInput } from '../../domains/knowledge/types'
import type { SettingsScope } from '../../domains/settings/types'
import { useScopeStore } from '../../domains/scope/store'
import BaseButton from '../base/BaseButton.vue'
import BaseDialog from '../base/BaseDialog.vue'
import BaseSelect, { type BaseSelectOption } from '../base/BaseSelect.vue'

const props = defineProps<{
  scope: SettingsScope | null
  submitting: boolean
  errorMessage: string | null
  errorCode: string | null
  errorStatus: number | null
}>()

const emit = defineEmits<{
  close: []
  submit: [input: RepositoryBuildInput]
}>()

const scopeStore = useScopeStore()
const codingStore = useCodingStore()
const projectId = ref('')
const bindingId = ref('')
const commit = ref('')
const submitted = ref(false)
const formId = useId()
const formSnapshot = computed(() => ({ projectId: projectId.value, bindingId: bindingId.value, commit: commit.value }))
const dirtyForm = useDirtyForm(formSnapshot)

/** The scope store keeps the ACTIVE work projects of the selected Team already loaded. */
const projects = computed(() => scopeStore.state.projects)
const projectOptions = computed<BaseSelectOption[]>(() => projects.value.length > 0
  ? projects.value.map(project => ({ label: `${project.name}（${project.key}）`, value: project.id }))
  : [{ label: '当前 Team 暂无项目', value: '', disabled: true }])

/** Cascade: switching the project re-reads that project's repository bindings (ACTIVE only). */
const projectScope = computed<CodingScope | null>(() =>
  props.scope && projectId.value ? { organizationId: props.scope.organizationId, teamId: props.scope.teamId, projectId: projectId.value } : null)
const bindings = computed(() => (codingStore.state.repositories.value ?? []).filter(binding => binding.status === 'ACTIVE'))
const bindingsLoading = computed(() => codingStore.state.repositories.phase === 'loading' || codingStore.state.repositories.phase === 'idle')
const bindingsError = computed(() => codingStore.state.repositories.errorMessage)
const bindingOptions = computed<BaseSelectOption[]>(() => {
  if (!projectScope.value) return [{ label: '请先选择项目', value: '', disabled: true }]
  if (bindingsLoading.value) return [{ label: '正在读取仓库绑定…', value: '', disabled: true }]
  if (bindings.value.length === 0) return [{ label: '该项目暂无生效的仓库绑定', value: '', disabled: true }]
  return bindings.value.map(binding => ({ label: `${binding.repositoryKey} · ${binding.defaultBranch}`, value: binding.id }))
})

const commitValid = computed(() => COMMIT_PATTERN.test(commit.value.trim()))
const valid = computed(() => Boolean(projectId.value && bindingId.value && commitValid.value))

watch(projectScope, scope => {
  bindingId.value = ''
  if (scope) void codingStore.loadRepositories(scope, true)
}, { immediate: true })

watch(formSnapshot, () => { submitted.value = false; dirtyForm.markDirty() })

/** Contract §2: the command is structurally idempotent, so only the 4xx shapes need worded guidance. */
const commandError = computed(() => {
  if (!props.errorMessage) return null
  if (props.errorCode === 'aggregate_not_found') return '仓库绑定不存在或不属于该项目。'
  if (props.errorStatus === 422) return '所选仓库绑定已停用。'
  return props.errorMessage
})

async function requestClose(): Promise<void> {
  if (props.submitting) return
  await dirtyForm.closeWithGuard(() => emit('close'))
}

function submit(): void {
  submitted.value = true
  if (!valid.value || props.submitting) return
  dirtyForm.markClean()
  emit('submit', {
    projectId: projectId.value,
    bindingId: bindingId.value,
    commit: commit.value.trim(),
  })
}

function reloadBindings(): void {
  if (projectScope.value) void codingStore.loadRepositories(projectScope.value, true)
}
</script>

<template>
  <BaseDialog
    open
    title="构建仓库索引"
    eyebrow="Knowledge index"
    width="min(560px, 100%)"
    :close-disabled="submitting"
    close-label="关闭构建仓库索引"
    @close="requestClose"
  >
    <template #icon><GitBranchPlus :size="20" /></template>
    <form :id="formId" class="build-dialog__content" @submit.prevent="submit">
      <label class="build-dialog__field">
        <span>项目</span>
        <BaseSelect v-model="projectId" :options="projectOptions" :disabled="submitting" aria-label="项目" />
      </label>

      <label class="build-dialog__field">
        <span>仓库绑定</span>
        <BaseSelect
          v-model="bindingId"
          :options="bindingOptions"
          :disabled="submitting || !projectId"
          aria-label="仓库绑定"
          :aria-describedby="bindingsError ? 'repository-build-binding-error' : undefined"
        />
        <small v-if="bindingsError" id="repository-build-binding-error" class="build-dialog__error">
          {{ bindingsError }}
          <button type="button" class="build-dialog__retry" @click="reloadBindings">重试读取</button>
        </small>
        <small v-else-if="projectId" class="build-dialog__hint">仅列出 ACTIVE 的受管仓库绑定。</small>
      </label>

      <label class="build-dialog__field">
        <span>提交（Commit）</span>
        <input
          v-model="commit"
          class="build-dialog__mono-input"
          type="text"
          autocomplete="off"
          spellcheck="false"
          :disabled="submitting"
          :aria-invalid="submitted && !commitValid || undefined"
          aria-describedby="repository-build-commit-hint"
          required
        >
        <small id="repository-build-commit-hint" class="build-dialog__hint">要索引的提交，40 或 64 位十六进制字符。</small>
        <small v-if="submitted && !commitValid" class="build-dialog__error">提交需为 40 或 64 位十六进制字符。</small>
      </label>

      <p v-if="commandError" class="build-dialog__command-error" role="alert">{{ commandError }}</p>
    </form>

    <template #footer>
      <BaseButton type="button" variant="ghost" :disabled="submitting" @click="requestClose">取消</BaseButton>
      <BaseButton type="submit" :form="formId" :loading="submitting" :disabled="!valid">入队构建</BaseButton>
    </template>
  </BaseDialog>
</template>

<style scoped>
.build-dialog__content { display: grid; gap: var(--cs-space-16); }
.build-dialog__field { display: grid; gap: var(--cs-space-4); }
.build-dialog__field > span { color: var(--cs-text-secondary); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.build-dialog__field input { width: 100%; min-height: 40px; padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); font-size: var(--cs-text-base); }
.build-dialog__mono-input { font-family: var(--cs-font-mono, ui-monospace, monospace); }
.build-dialog__hint { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.build-dialog__error { color: var(--cs-danger); font-size: var(--cs-text-xs); }
.build-dialog__retry { border: none; background: none; padding: 0; color: var(--cs-text-brand); font-size: var(--cs-text-xs); cursor: pointer; text-decoration: underline; }
.build-dialog__command-error { margin: 0; color: var(--cs-danger); font-size: var(--cs-text-sm); }

/* M12b-F01：悬停反馈补齐——此前这些可点元素对指针移动完全沉默。 */
.build-dialog__retry:hover:not(:disabled) { background: var(--cs-surface-accent); }
.build-dialog__retry:active:not(:disabled) { transform: scale(.97); }
.build-dialog__retry { transition: background-color var(--cs-motion-fast) var(--cs-ease-out), border-color var(--cs-motion-fast) var(--cs-ease-out), transform var(--cs-motion-fast) var(--cs-ease-out); }
</style>
