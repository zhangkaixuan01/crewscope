<script setup lang="ts">
import { LockKeyhole, UsersRound } from '@lucide/vue'
import { inject, useId, watch, ref } from 'vue'
import BaseButton from '../base/BaseButton.vue'
import BaseDialog from '../base/BaseDialog.vue'
import type { ConversationVisibility } from '../../domains/conversation/types'
import type { PrincipalScope } from '../../domains/principal/types'
import { clearConversationCreateDraft, readConversationCreateDraft, writeConversationCreateDraft } from '../../domains/conversation/createDraft'
import { AUTH_PRINCIPAL } from '../../app/auth'

const props = withDefaults(defineProps<{
  scope: PrincipalScope | null
  pending?: boolean
  error?: string | null
}>(), {
  pending: false,
  error: null,
})

const emit = defineEmits<{
  close: []
  submit: [payload: { title: string, visibility: ConversationVisibility }]
}>()

const title = ref('')
const visibility = ref<ConversationVisibility>('PRIVATE')
const formId = useId()
const principal = inject(AUTH_PRINCIPAL)

restoreDraft()

// Closing or escaping keeps the in-progress title as a draft; only a successful create clears it.
watch([title, visibility], () => {
  if (!props.scope) return
  if (title.value.trim()) writeConversationCreateDraft(props.scope, { title: title.value, visibility: visibility.value }, principal)
  else clearConversationCreateDraft(props.scope, principal)
})

function restoreDraft(): void {
  if (!props.scope || title.value.trim()) return
  const draft = readConversationCreateDraft(props.scope, principal)
  if (!draft) return
  title.value = draft.title
  visibility.value = draft.visibility
}

function submit(): void {
  emit('submit', { title: title.value, visibility: visibility.value })
}
</script>

<template>
  <BaseDialog open title="新建对话" eyebrow="New conversation" width="min(520px, 100%)" :close-on-backdrop="!pending" close-label="关闭新建对话" @close="emit('close')">
    <form :id="formId" class="create-dialog-form" @submit.prevent="submit">
      <label>
        <span>标题</span>
        <input v-model="title" autofocus maxlength="200" autocomplete="off" :disabled="pending" placeholder="例如：规划 GitHub Provider 接入" />
      </label>
      <fieldset>
        <legend>可见范围</legend>
        <label :class="{ active: visibility === 'PRIVATE' }">
          <input v-model="visibility" type="radio" value="PRIVATE" :disabled="pending" />
          <LockKeyhole :size="17" /><span><strong>私有对话</strong><small>仅 Owner、Personal Agent 与显式参与者可见</small></span>
        </label>
        <label :class="{ active: visibility === 'TEAM' }">
          <input v-model="visibility" type="radio" value="TEAM" :disabled="pending" />
          <UsersRound :size="17" /><span><strong>团队对话</strong><small>当前 Team 成员可发现，写入仍需 Participant 资格</small></span>
        </label>
      </fieldset>
      <p v-if="props.error" class="form-error" role="alert">{{ props.error }}</p>
    </form>
    <template #footer>
      <BaseButton variant="secondary" @click="emit('close')">取消</BaseButton>
      <BaseButton type="submit" :form="formId" :loading="props.pending">创建对话</BaseButton>
    </template>
  </BaseDialog>
</template>

<style scoped>
.create-dialog-form { display: grid; gap: var(--cs-space-20); }
.create-dialog-form > label > span, .create-dialog-form legend { display: block; margin-bottom: var(--cs-space-8); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }
.create-dialog-form input[type='text'], .create-dialog-form > label > input { width: 100%; min-height: 40px; padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); color: var(--cs-text); }
.create-dialog-form fieldset { display: grid; gap: var(--cs-space-8); padding: 0; border: 0; }
.create-dialog-form fieldset label { display: grid; grid-template-columns: 16px 18px 1fr; align-items: start; gap: var(--cs-space-8); padding: var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); cursor: pointer; }
.create-dialog-form fieldset label.active { border-color: var(--cs-border-accent-strong); background: var(--cs-surface-accent); }
.create-dialog-form fieldset strong, .create-dialog-form fieldset small { display: block; }
.create-dialog-form fieldset strong { font-size: var(--cs-text-sm); }
.create-dialog-form fieldset small { margin-top: var(--cs-space-2); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.form-error { margin: 0; color: var(--cs-danger); font-size: var(--cs-text-sm); }
@media (max-width: 767px) { .create-dialog-form input[type='text'], .create-dialog-form > label > input { font-size: var(--cs-text-md); } }

/* M12b-F01：悬停反馈补齐——此前这些可点元素对指针移动完全沉默。 */
.create-dialog-form fieldset label:hover:not(:disabled) { background: var(--cs-surface-accent); }
.create-dialog-form fieldset label:active:not(:disabled) { transform: scale(.97); }
.create-dialog-form fieldset label { transition: background-color var(--cs-motion-fast) var(--cs-ease-out), border-color var(--cs-motion-fast) var(--cs-ease-out), transform var(--cs-motion-fast) var(--cs-ease-out); }
</style>
