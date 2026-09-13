import { computed, ref, type Ref } from 'vue'

/** A small, framework-friendly sorting state shared by every paginated list. */
export type SortDirection = 'asc' | 'desc'

export interface ListSortState<Key extends string> {
  key: Key
  direction: SortDirection
}

export interface UseListSortOptions<Key extends string> {
  defaultKey: Key
  defaultDirection?: SortDirection
  allowedKeys?: readonly Key[]
}

export interface UseListSortResult<Key extends string> {
  key: Ref<Key>
  direction: Ref<SortDirection>
  state: Readonly<Ref<ListSortState<Key>>>
  setSort: (key: Key, direction?: SortDirection) => void
  toggleSort: (key: Key) => void
  reset: () => void
  query: Readonly<Ref<{ sort: Key, direction: SortDirection }>>
}

/**
 * Keeps sorting deliberately independent from data fetching. A page can feed
 * `query.value` to its gateway and use `toggleSort` from either a table header
 * or a toolbar. The allow-list prevents accidentally sending unsupported keys.
 */
export function useListSort<Key extends string>(options: UseListSortOptions<Key>): UseListSortResult<Key> {
  const initialDirection = options.defaultDirection ?? 'asc'
  const key = ref(options.defaultKey) as Ref<Key>
  const direction = ref<SortDirection>(initialDirection)
  const isAllowed = (value: Key): boolean => !options.allowedKeys || options.allowedKeys.includes(value)
  const state = computed(() => ({ key: key.value, direction: direction.value }))
  const query = computed(() => ({ sort: key.value, direction: direction.value }))

  function setSort(nextKey: Key, nextDirection?: SortDirection): void {
    if (!isAllowed(nextKey)) return
    if (nextKey === key.value && nextDirection === undefined) {
      direction.value = direction.value === 'asc' ? 'desc' : 'asc'
      return
    }
    key.value = nextKey
    direction.value = nextDirection ?? 'asc'
  }

  function toggleSort(nextKey: Key): void { setSort(nextKey) }
  function reset(): void {
    key.value = options.defaultKey
    direction.value = initialDirection
  }

  return { key, direction, state, setSort, toggleSort, reset, query }
}

