/**
 * IME composition guard for keydown handlers (R27).
 *
 * While a member is typing Chinese/Japanese/Korean through an IME, the browser still fires
 * keydown for the Enter that confirms the candidate. `isComposing` is the modern signal and
 * `keyCode === 229` the legacy one — both are checked because jsdom and older engines only set
 * one of them. Callers skip their Enter/shortcut branch when this returns true, so confirming a
 * candidate never submits a form or runs a command. `src/app/shortcuts.ts` is the canonical
 * example of this pattern.
 */
export function isImeComposition(event: KeyboardEvent): boolean {
  return event.isComposing || event.keyCode === 229
}
