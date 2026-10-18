<script setup lang="ts">
import { nextTick, onUnmounted, useId, useTemplateRef, watch } from 'vue'

/**
 * The one dropdown primitive (M12b-F03)：trigger + popup with a single keyboard contract.
 *
 * 行为合同（源自 action/ActionMenu 的成熟范式，全站 6 套手写下拉在此收敛）：
 * - 打开（点击或触发钮方向键）即聚焦第一个可用条目（跳过 `aria-disabled`/`disabled`）；
 * - ArrowUp/ArrowDown 在条目间循环，Home/End 跳首尾；
 * - Escape 关闭并把焦点还给触发钮（preventDefault + stopPropagation，不穿透到背后的层）；
 * - Tab 关闭且不拦截——菜单不是对话框，焦点自然流向下一个控件；
 * - 弹层外 pointerdown 关闭，不动焦点；
 * - 消费者程序化关闭（选完即关）时，若焦点原在弹层内，则还给触发钮——除非此刻有模态打开
 *   （焦点归模态陷阱，M12b-F02 的 MemberActionMenu 竞态在此泛化）。
 *
 * 弹层不 teleport：留在消费者子树内，消费者的 scoped 定制经「自己的根类 + :deep(菜单类)」命中
 * （teleport 会丢父 scope id，见 M12b-F02 记录）；该写法的特异度也高于本组件的默认弹层样式。
 *
 * kind=listbox 用于带搜索的组合框（ModelOptionPicker 一类）：弹层本身不带 role——消费者在弹层内
 * 自行渲染 role="listbox" 的列表容器与 role="option" 条目（输入框不能是 listbox 的子节点）；
 * rovingFromEditable 让方向键/Home/End 即使在输入框聚焦时也驱动列表（默认让这些键回归文本编辑）。
 */
const props = withDefaults(defineProps<{
  /** v-model:open——开合状态归消费者持有，业务时机（选完即关、换行即关）都在消费者手里。 */
  open?: boolean
  kind?: 'menu' | 'listbox'
  placement?: 'bottom-start' | 'bottom-end' | 'top-start' | 'top-end'
  /** 弹层可访问名；不传则 aria-labelledby 指向触发钮。 */
  label?: string
  /** 触发钮 aria-label（触发钮内容由 slot 自述时可省）。 */
  triggerLabel?: string
  disabled?: boolean
  /** 触发钮外观类（消费者 scoped 样式须经自己的根类 + :deep 命中）。 */
  triggerClass?: string | readonly string[]
  /** 弹层外观/几何类；消费者可借此覆盖默认定位（含断点下的 position:fixed）。 */
  menuClass?: string
  /** 打开时的初始焦点：first-item（默认，菜单范式）或 none（组合框自聚焦搜索框）。 */
  initialFocus?: 'first-item' | 'none'
  /** 焦点在弹层内输入框时，方向键/Home/End 仍驱动条目（组合框形态）。 */
  rovingFromEditable?: boolean
}>(), {
  open: false,
  kind: 'menu',
  placement: 'bottom-end',
  label: '',
  triggerLabel: '',
  disabled: false,
  triggerClass: '',
  menuClass: '',
  initialFocus: 'first-item',
  rovingFromEditable: false,
})

const emit = defineEmits<{ 'update:open': [open: boolean] }>()

const trigger = useTemplateRef<HTMLButtonElement>('trigger')
const popup = useTemplateRef<HTMLElement>('popup')
const triggerId = `base-menu-${useId()}`

function itemSelector(): string {
  return props.kind === 'menu' ? '[role^="menuitem"]' : '[role="option"]'
}

function itemButtons(): HTMLButtonElement[] {
  return popup.value
    ? [...popup.value.querySelectorAll<HTMLButtonElement>(itemSelector())]
    : []
}

function focusableItems(): HTMLButtonElement[] {
  // aria-disabled 的条目保持可聚焦性之外的真实禁用语义由消费者决定；键盘跳过它们——
  // 与 ActionMenu 同一合同：被服务端挡住的动作不占据焦点位。
  return itemButtons().filter(button => button.getAttribute('aria-disabled') !== 'true' && !button.disabled)
}

function focusFirst(): void {
  focusableItems()[0]?.focus()
}

function focusLast(): void {
  focusableItems().at(-1)?.focus()
}

function moveFocus(delta: number): void {
  const buttons = focusableItems()
  if (!buttons.length) return
  const current = buttons.findIndex(button => button === document.activeElement)
  const next = current === -1
    ? (delta > 0 ? 0 : buttons.length - 1)
    : (current + delta + buttons.length) % buttons.length
  buttons[next]?.focus()
}

function isEditable(target: EventTarget | null): boolean {
  return target instanceof HTMLElement && (target.isContentEditable || target.tagName === 'INPUT' || target.tagName === 'TEXTAREA')
}

function onPopupKeydown(event: KeyboardEvent): void {
  // Escape 由 document 捕获阶段统一仲裁（见 onDocumentKeydown），这里不重复处理。
  if (event.key === 'Tab') {
    // 菜单不陷阱：从条目上 Tab 关闭并放行默认移焦。弹层内含表单（保存视图命名）时，
    // 在输入框里 Tab 是表单内移动，菜单保持打开。
    if (!isEditable(event.target)) emit('update:open', false)
    return
  }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  // 弹层里有文本输入（保存视图命名、模型搜索）时，这些键先服务文本编辑——组合框形态显式要求接管。
  if (!props.rovingFromEditable && isEditable(event.target)) return
  event.preventDefault()
  if (event.key === 'Home') focusFirst()
  else if (event.key === 'End') focusLast()
  else moveFocus(event.key === 'ArrowDown' ? 1 : -1)
}

