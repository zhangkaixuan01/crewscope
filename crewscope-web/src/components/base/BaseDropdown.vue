<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, useTemplateRef } from 'vue'
export interface BaseDropdownItem { label: string; value: string; disabled?: boolean }
withDefaults(defineProps<{ items?: readonly BaseDropdownItem[] }>(), { items: () => [] })
const emit = defineEmits<{ select: [value: string] }>()
const open = ref(false)
const menu = useTemplateRef<HTMLElement>('menu')

function itemButtons(): HTMLButtonElement[] {
  return [...(menu.value?.querySelectorAll<HTMLButtonElement>('button[role="menuitem"]') ?? [])]
}
function moveFocus(delta: number): void {
  const buttons = itemButtons().filter(button => !button.disabled)
  if (!buttons.length) return
  const current = buttons.findIndex(button => button === document.activeElement)
  const next = current === -1
    ? (delta > 0 ? 0 : buttons.length - 1)
    : (current + delta + buttons.length) % buttons.length
  buttons[next]?.focus()
}
function onMenuKeydown(event: KeyboardEvent): void {
  if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
    event.preventDefault()
    if (event.key === 'Home') itemButtons().find(button => !button.disabled)?.focus()
    else if (event.key === 'End') [...itemButtons()].reverse().find(button => !button.disabled)?.focus()
    else moveFocus(event.key === 'ArrowDown' ? 1 : -1)
    return
  }
  // Escape returns to the trigger; Tab closes and lets the browser move on — a menu is not a
  // dialog, so Tab must never be trapped (same contract as ActionMenu).
  if (event.key === 'Escape') {
    event.preventDefault()
    close()
    menu.value?.parentElement?.querySelector<HTMLButtonElement>('[aria-haspopup="menu"]')?.focus()
  }
  else if (event.key === 'Tab') close()
}
function close(): void { open.value = false }

onMounted(() => document.addEventListener('click', onDocumentClick))
onBeforeUnmount(() => document.removeEventListener('click', onDocumentClick))
function onDocumentClick(event: MouseEvent): void {
  if (open.value && menu.value && !menu.value.parentElement?.contains(event.target as Node)) close()
}
</script>
<template><span class="base-dropdown"><button type="button" aria-haspopup="menu" :aria-expanded="open" @click="open = !open"><slot name="trigger">更多</slot></button><div v-if="open" ref="menu" class="base-dropdown__menu" role="menu" @keydown="onMenuKeydown"><button v-for="item in items" :key="item.value" type="button" role="menuitem" :disabled="item.disabled" @click="emit('select', item.value); open = false">{{ item.label }}</button><slot /></div></span></template>
<style scoped>
.base-dropdown { position: relative; display: inline-flex; }.base-dropdown__menu { position: absolute; z-index: var(--cs-z-popover); top: calc(100% + var(--cs-space-8)); right: 0; display: grid; min-width: 180px; padding: var(--cs-space-4); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface-raised); box-shadow: var(--cs-shadow-float); }.base-dropdown__menu button { padding: var(--cs-space-8) var(--cs-space-12); border-radius: var(--cs-radius-sm); background: transparent; color: var(--cs-text); text-align: left; cursor: pointer; }.base-dropdown__menu button:hover:not(:disabled) { background: var(--cs-surface-subtle); }.base-dropdown__menu button:disabled { cursor: not-allowed; opacity: .5; }
</style>
