<script setup lang="ts">
import { onBeforeUnmount, ref, toRef, watch } from 'vue'
import { useFocusTrap } from '../../composables/useFocusTrap'
const props = withDefaults(defineProps<{ open?: boolean; title?: string; closeOnBackdrop?: boolean }>(), { open: false, title: '', closeOnBackdrop: true })
const emit = defineEmits<{ close: [] }>()
const surface = ref<HTMLElement | null>(null)
useFocusTrap(surface, toRef(props, 'open'))
function close(): void { emit('close') }
watch(() => props.open, value => { if (value) document.body.classList.add('cs-dialog-open'); else document.body.classList.remove('cs-dialog-open') })
onBeforeUnmount(() => document.body.classList.remove('cs-dialog-open'))
</script>
<template><Teleport to="body"><Transition name="cs-fade"><div v-if="open" class="base-dialog__backdrop" @mousedown.self="closeOnBackdrop && close()" @keydown.esc="close"><section ref="surface" class="base-dialog" role="dialog" aria-modal="true" tabindex="-1" :aria-label="title || undefined"><header v-if="title || $slots.header"><slot name="header"><h2>{{ title }}</h2></slot><button type="button" aria-label="关闭" @click="close">×</button></header><div class="base-dialog__body"><slot /></div><footer v-if="$slots.footer"><slot name="footer" /></footer></section></div></Transition></Teleport></template>
<style scoped>
.base-dialog__backdrop { position: fixed; z-index: var(--cs-z-dialog); inset: 0; display: grid; place-items: center; padding: var(--cs-space-24); background: var(--cs-scrim); }.base-dialog { width: min(560px, 100%); max-height: min(720px, calc(100vh - var(--cs-space-40))); overflow: auto; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-lg); background: var(--cs-surface); color: var(--cs-text); box-shadow: var(--cs-shadow-float); }.base-dialog header { display: flex; align-items: center; justify-content: space-between; padding: var(--cs-space-16) var(--cs-space-20); border-bottom: 1px solid var(--cs-border); }.base-dialog h2 { margin: 0; font-size: var(--cs-text-md); }.base-dialog header button { width: var(--cs-space-32); height: var(--cs-space-32); border-radius: var(--cs-radius-sm); background: transparent; color: inherit; font-size: var(--cs-text-lg); cursor: pointer; }.base-dialog__body { padding: var(--cs-space-20); }.base-dialog footer { display: flex; justify-content: flex-end; gap: var(--cs-space-8); padding: var(--cs-space-16) var(--cs-space-20); border-top: 1px solid var(--cs-border); }.cs-fade-enter-active, .cs-fade-leave-active { transition: opacity var(--cs-motion-base) var(--cs-ease-out); }.cs-fade-enter-from, .cs-fade-leave-to { opacity: 0; }
</style>
