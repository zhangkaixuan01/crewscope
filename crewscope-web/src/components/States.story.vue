<script setup lang="ts">
import '../design/tokens.css'
import '../design/base.css'
import StatePanel from './feedback/StatePanel.vue'
</script>

<template>
  <Story title="Foundation/Page states">
    <Variant v-for="state in ['loading', 'empty', 'error', 'forbidden', 'conflict', 'offline', 'reconnecting', 'cancelled'] as const" :key="state" :title="state">
      <div class="state-story"><StatePanel :state="state" /></div>
    </Variant>
    <Variant title="Long content"><div class="state-story"><StatePanel state="empty" title="这是一个用于验证组件在超长标题、窄容器和多语言内容中仍然保持稳定布局的状态标题" description="领域组件必须覆盖长内容，不能依赖固定长度的演示文案。这里同时验证换行、阅读宽度和操作区域不会互相挤压。" /></div></Variant>
  </Story>
</template>

<style scoped>
.state-story { max-width: 680px; min-height: 360px; border: 1px solid var(--cs-border); border-radius: 16px; background: var(--cs-surface); font-family: var(--cs-font-sans); }
</style>
