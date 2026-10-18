<script setup lang="ts">
import BaseButton from '../base/BaseButton.vue'

/**
 * Shared command-receipt frame for the distillation dialogs (M12b-F04): the idempotent-replay
 * banner, the facts list container, the optional follow-up hint and the open/another actions.
 * The fact rows themselves are domain territory — they render through the #facts slot.
 */
defineProps<{
  /** Section aria-label, e.g. 蒸馏回执 (named `label` — an `ariaLabel` prop would collide with the native attribute). */
  label: string
  /** Replay banner copy; shown only when the receipt reports an idempotent replay. */
  replayed: boolean
  replayedNote: string
  /** Primary action label (打开 Skill / 打开条目); rendered only while openAvailable. */
  openLabel: string
  openAvailable: boolean
  /** Follow-up hint line under the facts (knowledge index status guidance); optional. */
  hint?: string
}>()

const emit = defineEmits<{
  open: []
  another: []
}>()
</script>

<template>
  <section class="base-receipt" :aria-label="label">
    <p v-if="replayed" class="base-receipt__replayed" role="status">{{ replayedNote }}</p>
    <dl>
      <slot name="facts" />
    </dl>
    <p v-if="hint" class="base-receipt__hint">{{ hint }}</p>
    <div class="base-receipt__actions">
      <BaseButton v-if="openAvailable" type="button" variant="primary" size="small" @click="emit('open')">{{ openLabel }}</BaseButton>
      <BaseButton type="button" variant="secondary" size="small" @click="emit('another')">再蒸馏一条</BaseButton>
    </div>
  </section>
</template>

<style scoped>
.base-receipt { display: flex; flex-direction: column; gap: var(--cs-space-12); padding: var(--cs-space-12); border: 1px solid var(--cs-border-accent); border-radius: var(--cs-radius-md); background: var(--cs-surface-accent); }
.base-receipt__replayed { margin: 0; color: var(--cs-text); font-size: var(--cs-text-sm); }
.base-receipt dl { display: flex; margin: 0; flex-direction: column; gap: var(--cs-space-8); }
.base-receipt dl div { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: var(--cs-space-8); }
.base-receipt dt { color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.base-receipt dd { margin: 0; font-size: var(--cs-text-sm); overflow-wrap: anywhere; }
.base-receipt :slotted(.base-receipt__mono) { font-family: var(--cs-font-mono, ui-monospace, monospace); font-size: var(--cs-text-xs); }
.base-receipt__hint { margin: 0; color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.base-receipt__actions { display: flex; flex-wrap: wrap; gap: var(--cs-space-8); }
</style>
