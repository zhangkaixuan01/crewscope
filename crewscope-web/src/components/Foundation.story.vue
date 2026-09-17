<script setup lang="ts">
import '../design/tokens.css'
import '../design/base.css'
import BaseButton from './base/BaseButton.vue'
import StatusBadge from './base/StatusBadge.vue'
import BaseBadge from './base/BaseBadge.vue'
import BaseTag from './base/BaseTag.vue'
import BaseInput from './base/BaseInput.vue'
import BaseTextarea from './base/BaseTextarea.vue'
import BaseSelect from './base/BaseSelect.vue'
import BaseCheckbox from './base/BaseCheckbox.vue'
import BaseSwitch from './base/BaseSwitch.vue'
import BaseTabs from './base/BaseTabs.vue'
import BaseSkeleton from './base/BaseSkeleton.vue'
import ResponsibilityChain from './domain/ResponsibilityChain.vue'
import { demoResponsibilities } from '../domains/demo/fixtures'
</script>

<template>
  <Story title="Foundation/Components">
    <Variant title="Buttons and status">
      <div class="story-stack"><div><BaseButton>主要动作</BaseButton><BaseButton variant="secondary">次要动作</BaseButton><BaseButton variant="ghost">弱动作</BaseButton><BaseButton loading>执行中</BaseButton></div><div><StatusBadge tone="success" dot>已完成</StatusBadge><StatusBadge tone="info" dot>进行中</StatusBadge><StatusBadge tone="agent" dot>Agent</StatusBadge><StatusBadge tone="warning">待 Review</StatusBadge><StatusBadge tone="danger">已阻塞</StatusBadge></div></div>
    </Variant>
    <Variant title="Design System v2 controls">
      <div class="story-controls" data-density="comfortable">
        <div class="story-controls__row"><BaseInput placeholder="输入工作项名称" /><BaseSelect :options="[{ label: '选择状态', value: '' }, { label: '待处理', value: 'BACKLOG' }]" /><BaseButton>保存</BaseButton></div>
        <BaseTextarea :rows="3" placeholder="补充说明" />
        <div class="story-controls__row"><BaseCheckbox>订阅动态</BaseCheckbox><BaseSwitch label="实时更新" /><BaseBadge tone="success">健康</BaseBadge><BaseTag removable>crewscope</BaseTag></div>
        <BaseTabs model-value="overview" :tabs="[{ label: '概览', value: 'overview' }, { label: '历史', value: 'history' }]" />
        <BaseSkeleton height="20px" /><BaseSkeleton width="55%" height="14px" />
      </div>
    </Variant>
    <Variant title="Responsibility chain">
      <div class="story-grid"><section><h3>Responsibility Chain</h3><ResponsibilityChain :members="demoResponsibilities" /></section></div>
    </Variant>
  </Story>
</template>

<style scoped>
.story-stack, .story-grid, .story-controls { min-height: 420px; padding: var(--cs-space-32); background: var(--cs-canvas); color: var(--cs-text); font-family: var(--cs-font-sans); }.story-stack > div { display: flex; flex-wrap: wrap; gap: var(--cs-space-12); margin-bottom: var(--cs-space-20); }.story-grid { display: grid; grid-template-columns: 300px 380px; gap: var(--cs-space-20); }.story-grid section { padding: var(--cs-space-20); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-lg); background: var(--cs-surface); }.story-grid h3 { margin-bottom: var(--cs-space-20); }.story-controls { display: grid; max-width: 720px; gap: var(--cs-space-16); }.story-controls__row { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cs-space-12); }.story-controls__row > .base-input, .story-controls__row > .base-select { flex: 1; min-width: 180px; }
</style>
