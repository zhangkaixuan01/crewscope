<script setup lang="ts">
import { computed, ref } from 'vue'
const props = withDefaults(defineProps<{ text: string; placement?: 'top' | 'bottom' }>(), { placement: 'top' })
const visible = ref(false)
const id = `cs-tooltip-${Math.random().toString(36).slice(2, 9)}`
const describedBy = computed(() => visible.value ? id : undefined)
</script>
<template><span class="base-tooltip" @mouseenter="visible = true" @mouseleave="visible = false" @focusin="visible = true" @focusout="visible = false"><span :aria-describedby="describedBy"><slot /></span><span v-if="visible" :id="id" class="base-tooltip__content" role="tooltip" :class="`base-tooltip__content--${placement}`">{{ props.text }}</span></span></template>
<style scoped>
.base-tooltip { position: relative; display: inline-flex; }.base-tooltip__content { position: absolute; z-index: var(--cs-z-tooltip); width: max-content; max-width: 280px; padding: var(--cs-space-8) var(--cs-space-12); border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-sm); background: var(--cs-surface-raised); color: var(--cs-text); box-shadow: var(--cs-shadow-float); font-size: var(--cs-text-xs); line-height: var(--cs-leading-normal); pointer-events: none; }.base-tooltip__content--top { bottom: calc(100% + var(--cs-space-8)); left: 50%; transform: translateX(-50%); }.base-tooltip__content--bottom { top: calc(100% + var(--cs-space-8)); left: 50%; transform: translateX(-50%); }
</style>
