import { computed, onBeforeUnmount, ref, type Ref } from 'vue'
import { onBeforeRouteLeave, type RouteLocationNormalizedLoaded } from 'vue-router'
import { useConfirm } from './useConfirm'
import { readF05, removeF05, writeF05, type F05Kind, type F05Scope, type F05StorageResult } from '../app/f05Storage'

export interface DirtyFormOptions {
  title?: string
  description?: string
  confirmLabel?: string
  cancelLabel?: string
  draftScope?: F05Scope | Ref<F05Scope | undefined>
  draftKind?: F05Kind
  /** Scope switches must save a local draft and continue without a confirmation dialog. */
  bypassScopeSwitch?: boolean
}

/**
 * Shared guard for configuration forms. It protects navigation and refresh while
 * deliberately allowing callers to persist a draft before changing Scope. Drafts
 * only ever land in the scoped namespace; a write reports whether it really
 * persisted so callers never claim a save that did not happen.
 */
export function useDirtyForm<T = unknown>(value?: Ref<T>, options: DirtyFormOptions = {}) {
  const confirm = useConfirm()
  const dirty = ref(false)
  const baseline = ref<string | null>(null)
  const draftScope = options.draftScope
  const draftKind = options.draftKind ?? 'draft'

  function serialize(input: unknown): string {
    try { return JSON.stringify(input) } catch { return String(input) }
  }

  function markClean(nextValue?: T): void {
    baseline.value = nextValue === undefined && value ? serialize(value.value) : serialize(nextValue)
    dirty.value = false
  }

  function markDirty(): void { dirty.value = true }

  function sync(nextValue: T): void {
    const serialized = serialize(nextValue)
    if (baseline.value === null) baseline.value = serialized
    dirty.value = serialized !== baseline.value
  }

  function saveDraft(nextValue?: T): F05StorageResult {
    if (!value && nextValue === undefined) return { ok: false, reason: 'invalid' }
    const payload = nextValue === undefined ? value?.value : nextValue
    const scope = draftScope && 'value' in draftScope ? draftScope.value : draftScope
    if (!scope) return { ok: false, reason: 'invalid' }
    return writeF05(draftKind, scope, payload)
  }

  function restoreDraft(): T | null {
    const scope = draftScope && 'value' in draftScope ? draftScope.value : draftScope
    if (!scope) return null
    return readF05<T>(draftKind, scope)?.value ?? null
  }

  function clearDraft(): void {
    const scope = draftScope && 'value' in draftScope ? draftScope.value : draftScope
    if (scope) removeF05(draftKind, scope)
  }

  async function confirmDiscard(): Promise<boolean> {
    if (!dirty.value) return true
    return confirm.confirm({
      title: options.title ?? '放弃未保存的修改？',
      description: options.description ?? '当前表单有未保存内容，离开后这些修改会丢失。',
      confirmLabel: options.confirmLabel ?? '放弃修改',
      cancelLabel: options.cancelLabel ?? '继续编辑',
      danger: true,
    })
  }

  async function closeWithGuard(close: () => void | Promise<void>): Promise<boolean> {
    if (!await confirmDiscard()) return false
    await close()
    return true
  }

  function handleBeforeUnload(event: BeforeUnloadEvent): void {
    if (!dirty.value) return
    event.preventDefault()
    event.returnValue = ''
  }

  function handleScopeSwitching(): void {
    if (options.bypassScopeSwitch === false || !dirty.value) return
    saveDraft()
  }

  if (value) {
    // Consumers can call sync from a watcher after loading the authoritative value.
    sync(value.value)
  }
  onBeforeRouteLeave(async (to: RouteLocationNormalizedLoaded, from: RouteLocationNormalizedLoaded) => {
    if (options.bypassScopeSwitch !== false && to.name === from.name && to.query.team !== from.query.team) {
      saveDraft()
      return true
    }
    return await confirmDiscard()
  })
  if (typeof window !== 'undefined') {
    window.addEventListener('beforeunload', handleBeforeUnload)
    window.addEventListener('crewscope:scope-switching', handleScopeSwitching)
  }
  onBeforeUnmount(() => {
    if (typeof window !== 'undefined') {
      window.removeEventListener('beforeunload', handleBeforeUnload)
      window.removeEventListener('crewscope:scope-switching', handleScopeSwitching)
    }
  })

  return {
    dirty: computed(() => dirty.value),
    isDirty: dirty,
    baseline,
    markDirty,
    markClean,
    sync,
    confirmDiscard,
    closeWithGuard,
    handleBeforeUnload,
    saveDraft,
    restoreDraft,
    clearDraft,
  }
}

/** Backwards-compatible name used by the design plan and existing consumers. */
export const useDirtyGuard = useDirtyForm
