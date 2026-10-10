<script setup lang="ts">
import { computed } from 'vue'
const props = withDefaults(defineProps<{ displayName: string; size?: 'sm' | 'md' }>(), { size: 'md' })
const initial = computed(() => props.displayName.trim().charAt(0) || '?')
</script>

<template>
  <span class="base-avatar" :class="`base-avatar--${size}`" aria-hidden="true">{{ initial }}</span>
</template>

<style scoped>
/* Visual-first marker (aria-hidden): the accessible name list is provided by the
   surrounding group (e.g. PresenceAvatarGroup), so screen readers hear the names once. */
.base-avatar { display: grid; place-items: center; flex: none; border-radius: 50%; background: var(--cs-brand-600); color: var(--cs-text-on-dark); font-weight: var(--cs-weight-semibold); }
.base-avatar--md { width: 32px; height: 32px; font-size: var(--cs-text-sm); }
.base-avatar--sm { width: 24px; height: 24px; font-size: var(--cs-text-xs); }
</style>
