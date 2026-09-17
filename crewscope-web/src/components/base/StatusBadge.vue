<script setup lang="ts">
import type { SemanticTone } from './types'

/**
 * The read-only status chip.
 *
 * It deliberately has no interactive mode. An earlier revision carried one — a badge that opened a
 * menu of `availableActions` on hover — but nothing ever passed it those props, and the menu it
 * opened had no keyboard contract at all (CSS `:focus-within` instead of state, no Escape, no
 * `aria-expanded`). The action entry is `components/action/StatusTransitionMenu.vue`, which renders
 * this badge's visual and owns a menu the keyboard can actually use; keeping a second, half-built
 * entry point here would only let the two drift.
 *
 * The visual definition lives in `design/base.css`, because two components render it.
 */
withDefaults(defineProps<{
  tone?: SemanticTone
  dot?: boolean
}>(), {
  tone: 'neutral',
  dot: false,
})
</script>

<template>
  <span class="status-badge" :class="`status-badge--${tone}`">
    <span v-if="dot" class="status-badge__dot" aria-hidden="true" />
    <slot />
  </span>
</template>
