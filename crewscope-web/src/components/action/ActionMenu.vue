<script setup lang="ts">
import { ArrowRight } from '@lucide/vue'
import { nextTick, onMounted, useTemplateRef, watch } from 'vue'
import { RouterLink } from 'vue-router'
import type { WorkItemAvailableTransition, WorkItemStatus } from '../../domains/workitem/types'

/**
 * The action list of one WorkItem, rendered as a real menu.
 *
 * The list is whatever the server returned — this component never widens it and never invents a
 * label: a disabled entry keeps the server's own wording, and an action the server did not send
 * simply is not here. Disabled entries use `aria-disabled` rather than `disabled` so the keyboard
 * can still reach them and hear the reason; they stay inert because {@link choose} ignores them,
 * which is the only thing that actually prevents the command.
 *
 * The caller owns opening and closing: this renders the menu body and reports Escape and Tab, so the
 * trigger can restore focus to itself. That split is what makes the same menu usable from a badge, a
 * card and a drawer without three copies of the focus dance.
 */
const props = withDefaults(defineProps<{
  actions: readonly WorkItemAvailableTransition[]
  /** Id of the control that opened this menu; the menu is labelled by it. */
  triggerId: string
  /** The action awaiting a second click, when the caller asked irreversible actions to confirm. */
  confirmingTarget?: WorkItemStatus | null
  /** A command is in flight; the menu stays open but nothing new can be started. */
  busy?: boolean
  /** Take focus on mount, for a menu that has just opened. */
  autofocus?: boolean
}>(), {
  confirmingTarget: null,
  busy: false,
  autofocus: false,
})

const emit = defineEmits<{
  select: [action: WorkItemAvailableTransition]
  close: []
}>()

const menu = useTemplateRef<HTMLElement>('menu')

onMounted(() => {
  if (props.autofocus) void nextTick(() => focusFirst())
})

// A menu that opens under a different item keeps a sane focus target instead of leaving focus on a
// row that is no longer rendered.
watch(() => props.actions, () => {
  if (!props.autofocus) return
  const active = document.activeElement
  if (active instanceof HTMLElement && menu.value?.contains(active)) return
  void nextTick(() => focusFirst())
})

function itemButtons(): HTMLButtonElement[] {
  return menu.value ? [...menu.value.querySelectorAll<HTMLButtonElement>('[role="menuitem"]')] : []
}

function focusFirst(): void {
  itemButtons().find(button => button.getAttribute('aria-disabled') !== 'true')?.focus()
}

function moveFocus(delta: number): void {
  const buttons = itemButtons().filter(button => button.getAttribute('aria-disabled') !== 'true')
  if (!buttons.length) return
  const current = buttons.findIndex(button => button === document.activeElement)
  const next = current === -1
    ? (delta > 0 ? 0 : buttons.length - 1)
    : (current + delta + buttons.length) % buttons.length
  buttons[next]?.focus()
}

function onKeydown(event: KeyboardEvent): void {
  if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
    event.preventDefault()
    if (event.key === 'Home') itemButtons()[0]?.focus()
    else if (event.key === 'End') itemButtons().at(-1)?.focus()
    else moveFocus(event.key === 'ArrowDown' ? 1 : -1)
    return
  }
  // Escape returns the member to the control they opened; Tab closes the menu and lets the browser
  // move on, which is what a menu that is not a dialog should do.
  if (event.key === 'Escape' || event.key === 'Tab') emit('close')
}

function choose(action: WorkItemAvailableTransition): void {
  if (props.busy || !action.enabled) return
  emit('select', action)
}

function reasonId(action: WorkItemAvailableTransition): string {
  return `action-reason-${props.triggerId}-${action.actionId}`
}
</script>

<template>
  <div ref="menu" class="action-menu" role="menu" :aria-labelledby="triggerId" @keydown="onKeydown">
    <div v-for="action in actions" :key="action.actionId" class="action-menu__entry" role="none">
      <button
        type="button"
        role="menuitem"
        class="action-menu__item"
        :class="[`action-menu__item--${action.strength.toLowerCase()}`, { 'action-menu__item--blocked': !action.enabled }]"
        :aria-disabled="action.enabled ? undefined : 'true'"
        :aria-describedby="action.enabled ? undefined : reasonId(action)"
        :disabled="busy"
        @click="choose(action)"
      >
        <span>{{ confirmingTarget === action.targetStatus ? `再次点击确认${action.label}` : action.label }}</span>
        <ArrowRight v-if="action.enabled" :size="13" />
      </button>
      <p v-if="!action.enabled" :id="reasonId(action)" class="action-menu__reason">
        {{ action.reasonMessage ?? '当前不可执行' }}
        <RouterLink v-if="action.remedyRoute" :to="action.remedyRoute">{{ action.remedyLabel ?? '前往处理' }}</RouterLink>
      </p>
    </div>
  </div>
</template>

<style scoped>
.action-menu { display: grid; gap: var(--cs-space-2); min-width: 208px; padding: var(--cs-space-4); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface-raised); box-shadow: var(--cs-shadow-float); }
.action-menu__entry { display: grid; gap: var(--cs-space-2); }
.action-menu__item { display: flex; min-height: var(--cs-density-control-height); align-items: center; justify-content: space-between; gap: var(--cs-space-8); padding: var(--cs-space-8); border-radius: var(--cs-radius-sm); background: transparent; color: var(--cs-text); font-size: var(--cs-text-sm); font-weight: var(--cs-weight-semibold); text-align: left; cursor: pointer; }
.action-menu__item:hover:not(:disabled) { background: var(--cs-surface-subtle); }
.action-menu__item:focus-visible { outline: 2px solid var(--cs-focus); outline-offset: -2px; }
.action-menu__item:disabled { cursor: not-allowed; opacity: .6; }
.action-menu__item--danger { color: var(--cs-danger); }
/* A blocked entry stays legible: its reason is the point of showing it at all. */
.action-menu__item--blocked { color: var(--cs-text-muted); font-weight: var(--cs-weight-regular); cursor: not-allowed; }
.action-menu__reason { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cs-space-4); margin: 0; padding: 0 var(--cs-space-8) var(--cs-space-4); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }
.action-menu__reason a { color: var(--cs-text-brand); font-weight: var(--cs-weight-semibold); }
</style>
