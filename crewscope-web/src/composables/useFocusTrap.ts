import { onBeforeUnmount, watch, type Ref } from 'vue'

const focusableSelector = 'a[href], button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])'

/** Keeps keyboard focus inside a modal surface and restores its trigger on close. */
export function useFocusTrap(container: Ref<HTMLElement | null>, active: Ref<boolean>): { release: () => void } {
  let previouslyFocused: HTMLElement | null = null
  const onKeydown = (event: KeyboardEvent): void => {
    if (!active.value || event.key !== 'Tab' || !container.value) return
    const nodes = [...container.value.querySelectorAll<HTMLElement>(focusableSelector)]
    if (!nodes.length) { container.value.focus(); return }
    const first = nodes[0]
    const last = nodes[nodes.length - 1]
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
  }
  const release = (): void => {
    document.removeEventListener('keydown', onKeydown)
    previouslyFocused?.focus()
    previouslyFocused = null
  }
  watch(active, value => {
    if (value) {
      previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null
      document.addEventListener('keydown', onKeydown)
      queueMicrotask(() => {
        const first = container.value?.querySelector<HTMLElement>(focusableSelector)
        if (first) first.focus()
        else container.value?.focus()
      })
    } else release()
  }, { immediate: true })
  onBeforeUnmount(release)
  return { release }
}
