import { describe, expect, it } from 'vitest'
import { formatAbsoluteTime, formatRelativeTime } from './formatRelativeTime'

describe('formatRelativeTime', () => {
  const now = Date.parse('2026-09-13T12:00:00Z')
  it('uses stable Chinese thresholds and avoids negative future durations', () => {
    expect(formatRelativeTime(now - 20_000, now)).toBe('刚刚')
    expect(formatRelativeTime(now - 5 * 60_000, now)).toBe('5 分钟前')
    expect(formatRelativeTime(now - 2 * 60 * 60_000, now)).toBe('2 小时前')
    expect(formatRelativeTime(now + 3 * 60_000, now)).toBe('刚刚')
    expect(formatAbsoluteTime('invalid')).toBe('时间未知')
  })
})

