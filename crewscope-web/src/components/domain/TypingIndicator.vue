<script setup lang="ts">
import { computed } from 'vue'
import { presenceLabels, typingIndicatorLabel } from '../../domains/collaboration/labels'
import type { TypingEntry } from '../../domains/collaboration/store'

/**
 * "X is typing" for the conversation composer and the work-item comment box
 * (M11-F01). The list clears itself client-side after the welcome typing window —
 * aria-live="polite" announces entries as they appear; the pulsing dots ride the
 * motion tokens so Reduced Motion collapses them to a static mark.
 */
const props = withDefaults(defineProps<{ typers: readonly TypingEntry[]; degraded?: boolean }>(), { degraded: false })

const label = computed(() => typingIndicatorLabel(props.typers.map(entry => entry.displayName)))
</script>

<template>
  <p v-if="degraded" class="typing-indicator typing-indicator--degraded">{{ presenceLabels.unavailable }}</p>
  <p v-else-if="label" class="typing-indicator" aria-live="polite">
    <span class="typing-indicator__dots" aria-hidden="true"><i></i><i></i><i></i></span>{{ label }}
  </p>
</template>

<style scoped>
.typing-indicator { display: inline-flex; align-items: center; gap: var(--cs-space-8); margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.typing-indicator__dots { display: inline-flex; gap: 3px; }
.typing-indicator__dots i { width: 5px; height: 5px; border-radius: 50%; background: currentColor; animation: typing-pulse var(--cs-motion-slow) var(--cs-ease-in-out) infinite alternate; }
.typing-indicator__dots i:nth-child(2) { animation-delay: 120ms; }
.typing-indicator__dots i:nth-child(3) { animation-delay: 240ms; }
@keyframes typing-pulse { from { opacity: .35; } to { opacity: 1; } }
.typing-indicator--degraded { margin: 0; }
</style>
