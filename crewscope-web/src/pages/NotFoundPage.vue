<script setup lang="ts">
import { ArrowLeft } from '@lucide/vue'
import { RouterLink } from 'vue-router'
import BaseButton from '../components/base/BaseButton.vue'
import AppShell from '../components/layout/AppShell.vue'
import StatePanel from '../components/feedback/StatePanel.vue'
</script>

<template>
  <AppShell title="页面不存在" eyebrow="导航 · 找不到页面">
    <main class="not-found"><p class="mono">404</p><h1>这个工作范围不存在</h1><span>链接可能已失效，或当前身份没有对应范围。</span><StatePanel state="empty" title="页面不存在" description="请返回个人工作台，或使用搜索找到需要的内容。"><template #action><div class="not-found__actions"><RouterLink v-slot="{ navigate }" custom :to="{ name: 'today' }"><BaseButton @click="navigate"><ArrowLeft :size="15" />返回 Today</BaseButton></RouterLink><RouterLink to="/search"><BaseButton variant="secondary">打开搜索</BaseButton></RouterLink></div></template></StatePanel></main>
  </AppShell>
</template>

<style scoped>
.not-found { display: grid; min-height: 100vh; place-content: center; justify-items: center; padding: var(--cs-space-24); background: radial-gradient(circle at center, var(--cs-surface-accent), var(--cs-canvas) 56%); text-align: center; }.not-found p { margin-bottom: var(--cs-space-8); color: var(--cs-text-brand); font-size: var(--cs-text-base); font-weight: var(--cs-weight-semibold); }.not-found h1 { margin-bottom: var(--cs-space-8); font: var(--cs-text-2xl) var(--cs-font-display); }.not-found span { margin-bottom: var(--cs-space-24); color: var(--cs-text-muted); }
.not-found__actions { display: flex; gap: var(--cs-space-8); }
</style>
