import { inject, reactive, readonly, type App, type InjectionKey } from 'vue'
import type { ActionContext, ActionRegistry } from './actionRegistry'

export interface ShortcutManagerState { pending: string[]; helpOpen: boolean }
export interface ShortcutManager {
  state: Readonly<ShortcutManagerState>
  start(): void
  stop(): void
  openCommandPalette(): void
  openHelp(): void
  closeHelp(): void
}

export const SHORTCUT_MANAGER: InjectionKey<ShortcutManager> = Symbol('crewscope-shortcut-manager')
export const COMMAND_PALETTE_EVENT = 'crewscope:command-palette'
export const SHORTCUT_HELP_EVENT = 'crewscope:shortcut-help'

interface ShortcutManagerOptions {
  registry: ActionRegistry
  getContext: () => ActionContext
  target?: Document
}

/** Global keyboard bridge. Controls and modal surfaces own their keyboard events. */
export function createShortcutManager(options: ShortcutManagerOptions): ShortcutManager {
  const state = reactive<ShortcutManagerState>({ pending: [], helpOpen: false })
  const target = options.target ?? (typeof document !== 'undefined' ? document : null)
  let started = false
  let timer: number | null = null

  function clearSequence(): void {
    state.pending = []
    if (timer !== null && typeof window !== 'undefined') window.clearTimeout(timer)
    timer = null
  }

  function request(eventName: string): void {
    if (typeof window !== 'undefined') window.dispatchEvent(new CustomEvent(eventName))
  }

  function openCommandPalette(): void { request(COMMAND_PALETTE_EVENT) }
  function openHelp(): void { state.helpOpen = true; request(SHORTCUT_HELP_EVENT) }
  function closeHelp(): void { state.helpOpen = false }

  function onKeydown(event: KeyboardEvent): void {
    if (event.defaultPrevented || event.isComposing || event.keyCode === 229 || isIgnoredTarget(event.target)) return
    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
      event.preventDefault()
      openCommandPalette()
      return
    }
    if (!event.metaKey && !event.ctrlKey && !event.altKey && event.key === '?') {
      event.preventDefault()
      openHelp()
      return
    }
    if (event.metaKey || event.ctrlKey || event.altKey || event.key.length !== 1) return

    const key = event.key.toLowerCase()
    const candidate = [...state.pending, key].join(' ')
    const context = options.getContext()
    const entries = options.registry.list(context)
    const matching = entries.filter(entry => entry.action.shortcut && normalizeShortcut(entry.action.shortcut) === candidate)
    if (matching.length) {
      event.preventDefault()
      clearSequence()
      void options.registry.execute(matching[0].action.id, context)
      return
    }
    const hasPrefix = entries.some(entry => entry.action.shortcut && normalizeShortcut(entry.action.shortcut).startsWith(`${candidate} `))
    if (hasPrefix) {
      event.preventDefault()
      state.pending = [...state.pending, key]
      if (timer !== null && typeof window !== 'undefined') window.clearTimeout(timer)
      if (typeof window !== 'undefined') timer = window.setTimeout(clearSequence, 250)
    } else clearSequence()
  }

  function start(): void {
    if (started || !target) return
    started = true
    target.addEventListener('keydown', onKeydown)
  }
  function stop(): void {
    if (!started || !target) return
    started = false
    target.removeEventListener('keydown', onKeydown)
    clearSequence()
  }
  return { state: readonly(state) as Readonly<ShortcutManagerState>, start, stop, openCommandPalette, openHelp, closeHelp }
}

export function installShortcutManager(app: App, manager: ShortcutManager): void { app.provide(SHORTCUT_MANAGER, manager) }
export function useShortcutManager(): ShortcutManager {
  const manager = inject(SHORTCUT_MANAGER)
  if (!manager) throw new Error('CrewScope Shortcut Manager is not installed')
  return manager
}
export function requestCommandPalette(): void {
  if (typeof window !== 'undefined') window.dispatchEvent(new CustomEvent(COMMAND_PALETTE_EVENT))
}

function normalizeShortcut(value: string): string { return value.trim().toLowerCase().replace(/\s+/g, ' ') }

function isIgnoredTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  if (target.isContentEditable || Boolean(target.closest('[contenteditable="true"]'))) return true
  if (target.matches('input, textarea, select, option, [role="textbox"]')) return true
  // Dialogs and drawers own Escape, arrows and any local shortcuts.
  return Boolean(target.closest('[role="dialog"], [aria-modal="true"], [data-shortcuts-disabled="true"]'))
}
