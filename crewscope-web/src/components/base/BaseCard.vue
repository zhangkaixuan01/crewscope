<script setup lang="ts">
withDefaults(defineProps<{ elevated?: boolean }>(), { elevated: false })
</script>
<template><section class="base-card" :class="{ 'base-card--elevated': elevated }"><slot /></section></template>
<style scoped>
.base-card { padding: var(--cs-space-16); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface); color: var(--cs-text); }.base-card--elevated { box-shadow: var(--cs-shadow-float); }
</style>
