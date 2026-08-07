<script setup lang="ts">
import { ArrowLeft } from '@lucide/vue'
import { RouterLink } from 'vue-router'
import BaseButton from '../components/base/BaseButton.vue'
</script>

<template>
  <main class="not-found"><p class="mono">404</p><h1>这个工作范围不存在</h1><span>链接可能已失效，或当前身份没有对应范围。</span><RouterLink v-slot="{ navigate }" custom :to="{ name: 'conversation' }"><BaseButton @click="navigate"><ArrowLeft :size="15" />返回对话</BaseButton></RouterLink></main>
</template>

<style scoped>
.not-found { display: grid; min-height: 100vh; place-content: center; justify-items: center; padding: 24px; background: radial-gradient(circle at center, var(--cs-brand-50), var(--cs-canvas) 56%); text-align: center; }.not-found p { margin-bottom: 8px; color: var(--cs-brand-600); font-size: 12px; font-weight: 750; }.not-found h1 { margin-bottom: 8px; font: 28px var(--cs-font-display); }.not-found span { margin-bottom: 22px; color: var(--cs-text-muted); }
</style>
