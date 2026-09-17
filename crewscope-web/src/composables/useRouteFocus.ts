import { computed, nextTick, ref, watch, type ComputedRef, type Ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

export interface RouteFocus {
  /** The identity the URL asks this page to locate, or null when the URL asks for nothing. */
  locatedId: ComputedRef<string | null>
  /** The element the located row currently occupies, once the list has rendered it. */
  locatedRow: Ref<HTMLElement | null>
  /** Row binding: `:ref="element => bindRow(element, row.id)"`. */
  bindRow(element: unknown, id: string): void
  clear(): void
}

/**
 * Search results deep-link into a list with the row's identity (`?member=`, `?binding=`). Locating it
 * is a read-only affordance — the row is highlighted and scrolled to, and the parameter stays in the
 * URL until the member dismisses it, so a shared link keeps pointing at the same row.
 */
export function useRouteFocus(param: string): RouteFocus {
  const route = useRoute()
  const router = useRouter()
  const locatedRow = ref<HTMLElement | null>(null)
  const locatedId = computed(() => {
    const value = route.query[param]
    return typeof value === 'string' && value.trim() ? value : null
  })

  /*
   * Vue calls the previous render's ref function with `null` before the current one with the element,
   * so a binding may only ever write for the row the URL names — writing on every call would put
   * reactive state into the patch phase. Dismissing the hint also lands here: the render that follows
   * can still see the old parameter, which is what the reset below is for.
   */
  function bindRow(element: unknown, id: string): void {
    if (!locatedId.value || id !== locatedId.value) return
    locatedRow.value = element instanceof HTMLElement ? element : null
    if (locatedRow.value) void nextTick(() => locatedRow.value?.scrollIntoView({ block: 'center' }))
  }

  function clear(): void {
    void router.replace({ query: { ...route.query, [param]: undefined } })
  }

  watch(locatedId, id => { if (!id) locatedRow.value = null })

  return { locatedId, locatedRow, bindRow, clear }
}
