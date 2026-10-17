<script setup lang="ts">
import { ref, toRef } from 'vue'
import { useFocusTrap } from '../../composables/useFocusTrap'
const props = withDefaults(defineProps<{
  open?: boolean
  title?: string
  /** 小字眉题（如「Team skill」），渲染在标题上方。 */
  eyebrow?: string
  /** 标题下方的说明文字。 */
  subtitle?: string
  /**
   * 对话框宽度，直接作为 surface 的 width 值（务必用 min(Xpx, 100%) 形态保持窄屏自适应）。
   * 宽度是每个对话框的布局参数而非设计步进，因此不走 token（对照 24 份手写外壳 420–780px 共 15 档）。
   */
  width?: string
  /** 警告型确认框用 alertdialog；isTopmostModal 的层叠查询两者都认。 */
  role?: 'dialog' | 'alertdialog'
  /** 顶部锚定形态（命令面板式 spotlight）；默认居中。与 width 同属调用方的布局参数。 */
  anchor?: 'center' | 'top'
  /**
   * body 内边距，直接作为 CSS padding 值。命令面板这类内容区自带通栏分隔设计的面板传 0；
   * teleport 根组件不继承父 scope id，调用方的 :deep 覆盖到不了这里（BaseDrawer 同款先例）。
   */
  padding?: string
  /** aria-describedby 的目标 id，指向 body 内的影响说明。 */
  describedBy?: string
  closeOnBackdrop?: boolean
  /** 提交进行中：关闭钮禁用，Esc 与 backdrop 不再发 close（脏表单守卫仍在 @close 一侧）。 */
  closeDisabled?: boolean
  closeLabel?: string
}>(), { open: false, title: '', eyebrow: '', subtitle: '', width: 'min(560px, 100%)', role: 'dialog', anchor: 'center', padding: 'var(--cs-space-20)', describedBy: '', closeOnBackdrop: true, closeDisabled: false, closeLabel: '关闭' })
const emit = defineEmits<{ close: [] }>()
const surface = ref<HTMLElement | null>(null)
function close(): void { if (!props.closeDisabled) emit('close') }
// Esc 的层叠守卫（isTopmostModal）在 composable 内；滚动锁同样随 open 生命周期由 trap 持有。
useFocusTrap(surface, toRef(props, 'open'), close)
</script>
<template><Teleport to="body"><Transition name="cs-fade"><div v-if="open" class="base-dialog__backdrop" :class="{ 'base-dialog__backdrop--top': anchor === 'top' }" @mousedown.self="closeOnBackdrop && close()"><section ref="surface" class="base-dialog" :role="role" aria-modal="true" tabindex="-1" :aria-label="title || undefined" :aria-describedby="describedBy || undefined" :style="{ width }"><!-- 标题条用 div 而非 header：teleport 到 body 后，role="dialog" 的 section 不算 sectioning content，裸 <header> 会成为第二个 banner landmark（axe landmark-no-duplicate-banner）。标题语义由 surface 的 aria-label 承担。 --><div v-if="title || eyebrow || subtitle || $slots.header || $slots.icon" class="base-dialog__header"><slot name="header"><span v-if="$slots.icon" class="base-dialog__icon"><slot name="icon" /></span><div><p v-if="eyebrow" class="base-dialog__eyebrow">{{ eyebrow }}</p><h2 v-if="title">{{ title }}</h2><span v-if="subtitle" class="base-dialog__subtitle">{{ subtitle }}</span></div></slot><button type="button" :aria-label="closeLabel" :disabled="closeDisabled" @click="close">×</button></div><div class="base-dialog__body" :style="{ padding }"><slot /></div><!-- 按钮条同理不用 footer：landmark 重复规则按全文档评估，teleport 出来的 footer 会与页面页脚构成重复 contentinfo。 --><div v-if="$slots.footer" class="base-dialog__footer"><slot name="footer" /></div></section></div></Transition></Teleport></template>
<style scoped>
.base-dialog__backdrop { position: fixed; z-index: var(--cs-z-dialog); inset: 0; display: grid; place-items: center; padding: var(--cs-space-20); background: var(--cs-scrim); backdrop-filter: blur(3px); }.base-dialog__backdrop--top { place-items: start center; padding: 12vh var(--cs-space-20) var(--cs-space-24); }.base-dialog { max-height: min(720px, calc(100dvh - var(--cs-space-40))); overflow: auto; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-lg); background: var(--cs-surface); color: var(--cs-text); box-shadow: var(--cs-shadow-float); }.base-dialog__header { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-12); padding: var(--cs-space-16) var(--cs-space-20); border-bottom: 1px solid var(--cs-border); }.base-dialog h2 { margin: 0; font-size: var(--cs-text-lg); }.base-dialog__eyebrow { margin: 0 0 var(--cs-space-2); color: var(--cs-text-brand); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); letter-spacing: .08em; text-transform: uppercase; }.base-dialog__subtitle { display: block; margin-top: var(--cs-space-2); color: var(--cs-text-muted); font-size: var(--cs-text-xs); }.base-dialog__icon { display: grid; width: 42px; height: 42px; flex: 0 0 auto; place-items: center; border-radius: 12px; background: var(--cs-surface-accent-strong); color: var(--cs-text-brand); }.base-dialog__header button { width: var(--cs-space-32); height: var(--cs-space-32); flex: 0 0 auto; border-radius: var(--cs-radius-sm); background: transparent; color: inherit; font-size: var(--cs-text-lg); cursor: pointer; }.base-dialog__footer { display: flex; justify-content: flex-end; gap: var(--cs-space-8); padding: var(--cs-space-16) var(--cs-space-20); border-top: 1px solid var(--cs-border); }.cs-fade-enter-active { transition: opacity var(--cs-motion-base) var(--cs-ease-out); }.cs-fade-leave-active { transition: opacity var(--cs-motion-base) var(--cs-ease-in); }.cs-fade-enter-from, .cs-fade-leave-to { opacity: 0; }
/* 面板本体从 .96 起身——背板只做淡入，浮层要有「落下来」的重量感而不是整块透明度平移。 */
.cs-fade-enter-active .base-dialog { transition: transform var(--cs-motion-base) var(--cs-ease-out); }.cs-fade-leave-active .base-dialog { transition: transform var(--cs-motion-base) var(--cs-ease-in); }.cs-fade-enter-from .base-dialog, .cs-fade-leave-to .base-dialog { transform: scale(.96) translateY(10px); }
/* 窄屏落底成 sheet：width prop 的 min() 已自适应满宽，这里只改停靠与圆角。 */
@media (max-width: 767px) { .base-dialog__backdrop { align-items: end; padding: 0; } .base-dialog__backdrop--top { align-items: start; padding: var(--cs-space-12) 0 0; } .base-dialog { max-height: calc(100dvh - var(--cs-space-12)); border-radius: var(--cs-radius-lg) var(--cs-radius-lg) 0 0; } }

/* M12b-F01：悬停反馈补齐——此前这些可点元素对指针移动完全沉默。 */
.base-dialog__header button:hover:not(:disabled) { background: var(--cs-surface-accent); }
.base-dialog__header button:active:not(:disabled) { transform: scale(.97); }
.base-dialog__header button { transition: background-color var(--cs-motion-fast) var(--cs-ease-out), border-color var(--cs-motion-fast) var(--cs-ease-out), transform var(--cs-motion-fast) var(--cs-ease-out); }
</style>
