import { isImeComposition } from './useImeGuard'

/**
 * R27: the Enter that confirms an IME candidate is typing input, not a command — it must never
 * submit a form or run a highlighted entry. Both signals are checked because jsdom and older
 * engines only set one of them.
 */
describe('isImeComposition', () => {
  it('detects the modern isComposing signal', () => {
    expect(isImeComposition(new KeyboardEvent('keydown', { isComposing: true }))).toBe(true)
  })

  it('falls back to the legacy keyCode 229 signal', () => {
    // keyCode is a legacy read-only field jsdom refuses to construct; the guard only reads it.
    expect(isImeComposition({ isComposing: false, keyCode: 229 } as KeyboardEvent)).toBe(true)
    expect(isImeComposition({ isComposing: true, keyCode: 13 } as KeyboardEvent)).toBe(true)
  })

  it('lets a plain keydown through', () => {
    expect(isImeComposition(new KeyboardEvent('keydown', {}))).toBe(false)
  })
})
