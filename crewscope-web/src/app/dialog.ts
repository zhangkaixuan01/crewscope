/**
 * Returns true only for the last modal in DOM order. Layered drawers use this guard so a
 * keyboard event cannot close or cycle focus inside a background dialog.
 */
export function isTopmostModal(element: HTMLElement | null): element is HTMLElement {
  if (!element) return false
  const modals = document.querySelectorAll<HTMLElement>(
    '[role="dialog"][aria-modal="true"], [role="alertdialog"][aria-modal="true"]',
  )
  return modals.item(modals.length - 1) === element
}
