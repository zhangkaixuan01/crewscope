<script setup lang="ts" generic="D extends object, T extends object">
import { computed, ref, watch } from 'vue'
import { useDirtyForm } from '../../composables/useDirtyForm'
import BaseButton from '../base/BaseButton.vue'
import StatePanel from '../feedback/StatePanel.vue'

/**
 * Shared draft-editor lifecycle for the skill/knowledge twins (M12b-F04). Owns the local
 * draft state machine: dirty tracking (useDirtyForm), the recovery banner, the save
 * round-trip confirmation, and the applyServerDraft/confirmDiscard expose contract the
 * detail shells rely on. The field set is domain territory — it renders through
 * `#fields` (v-model onto the exposed snapshot object) and `#panels`.
 */
const props = defineProps<{
  /** Draft carried on the refreshed head; null is the read-only rest state. */
  serverDraft: D | null
  /** Owning object id: DOM id prefix and the F05 draft scope's objectId. */
  entryId: string
  scope: { accountId: string; principalId: string; organizationId: string; teamId: string }
  canManage: boolean
  saving: boolean
  /** Spoken reason while the manage permission is missing (domain vocabulary). */
  managePermissionReason: string
  /** Domain validation over the live snapshot; null means savable. */
  validate: (snapshot: T) => string | null
  /** Read-only rest-state copy, e.g. 「已发布的内容全部生效…」. */
  emptyDescription: string
  /** Seed for 「开始新草稿」 — knowledge starts blank, skill seeds frontmatter. */
  newDraft: () => T
  /** Server draft → form snapshot (knowledge folds in the head category, skill is content-only). */
  fromServer(draft: D | null): T
  /** Save round-trip check (knowledge compares title+content, skill just content).
   *  Both in method syntax: bivariance keeps them mountable when generics bind to constraints. */
  isSynced(server: D, snapshot: T): boolean
}>()

const emit = defineEmits<{
  save: [payload: T]
}>()

const drafting = ref(props.serverDraft !== null)
const snapshot = ref<T>(props.fromServer(props.serverDraft))
const restored = ref(false)

const formSnapshot = computed(() => ({ ...snapshot.value }) as T)
const draftScope = computed(() => ({ ...props.scope, objectId: props.entryId }))
const dirtyForm = useDirtyForm(formSnapshot, {
  draftScope,
  title: '放弃未保存的草稿修改？',
  description: '当前草稿有未保存内容，离开后这些修改会丢失。',
  confirmLabel: '放弃修改',
  cancelLabel: '继续编辑',
})
const draftAvailable = ref(Boolean(dirtyForm.restoreDraft()))
watch(formSnapshot, value => dirtyForm.sync(value))

watch(() => props.entryId, () => {
  drafting.value = props.serverDraft !== null
  snapshot.value = props.fromServer(props.serverDraft)
  restored.value = false
  dirtyForm.markClean()
  draftAvailable.value = Boolean(dirtyForm.restoreDraft())
})

// A refreshed head whose draft equals the form confirms the save round-trip; a different
// server draft (409 reload) must NOT overwrite the editor — the conflict panel gates
// applyServerDraft.
watch(() => props.serverDraft, draft => {
  if (draft !== null && props.isSynced(draft, snapshot.value)) {
    dirtyForm.markClean()
    dirtyForm.clearDraft()
    draftAvailable.value = false
  }
})

const saveDisabledReason = computed<string | null>(() => {
  if (!props.canManage) return props.managePermissionReason
  if (props.saving) return null
  return props.validate(snapshot.value)
})
const saveDisabled = computed(() => saveDisabledReason.value !== null || props.saving)
/** The read-only lock for members without the manage permission is a permission guard, so
 * its spoken reason must be programmatically tied to the locked controls. */
const readonlyReason = computed(() => !props.canManage ? `${props.entryId}-draft-readonly` : undefined)

function submit(): void {
  if (saveDisabled.value) return
  emit('save', snapshot.value)
}

function restoreDraft(): void {
  const saved = dirtyForm.restoreDraft() as T | null
  if (!saved) return
  snapshot.value = saved
  restored.value = true
  dirtyForm.markDirty()
}

function startDraft(): void {
  drafting.value = true
  snapshot.value = props.newDraft()
  dirtyForm.markClean()
}

/** Overwrites the form from the current head draft — callers gate this behind confirmDiscard. */
function applyServerDraft(): void {
  snapshot.value = props.fromServer(props.serverDraft)
  restored.value = false
  drafting.value = props.serverDraft !== null
  dirtyForm.markClean()
}

defineExpose({ applyServerDraft, confirmDiscard: dirtyForm.confirmDiscard, isDirty: dirtyForm.isDirty })
</script>

<template>
  <div class="draft-editor">
    <section v-if="draftAvailable" class="draft-editor__recovery" role="status">
      <div>
        <strong>发现未保存的本地草稿</strong>
        <span>上次离开时已安全保留当前浏览器中的草稿修改。</span>
      </div>
      <div>
        <BaseButton type="button" variant="secondary" size="small" @click="restoreDraft">恢复草稿</BaseButton>
        <BaseButton type="button" variant="ghost" size="small" @click="dirtyForm.clearDraft(); draftAvailable = false">丢弃</BaseButton>
      </div>
    </section>

    <p v-if="restored" class="draft-editor__restored" role="status">已恢复本地草稿，内容尚未保存到服务端。</p>

    <StatePanel v-if="!drafting" state="empty" title="当前没有未保存的草稿" :description="emptyDescription">
      <template #action>
        <BaseButton v-if="canManage" variant="secondary" size="small" @click="startDraft">开始新草稿</BaseButton>
      </template>
    </StatePanel>

    <form v-else class="draft-editor__form" @submit.prevent="submit">
      <slot name="fields" :snapshot="snapshot" :readonly-reason="readonlyReason" />
      <slot name="panels" :snapshot="snapshot" />
      <footer class="draft-editor__actions">
        <BaseButton type="submit" variant="primary" size="medium" :loading="saving" :disabled="saveDisabled">{{ saveDisabledReason ?? '保存草稿' }}</BaseButton>
        <span v-if="saveDisabledReason && !saving && canManage" class="draft-editor__reason">{{ saveDisabledReason }}</span>
        <span v-else-if="!canManage" :id="`${entryId}-draft-readonly`" class="draft-editor__reason">{{ saveDisabledReason }}</span>
      </footer>
    </form>
  </div>
</template>

<style scoped>
.draft-editor { display: flex; min-width: 0; flex-direction: column; gap: var(--cs-space-12); }
.draft-editor__restored { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.draft-editor__recovery { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-12); padding: var(--cs-space-12); border: 1px solid var(--cs-border-accent); border-radius: var(--cs-radius-md); background: var(--cs-surface-accent); }
.draft-editor__recovery > div { display: flex; flex-direction: column; gap: var(--cs-space-2); }
.draft-editor__recovery > div:last-child { flex-direction: row; align-items: center; }
.draft-editor__form { display: flex; flex-direction: column; gap: var(--cs-space-12); }
.draft-editor__actions { display: flex; align-items: center; gap: var(--cs-space-8); }
.draft-editor__reason { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
</style>
