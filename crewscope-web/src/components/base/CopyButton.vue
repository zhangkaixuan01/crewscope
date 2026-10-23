<script setup lang="ts">
import { Check, Copy } from '@lucide/vue'
import { computed, ref } from 'vue'
import { useClipboard } from '../../composables/useClipboard'

/**
 * M13b-F11（C3）：复制反馈的统一形态——成功有可见变化 + 读屏播报「已复制」；失败显式说
 * 「无法复制，请手动选择」（useClipboard 已带 execCommand 降级，走到失败即降级也救不了：
 * 非安全上下文/权限拒绝）。样式完全由调用点掌控：这里的按钮不自带样式，只统一行为。
 */
const props = withDefaults(defineProps<{
  value: string
  /** Distinguishes sibling buttons' success ticks; defaults to the copied value itself. */
  copyKey?: string
  /** Visible text next to the icon; icon-only buttons still surface the failure note. */
  label?: string
  iconOnly?: boolean
}>(), {
  copyKey: undefined,
  label: '复制',
  iconOnly: false,
})

const clipboard = useClipboard()
const failed = ref(false)
let failureTimer: ReturnType<typeof setTimeout> | null = null
const identity = computed(() => props.copyKey ?? props.value)
const copied = computed(() => clipboard.copied.value === identity.value)

async function copy(): Promise<void> {
  if (failureTimer !== null) { clearTimeout(failureTimer); failureTimer = null }
  failed.value = false
  if (!await clipboard.copy(props.value, identity.value)) {
    failed.value = true
    failureTimer = setTimeout(() => { failed.value = false }, 4000)
  }
}
</script>

<template>
  <button type="button" :aria-label="copied ? '已复制' : label" @click="copy">
    <Check v-if="copied" :size="12" aria-hidden="true" />
    <Copy v-else :size="12" aria-hidden="true" />
    <span v-if="failed" role="alert">无法复制，请手动选择</span>
    <template v-else-if="!iconOnly">{{ copied ? '已复制' : label }}</template>
    <!-- Visible label changes are not announced — the polite status span carries the result
         to screen readers in every form factor, icon-only or not. -->
    <span v-if="copied" class="sr-only" role="status">已复制</span>
  </button>
</template>
