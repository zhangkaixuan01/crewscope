import { describe, expect, it, beforeEach } from 'vitest'
import { nextTick } from 'vue'
import { usePreference } from './usePreference'
import { applyDevicePreferences, resolveThemePreference } from '../app/preference'

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

  it('falls back when a version-matched value fails its runtime guard', () => {
    localStorage.setItem('theme', JSON.stringify({ version: 1, value: 'ultraviolet' }))
    const preference = usePreference<'system' | 'light' | 'dark'>('theme', 'system', {
      version: 1,
      validate: (value): value is 'system' | 'light' | 'dark' => value === 'system' || value === 'light' || value === 'dark',
    })
    expect(preference.value.value).toBe('system')

    window.dispatchEvent(new StorageEvent('storage', {
      key: 'theme',
      newValue: JSON.stringify({ version: 1, value: { unexpected: true } }),
    }))
    expect(preference.value.value).toBe('system')
  })

  it('resolves system theme and applies density without mutating the preference value', () => {
    expect(resolveThemePreference('system', true)).toBe('dark')
    expect(resolveThemePreference('system', false)).toBe('light')
    expect(resolveThemePreference('dark', false)).toBe('dark')

    const root = document.documentElement
    applyDevicePreferences(root, 'dark', 'compact', false)
    expect(root.dataset.theme).toBe('dark')
    expect(root.dataset.density).toBe('compact')
  })
})
