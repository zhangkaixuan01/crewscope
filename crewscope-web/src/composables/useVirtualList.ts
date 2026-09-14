import { computed, onBeforeUnmount, onMounted, ref, type ComputedRef, type Ref } from 'vue'

export interface VirtualListResult<T> {
  container: Ref<HTMLElement | null>
  visibleItems: ComputedRef<T[]>
  topPadding: ComputedRef<number>
  bottomPadding: ComputedRef<number>
  onScroll: () => void
  reset: () => void
}

/** Lightweight fixed-height virtualisation for long conversations. Variable content remains accessible. */
export function useVirtualList<T>(items: Ref<T[]> | ComputedRef<T[]>, itemHeight = 104, overscan = 8): VirtualListResult<T> {
  const container = ref<HTMLElement | null>(null)
  const scrollTop = ref(0)
  const viewportHeight = ref(0)
  const start = computed(() => {
    if (viewportHeight.value <= 0) return 0
    return Math.max(0, Math.floor(scrollTop.value / itemHeight) - overscan)
  })
  const count = computed(() => viewportHeight.value <= 0 ? items.value.length : Math.ceil(viewportHeight.value / itemHeight) + overscan * 2)
  const visibleItems = computed(() => items.value.slice(start.value, start.value + count.value))
  const topPadding = computed(() => start.value * itemHeight)
  const bottomPadding = computed(() => Math.max(0, (items.value.length - start.value - visibleItems.value.length) * itemHeight))
  function onScroll(): void {
    const element = container.value
    if (!element) return
    scrollTop.value = element.scrollTop
    viewportHeight.value = element.clientHeight
  }
  function reset(): void { scrollTop.value = 0; onScroll() }
  onMounted(() => { onScroll(); window.addEventListener('resize', onScroll) })
  onBeforeUnmount(() => window.removeEventListener('resize', onScroll))
  return { container, visibleItems, topPadding, bottomPadding, onScroll, reset }
}
