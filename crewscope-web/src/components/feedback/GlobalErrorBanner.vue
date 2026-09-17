<script setup lang="ts">
import { AlertTriangle, X } from '@lucide/vue'
import { clearGlobalError, globalErrorMessage } from '../../app/errors'
</script>

<template>
  <div v-if="globalErrorMessage" class="error-banner" role="alert">
    <AlertTriangle :size="17" aria-hidden="true" />
    <span>{{ globalErrorMessage }}</span>
    <button type="button" aria-label="关闭错误提示" @click="clearGlobalError">
      <X :size="17" aria-hidden="true" />
    </button>
  </div>
</template>

<style scoped>
.error-banner { position: fixed; z-index: var(--cs-z-banner); top: 14px; left: 50%; display: flex; width: min(620px, calc(100vw - 28px)); min-height: 44px; align-items: center; gap: var(--cs-space-12); padding: var(--cs-space-12) var(--cs-space-12) var(--cs-space-12) var(--cs-space-16); transform: translateX(-50%); border: 1px solid var(--cs-danger-border); border-radius: var(--cs-radius-md); background: var(--cs-danger-soft); box-shadow: var(--cs-shadow-float); color: var(--cs-danger); }
.error-banner span { flex: 1; }
.error-banner button { display: grid; width: 30px; height: 30px; place-items: center; border-radius: var(--cs-radius-sm); background: transparent; color: inherit; cursor: pointer; }
.error-banner button:hover { background: var(--cs-danger-border); }
</style>
