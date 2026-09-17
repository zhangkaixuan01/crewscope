import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

/**
 * The first gate against design-token drift.
 *
 * `scripts/check-design-tokens.mjs` only checks *usage* (no bare colours, spacing, sizes, motion or
 * z-index outside the token files). Nothing checked the token files themselves, so a step could be
 * added, renamed or re-valued — and every surface that consumed it would move with it silently. §10.3
 * is a closed set: seven type steps, three line heights, three weights, eleven spacing steps, ten
 * z-index tiers, three transition durations plus the spinner cycle, two curves and four breakpoints.
 * This reads the stylesheet as text, because the values are the contract and jsdom does not resolve
 * custom properties.
 */
const css = readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'tokens.css'), 'utf8')

/** The declarations of the first `:root` block, which is the light-theme base. */
function baseTokens(source: string): Map<string, string> {
  const start = source.indexOf(':root {')
  expect(start, 'tokens.css must open with a :root block').toBeGreaterThanOrEqual(0)
  let depth = 0
  let end = start
  for (let index = start; index < source.length; index += 1) {
    if (source[index] === '{') depth += 1
    if (source[index] === '}') {
      depth -= 1
      if (depth === 0) {
        end = index
        break
      }
    }
  }
  const declarations = new Map<string, string>()
  for (const match of source.slice(start, end).matchAll(/^\s*(--[\w-]+):\s*([^;]+);/gm)) {
    declarations.set(match[1], match[2].trim())
  }
  return declarations
}

const base = baseTokens(css)

function token(name: string): string {
  const value = base.get(name)
  expect(value, `${name} must be declared in tokens.css`).toBeDefined()
  return value!
}

/** Every declared token whose name starts with a prefix, as name → value. */
function prefixed(prefix: string): Map<string, string> {
  return new Map([...base].filter(([name]) => name.startsWith(prefix)))
}

describe('design tokens', () => {
  it('carries the seven type steps and nothing below the 12px floor', () => {
    const sizes = [...prefixed('--cs-text-').values()].filter(value => value.endsWith('px'))
    // `--cs-text-*` also holds colour tokens (secondary, muted, brand…); only the sizes are stepped.
    expect(sizes.sort((left, right) => Number.parseFloat(left) - Number.parseFloat(right)))
      .toEqual(['12px', '13px', '14px', '16px', '18px', '24px', '32px'])
    for (const name of ['xs', 'sm', 'base', 'md', 'lg', 'xl', '2xl']) {
      expect(token(`--cs-text-${name}`)).toMatch(/^\d+px$/)
    }
    // 11px exists only in the file-by-file whitelist for monospace code and Diff line numbers, which
    // is exactly why it is not a step: a step would let any surface reach it by name.
    expect(css).not.toMatch(/--cs-text-[\w-]+:\s*11px/)
    expect(token('--cs-leading-tight')).toBe('1.3')
    expect(token('--cs-leading-normal')).toBe('1.5')
    expect(token('--cs-leading-relaxed')).toBe('1.7')
  })

  it('keeps three weights and never reaches bold', () => {
    expect(prefixed('--cs-weight-')).toEqual(new Map([
      ['--cs-weight-regular', '400'],
      ['--cs-weight-medium', '500'],
      ['--cs-weight-semibold', '600'],
    ]))
    for (const value of prefixed('--cs-weight-').values()) {
      expect(Number.parseFloat(value)).toBeLessThan(700)
    }
  })

  it('spaces on eleven named steps, in ascending order', () => {
    const spacing = prefixed('--cs-space-')
    // Ten steps plus the half step --cs-space-2; the name is the value, so a re-valued token is a
    // renamed token and this fails rather than quietly moving every layout that used it.
    expect(spacing.size).toBe(11)
    const values = [...spacing].map(([name, value]) => [name, value] as const)
    for (const [name, value] of values) {
      expect(value, name).toBe(`${name.replace('--cs-space-', '')}px`)
    }
    const numbers = values.map(([, value]) => Number.parseFloat(value))
    expect(numbers).toEqual([...numbers].sort((left, right) => left - right))
    expect(Math.min(...numbers)).toBeGreaterThanOrEqual(2)
    expect(Math.max(...numbers)).toBe(64)
  })

  it('orders the ten z-index tiers so an explanation can always reach the surface it explains', () => {
    const tiers = prefixed('--cs-z-')
    expect([...tiers.keys()]).toEqual([
      '--cs-z-base',
      '--cs-z-raised',
      '--cs-z-sticky',
      '--cs-z-banner',
      '--cs-z-drawer',
      '--cs-z-overlay',
      '--cs-z-dialog',
      '--cs-z-popover',
      '--cs-z-toast',
      '--cs-z-tooltip',
    ])
    for (const [name, value] of tiers) {
      expect(Number.isInteger(Number(value)), `${name} must be a whole number`).toBe(true)
      // Every tier but the document flow itself clears the 200s, where migration-era bare values live.
      if (name !== '--cs-z-base') expect(Number(value), name).toBeGreaterThanOrEqual(300)
    }
    // The exact numbers are deliberately not pinned: §10.3 declares the tiers semantic, so only their
    // order and the floor are contract. A re-valued tier that keeps both is still correct.
    // Two relative orders are hard constraints, not preferences: the undo toast appears after the
    // confirmation dialog it reports on, and a disabled-reason tooltip has to be readable on top of
    // that toast.
    expect(Number(token('--cs-z-toast'))).toBeGreaterThan(Number(token('--cs-z-dialog')))
    expect(Number(token('--cs-z-tooltip'))).toBeGreaterThan(Number(token('--cs-z-toast')))
  })

  it('times three transitions, one spinner cycle and two curves', () => {
    expect(token('--cs-motion-fast')).toBe('120ms')
    expect(token('--cs-motion-base')).toBe('180ms')
    expect(token('--cs-motion-slow')).toBe('260ms')
    // The spinner's period is a loop, not a transition: it is the one duration the reduced-motion
    // block must leave alone, because a zero-length cycle is a frozen spinner rather than a calmer one.
    expect(token('--cs-motion-spin')).toBe('900ms')
    expect(token('--cs-ease-out')).toBe('cubic-bezier(.2, .8, .2, 1)')
    expect(token('--cs-ease-in-out')).toBe('cubic-bezier(.4, 0, .2, 1)')
    expect(prefixed('--cs-motion-').size).toBe(4)
    expect(prefixed('--cs-ease-').size).toBe(2)

    const reduced = css.slice(css.indexOf('@media (prefers-reduced-motion: reduce)'))
    expect(reduced).toContain('--cs-motion-fast: 0ms')
    expect(reduced).toContain('--cs-motion-base: 0ms')
    expect(reduced).toContain('--cs-motion-slow: 0ms')
    expect(reduced).not.toContain('--cs-motion-spin')
  })

  it('publishes the four breakpoints as values, because var() cannot be read in a media query', () => {
    expect(prefixed('--cs-bp-')).toEqual(new Map([
      ['--cs-bp-sm', '640px'],
      ['--cs-bp-md', '768px'],
      ['--cs-bp-lg', '1100px'],
      ['--cs-bp-xl', '1400px'],
    ]))
  })
})
