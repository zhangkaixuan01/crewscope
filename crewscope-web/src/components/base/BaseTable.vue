<script setup lang="ts">
import { useId } from 'vue'

withDefaults(defineProps<{ caption?: string }>(), { caption: '' })

// The wrap becomes a keyboard-scrollable region on narrow viewports (overflow-x); a scrollable
// element must itself be focusable (axe scrollable-region-focusable), and a captioned table
// names that region after its caption.
const captionId = useId()
</script>
<template><div class="base-table-wrap" tabindex="0" :role="caption ? 'region' : undefined" :aria-labelledby="caption ? captionId : undefined"><table class="base-table"><caption v-if="caption" :id="captionId" class="sr-only">{{ caption }}</caption><slot /></table></div></template>
<style scoped>
.base-table-wrap { overflow-x: auto; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); }.base-table-wrap:focus-visible { outline: none; box-shadow: var(--cs-focus-ring); }.base-table { width: 100%; border-collapse: collapse; background: var(--cs-surface); color: var(--cs-text); font-size: var(--cs-text-base); }.base-table :deep(th), .base-table :deep(td) { padding: var(--cs-space-12); border-bottom: 1px solid var(--cs-border); text-align: left; vertical-align: top; }.base-table :deep(th) { color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.base-table :deep(tr:last-child td) { border-bottom: 0; }
</style>
