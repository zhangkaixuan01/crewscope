<script setup lang="ts">
import { LoaderCircle } from '@lucide/vue'

withDefaults(defineProps<{
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger'
  size?: 'small' | 'medium'
  loading?: boolean
  disabled?: boolean
  type?: 'button' | 'submit' | 'reset'
}>(), {
  variant: 'primary',
  size: 'medium',
  loading: false,
  disabled: false,
  type: 'button',
})
</script>

<template>
  <button
    class="base-button"
    :class="[`base-button--${variant}`, `base-button--${size}`]"
    :type="type"
    :disabled="disabled || loading"
    :aria-busy="loading"
  >
    <LoaderCircle v-if="loading" class="base-button__spinner" :size="15" aria-hidden="true" />
    <slot name="icon" />
    <slot />
  </button>
</template>

<style scoped>
.base-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--cs-space-8);
  border: 1px solid transparent;
  border-radius: var(--cs-radius-sm);
  font-weight: var(--cs-weight-semibold);
  cursor: pointer;
  transition: background-color var(--cs-motion-fast) var(--cs-ease-out), border-color var(--cs-motion-fast) var(--cs-ease-out), color var(--cs-motion-fast) var(--cs-ease-out);
}
.base-button--medium { min-height: var(--cs-density-control-height); padding: 0 var(--cs-space-16); }
.base-button--small { min-height: calc(var(--cs-density-control-height) - var(--cs-space-4)); padding: 0 var(--cs-space-12); font-size: var(--cs-text-xs); }
.base-button--primary { background: var(--cs-action-primary); color: var(--cs-action-primary-text); }
.base-button--primary:hover:not(:disabled) { background: var(--cs-action-primary-hover); }
.base-button--secondary { border-color: var(--cs-border-strong); background: var(--cs-surface); color: var(--cs-text); }
.base-button--secondary:hover:not(:disabled), .base-button--ghost:hover:not(:disabled) { background: var(--cs-surface-accent); }
.base-button--ghost { background: transparent; color: var(--cs-text-secondary); }
.base-button--danger { background: var(--cs-danger); color: var(--cs-text-on-semantic); }
.base-button:disabled { cursor: not-allowed; opacity: .55; }
.base-button__spinner { animation: spin var(--cs-motion-base) linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .base-button__spinner { animation: none; } }
</style>
