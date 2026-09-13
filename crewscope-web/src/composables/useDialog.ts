import { ref, type Ref } from 'vue'
import { useFocusTrap } from './useFocusTrap'

/** Shared modal state for dialogs and drawers; callers supply the rendered surface ref. */
export function useDialog(container: Ref<HTMLElement | null>): { open: Ref<boolean>; show: () => void; close: () => void } {
  const open = ref(false)
  const show = (): void => { open.value = true }
  const close = (): void => { open.value = false }
  useFocusTrap(container, open)
  return { open, show, close }
}
