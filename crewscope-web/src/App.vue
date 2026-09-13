<script setup lang="ts">
import { RouterView } from 'vue-router'
import { watch } from 'vue'
import GlobalErrorBanner from './components/feedback/GlobalErrorBanner.vue'
import ToastHost from './components/feedback/ToastHost.vue'
import ConfirmHost from './components/feedback/ConfirmHost.vue'
import AuthSessionBoundary from './components/auth/AuthSessionBoundary.vue'
import { useAuthStore } from './domains/identity/store'
import { usePreference } from './app/preference'

const authStore = useAuthStore()
const theme = usePreference<'system' | 'light' | 'dark'>('cs.pref.device.theme.v1', 'system', { version: 1 })
const density = usePreference<'comfortable' | 'compact'>('cs.pref.device.density.v1', 'comfortable', { version: 1 })

function applyPreferences(): void {
  if (typeof document === 'undefined') return
  const root = document.documentElement
  const systemDark = typeof window !== 'undefined' && window.matchMedia?.('(prefers-color-scheme: dark)').matches
  const resolvedTheme = theme.value.value === 'system' ? (systemDark ? 'dark' : 'light') : theme.value.value
  root.dataset.theme = resolvedTheme
  root.dataset.density = density.value.value
}
watch([theme.value, density.value], applyPreferences, { immediate: true })
if (typeof window !== 'undefined' && window.matchMedia) {
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener?.('change', applyPreferences)
}
</script>

<template>
  <AuthSessionBoundary v-if="['idle', 'restoring', 'error'].includes(authStore.state.phase)" />
  <template v-else>
    <GlobalErrorBanner />
    <ToastHost />
    <ConfirmHost />
    <RouterView />
  </template>
</template>
