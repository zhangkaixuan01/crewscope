<script setup lang="ts">
import { Lightbulb, ArrowUp, LoaderCircle } from '@lucide/vue'
import { nextTick, ref, useId, watch } from 'vue'

const props = withDefaults(defineProps<{
  modelValue: string
  disabled?: boolean
  sending?: boolean
  submitDisabled?: boolean
  offline?: boolean
  disabledReason?: string | null
  placeholder?: string
  /** 首句建议（如 starter 对话的示例目标）：点击即填入草稿并聚焦，把「试着输入」降到一键。 */
  suggestion?: string | null
}>(), {
  disabled: false,
  sending: false,
  submitDisabled: false,
  offline: false,
  disabledReason: null,
  placeholder: '向 Personal Agent 描述目标或补充上下文…',
  suggestion: null,
})

const fieldId = useId()
const guidanceId = `${fieldId}-guidance`
const countId = `${fieldId}-count`
const textarea = ref<HTMLTextAreaElement | null>(null)
defineExpose({ focus: () => textarea.value?.focus() })

const emit = defineEmits<{
  'update:modelValue': [value: string]
  submit: [content: string]
}>()

function applySuggestion(): void {
  if (!props.suggestion || props.disabled) return
  emit('update:modelValue', props.suggestion)
  void nextTick(() => textarea.value?.focus())
}

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
    <button
      v-if="suggestion && !disabled && !sending && !submitDisabled"
      type="button"
      class="composer-suggestion"
      @click="applySuggestion"
    >
      <Lightbulb :size="14" aria-hidden="true" />试试：{{ suggestion }}
    </button>
    <!-- 卡片式 composer（参考图对齐）：一张圆角卡承担输入区，圆钮内嵌卡片右下——
         accessible name 全走 aria-label，e2e 的「发送」定位与「消息内容」label 不变。 -->
    <div class="composer-card" :class="{ 'composer-card--disabled': disabled }">
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
          rows="2"
          @input="update"
          @keydown.enter="handleEnter"
        />
      </label>
      <div class="composer-bar">
        <button
          type="submit"
          class="composer-send"
          :disabled="disabled || submitDisabled || !modelValue.trim()"
          :data-sending="sending || undefined"
          :aria-label="sending ? '发送中' : '发送'"
          :aria-describedby="guidanceId"
        >
          <LoaderCircle v-if="sending" class="composer-send__spinner" :size="16" aria-hidden="true" />
          <ArrowUp v-else :size="16" aria-hidden="true" />
        </button>
      </div>
    </div>
    <p class="composer-hint">
      <span :id="guidanceId">{{ disabledReason ?? (offline ? '当前离线，可继续编辑草稿' : 'Enter 发送 · Shift + Enter 换行') }}</span>
      <span :id="countId">{{ modelValue.length.toLocaleString('zh-CN') }} / 50,000 字符</span>
    </p>
  </form>
</template>

<style scoped>

.conversation-composer { display: grid; gap: var(--cs-space-8); width: 100%; max-width: 760px; margin: 0 auto; padding: var(--cs-space-12) var(--cs-space-16) var(--cs-space-16); background: var(--cs-surface); }
.composer-suggestion { display: inline-flex; align-items: center; gap: var(--cs-space-4); justify-self: start; min-height: 32px; padding: var(--cs-space-4) var(--cs-space-12); border: 1px dashed var(--cs-border-accent); border-radius: 999px; background: var(--cs-surface-accent); color: var(--cs-text-brand-strong); font-size: var(--cs-text-xs); cursor: pointer; }
.composer-suggestion:hover { border-style: solid; border-color: var(--cs-border-accent-strong); }
.composer-suggestion:focus-visible { outline: 2px solid var(--cs-focus); outline-offset: 2px; }
/* 卡片（dsh InputBar 对齐）：浮起输入面 + 发丝描边 + 双层柔和影；聚焦只换描边色不加环。 */
.composer-card { display: grid; gap: 12px; padding-top: 8px; border: 1px solid var(--cs-border-strong); border-radius: 20px; background: var(--cs-surface-raised); box-shadow: var(--cs-shadow-hairline); transition: border-color var(--cs-motion-fast) var(--cs-ease-out); }
.composer-card:focus-within { border-color: var(--cs-border-accent-strong); }
.composer-card--disabled { opacity: .66; }
.conversation-composer label { display: block; }
.conversation-composer textarea { display: block; width: 100%; min-height: 36px; max-height: 220px; resize: vertical; padding: 4px 8px 0 14px; border: 0; background: transparent; color: var(--cs-text); font: var(--cs-text-base)/24px var(--cs-font-sans); }
.conversation-composer textarea:focus { outline: none; }
.conversation-composer textarea:disabled { cursor: not-allowed; }
.composer-bar { display: flex; align-items: center; justify-content: flex-end; gap: var(--cs-space-8); padding: 2px 8px 6px; }
/* 发送钮（dsh 34px 圆）：空稿保持品牌底但退到 0.4 透明度；发送中不淡化，箭头位换旋转弧。 */
.composer-send { display: grid; width: 34px; height: 34px; place-items: center; padding: 0; border: 0; border-radius: 50%; background: var(--cs-action-primary); color: var(--cs-action-primary-text); cursor: pointer; }
.composer-send:disabled { opacity: .4; cursor: default; }
.composer-send[data-sending] { opacity: 1; cursor: progress; }
.composer-send:not(:disabled):hover { background: var(--cs-action-primary-hover); }
.composer-send:focus-visible { outline: 2px solid var(--cs-focus); outline-offset: 2px; }
.composer-send__spinner { animation: composer-spin 1s linear infinite; }
@keyframes composer-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .composer-send__spinner { animation: none; } }
.composer-hint { display: flex; justify-content: space-between; gap: var(--cs-space-12); margin: 0 var(--cs-space-4); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; clip-path: inset(50%); }
@media (max-width: 767px) {
  .conversation-composer { position: sticky; bottom: 0; z-index: 2; gap: var(--cs-space-8); padding: var(--cs-space-8) var(--cs-space-12) max(var(--cs-space-12), env(safe-area-inset-bottom)); }
  .conversation-composer textarea { min-height: 44px; max-height: 120px; font-size: var(--cs-text-md); resize: none; }
  .composer-hint span:first-child { display: none; }
}
</style>