/** 触发钮方向键打开时的落焦方向（ArrowDown 首项 / ArrowUp 末项），一次性，进 open watch 消费。 */
let pendingInitialFocus: 'first-item' | 'last-item' | null = null

function onTriggerKeydown(event: KeyboardEvent): void {
  if (props.disabled || props.open) return
  if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return
  event.preventDefault()
  pendingInitialFocus = event.key === 'ArrowDown' ? 'first-item' : 'last-item'
  emit('update:open', true)
}

function toggle(): void {
  if (props.disabled) return
  emit('update:open', !props.open)
}

function closeAndRestore(): void {
  emit('update:open', false)
  trigger.value?.focus()
}

/** 焦点还给触发钮；卡片可能已被服务端动作移走，或模态已打开接管焦点。 */
function restoreTriggerFocus(): void {
  if (document.querySelector('[role="dialog"][aria-modal="true"]')) return
  if (trigger.value?.isConnected) trigger.value.focus()
}

function onDocumentPointerdown(event: PointerEvent): void {
  if (!props.open) return
  const root = popup.value?.parentElement
  if (root?.contains(event.target as Node)) return
  emit('update:open', false)
}

/**
 * Escape 在 document 捕获阶段单点仲裁：菜单开着时焦点可能已在弹层之外（点开后又点了页面），
 * 只挂在弹层上会漏。消费并止传——弹层背后的抽屉/对话框不能跟着关。
 */
function onDocumentKeydown(event: KeyboardEvent): void {
  if (!props.open || event.key !== 'Escape') return
  event.preventDefault()
  event.stopPropagation()
  closeAndRestore()
}

document.addEventListener('pointerdown', onDocumentPointerdown, true)
document.addEventListener('keydown', onDocumentKeydown, true)
onUnmounted(() => {
  document.removeEventListener('pointerdown', onDocumentPointerdown, true)
  document.removeEventListener('keydown', onDocumentKeydown, true)
})

// 打开即聚焦第一个可用条目（菜单范式；组合框传 initialFocus="none" 自行聚焦搜索框）；关闭时若
// 焦点仍在弹层内（选完即关的路径），同步检查（弹层还在 DOM）后异步归还触发钮——此刻可能刚
// 打开模态（生命周期确认、责任交接），焦点归陷阱而不是触发钮。
watch(() => props.open, open => {
  if (open) {
    const target = pendingInitialFocus ?? (props.initialFocus === 'none' ? null : 'first-item')
    pendingInitialFocus = null
    if (target) void nextTick(() => (target === 'first-item' ? focusFirst() : focusLast()))
    return
  }
  if (popup.value?.contains(document.activeElement)) void nextTick(() => restoreTriggerFocus())
}, { flush: 'sync' })
</script>
<template>
  <span class="base-menu" :class="`base-menu--${placement}`">
    <button
      ref="trigger"
      :id="triggerId"
      type="button"
      class="base-menu__trigger"
      :class="typeof triggerClass === 'string' ? triggerClass : triggerClass.join(' ')"
      :aria-label="triggerLabel || undefined"
      :aria-haspopup="kind"
      :aria-expanded="open"
      :disabled="disabled"
      @click="toggle"
      @keydown="onTriggerKeydown"
    ><slot name="trigger" /></button>
    <Transition name="cs-pop">
      <div
        v-if="open"
        ref="popup"
        class="base-menu__popup"
        :class="menuClass"
        :role="kind === 'menu' ? 'menu' : undefined"
        :aria-label="label || undefined"
        :aria-labelledby="kind === 'menu' && !label ? triggerId : undefined"
        @keydown="onPopupKeydown"
      ><slot :trigger-id="triggerId" /></div>
    </Transition>
  </span>
</template>
<style scoped>
.base-menu { position: relative; display: inline-flex; }
.base-menu--bottom-start .base-menu__popup { top: calc(100% + var(--cs-space-4)); left: 0; }
.base-menu--bottom-end .base-menu__popup { top: calc(100% + var(--cs-space-4)); right: 0; }
.base-menu--top-start .base-menu__popup { bottom: calc(100% + var(--cs-space-4)); left: 0; }
.base-menu--top-end .base-menu__popup { bottom: calc(100% + var(--cs-space-4)); right: 0; }
.base-menu__popup { position: absolute; z-index: var(--cs-z-popover); display: grid; min-width: 180px; padding: var(--cs-space-4); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-md); background: var(--cs-surface-raised); box-shadow: var(--cs-shadow-float); }
/* 进出场：motion token 冻结曲线；prefers-reduced-motion 由 base.css 全局归零。 */
.cs-pop-enter-active { transition: opacity var(--cs-motion-fast) var(--cs-ease-out), transform var(--cs-motion-fast) var(--cs-ease-out); }
.cs-pop-leave-active { transition: opacity var(--cs-motion-fast) var(--cs-ease-in), transform var(--cs-motion-fast) var(--cs-ease-in); }
.cs-pop-enter-from, .cs-pop-leave-to { opacity: 0; transform: translateY(-2px); }
</style>
