import { afterEach, describe, expect, it } from 'vitest'
import { browserStorage, safeGet, safeRemove, safeSet } from './browserStorage'

describe('browserStorage', () => {
  afterEach(() => { sessionStorage.clear(); localStorage.clear() })

  it('round-trips values through sessionStorage', () => {
    const storage = browserStorage()
    expect(storage).toBe(sessionStorage)
    safeSet(storage, 'crewscope:probe', 'kept')
    expect(safeGet(storage, 'crewscope:probe')).toBe('kept')
    safeRemove(storage, 'crewscope:probe')
    expect(safeGet(storage, 'crewscope:probe')).toBeNull()
  })

  it('stays silent when storage is unavailable', () => {
    expect(safeGet(null, 'any')).toBeNull()
    expect(() => { safeSet(null, 'any', 'value'); safeRemove(null, 'any') }).not.toThrow()
    expect(browserStorage()).toBe(sessionStorage)
  })
})
