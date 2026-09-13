import { computed, ref, type Ref } from 'vue'

export interface UseSelectionOptions<T = { id: string }> {
  /** Maximum number of selected rows, including rows selected across pages. */
  maxSelected?: number
  /** Current page (or the complete result set) used to expose selected rows. */
  items?: Ref<readonly T[]>
  /** Override when the domain uses a descriptive identifier (for example inboxItemId). */
  getId?: (item: T) => string
}

export interface UseSelectionResult<T> {
  selectedIds: Readonly<Ref<Set<string>>>
  selectedCount: Readonly<Ref<number>>
  selectedItems: Readonly<Ref<T[]>>
  isSelected: (id: string) => boolean
  toggle: (id: string) => boolean
  select: (id: string) => boolean
  deselect: (id: string) => void
  selectPage: (items: readonly T[]) => number
  clear: () => void
}

/**
 * Selection is page-agnostic so a cursor-paginated list can retain choices
 * while the user loads another page. Mutations replace the Set to keep Vue's
 * reactivity predictable and return whether the requested selection succeeded.
 */
export function useSelection<T>(options: UseSelectionOptions<T> = {}): UseSelectionResult<T> {
  const selectedIds = ref(new Set<string>())
  const selectedCount = computed(() => selectedIds.value.size)
  const getId = options.getId ?? ((item: T) => (item as T & { id: string }).id)
  const selectedItems = computed(() => (options.items?.value ?? []).filter(item => selectedIds.value.has(getId(item))) as T[])
  const limit = options.maxSelected ?? Number.POSITIVE_INFINITY

  function select(id: string): boolean {
    if (selectedIds.value.has(id)) return true
    if (selectedIds.value.size >= limit) return false
    selectedIds.value = new Set([...selectedIds.value, id])
    return true
  }
  function deselect(id: string): void {
    if (!selectedIds.value.has(id)) return
    const next = new Set(selectedIds.value)
    next.delete(id)
    selectedIds.value = next
  }
  function toggle(id: string): boolean {
    if (selectedIds.value.has(id)) {
      deselect(id)
      return true
    }
    return select(id)
  }
  function selectPage(items: readonly T[]): number {
    let added = 0
    items.forEach(item => { const id = getId(item); if (!selectedIds.value.has(id) && select(id)) added += 1 })
    return added
  }
  function clear(): void { selectedIds.value = new Set() }

  return {
    selectedIds,
    selectedCount,
    selectedItems,
    isSelected: (id: string) => selectedIds.value.has(id),
    toggle,
    select,
    deselect,
    selectPage,
    clear,
  }
}
