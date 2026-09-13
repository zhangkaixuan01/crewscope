<script setup lang="ts">
withDefaults(defineProps<{ caption?: string }>(), { caption: '' })
</script>
<template><div class="base-table-wrap"><table class="base-table"><caption v-if="caption" class="sr-only">{{ caption }}</caption><slot /></table></div></template>
<style scoped>
.base-table-wrap { overflow-x: auto; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); }.base-table { width: 100%; border-collapse: collapse; background: var(--cs-surface); color: var(--cs-text); font-size: var(--cs-text-base); }.base-table :deep(th), .base-table :deep(td) { padding: var(--cs-space-3); border-bottom: 1px solid var(--cs-border); text-align: left; vertical-align: top; }.base-table :deep(th) { color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.base-table :deep(tr:last-child td) { border-bottom: 0; }
</style>
