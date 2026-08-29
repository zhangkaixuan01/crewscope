<script setup lang="ts">
import { Check, Circle, X } from '@lucide/vue'
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  value?: string
  minimum?: number
  maximum?: number
}>(), {
  value: '',
  minimum: 12,
  maximum: 128,
})

// The server policy counts Unicode code points, so surrogate pairs must not consume two characters here.
const characterCount = computed(() => Array.from(props.value).length)
const entered = computed(() => characterCount.value > 0)
const minimumMet = computed(() => characterCount.value >= props.minimum)
const maximumMet = computed(() => characterCount.value <= props.maximum)
const status = computed(() => {
  if (!entered.value) return `密码长度要求为 ${props.minimum} 至 ${props.maximum} 个字符`
  if (!maximumMet.value) return `密码超过 ${props.maximum} 个字符上限`
  if (!minimumMet.value) return `还需要 ${props.minimum - characterCount.value} 个字符`
  return '密码长度符合要求'
})
</script>

<template>
  <div class="auth-password-guidance">
    <p role="status" aria-live="polite" aria-atomic="true">{{ status }}</p>
    <ul aria-label="密码要求">
      <li :class="{ passed: minimumMet }">
        <Check v-if="minimumMet" :size="13" aria-hidden="true" />
        <Circle v-else :size="11" aria-hidden="true" />
        至少 {{ minimum }} 个字符
      </li>
      <li class="neutral"><Check :size="13" aria-hidden="true" />支持完整短语</li>
      <li :class="{ failed: !maximumMet }">
        <X v-if="!maximumMet" :size="13" aria-hidden="true" />
        <Circle v-else :size="11" aria-hidden="true" />
        最多 {{ maximum }} 个字符
      </li>
    </ul>
  </div>
</template>

<style scoped>
.auth-password-guidance { margin-top: 7px; }
.auth-password-guidance > p { margin: 0 0 6px; color: var(--cs-text-muted); font-size: 9px; }
.auth-password-guidance ul {
  display: flex;
  flex-wrap: wrap;
  gap: 7px 14px;
  padding: 0;
  margin: 0;
  color: var(--cs-text-muted);
  font-size: 9px;
  list-style: none;
}
.auth-password-guidance li { display: inline-flex; align-items: center; gap: 4px; }
.auth-password-guidance .passed,
.auth-password-guidance .neutral { color: var(--cs-brand-700); }
.auth-password-guidance .failed { color: var(--cs-danger); }
</style>
