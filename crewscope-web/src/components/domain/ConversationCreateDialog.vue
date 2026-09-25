<script setup lang="ts">
import { LockKeyhole, UsersRound, X } from '@lucide/vue'
import { inject, nextTick, onMounted, ref, watch } from 'vue'
import BaseButton from '../base/BaseButton.vue'
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
const dialog = ref<HTMLElement | null>(null)
const titleInput = ref<HTMLInputElement | null>(null)
const principal = inject(AUTH_PRINCIPAL)

onMounted(() => {
  restoreDraft()
  void nextTick(() => titleInput.value?.focus())
})

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

function handleKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    emit('close')
    return
  }
  if (event.key !== 'Tab' || !dialog.value) return
  const focusable = [...dialog.value.querySelectorAll<HTMLElement>(
    'button:not([disabled]), input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [href], [tabindex]:not([tabindex="-1"])',
  )].filter(element => !element.hasAttribute('hidden'))
  if (focusable.length === 0) return
  const first = focusable[0]
  const last = focusable.at(-1)
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last?.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first?.focus()
  }
}

function submit(): void {
  emit('submit', { title: title.value, visibility: visibility.value })
}
</script>

<template>
  <div class="dialog-backdrop" @keydown="handleKeydown">
    <section ref="dialog" class="create-dialog" role="dialog" aria-modal="true" aria-labelledby="create-conversation-title">
      <header>
        <div><p class="eyebrow">New conversation</p><h2 id="create-conversation-title">新建对话</h2></div>
        <button type="button" aria-label="关闭新建对话" @click="emit('close')"><X :size="18" /></button>
      </header>
      <form @submit.prevent="submit">
        <label>
          <span>标题</span>
          <input ref="titleInput" v-model="title" maxlength="200" autocomplete="off" :disabled="pending" placeholder="例如：规划 GitHub Provider 接入" />
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
        <footer>
          <BaseButton variant="secondary" @click="emit('close')">取消</BaseButton>
          <BaseButton type="submit" :loading="props.pending">创建对话</BaseButton>
        </footer>
      </form>
    </section>
  </div>
</template>

<style scoped>
.dialog-backdrop { position: fixed; inset: 0; z-index: var(--cs-z-dialog); display: grid; place-items: center; padding: var(--cs-space-20); background: var(--cs-scrim); backdrop-filter: blur(3px); }
.create-dialog { width: min(520px, 100%); max-height: calc(100dvh - 40px); overflow: auto; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-lg); background: var(--cs-surface); box-shadow: var(--cs-shadow-float); }
.create-dialog > header { display: flex; align-items: flex-start; justify-content: space-between; padding: var(--cs-space-20) var(--cs-space-24) var(--cs-space-16); border-bottom: 1px solid var(--cs-border); }.create-dialog h2 { margin-bottom: 0; font-size: var(--cs-text-lg); }.create-dialog header button { display: grid; width: 30px; height: 30px; place-items: center; border-radius: var(--cs-radius-sm); background: transparent; cursor: pointer; }
.create-dialog form { display: grid; gap: var(--cs-space-20); padding: var(--cs-space-20) var(--cs-space-24) var(--cs-space-24); }.create-dialog form > label > span, .create-dialog legend { display: block; margin-bottom: var(--cs-space-8); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); }.create-dialog input[type='text'], .create-dialog form > label > input { width: 100%; min-height: 40px; padding: 0 var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface); }.create-dialog fieldset { display: grid; gap: var(--cs-space-8); padding: 0; border: 0; }.create-dialog fieldset label { display: grid; grid-template-columns: 16px 18px 1fr; align-items: start; gap: var(--cs-space-8); padding: var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); cursor: pointer; }.create-dialog fieldset label.active { border-color: var(--cs-border-accent-strong); background: var(--cs-surface-accent); }.create-dialog fieldset strong, .create-dialog fieldset small { display: block; }.create-dialog fieldset strong { font-size: var(--cs-text-sm); }.create-dialog fieldset small { margin-top: var(--cs-space-2); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.create-dialog form footer { display: flex; justify-content: flex-end; gap: var(--cs-space-8); }.form-error { margin: calc(var(--cs-space-4) * -1) 0 0; color: var(--cs-danger); font-size: var(--cs-text-sm); }
@media (max-width: 767px) { .dialog-backdrop { align-items: end; padding: 0; }.create-dialog { max-height: calc(100dvh - 12px); padding-bottom: env(safe-area-inset-bottom); border-radius: var(--cs-radius-lg) var(--cs-radius-lg) 0 0; }.create-dialog input[type='text'], .create-dialog form > label > input { font-size: var(--cs-text-md); } }
</style>
