<script setup lang="ts">
import { RouterView } from 'vue-router'
import GlobalErrorBanner from './components/feedback/GlobalErrorBanner.vue'
</script>

<template>
  <GlobalErrorBanner />
  <RouterView />
</template>
