<script setup lang="ts">
import { ChevronDown } from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import type { SemanticTone } from '../base/types'
import type { WorkItemAvailableTransition, WorkItemStatus } from '../../domains/workitem/types'
import BaseMenu from '../base/BaseMenu.vue'
import ActionMenu from './ActionMenu.vue'

/**
 * The status badge as the action entry: the one control a member presses to advance a WorkItem.
 *
 * Opening is an explicit state rather than a CSS `:focus-within`, because a menu that appears on
 * hover cannot be closed with Escape, cannot report whether it is open, and puts the keyboard user
 * on the item they were about to read instead of the one they can execute. The keyboard contract is
 * the menu pattern's——M12b-F03 起由 BaseMenu 全站单源实现：Enter/Space 或方向键打开并聚焦第一个
 * 可执行动作，箭头在菜单内移动，Escape 关闭并把焦点还给徽章，Tab 关闭并继续。
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

const open = ref(false)
const executable = computed(() => props.actions.filter(action => action.enabled))

// 任何关闭（Esc/Tab/外点/选中可逆动作）都算 dismissed——与旧 close() 的语义一致。
watch(open, value => {
  if (!value) emit('dismissed')
})

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
    open.value = false
    return
  }
  awaitingConfirmation.value = true
  emit('select', action)
}

watch(() => props.confirmingTarget, target => {
  if (!awaitingConfirmation.value || target !== null) return
  awaitingConfirmation.value = false
  open.value = false
})
</script>

<template>
  <BaseMenu
    v-model:open="open"
    class="transition-menu"
    placement="bottom-start"
    menu-class="transition-menu__panel"
    :disabled="busy"
    :trigger-class="['status-badge', 'status-badge--interactive', `status-badge--${tone}`]"
  >
    <template #trigger>
      <span v-if="dot" class="status-badge__dot" aria-hidden="true" />
      <slot />
      <ChevronDown v-if="!compact" :size="12" aria-hidden="true" />
    </template>
    <ActionMenu
      :actions="actions"
      :confirming-target="confirmingTarget"
      :busy="busy"
      @select="choose"
    />
    <p v-if="!executable.length" class="transition-menu__empty">当前状态没有可执行的动作。</p>
  </BaseMenu>
</template>

<style scoped>
.transition-menu__empty { margin: 0; padding: var(--cs-space-8); color: var(--cs-text-muted); font-size: var(--cs-text-xs); white-space: nowrap; }
/*
 * The interactive badge keeps the non-interactive badge's box exactly: the popup is positioned
 * against this element, so a padding difference would move every menu in the product.
 */
.transition-menu :deep(.status-badge--interactive) { cursor: pointer; }

/* M12b-F01：悬停反馈补齐——此前这些可点元素对指针移动完全沉默。 */
.transition-menu :deep(.status-badge--interactive:hover:not(:disabled)) { background: var(--cs-surface-accent); }
.transition-menu :deep(.status-badge--interactive:active:not(:disabled)) { transform: scale(.97); }
.transition-menu :deep(.status-badge--interactive) { transition: background-color var(--cs-motion-fast) var(--cs-ease-out), border-color var(--cs-motion-fast) var(--cs-ease-out), transform var(--cs-motion-fast) var(--cs-ease-out); }
/* 动作菜单沿用紧凑圆角与最小宽度（外壳其余观感由 BaseMenu 默认弹层承担）。 */
.transition-menu :deep(.transition-menu__panel) { min-width: 208px; border-radius: var(--cs-radius-sm); }
</style>
