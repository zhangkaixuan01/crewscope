<script setup lang="ts">
import { Eye, PencilLine, SendHorizontal } from '@lucide/vue'
import { nextTick, ref, useId, watch } from 'vue'
import BaseButton from '../base/BaseButton.vue'
import SafeMarkdown from './SafeMarkdown.vue'

const props = withDefaults(defineProps<{
  modelValue: string
  disabled?: boolean
  sending?: boolean
  submitDisabled?: boolean
  offline?: boolean
  disabledReason?: string | null
  placeholder?: string
}>(), {
  disabled: false,
  sending: false,
  submitDisabled: false,
  offline: false,
  disabledReason: null,
  placeholder: '向 Personal Agent 描述目标或补充上下文…',
})

const fieldId = useId()
const guidanceId = `${fieldId}-guidance`
const countId = `${fieldId}-count`
const textarea = ref<HTMLTextAreaElement | null>(null)
// R40: a lightweight rendered preview of the draft — not a WYSIWYG editor. The toggle is a view
// state only; it never alters the draft and always re-enters editing without losing text.
const previewing = ref(false)
function togglePreview(): void {
  previewing.value = !previewing.value
}
watch(() => props.modelValue, () => { if (previewing.value) void nextTick(resize) }, { immediate: true })
defineExpose({ focus: () => textarea.value?.focus() })

const emit = defineEmits<{
  'update:modelValue': [value: string]
  submit: [content: string]
}>()

function update(event: Event): void {
  emit('update:modelValue', (event.target as HTMLTextAreaElement).value)
  resize()
}

function submit(): void {
  const content = props.modelValue.trim()
  if (!props.disabled && !props.submitDisabled && !props.sending && content) emit('submit', content)
}

function handleEnter(event: KeyboardEvent): void {
  if (event.shiftKey || event.isComposing) return
  event.preventDefault()
  submit()
}

function resize(): void {
  const element = textarea.value
  if (!element) return
  element.style.height = 'auto'
  element.style.height = `${Math.min(element.scrollHeight, 220)}px`
}

watch(() => props.modelValue, () => void nextTick(resize), { immediate: true })
</script>

<template>
  <form class="conversation-composer" aria-label="发送消息" @submit.prevent="submit">
    <label v-if="!previewing" :for="fieldId">
      <span class="sr-only">消息内容</span>
      <textarea
        ref="textarea"
        :id="fieldId"
        :value="modelValue"
        :disabled="disabled"
        :aria-describedby="`${guidanceId} ${countId}`"
        :placeholder="placeholder"
        maxlength="50000"
        rows="3"
        @input="update"
        @keydown.enter="handleEnter"
      />
    </label>
    <!-- R40: same SafeMarkdown pipeline as delivered messages, so what members preview is what recipients read. -->
    <div v-else class="composer-preview safe-markdown-host" :aria-label="`${placeholder} 预览`">
      <SafeMarkdown v-if="modelValue.trim()" :content="modelValue" />
      <p v-else class="composer-preview__empty">暂无内容，回到编辑继续输入。</p>
    </div>
    <footer>
      <span :id="guidanceId">{{ disabledReason ?? (offline ? '当前离线，可继续编辑草稿' : 'Enter 发送 · Shift + Enter 换行') }}</span>
      <span :id="countId">{{ modelValue.length.toLocaleString('zh-CN') }} / 50,000 字符</span>
      <button type="button" class="composer-preview-toggle" :aria-pressed="previewing" @click="togglePreview">
        <component :is="previewing ? PencilLine : Eye" :size="14" aria-hidden="true" />{{ previewing ? '编辑' : '预览' }}
      </button>
      <BaseButton type="submit" size="small" :disabled="disabled || submitDisabled || !modelValue.trim()" :loading="sending" :aria-describedby="guidanceId">
        <template #icon><SendHorizontal :size="14" aria-hidden="true" /></template>
        发送
      </BaseButton>
    </footer>
  </form>
</template>

<style scoped>
.conversation-composer { display: grid; gap: var(--cs-space-8); padding: var(--cs-space-12) var(--cs-space-16) var(--cs-space-16); border-top: 1px solid var(--cs-border); background: var(--cs-surface); }
.conversation-composer label { display: block; }
.conversation-composer textarea { width: 100%; min-height: 74px; max-height: 180px; resize: vertical; padding: var(--cs-space-12) var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-md); background: var(--cs-surface-subtle); color: var(--cs-text); font: var(--cs-text-base)/var(--cs-leading-normal) var(--cs-font-sans); }
.conversation-composer textarea:focus { border-color: var(--cs-border-accent-strong); outline: 3px solid var(--cs-ring-brand); }
.conversation-composer textarea:disabled { cursor: not-allowed; opacity: .66; }
.conversation-composer footer { display: grid; grid-template-columns: minmax(0, 1fr) auto auto auto; align-items: center; gap: var(--cs-space-12); }
.composer-preview-toggle { display: inline-flex; align-items: center; gap: var(--cs-space-4); min-height: 44px; padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); cursor: pointer; }
.composer-preview-toggle:hover, .composer-preview-toggle:focus-visible { border-color: var(--cs-border-accent); color: var(--cs-text-brand); }
.composer-preview-toggle[aria-pressed="true"] { border-color: var(--cs-border-accent-strong); background: var(--cs-surface-accent); color: var(--cs-text-brand); }
.composer-preview { min-height: 74px; max-height: 220px; overflow-y: auto; padding: var(--cs-space-12); border: 1px dashed var(--cs-border-strong); border-radius: var(--cs-radius-md); background: var(--cs-surface-subtle); color: var(--cs-text-secondary); font-size: var(--cs-text-sm); }
.composer-preview__empty { margin: 0; color: var(--cs-text-muted); }
.conversation-composer footer > span { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; clip-path: inset(50%); }
@media (max-width: 767px) {
  .conversation-composer { position: sticky; bottom: 0; z-index: 2; gap: var(--cs-space-8); padding: var(--cs-space-8) var(--cs-space-12) max(var(--cs-space-12), env(safe-area-inset-bottom)); }
  .conversation-composer textarea { min-height: 58px; max-height: 120px; padding: var(--cs-space-8) var(--cs-space-12); font-size: var(--cs-text-md); resize: none; }
  .conversation-composer footer { grid-template-columns: minmax(0, 1fr) auto; gap: var(--cs-space-8); }
  .conversation-composer footer > span:first-child { display: none; }
  .conversation-composer footer > span:nth-child(2) { display: none; }
  .conversation-composer footer > :deep(.base-button) { min-width: 82px; min-height: 42px; }
}
</style>
