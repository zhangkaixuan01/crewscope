import { describe, expect, it, beforeEach } from 'vitest'
import { nextTick } from 'vue'
import { usePreference } from './usePreference'

describe('usePreference', () => {
  beforeEach(() => localStorage.clear())

  it('persists versioned values, resets malformed data and responds to another tab', async () => {
    localStorage.setItem('list', JSON.stringify({ version: 99, value: 'stale' }))
    const preference = usePreference('list', { view: 'list' }, { version: 2 })
    expect(preference.value.value).toEqual({ view: 'list' })
    preference.value.value = { view: 'board' }
    await nextTick()
    expect(JSON.parse(localStorage.getItem('list')!).value).toEqual({ view: 'board' })
    window.dispatchEvent(new StorageEvent('storage', { key: 'list', newValue: JSON.stringify({ version: 2, value: { view: 'compact' } }) }))
    expect(preference.value.value).toEqual({ view: 'compact' })
    preference.reset()
    expect(preference.value.value).toEqual({ view: 'list' })
  })
})

