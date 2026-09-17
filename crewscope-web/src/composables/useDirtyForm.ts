import { computed, onBeforeUnmount, ref, type Ref } from 'vue'
import { onBeforeRouteLeave, type RouteLocationNormalizedLoaded } from 'vue-router'
import { useConfirm } from './useConfirm'

export interface DirtyFormOptions {
  title?: string
  description?: string
  confirmLabel?: string
  cancelLabel?: string
  draftKey?: string
  /** Scope switches must save a local draft and continue without a confirmation dialog. */
  bypassScopeSwitch?: boolean
}

/**
 * Shared guard for configuration forms. It protects navigation and refresh while
 * deliberately allowing callers to persist a draft before changing Scope.
 */
export function useDirtyForm<T = unknown>(value?: Ref<T>, options: DirtyFormOptions = {}) {
  const confirm = useConfirm()
  const dirty = ref(false)
  const baseline = ref<string | null>(null)
  const draftKey = options.draftKey

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

  function saveDraft(nextValue?: T): void {
    if (!draftKey || typeof localStorage === 'undefined' || !value && nextValue === undefined) return
    const payload = nextValue === undefined ? value?.value : nextValue
    try { localStorage.setItem(draftKey, serialize(payload)) } catch { /* storage is best effort */ }
  }

  function restoreDraft(): T | null {
    if (!draftKey || typeof localStorage === 'undefined') return null
    try {
      const raw = localStorage.getItem(draftKey)
      return raw ? JSON.parse(raw) as T : null
    } catch { return null }
  }

  function clearDraft(): void {
    if (!draftKey || typeof localStorage === 'undefined') return
    try { localStorage.removeItem(draftKey) } catch { /* storage is best effort */ }
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
