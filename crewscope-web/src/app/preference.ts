/** Canonical application entry point for device preference persistence. */
export { usePreference } from '../composables/usePreference'
export type { UsePreferenceOptions, UsePreferenceResult } from '../composables/usePreference'

/** Runtime guards shared by the shell and the account page when restoring device preferences. */
export const isThemePreference = (value: unknown): value is 'system' | 'light' | 'dark' => value === 'system' || value === 'light' || value === 'dark'
export const isDensityPreference = (value: unknown): value is 'comfortable' | 'compact' => value === 'comfortable' || value === 'compact'

export type ThemePreference = 'system' | 'light' | 'dark'
export type DensityPreference = 'comfortable' | 'compact'

/** Resolve a persisted theme to the concrete mode consumed by semantic CSS tokens. */
export function resolveThemePreference(preference: ThemePreference, systemDark: boolean): 'light' | 'dark' {
  return preference === 'system' ? (systemDark ? 'dark' : 'light') : preference
}

/** Apply device preferences in one place so startup, watcher updates and tests share the same contract. */
export function applyDevicePreferences(root: HTMLElement, theme: ThemePreference, density: DensityPreference, systemDark: boolean): void {
  root.dataset.theme = resolveThemePreference(theme, systemDark)
  root.dataset.density = density
}
