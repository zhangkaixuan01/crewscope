<script setup lang="ts">
import { ChevronDown } from '@lucide/vue'
import { computed, nextTick, ref, useId, useTemplateRef, watch } from 'vue'
import type { SemanticTone } from '../base/types'
import type { WorkItemAvailableTransition, WorkItemStatus } from '../../domains/workitem/types'
import ActionMenu from './ActionMenu.vue'

/**
 * The status badge as the action entry: the one control a member presses to advance a WorkItem.
 *
 * Opening is an explicit state rather than a CSS `:focus-within`, because a menu that appears on
 * hover cannot be closed with Escape, cannot report whether it is open, and puts the keyboard user
 * on the item they were about to read instead of the one they can execute. The keyboard contract is
 * the menu pattern's: Enter or Space opens and focuses the first executable action, arrows move
 * inside, Escape closes and returns focus to this button, Tab closes and moves on.
 *
 * The badge renders the status it was given and nothing else — no action label is derived here, and
 * an action the server did not send cannot be reached from this control.
 */
const props = withDefaults(defineProps<{
  tone?: SemanticTone
  dot?: boolean
  /** Every action the server offered for this WorkItem, disabled entries included. */
  actions: readonly WorkItemAvailableTransition[]
  /** The action awaiting a second click; irreversible actions confirm before they run. */
  confirmingTarget?: WorkItemStatus | null
  busy?: boolean
  /** Hides the chevron where the surrounding layout already implies the control is a menu. */
  compact?: boolean
}>(), {
  tone: 'neutral',
  dot: false,
  confirmingTarget: null,
  busy: false,
  compact: false,
})

const emit = defineEmits<{
  select: [action: WorkItemAvailableTransition]
  /** The member opened and then dismissed the menu without choosing anything. */
  dismissed: []
}>()

const trigger = useTemplateRef<HTMLButtonElement>('trigger')
const open = ref(false)
const triggerId = `status-transition-${useId()}`
const executable = computed(() => props.actions.filter(action => action.enabled))

function toggle(): void {
  if (props.busy) return
  open.value = !open.value
}

/**
 * Closing returns focus to the trigger.
 *
 * A menu that closes while focus is inside it leaves focus on a removed node, which drops the
 * keyboard user back to the top of the document — the failure that makes a popup unusable without a
 * mouse.
 */
function close(restoreFocus = true): void {
  if (!open.value) return
  open.value = false
  emit('dismissed')
  // Restoring focus is skipped when the trigger is gone — executing an action can move the card to
  // another column, and focusing a detached node silently leaves the member at the top of the page.
  if (restoreFocus) void nextTick(() => { if (trigger.value?.isConnected) trigger.value.focus() })
}

/**
 * A reversible action runs and the menu closes; an irreversible one stays open for its second click.
 *
 * The menu cannot close on the first click of an irreversible action, because the label it has to
 * change into — "click again to confirm" — is rendered inside the menu that would have just been
 * removed. It closes when the caller reports the confirmation is over, which is the moment the
 * action actually ran or was abandoned.
 */
const awaitingConfirmation = ref(false)

function choose(action: WorkItemAvailableTransition): void {
  if (action.reversible) {
    emit('select', action)
    close()
    return
  }
  awaitingConfirmation.value = true
  emit('select', action)
}

watch(() => props.confirmingTarget, target => {
  if (!awaitingConfirmation.value || target !== null) return
  awaitingConfirmation.value = false
  close()
})
</script>

<template>
  <span class="transition-menu">
    <button
      ref="trigger"
      :id="triggerId"
      type="button"
      class="status-badge status-badge--interactive"
      :class="`status-badge--${tone}`"
      aria-haspopup="menu"
      :aria-expanded="open"
      :disabled="busy"
      @click="toggle"
    >
      <span v-if="dot" class="status-badge__dot" aria-hidden="true" />
      <slot />
      <ChevronDown v-if="!compact" :size="12" aria-hidden="true" />
    </button>
    <span v-if="open" class="transition-menu__popup">
      <ActionMenu
        :actions="actions"
        :trigger-id="triggerId"
        :confirming-target="confirmingTarget"
        :busy="busy"
        autofocus
        @select="choose"
        @close="close()"
      />
      <p v-if="!executable.length" class="transition-menu__empty">当前状态没有可执行的动作。</p>
    </span>
  </span>
</template>

<style scoped>
.transition-menu { position: relative; display: inline-flex; }
.transition-menu__popup { position: absolute; z-index: var(--cs-z-popover); top: calc(100% + var(--cs-space-4)); left: 0; }
.transition-menu__empty { margin: 0; padding: var(--cs-space-8); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface-raised); box-shadow: var(--cs-shadow-float); color: var(--cs-text-muted); font-size: var(--cs-text-xs); white-space: nowrap; }
/*
 * The interactive badge keeps the non-interactive badge's box exactly: the popup is positioned
 * against this element, so a padding difference would move every menu in the product.
 */
.status-badge--interactive { cursor: pointer; }
</style>
