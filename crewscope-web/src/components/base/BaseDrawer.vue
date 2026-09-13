<script setup lang="ts">
import { ref, toRef } from 'vue'
import { useFocusTrap } from '../../composables/useFocusTrap'
const props = withDefaults(defineProps<{ open?: boolean; title?: string; side?: 'left' | 'right' }>(), { open: false, title: '', side: 'right' })
const emit = defineEmits<{ close: [] }>()
const surface = ref<HTMLElement | null>(null)
useFocusTrap(surface, toRef(props, 'open'))
</script>
<template><Teleport to="body"><Transition name="cs-drawer"><div v-if="open" class="base-drawer__backdrop" @mousedown.self="emit('close')" @keydown.esc="emit('close')"><aside ref="surface" class="base-drawer" :class="`base-drawer--${side}`" role="dialog" aria-modal="true" tabindex="-1" :aria-label="title || undefined"><header><h2>{{ title }}</h2><button type="button" aria-label="关闭" @click="emit('close')">×</button></header><div class="base-drawer__body"><slot /></div><footer v-if="$slots.footer"><slot name="footer" /></footer></aside></div></Transition></Teleport></template>
<style scoped>
.base-drawer__backdrop { position: fixed; z-index: var(--cs-z-drawer); inset: 0; background: rgb(0 0 0 / 42%); }.base-drawer { position: absolute; top: 0; bottom: 0; display: flex; width: min(520px, 100%); flex-direction: column; border-inline: 1px solid var(--cs-border); background: var(--cs-surface); color: var(--cs-text); box-shadow: var(--cs-shadow-float); }.base-drawer--right { right: 0; }.base-drawer--left { left: 0; }.base-drawer header { display: flex; align-items: center; justify-content: space-between; padding: var(--cs-space-4) var(--cs-space-5); border-bottom: 1px solid var(--cs-border); }.base-drawer h2 { margin: 0; font-size: var(--cs-text-lg); }.base-drawer header button { width: var(--cs-space-7); height: var(--cs-space-7); border-radius: var(--cs-radius-sm); background: transparent; color: inherit; font-size: var(--cs-text-xl); cursor: pointer; }.base-drawer__body { flex: 1; overflow: auto; padding: var(--cs-space-5); }.base-drawer footer { padding: var(--cs-space-4) var(--cs-space-5); border-top: 1px solid var(--cs-border); }.cs-drawer-enter-active, .cs-drawer-leave-active { transition: opacity var(--cs-motion-base) var(--cs-ease-out); }.cs-drawer-enter-from, .cs-drawer-leave-to { opacity: 0; }
</style>
