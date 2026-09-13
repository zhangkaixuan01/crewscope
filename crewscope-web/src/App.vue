<script setup lang="ts">
import { RouterView } from 'vue-router'
import GlobalErrorBanner from './components/feedback/GlobalErrorBanner.vue'
import ToastHost from './components/feedback/ToastHost.vue'
import ConfirmHost from './components/feedback/ConfirmHost.vue'
import AuthSessionBoundary from './components/auth/AuthSessionBoundary.vue'
import { useAuthStore } from './domains/identity/store'

const authStore = useAuthStore()
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
