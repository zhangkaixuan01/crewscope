<script setup lang="ts">
import { AtSign, Paperclip, SendHorizontal } from '@lucide/vue'
import { nextTick, ref, useId, watch } from 'vue'
import BaseButton from '../base/BaseButton.vue'

const props = withDefaults(defineProps<{
  modelValue: string
  disabled?: boolean
  sending?: boolean
  submitDisabled?: boolean
  offline?: boolean
  disabledReason?: string | null
  placeholder?: string
  modelBudget?: number
}>(), {
  disabled: false,
  sending: false,
  submitDisabled: false,
  offline: false,
  disabledReason: null,
  placeholder: '向 Personal Agent 描述目标或补充上下文…',
  modelBudget: 32000,
})

const fieldId = useId()
const guidanceId = `${fieldId}-guidance`
const countId = `${fieldId}-count`
const textarea = ref<HTMLTextAreaElement | null>(null)

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

function insertSlash(): void {
  const content = props.modelValue.trimStart()
  emit('update:modelValue', content.startsWith('/') ? props.modelValue : `/${props.modelValue}`)
  void nextTick(() => { resize(); textarea.value?.focus() })
}

watch(() => props.modelValue, () => void nextTick(resize), { immediate: true })
</script>

<template>
  <form class="conversation-composer" aria-label="发送消息" @submit.prevent="submit">
    <label :for="fieldId">
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
    <footer>
      <div class="composer-tools" aria-label="消息工具">
        <button type="button" class="composer-tool" :disabled="disabled" :aria-describedby="guidanceId" aria-label="添加附件（即将支持）"><Paperclip :size="13" aria-hidden="true" />附件</button>
        <button type="button" class="composer-tool" :disabled="disabled" :aria-describedby="guidanceId" aria-label="插入 Slash 命令" @click="insertSlash"><AtSign :size="13" aria-hidden="true" />Slash</button>
      </div>
      <span :id="guidanceId">{{ disabledReason ?? (offline ? '当前离线，可继续编辑草稿' : 'Enter 发送 · Shift + Enter 换行') }}</span>
      <span :id="countId">{{ modelValue.length.toLocaleString('zh-CN') }} / 50,000 · 预算 {{ modelBudget.toLocaleString('zh-CN') }}</span>
      <BaseButton type="submit" size="small" :disabled="disabled || submitDisabled || !modelValue.trim()" :loading="sending">
        <template #icon><SendHorizontal :size="14" aria-hidden="true" /></template>
        发送
      </BaseButton>
    </footer>
  </form>
</template>

<style scoped>
.conversation-composer { display: grid; gap: 8px; padding: 12px 16px 14px; border-top: 1px solid var(--cs-border); background: var(--cs-surface); }
.conversation-composer label { display: block; }
.conversation-composer textarea { width: 100%; min-height: 74px; max-height: 180px; resize: vertical; padding: 11px 12px; border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-md); background: var(--cs-surface-subtle); color: var(--cs-text); font: 11px/1.55 var(--cs-font-sans); }
.conversation-composer textarea:focus { border-color: var(--cs-brand-400); outline: 3px solid var(--cs-brand-100); }
.conversation-composer textarea:disabled { cursor: not-allowed; opacity: .66; }
.conversation-composer footer { display: grid; grid-template-columns: auto minmax(0, 1fr) auto auto; align-items: center; gap: 10px; }
.composer-tools { display: flex; align-items: center; gap: 6px; }
.composer-tool { display: inline-flex; align-items: center; gap: 4px; min-height: 28px; padding: 0 7px; border: 1px solid var(--cs-border); border-radius: 7px; background: var(--cs-surface-subtle); color: var(--cs-text-secondary); font-size: 9px; cursor: pointer; }
.composer-tool:disabled { cursor: not-allowed; opacity: .52; }
.conversation-composer footer > span { color: var(--cs-text-muted); font-size: 8px; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; clip-path: inset(50%); }
@media (max-width: 767px) {
  .conversation-composer { position: sticky; bottom: 0; z-index: 2; gap: 7px; padding: 9px 10px max(10px, env(safe-area-inset-bottom)); }
  .conversation-composer textarea { min-height: 58px; max-height: 120px; padding: 9px 10px; font-size: 16px; resize: none; }
  .conversation-composer footer { grid-template-columns: minmax(0, 1fr) auto; gap: 8px; }
  .composer-tools { grid-column: 1 / -1; }
  .conversation-composer footer > span:first-child { display: none; }
  .conversation-composer footer > span:nth-child(3) { display: none; }
  .conversation-composer footer > :deep(.base-button) { min-width: 82px; min-height: 42px; }
}
</style>
