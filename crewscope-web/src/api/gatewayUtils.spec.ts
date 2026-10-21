// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { etagVersion, pick, segment } from './gatewayUtils'

describe('gatewayUtils', () => {
  it('encodes path segments so IDs cannot break the path shape', () => {
    expect(segment('a/b?c#d')).toBe('a%2Fb%3Fc%23d')
    expect(segment('普通键')).toBe('%E6%99%AE%E9%80%9A%E9%94%AE')
  })

  it('projects a parsed object onto an allow-list, dropping every other key', () => {
    const picked = pick({ keep: 1, drop: 2, nested: { x: 1 } }, ['keep'] as const)
    expect(picked).toEqual({ keep: 1 })
    expect('drop' in picked).toBe(false)
  })

  it('parses a strong ETag into its version number and rejects everything else', () => {
    expect(etagVersion('"42"', 'Skill')).toBe(42)
    expect(() => etagVersion('W/"42"', 'Skill')).toThrow('Skill ETag is invalid')
    expect(() => etagVersion('"9007199254740993"', 'Skill')).toThrow('Skill ETag is invalid')
  })
})