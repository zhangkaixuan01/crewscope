import { createFormCommandKeys } from './formCommandKeys'

describe('component-owned command keys', () => {
  it('retains keys when credentials are edited and changed back; clear starts a new interaction', () => {
    const keys = createFormCommandKeys()
    const original = keys.forInput({ target: 'A', version: 1, credential: 'test-only' })
    expect(keys.forInput({ credential: 'test-only', version: 1, target: 'A' })).toBe(original)
    expect(keys.forInput({ target: 'A', version: 1, credential: 'changed-test' })).not.toBe(original)
    expect(keys.forInput({ target: 'A', version: 2, credential: 'test-only' })).not.toBe(original)
    expect(keys.forInput({ target: 'A', version: 1, credential: 'test-only' })).toBe(original)
    keys.clear()
    expect(keys.forInput({ target: 'A', version: 1, credential: 'test-only' })).not.toBe(original)
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })
})
