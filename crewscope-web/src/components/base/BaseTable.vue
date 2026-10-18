<script setup lang="ts">
import { useId } from 'vue'

withDefaults(defineProps<{
  caption?: string
  /** Hide the wrap frame and the default cell skin — for tables living inside an already framed panel that owns its own cell styles (M12b-F03). */
  bare?: boolean
  /** Render the caption visibly instead of screen-reader-only; the consumer styles it via `:deep(caption)`. */
  captionVisible?: boolean
}>(), { caption: '', bare: false, captionVisible: false })

// The wrap becomes a keyboard-scrollable region on narrow viewports (overflow-x); a scrollable
// element must itself be focusable (axe scrollable-region-focusable), and a captioned table
// names that region after its caption.
const captionId = useId()
</script>
<template><div class="base-table-wrap" :class="{ 'base-table-wrap--bare': bare }" tabindex="0" :role="caption ? 'region' : undefined" :aria-labelledby="caption ? captionId : undefined"><table class="base-table" :class="{ 'base-table--bare': bare }"><caption v-if="caption" :id="captionId" :class="captionVisible ? 'base-table__caption' : 'sr-only'">{{ caption }}</caption><slot /></table></div></template>
<style scoped>
.base-table-wrap { overflow-x: auto; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); }.base-table-wrap:focus-visible { outline: none; box-shadow: var(--cs-focus-ring); }.base-table-wrap--bare { border: 0; border-radius: 0; }.base-table { width: 100%; border-collapse: collapse; }.base-table:not(.base-table--bare) { background: var(--cs-surface); color: var(--cs-text); font-size: var(--cs-text-base); }.base-table:not(.base-table--bare) :deep(th), .base-table:not(.base-table--bare) :deep(td) { padding: var(--cs-space-12); border-bottom: 1px solid var(--cs-border); text-align: left; vertical-align: top; }.base-table:not(.base-table--bare) :deep(th) { color: var(--cs-text-secondary); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); }.base-table:not(.base-table--bare) :deep(tr:last-child td) { border-bottom: 0; }.base-table__caption { text-align: left; }
</style>
