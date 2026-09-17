import { describe, expect, it } from 'vitest'
import {
  generateOptionFields,
  generateOptionLimit,
  limitBound,
  limitErrorMessage,
  limitRangeText,
  limitShape,
  seedBound,
  seedErrorMessage,
  withinGenerateOptionLimit,
  withinSeedBound,
} from './limits'

/**
 * The form's half of the range contract.
 *
 * The chain this depends on, and which the drift gate closes: the domain's
 * `AgentGenerateOptionsLimits` is copied into `api/generated/agent-limits.ts` by
 * `scripts/generate-agent-limits.mjs` and compared in CI, and this module is the only reader of that
 * copy. So the numbers asserted here are the numbers the aggregate validates against — not a second
 * hand-copied set that happens to agree today.
 */
describe('generate option limits', () => {
  it('publishes the four bounded fields in declaration order', () => {
    expect(generateOptionFields()).toEqual(['temperature', 'topP', 'maximumOutputTokens', 'maximumAttempts'])
  })

  it('keeps the bounds the server validates, spelled the way the domain spells them', () => {
    // Literal pins: these are the numbers a member's payload is checked against. If a bound moves in
    // the domain table, this fails before the form silently publishes a range nobody enforces.
    expect(limitBound('temperature', 'minimum')).toBe(0)
    expect(limitBound('temperature', 'maximum')).toBe(2)
    expect(limitBound('temperature', 'step')).toBe(0.01)
    expect(limitBound('topP', 'minimum')).toBe(0)
    expect(limitBound('topP', 'maximum')).toBe(1)
    expect(limitBound('topP', 'step')).toBe(0.01)
    expect(limitBound('maximumOutputTokens', 'minimum')).toBe(1)
    expect(limitBound('maximumOutputTokens', 'maximum')).toBe(10_000_000)
    expect(limitBound('maximumOutputTokens', 'step')).toBe(1)
    expect(limitBound('maximumAttempts', 'minimum')).toBe(1)
    expect(limitBound('maximumAttempts', 'maximum')).toBe(10)
    expect(limitBound('maximumAttempts', 'step')).toBe(1)
  })

  it('prints the range from those same bounds', () => {
    expect(limitRangeText('temperature')).toBe('0–2')
    expect(limitRangeText('topP')).toBe('大于 0 且不超过 1')
    expect(limitRangeText('maximumOutputTokens')).toBe('1–10,000,000')
    expect(limitRangeText('maximumAttempts')).toBe('1–10')
  })

  it('names the range again in the error, so the visible message and the label cannot disagree', () => {
    expect(limitErrorMessage('temperature')).toContain(limitRangeText('temperature'))
    expect(limitErrorMessage('topP')).toContain('大于 0 且不超过 1')
    expect(limitErrorMessage('maximumOutputTokens')).toContain('10,000,000')
  })

  it('lets the published range decide which side of each bound is inside', () => {
    expect(withinGenerateOptionLimit('temperature', '0')).toBe(true)
    expect(withinGenerateOptionLimit('temperature', '0.01')).toBe(true)
    expect(withinGenerateOptionLimit('temperature', '2')).toBe(true)
    expect(withinGenerateOptionLimit('temperature', '2.01')).toBe(false)
    expect(withinGenerateOptionLimit('temperature', '-0.01')).toBe(false)

    // The floor is open for topP: exactly zero is the provider default, not a value to send.
    expect(withinGenerateOptionLimit('topP', '0')).toBe(false)
    expect(withinGenerateOptionLimit('topP', '0.01')).toBe(true)
    expect(withinGenerateOptionLimit('topP', '1')).toBe(true)
    expect(withinGenerateOptionLimit('topP', '1.01')).toBe(false)

    expect(withinGenerateOptionLimit('maximumAttempts', '10')).toBe(true)
    expect(withinGenerateOptionLimit('maximumAttempts', '11')).toBe(false)
    expect(withinGenerateOptionLimit('maximumOutputTokens', '10000000')).toBe(true)
    expect(withinGenerateOptionLimit('maximumOutputTokens', '10000001')).toBe(false)
  })

  it('refuses a fraction in a whole-number field and a non-number anywhere', () => {
    expect(limitShape('maximumAttempts')).toBe('INTEGER')
    expect(limitShape('temperature')).toBe('DECIMAL')
    expect(withinGenerateOptionLimit('maximumAttempts', '1.5')).toBe(false)
    expect(withinGenerateOptionLimit('temperature', '1.5')).toBe(true)
    // A number input that has been emptied by the browser arrives as text, not as a number.
    expect(withinGenerateOptionLimit('temperature', 'abc')).toBe(false)
    expect(withinGenerateOptionLimit('maximumAttempts', '1e999')).toBe(false)
  })

  it('reads an empty field as the model default — except where the server needs a number', () => {
    // The parameter's own type decides: `Optional<...>` leaves the field to the model default, while
    // the attempt count is a primitive `int`. Reading an emptied attempt count as zero is how a form
    // sends 0 to a validator that starts at 1.
    expect(generateOptionLimit('maximumAttempts').required).toBe(true)
    for (const field of generateOptionFields()) {
      const required = generateOptionLimit(field).required
      expect(withinGenerateOptionLimit(field, ''), `${field} empty`).toBe(!required)
      expect(withinGenerateOptionLimit(field, '   '), `${field} blank`).toBe(!required)
      expect(withinGenerateOptionLimit(field, null), `${field} absent`).toBe(!required)
    }
    expect(withinGenerateOptionLimit('maximumAttempts', '1')).toBe(true)
  })

  it('bounds seed by what a JSON number carries, because the server sets no range on it', () => {
    expect(generateOptionLimit('maximumAttempts').includeMinimum).toBe(true)
    // Seed stays out of the published table: the server accepts it as an optional whole number with
    // no range, so there is no bound to publish.
    expect(generateOptionFields()).not.toContain('seed')
    expect(withinSeedBound('')).toBe(true)
    expect(withinSeedBound(String(seedBound.maximum))).toBe(true)
    expect(withinSeedBound(String(seedBound.minimum))).toBe(true)
    // 2^53 is where a JSON number stops being able to represent every integer.
    expect(withinSeedBound('9007199254740993')).toBe(false)
    expect(seedErrorMessage).toContain('安全整数')
  })
})
