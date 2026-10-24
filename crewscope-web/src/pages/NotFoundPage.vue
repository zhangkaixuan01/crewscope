<script setup lang="ts">
import { ArrowLeft, Search } from '@lucide/vue'
import { RouterLink } from 'vue-router'
import BaseButton from '../components/base/BaseButton.vue'
import AppShell from '../components/layout/AppShell.vue'
</script>

<template>
  <!-- M13b-F10（L13）：唯一 h1 在 context header；页内不再叠 h1/StatePanel，说明与行动收进一块，
    min-height 从 100vh 改为扣除 shell chrome（否则 workspace 内必然多出一屏滚动）。 -->
  <AppShell title="页面不存在" eyebrow="导航 · 找不到页面">
    <!-- M13b-F12（二轮 A05）：AppShell 已有唯一 main——这里再开 main 是重复 landmark；div 承担布局。 -->
    <div class="not-found">
      <p class="mono">404</p>
      <h2>这个工作范围不存在</h2>
      <p class="not-found__note">链接可能已失效，或当前身份没有对应范围。请返回个人工作台，或使用搜索找到需要的内容。</p>
      <div class="not-found__actions">
        <RouterLink v-slot="{ navigate }" custom :to="{ name: 'today' }">
          <BaseButton @click="navigate"><ArrowLeft :size="15" />返回 Today</BaseButton>
        </RouterLink>
        <RouterLink v-slot="{ navigate }" custom to="/search">
          <BaseButton variant="secondary" @click="navigate"><Search :size="15" />打开搜索</BaseButton>
        </RouterLink>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
.not-found {
  display: grid; min-height: calc(100dvh - 140px); place-content: center; justify-items: center;
  padding: var(--cs-space-24); gap: 0;
  background: radial-gradient(circle at center, var(--cs-surface-accent), var(--cs-canvas) 56%); text-align: center;
}
.not-found p { margin-bottom: var(--cs-space-8); color: var(--cs-text-brand); font-size: var(--cs-text-base); font-weight: var(--cs-weight-semibold); }
.not-found h2 { margin-bottom: var(--cs-space-8); font: var(--cs-text-2xl) var(--cs-font-display); }
.not-found__note { max-width: 46ch; margin-bottom: var(--cs-space-24); color: var(--cs-text-muted); }
.not-found__actions { display: flex; flex-wrap: wrap; justify-content: center; gap: var(--cs-space-8); }
</style>
