import { describe, expect, it } from 'vitest'
import { useListSort } from './useListSort'

describe('useListSort', () => {
  it('toggles the same key and resets to the configured default', () => {
    const sort = useListSort({ defaultKey: 'updatedAt' as const, allowedKeys: ['updatedAt', 'priority'] as const })
    expect(sort.query.value).toEqual({ sort: 'updatedAt', direction: 'asc' })
    sort.toggleSort('updatedAt')
    expect(sort.query.value.direction).toBe('desc')
    sort.setSort('priority')
    expect(sort.query.value).toEqual({ sort: 'priority', direction: 'asc' })
    sort.setSort('unsupported' as 'priority')
    expect(sort.query.value.sort).toBe('priority')
    sort.reset()
    expect(sort.state.value).toEqual({ key: 'updatedAt', direction: 'asc' })
  })
})
