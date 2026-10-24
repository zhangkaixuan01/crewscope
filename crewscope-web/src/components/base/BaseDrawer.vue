<script setup lang="ts">
import { ref, toRef } from 'vue'
import { useFocusTrap } from '../../composables/useFocusTrap'
const props = withDefaults(defineProps<{
  open?: boolean
  title?: string
  /** 小字眉题，渲染在标题上方（如「Unified workspace」）。 */
  eyebrow?: string
  side?: 'left' | 'right'
  /**
   * 抽屉宽度，直接作为 surface 的 width 值（用 min(Xpx, 92vw…) 形态保持窄屏自适应）。
   * 宽 1040px 的工作区抽屉与 520px 的表单抽屉是两种布局，不是同一档位的两档。
   */
  width?: string
  /**
   * body 内边距，直接作为 CSS padding 值。与 width 同属「调用方的布局参数」：
   * 高密度工作区（12px 网格的粘性导航、容器查询）与默认表单密度（20px）不是同一档位，
   * 而 teleport 根组件不继承父 scope id，调用方的 :deep 覆盖到不了这里。
   */
  padding?: string
  closeLabel?: string
}>(), { open: false, title: '', eyebrow: '', side: 'right', width: 'min(520px, 100%)', padding: 'var(--cs-space-20)', closeLabel: '关闭' })
const emit = defineEmits<{ close: [] }>()
const surface = ref<HTMLElement | null>(null)
// Esc 的层叠守卫（isTopmostModal）与滚动锁都在 composable 内。
useFocusTrap(surface, toRef(props, 'open'), () => emit('close'))
</script>
<template><Teleport to="body"><Transition name="cs-drawer"><div v-if="open" class="base-drawer__backdrop" @mousedown.self="emit('close')"><aside ref="surface" class="base-drawer" :class="`base-drawer--${side}`" role="dialog" aria-modal="true" tabindex="-1" :aria-label="title || undefined" :style="{ width }"><!-- 标题条与按钮条都不用 header/footer：teleport 到 body 后裸 landmark 元素会与页面的 banner/contentinfo 构成重复（axe landmark-no-duplicate-*，规则按全文档评估）。标题语义由 aside 的 aria-label 承担。 --><div class="base-drawer__header"><slot name="header"><div><p v-if="eyebrow" class="base-drawer__eyebrow">{{ eyebrow }}</p><h2 v-if="title">{{ title }}</h2></div></slot><button type="button" :aria-label="closeLabel" @click="emit('close')">×</button></div><div class="base-drawer__body" :style="{ padding }"><slot /></div><div v-if="$slots.footer" class="base-drawer__footer"><slot name="footer" /></div></aside></div></Transition></Teleport></template>
<style scoped>
.base-drawer__backdrop { position: fixed; z-index: var(--cs-z-drawer); inset: 0; background: var(--cs-scrim); backdrop-filter: blur(2px); }.base-drawer { position: absolute; top: 0; bottom: 0; display: flex; width: min(520px, 100%); flex-direction: column; border-inline: 1px solid var(--cs-border); background: var(--cs-surface); color: var(--cs-text); box-shadow: var(--cs-shadow-float); }.base-drawer--right { right: 0; }.base-drawer--left { left: 0; }.base-drawer__header { display: flex; align-items: center; justify-content: space-between; gap: var(--cs-space-12); padding: var(--cs-space-16) var(--cs-space-20); border-bottom: 1px solid var(--cs-border); }.base-drawer h2 { margin: 0; font-size: var(--cs-text-lg); }.base-drawer__eyebrow { margin: 0 0 var(--cs-space-2); color: var(--cs-text-muted); font-size: var(--cs-text-xs); font-weight: var(--cs-weight-semibold); letter-spacing: .08em; text-transform: uppercase; }.base-drawer__header button { width: var(--cs-space-32); height: var(--cs-space-32); flex: 0 0 auto; border-radius: var(--cs-radius-sm); background: transparent; color: inherit; font-size: var(--cs-text-lg); cursor: pointer; }.base-drawer__body { flex: 1; min-height: 0; overflow: auto; }.base-drawer__footer { padding: var(--cs-space-16) var(--cs-space-20); padding-bottom: calc(var(--cs-space-16) + env(safe-area-inset-bottom)); border-top: 1px solid var(--cs-border); }/* M13b-F12（二轮 A25）：软键盘避让最小实现——移动端 backdrop 用 dvh 收缩高度（键盘弹起时
   visualViewport 缩，抽屉随之变矮，footer 输入不被键盘盖住）；桌面 inset:0 不变。 */
@media (max-width: 767px) { .base-drawer__backdrop { bottom: auto; height: 100dvh; } }
.cs-drawer-enter-active { transition: opacity var(--cs-motion-base) var(--cs-ease-out); }.cs-drawer-leave-active { transition: opacity var(--cs-motion-base) var(--cs-ease-in); }.cs-drawer-enter-from, .cs-drawer-leave-to { opacity: 0; }
/* 抽屉按停靠侧滑入/滑出——方向是抽屉和模态的真实区别，与 --cs-shadow-drawer 的定向投影同一决定。 */
.cs-drawer-enter-active .base-drawer { transition: transform var(--cs-motion-base) var(--cs-ease-out); }.cs-drawer-leave-active .base-drawer { transition: transform var(--cs-motion-base) var(--cs-ease-in); }.cs-drawer-enter-from .base-drawer--right, .cs-drawer-leave-to .base-drawer--right { transform: translateX(100%); }.cs-drawer-enter-from .base-drawer--left, .cs-drawer-leave-to .base-drawer--left { transform: translateX(-100%); }

/* M12b-F01：悬停反馈补齐——此前这些可点元素对指针移动完全沉默。 */
.base-drawer__header button:hover:not(:disabled) { background: var(--cs-surface-accent); }
.base-drawer__header button:active:not(:disabled) { transform: scale(.97); }
.base-drawer__header button { transition: background-color var(--cs-motion-fast) var(--cs-ease-out), border-color var(--cs-motion-fast) var(--cs-ease-out), transform var(--cs-motion-fast) var(--cs-ease-out); }
</style>
