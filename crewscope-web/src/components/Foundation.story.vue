<script setup lang="ts">
import '../design/tokens.css'
import '../design/base.css'
import BaseButton from './base/BaseButton.vue'
import StatusBadge from './base/StatusBadge.vue'
import ResponsibilityChain from './domain/ResponsibilityChain.vue'
import AgentPresence from './domain/AgentPresence.vue'
import { demoAgent, demoResponsibilities } from '../domains/demo/fixtures'
</script>

<template>
  <Story title="Foundation/Components">
    <Variant title="Buttons and status">
      <div class="story-stack"><div><BaseButton>主要动作</BaseButton><BaseButton variant="secondary">次要动作</BaseButton><BaseButton variant="ghost">弱动作</BaseButton><BaseButton loading>执行中</BaseButton></div><div><StatusBadge tone="success" dot>已完成</StatusBadge><StatusBadge tone="info" dot>进行中</StatusBadge><StatusBadge tone="agent" dot>Agent</StatusBadge><StatusBadge tone="warning">待 Review</StatusBadge><StatusBadge tone="danger">已阻塞</StatusBadge></div></div>
    </Variant>
    <Variant title="Responsibility and agent">
      <div class="story-grid"><section><h3>Responsibility Chain</h3><ResponsibilityChain :members="demoResponsibilities" /></section><section><h3>Agent Presence</h3><AgentPresence :agent="demoAgent" /></section></div>
    </Variant>
  </Story>
</template>

<style scoped>
.story-stack, .story-grid { min-height: 420px; padding: 28px; background: var(--cs-canvas); font-family: var(--cs-font-sans); }.story-stack > div { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 20px; }.story-grid { display: grid; grid-template-columns: 300px 380px; gap: 20px; }.story-grid section { padding: 20px; border: 1px solid var(--cs-border); border-radius: 16px; background: white; }.story-grid h3 { margin-bottom: 20px; }
</style>
