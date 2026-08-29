<script setup lang="ts">
import { AlertCircle, Clock3 } from '@lucide/vue'
import { nextTick, onMounted, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  title: string
  messages?: readonly string[]
  tone?: 'error' | 'warning'
  autoFocus?: boolean
  focusKey?: string | number
}>(), {
  messages: () => [],
  tone: 'error',
  autoFocus: true,
  focusKey: 0,
})

const summary = ref<HTMLElement | null>(null)

function focusSummary(): void {
  if (props.autoFocus) void nextTick(() => summary.value?.focus())
}

onMounted(focusSummary)
watch(() => props.focusKey, focusSummary)
</script>

<template>
  <section
    ref="summary"
    class="auth-error-summary"
    :class="`auth-error-summary--${tone}`"
    role="alert"
    tabindex="-1"
  >
    <AlertCircle v-if="tone === 'error'" :size="17" aria-hidden="true" />
    <Clock3 v-else :size="17" aria-hidden="true" />
    <div>
      <strong>{{ title }}</strong>
      <ul v-if="messages.length > 1">
        <li v-for="message in messages" :key="message">{{ message }}</li>
      </ul>
      <p v-else-if="messages[0]">{{ messages[0] }}</p>
      <slot />
    </div>
  </section>
</template>

<style scoped>
.auth-error-summary {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px;
  margin-bottom: 18px;
  border: 1px solid var(--cs-auth-error-border);
  border-radius: 10px;
  background: var(--cs-auth-error-surface);
  color: var(--cs-auth-error-text);
}
.auth-error-summary--warning {
  border-color: #e6c898;
  background: var(--cs-warning-soft);
  color: #85551d;
}
.auth-error-summary div { display: grid; gap: 3px; }
.auth-error-summary strong { font-size: 10px; }
.auth-error-summary p,
.auth-error-summary ul { padding: 0; margin: 0; font-size: 9px; }
.auth-error-summary ul { padding-left: 16px; }
</style>
