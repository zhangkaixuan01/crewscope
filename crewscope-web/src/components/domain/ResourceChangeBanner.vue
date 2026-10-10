<script setup lang="ts">
import { RefreshCw } from '@lucide/vue'
import { resourceChangeLabels } from '../../domains/collaboration/labels'

/**
 * Remote-change notice for the open work item (M11-F01, ADR-032 wording): the frame
 * carries coordinates and a version only, so the banner never names an author. It is
 * presentation-only — the parent decides visibility (dirty editor) and owns the
 * controlled refresh; nothing here touches the editor's content.
 */
defineProps<{ visible: boolean }>()
defineEmits<{ refresh: [] }>()
</script>

<template>
  <section v-if="visible" class="change-banner" role="alert">
    <RefreshCw :size="16" aria-hidden="true" />
    <p>{{ resourceChangeLabels.banner }}</p>
    <button type="button" @click="$emit('refresh')">{{ resourceChangeLabels.action }}</button>
  </section>
</template>

<style scoped>
.change-banner { display: flex; align-items: center; gap: var(--cs-space-8); margin: 0 0 var(--cs-space-12); padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-info-soft); color: var(--cs-info); }
.change-banner p { flex: 1; margin: 0; font-size: var(--cs-text-sm); }
.change-banner button { border: 0; padding: 0; background: none; color: var(--cs-info); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); text-decoration: underline; cursor: pointer; }
.change-banner button:focus-visible { outline: none; box-shadow: var(--cs-focus-ring); border-radius: 4px; }
</style>
