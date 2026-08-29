<script setup lang="ts">
import { RouterView } from 'vue-router'
import GlobalErrorBanner from './components/feedback/GlobalErrorBanner.vue'
import AuthSessionBoundary from './components/auth/AuthSessionBoundary.vue'
import { useAuthStore } from './domains/identity/store'

const authStore = useAuthStore()
</script>

<template>
  <AuthSessionBoundary v-if="['idle', 'restoring', 'error'].includes(authStore.state.phase)" />
  <template v-else>
    <GlobalErrorBanner />
    <RouterView />
  </template>
</template>
