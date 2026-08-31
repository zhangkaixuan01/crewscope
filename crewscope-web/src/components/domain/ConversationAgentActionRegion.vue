<script setup lang="ts">
import { Bot, CircleStop } from '@lucide/vue'
import { computed } from 'vue'
import type { AgentInvocationPhase } from '../../domains/conversation/realtimeStore'
import type { ClarificationRequest } from '../../domains/conversation/types'
import ClarificationCard from './ClarificationCard.vue'

const props = defineProps<{
  phase: AgentInvocationPhase
  statusText: string | null
  invocationId: string | null
  online: boolean
  retryable: boolean
  clarification: ClarificationRequest | null
}>()

const emit = defineEmits<{
  cancel: []
  retry: []
  submitClarification: [answers: Record<string, string>]
}>()

const canCancel = computed(() => Boolean(
  props.invocationId
  && ['connecting', 'running', 'reconnecting', 'cancelling', 'interrupted'].includes(props.phase),
))
</script>

<template>
  <section class="conversation-agent-action-region" aria-label="Agent 当前操作">
    <div
      v-if="statusText"
      class="agent-live-status"
      :class="{ error: phase === 'error', cancelled: phase === 'cancelled' }"
      :role="phase === 'error' ? 'alert' : 'status'"
      :aria-live="phase === 'error' ? 'assertive' : 'polite'"
      aria-atomic="true"
    >
      <span>
        <CircleStop v-if="phase === 'cancelled'" :size="14" aria-hidden="true" />
        <Bot v-else :size="14" aria-hidden="true" />
        {{ statusText }}
      </span>
      <button
        v-if="canCancel"
        type="button"
        :disabled="phase === 'cancelling' || !online"
        @click="emit('cancel')"
      >取消</button>
      <button
        v-else-if="phase === 'error' && retryable"
        type="button"
        :disabled="!online"
        @click="emit('retry')"
      >重新连接</button>
    </div>

    <ClarificationCard
      v-if="phase === 'interrupted' && clarification"
      :request="clarification"
      @submit="emit('submitClarification', $event)"
    />
  </section>
</template>

<style scoped>
.conversation-agent-action-region { display: grid; max-width: 740px; gap: 10px; margin: 14px auto 0; scroll-margin-block: 16px; }
.agent-live-status { display: flex; min-height: 32px; align-items: center; justify-content: space-between; gap: 12px; padding: 7px 10px; border: 1px solid var(--cs-brand-200); border-radius: var(--cs-radius-sm); background: var(--cs-brand-50); color: var(--cs-brand-800); font-size: 9px; }
.agent-live-status > span { display: inline-flex; align-items: center; gap: 6px; }
.agent-live-status button { border: 0; background: transparent; color: var(--cs-brand-800); font-size: 9px; font-weight: 750; cursor: pointer; }
.agent-live-status button:disabled { cursor: wait; opacity: .55; }
.agent-live-status.error { border-color: #ecc7c2; background: #fff6f5; color: var(--cs-danger); }
.agent-live-status.error button { color: var(--cs-danger); }
.agent-live-status.cancelled { border-color: #cbd9cf; background: #f3f7f4; color: var(--cs-text-secondary); }
.conversation-agent-action-region :deep(.clarification-card) { width: 100%; margin-bottom: 0; }
</style>
