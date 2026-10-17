import { onBeforeUnmount, watch, type Ref } from 'vue'
import { isTopmostModal } from '../app/dialog'

const focusableSelector = 'a[href], button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), summary, [tabindex]:not([tabindex="-1"])'

/** Controls hidden by the `hidden` attribute or living inside a collapsed `<details>` cannot take focus. */
function reachable(element: HTMLElement): boolean {
  return !element.hidden && !element.closest('details:not([open])')
}

/**
 * Scroll-lock reference count. Layered modals each hold one lock; the page scrolls again only
 * when the last one closes — restoring a saved `style.overflow` value on outer-close would
 * unlock the page while an inner modal is still open.
 */
let lockCount = 0
function acquireScrollLock(): void {
  lockCount += 1
  if (lockCount === 1) document.body.classList.add('cs-modal-open')
}
function releaseScrollLock(): void {
  if (lockCount === 0) return
  lockCount -= 1
  if (lockCount === 0) document.body.classList.remove('cs-modal-open')
}

/**
 * Keeps keyboard focus inside a modal surface and restores its trigger on close. Passing
 * `onEscape` buys the layered contract for free: the handler only fires when this surface is
 * the topmost modal in DOM order, so a background dialog's Esc listener stays silent (M12b-F02).
 * The scroll lock is acquired for the same lifetime, including the mount-with-open case.
 */
export function useFocusTrap(
  container: Ref<HTMLElement | null>,
  active: Ref<boolean>,
  onEscape?: () => void,
): { release: () => void } {
  let previouslyFocused: HTMLElement | null = null
  let locked = false
  const focusable = (): HTMLElement[] => {
    if (!container.value) return []
    return [...container.value.querySelectorAll<HTMLElement>(focusableSelector)].filter(reachable)
  }
  const onKeydown = (event: KeyboardEvent): void => {
    if (!active.value || !container.value) return
    if (event.key === 'Escape') {
      if (onEscape && isTopmostModal(container.value)) {
        event.preventDefault()
        onEscape()
      }
      return
    }
    if (event.key !== 'Tab') return
    const nodes = focusable()
    if (!nodes.length) { container.value.focus(); return }
    const first = nodes[0]
    const last = nodes[nodes.length - 1]
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
  }
  const release = (): void => {
    document.removeEventListener('keydown', onKeydown)
    if (locked) {
      releaseScrollLock()
      locked = false
    }
    previouslyFocused?.focus()
    previouslyFocused = null
  }
  watch(active, value => {
    if (value) {
      previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null
      document.addEventListener('keydown', onKeydown)
      acquireScrollLock()
      locked = true
      queueMicrotask(() => {
        if (!container.value) return
        // A component that already placed focus inside the surface (e.g. a password field's
        // focus-on-mount) outranks both fallbacks; the generic strategy must not steal it.
        const current = document.activeElement
        if (current instanceof HTMLElement && current !== document.body && container.value.contains(current)) return
        // [autofocus] wins over DOM order: a form dialog opens on its primary input, not the
        // header's close button.
        const preferred = [...container.value.querySelectorAll<HTMLElement>('[autofocus]')].find(reachable)
        if (preferred) preferred.focus()
        else {
          const first = focusable()[0]
          if (first) first.focus()
          else container.value.focus()
        }
      })
    } else release()
  }, { immediate: true })
  onBeforeUnmount(release)
  return { release }
}
