import { describe, expect, it } from 'vitest'
import { enumQuery, localDateQuery, normalizeQuery, oneOf, queryValue, revisionQuery, uuidQuery } from './queryParams'

/**
 * 每条断言都对应迁移前至少一个页面靠它挡住过的深链形态——契约测试，不是实现快照。
 */
describe('queryParams', () => {
  it('queryValue keeps only non-empty strings and drops arrays', () => {
    expect(queryValue('OPEN')).toBe('OPEN')
    expect(queryValue('')).toBeNull()
    expect(queryValue(undefined)).toBeNull()
    expect(queryValue(['OPEN'])).toBeNull()
    expect(queryValue(3)).toBeNull()
  })

  it('oneOf returns the hit or the fallback', () => {
    const allowed = ['ALL', 'DRAFT', 'PUBLISHED'] as const
    expect(oneOf('DRAFT', allowed, 'ALL')).toBe('DRAFT')
    expect(oneOf('ARCHIVED', allowed, 'ALL')).toBe('ALL')
    expect(oneOf('', allowed, 'ALL')).toBe('ALL')
  })

  it('enumQuery returns the hit or null — no invented default', () => {
    expect(enumQuery('mapping', ['connection', 'mapping'] as const)).toBe('mapping')
    expect(enumQuery('nope', ['connection', 'mapping'] as const)).toBeNull()
    expect(enumQuery(null, ['connection', 'mapping'] as const)).toBeNull()
  })

  it('uuidQuery accepts version 1-5 uuids and rejects look-alikes', () => {
    expect(uuidQuery('550e8400-e29b-41d4-a716-446655440000')).toBe('550e8400-e29b-41d4-a716-446655440000')
    expect(uuidQuery('550E8400-E29B-41D4-A716-446655440000')).toBe('550E8400-E29B-41D4-A716-446655440000')
    // 版本位越界（v7/v8）与形似即过的垃圾都不放行——与迁移前 7 份实现同一正则。
    expect(uuidQuery('0198a4b2-6f1d-7c3e-9d2b-1f4a5b6c7d8e')).toBeNull()
    expect(uuidQuery('0198a4b2-6f1d-8c3e-9d2b-1f4a5b6c7d8e')).toBeNull()
    expect(uuidQuery('not-a-uuid')).toBeNull()
    expect(uuidQuery('')).toBeNull()
  })

  it('revisionQuery accepts integers >= 1 and rejects the rest', () => {
    expect(revisionQuery('12')).toBe(12)
    expect(revisionQuery('0')).toBeNull()
    expect(revisionQuery('-3')).toBeNull()
    expect(revisionQuery('2.5')).toBeNull()
    expect(revisionQuery('v3')).toBeNull()
  })

  it('localDateQuery accepts minute-precision timestamps that really parse', () => {
    expect(localDateQuery('2026-10-17T09:30')).toBe('2026-10-17T09:30')
    expect(localDateQuery('2026-13-40T09:30')).toBeNull()
    expect(localDateQuery('2026-10-17T09:30:00')).toBeNull()
    expect(localDateQuery('2026-10-17')).toBeNull()
  })

  it('normalizeQuery writes the non-default value, deletes the default, and reports real changes only', () => {
    const query: Record<string, unknown> = {}
    expect(normalizeQuery(query, 'status', 'DRAFT', 'ALL')).toBe(true)
    expect(query.status).toBe('DRAFT')
    expect(normalizeQuery(query, 'status', 'DRAFT', 'ALL')).toBe(false)
    expect(normalizeQuery(query, 'status', 'ALL', 'ALL')).toBe(true)
    expect('status' in query).toBe(false)
  })
})
