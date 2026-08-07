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
.error-banner { position: fixed; z-index: 50; top: 14px; left: 50%; display: flex; width: min(620px, calc(100vw - 28px)); min-height: 44px; align-items: center; gap: 10px; padding: 10px 12px 10px 14px; transform: translateX(-50%); border: 1px solid #efc7c7; border-radius: var(--cs-radius-md); background: var(--cs-danger-soft); box-shadow: var(--cs-shadow-float); color: #873737; }
.error-banner span { flex: 1; }
.error-banner button { display: grid; width: 30px; height: 30px; place-items: center; border-radius: var(--cs-radius-sm); background: transparent; color: inherit; cursor: pointer; }
.error-banner button:hover { background: rgb(184 76 76 / 10%); }
</style>
