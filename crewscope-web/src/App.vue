<script setup lang="ts">
import { RouterView, useRoute } from 'vue-router'
import CreationRecoveryPanel from './components/feedback/CreationRecoveryPanel.vue'
import { computed, inject, onBeforeUnmount, watch } from 'vue'
import GlobalErrorBanner from './components/feedback/GlobalErrorBanner.vue'
import ToastHost from './components/feedback/ToastHost.vue'
import ConfirmHost from './components/feedback/ConfirmHost.vue'
import AuthSessionBoundary from './components/auth/AuthSessionBoundary.vue'
import { useAuthStore } from './domains/identity/store'
import { applyDevicePreferences, isDensityPreference, isThemePreference, usePreference } from './app/preference'
import { ACTION_REGISTRY } from './app/actionRegistry'
import CommandPalette from './components/action/CommandPalette.vue'

const authStore = useAuthStore()
const route = useRoute()
const pageIdentity = computed(() => {
  // Identity pages own their anonymous→authenticated transition; keying them on the session identity
  // remounted them mid-transition and the fresh RegisterPage instance's authenticated redirect to
  // /conversation cancelled the original instance's onboarding navigation after a first registration.
  if (route.meta.publicIdentity === true) return 'identity'
  return JSON.stringify([authStore.state.session?.account?.accountId,
    authStore.state.session?.account?.securityVersion, authStore.state.session?.principal])
})
const actionRegistry = inject(ACTION_REGISTRY, null)
const theme = usePreference<'system' | 'light' | 'dark'>('cs.pref.device.theme.v1', 'system', { version: 1, validate: isThemePreference })
const density = usePreference<'comfortable' | 'compact'>('cs.pref.device.density.v1', 'comfortable', { version: 1, validate: isDensityPreference })

function applyPreferences(): void {
  if (typeof document === 'undefined') return
  const root = document.documentElement
  const systemDark = typeof window !== 'undefined' && window.matchMedia?.('(prefers-color-scheme: dark)').matches === true
  applyDevicePreferences(root, theme.value.value, density.value.value, systemDark)
  /*
   * 浏览器地址栏底色跟着画布走，而且是**读出来**的而不是再写一遍。
   * 原先这里硬写两个十六进制值，浅色那个是 #f5faf6 —— 而 --cs-canvas 是 #f3f5f2，
   * 也就是地址栏和它下面的页面本来就不是同一个颜色。两处真值必然漂移，改成读 Token 之后
   * 这件事只有一个来源。
   */
  const canvas = getComputedStyle(root).getPropertyValue('--cs-canvas').trim()
  if (canvas) document.querySelector('meta[name="theme-color"]')?.setAttribute('content', canvas)
}
watch([theme.value, density.value], applyPreferences, { immediate: true })
function onPreferenceChange(event: Event): void {
  const detail = (event as CustomEvent<{ key?: string, value?: unknown }>).detail
  if (detail?.key === 'cs.pref.device.theme.v1' && isThemePreference(detail.value)) theme.value.value = detail.value
  if (detail?.key === 'cs.pref.device.density.v1' && isDensityPreference(detail.value)) density.value.value = detail.value
}
if (typeof window !== 'undefined') {
  window.addEventListener('crewscope:preference-change', onPreferenceChange)
}
if (typeof window !== 'undefined' && window.matchMedia) {
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener?.('change', applyPreferences)
}
onBeforeUnmount(() => window.removeEventListener('crewscope:preference-change', onPreferenceChange))
</script>

<template>
  <AuthSessionBoundary v-if="['idle', 'restoring', 'error'].includes(authStore.state.phase)" />
  <template v-else>
    <GlobalErrorBanner />
    <CreationRecoveryPanel v-if="authStore.state.phase === 'authenticated'" />
    <ToastHost />
    <ConfirmHost />
    <CommandPalette v-if="actionRegistry" />
    <RouterView :key="pageIdentity" />
  </template>
</template>
