<script setup lang="ts">
import { X } from '@lucide/vue'
import { useToast } from '../../composables/useToast'
const { toasts, dismiss } = useToast()

/** Action buttons are one-shot affordances (notably the 10-second undo window). Remove the toast
 * before invoking user code so a successful action cannot leave a stale clickable invitation. */
async function runAction(id: number, action: () => void | Promise<void>): Promise<void> {
  dismiss(id)
  await action()
}
</script>
<template><div class="toast-host" aria-live="polite" aria-atomic="false"><TransitionGroup name="toast"><article v-for="toast in toasts" :key="toast.id" class="toast" :class="`toast--${toast.tone}`"><span>{{ toast.message }}</span><button v-if="toast.action" type="button" @click="runAction(toast.id, toast.action.onClick)">{{ toast.action.label }}</button><button type="button" aria-label="关闭提示" @click="dismiss(toast.id)"><X :size="15" aria-hidden="true" /></button></article></TransitionGroup></div></template>
<style scoped>
.toast-host { position: fixed; z-index: var(--cs-z-toast); right: var(--cs-space-16); bottom: var(--cs-space-16); display: grid; width: min(380px, calc(100vw - var(--cs-space-40))); gap: var(--cs-space-8); }.toast { display: flex; align-items: center; gap: var(--cs-space-8); padding: var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface-raised); box-shadow: var(--cs-shadow-float); color: var(--cs-text); font-size: var(--cs-text-base); }.toast > span { flex: 1; }.toast button { display: inline-flex; align-items: center; padding: var(--cs-space-4) var(--cs-space-8); border-radius: var(--cs-radius-sm); background: transparent; color: inherit; cursor: pointer; }.toast button:hover { background: var(--cs-surface-subtle); }.toast--success { border-color: var(--cs-success); }.toast--warning { border-color: var(--cs-warning); }.toast--danger { border-color: var(--cs-danger); }.toast-enter-active, .toast-leave-active { transition: opacity var(--cs-motion-base) var(--cs-ease-out), transform var(--cs-motion-base) var(--cs-ease-out); }.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(var(--cs-space-8)); }
</style>
