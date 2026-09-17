<script setup lang="ts">
withDefaults(defineProps<{ title?: string; description?: string }>(), { title: '暂无内容', description: '这里还没有可显示的内容。' })
</script>
<template><div class="base-empty"><div class="base-empty__icon" aria-hidden="true">—</div><h3>{{ title }}</h3><p>{{ description }}</p><div v-if="$slots.action" class="base-empty__action"><slot name="action" /></div></div></template>
<style scoped>
.base-empty { display: grid; justify-items: center; gap: var(--cs-space-8); padding: var(--cs-space-48) var(--cs-space-24); color: var(--cs-text-muted); text-align: center; }.base-empty__icon { display: grid; width: var(--cs-space-48); height: var(--cs-space-48); place-items: center; border-radius: 50%; background: var(--cs-surface-subtle); color: var(--cs-text-secondary); font-size: var(--cs-text-lg); }.base-empty h3 { margin: 0; color: var(--cs-text); font-size: var(--cs-text-md); }.base-empty p { max-width: 390px; margin: 0; font-size: var(--cs-text-base); }.base-empty__action { margin-top: var(--cs-space-8); }
</style>
