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
  gap: 7px;
  border: 1px solid transparent;
  border-radius: var(--cs-radius-sm);
  font-weight: 680;
  cursor: pointer;
  transition: background-color var(--cs-transition-fast), border-color var(--cs-transition-fast), color var(--cs-transition-fast);
}
.base-button--medium { min-height: 38px; padding: 0 14px; }
.base-button--small { min-height: 32px; padding: 0 10px; font-size: 12px; }
.base-button--primary { background: var(--cs-brand-950); color: var(--cs-text-on-dark); }
.base-button--primary:hover:not(:disabled) { background: var(--cs-brand-700); }
.base-button--secondary { border-color: var(--cs-border-strong); background: var(--cs-surface); color: var(--cs-text); }
.base-button--secondary:hover:not(:disabled), .base-button--ghost:hover:not(:disabled) { background: var(--cs-brand-50); }
.base-button--ghost { background: transparent; color: var(--cs-text-secondary); }
.base-button--danger { background: var(--cs-danger); color: white; }
.base-button:disabled { cursor: not-allowed; opacity: .55; }
.base-button__spinner { animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .base-button__spinner { animation: none; } }
</style>
