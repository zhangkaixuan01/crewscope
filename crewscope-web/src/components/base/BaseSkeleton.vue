<script setup lang="ts">
withDefaults(defineProps<{ width?: string; height?: string; rounded?: boolean }>(), { width: '100%', height: '16px', rounded: false })
</script>
<template><span class="base-skeleton" :class="{ 'base-skeleton--rounded': rounded }" :style="{ width, height }" aria-hidden="true" /></template>
<style scoped>
.base-skeleton { display: block; max-width: 100%; border-radius: var(--cs-radius-sm); background: linear-gradient(90deg, var(--cs-surface-subtle), var(--cs-border), var(--cs-surface-subtle)); background-size: 200% 100%; animation: shimmer var(--cs-motion-slow) var(--cs-ease-in-out) infinite; }.base-skeleton--rounded { border-radius: 50%; }@keyframes shimmer { to { background-position: -200% 0; } }@media (prefers-reduced-motion: reduce) { .base-skeleton { animation: none; } }
</style>
