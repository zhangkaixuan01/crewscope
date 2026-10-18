<script setup lang="ts">
import { ArrowRight } from '@lucide/vue'
import { useId } from 'vue'
import { RouterLink } from 'vue-router'
import type { WorkItemAvailableTransition, WorkItemStatus } from '../../domains/workitem/types'

/**
 * The action list of one WorkItem, rendered as menu entries.
 *
 * The list is whatever the server returned — this component never widens it and never invents a
 * label: a disabled entry keeps the server's own wording, and an action the server did not send
 * simply is not here. Disabled entries use `aria-disabled` rather than `disabled` so the keyboard
 * can still reach them and hear the reason; they stay inert because {@link choose} ignores them,
 * which is the only thing that actually prevents the command.
 *
 * Opening, closing, arrows/Home/End/Escape/Tab and focus return live in the surrounding
 * BaseMenu（M12b-F03 起全站同一份键盘合同）; this body only renders and guards the entries.
 */
const props = withDefaults(defineProps<{
  actions: readonly WorkItemAvailableTransition[]
  /** The action awaiting a second click, when the caller asked irreversible actions to confirm. */
  confirmingTarget?: WorkItemStatus | null
  /** A command is in flight; the menu stays open but nothing new can be started. */
  busy?: boolean
}>(), {
  confirmingTarget: null,
  busy: false,
})

const emit = defineEmits<{
  select: [action: WorkItemAvailableTransition]
}>()

function choose(action: WorkItemAvailableTransition): void {
  if (props.busy || !action.enabled) return
  emit('select', action)
}

// 同页会有多张卡各挂一个菜单：reason 的 id 必须按实例区分，aria-describedby 才指向自己的说明。
const menuUid = useId()

function reasonId(action: WorkItemAvailableTransition): string {
  return `action-reason-${menuUid}-${action.actionId}`
}
</script>

<template>
  <div class="action-menu">
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
.action-menu { display: grid; gap: var(--cs-space-2); }
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
