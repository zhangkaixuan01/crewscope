<script setup lang="ts">
withDefaults(defineProps<{ removable?: boolean }>(), { removable: false })
defineEmits<{ remove: [] }>()
</script>
<template><span class="base-tag"><slot /><button v-if="removable" type="button" aria-label="移除" @click="$emit('remove')">×</button></span></template>
<style scoped>
.base-tag { display: inline-flex; align-items: center; gap: var(--cs-space-4); min-height: var(--cs-space-24); padding: 0 var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-pill); background: var(--cs-surface); color: var(--cs-text-secondary); font-size: var(--cs-text-xs); }.base-tag button { padding: 0; background: transparent; color: inherit; cursor: pointer; }
</style>
