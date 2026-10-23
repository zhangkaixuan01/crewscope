import { nextTick, ref, type Ref } from 'vue'

/**
 * M13b-F11（C4）：内联详情面板的焦点生命周期——打开聚焦详情标题（等 patch + rAF，让触发它的
 * 键盘事件先走完），关闭把焦点还原给触发按钮（照 useFocusTrap 的 previouslyFocused 模型）。
 * 还原目标在打开动作发出前捕获：focus() 不阻止滚动，移动端单列布局会自然滚到详情标题。
 */
export function useDetailPaneFocus(heading: Ref<HTMLElement | null>) {
  const previouslyFocused = ref<HTMLElement | null>(null)

  /** Call right before the open action fires, while the trigger still holds focus. */
  function captureTrigger(): void {
    const active = document.activeElement
    previouslyFocused.value = active instanceof HTMLElement && active !== document.body ? active : null
  }

  /** Focus the detail heading once its panel has rendered. */
  async function focusHeading(): Promise<void> {
    await nextTick()
    const focusNow = (): void => { heading.value?.focus() }
    if (typeof requestAnimationFrame === 'function') requestAnimationFrame(focusNow)
    else focusNow()
  }

  /** Return focus to the trigger that opened the panel; deep links cold-start with none. */
  function restoreTrigger(): void {
    const target = previouslyFocused.value
    previouslyFocused.value = null
    target?.focus()
  }

  return { captureTrigger, focusHeading, restoreTrigger }
}
