import { computed, onBeforeUnmount, onMounted, reactive, ref, watch, type ComputedRef, type Ref } from 'vue'

/** Distance (px) from the bottom within which appended rows keep the list glued to the end (§4.4). */
const FOLLOW_THRESHOLD = 80
/** Rows shorter than this cannot occur in the message list; it bounds the window size estimate. */
const MIN_ROW_HEIGHT = 40

export interface VirtualListOptions<T> {
  keyOf: (item: T) => string
  /** Row height before the first measurement arrives; anchors correct whatever the estimate got wrong. */
  estimate?: number
  overscan?: number
  /** At or below this many rows everything renders plain — measurement machinery is not worth it. */
  plainThreshold?: number
  /** Space the layout puts between rows (grid gap); prefix sums must count it or anchors drift. */
  rowGap?: number
  /** Height read for one row; injectable so specs without layout can feed deterministic numbers. */
  measureRow?: (element: HTMLElement) => number
}

export interface VirtualAnchor {
  key: string
  /** Signed px from the anchor row's top to the viewport top; negative means the row starts above it. */
  offsetWithin: number
}

export interface VirtualListResult<T> {
  container: Ref<HTMLElement | null>
  listElement: Ref<HTMLElement | null>
  visibleItems: ComputedRef<readonly T[]>
  topPadding: ComputedRef<number>
  bottomPadding: ComputedRef<number>
  isFollowing: Ref<boolean>
  onScroll: () => void
  reset: () => void
  currentAnchor: () => VirtualAnchor | null
  scrollToAnchor: (anchor: VirtualAnchor) => boolean
}

/**
 * Two-mode reading list (contract §4.4): short lists render plain; long lists virtualise on measured
 * heights (one shared ResizeObserver, per-key height map, prefix sums, binary search). Any change to
 * the content above the reading position is compensated through a message anchor captured from the
 * previous list, so prepended history, measured-height corrections and resizes never move reading.
 */
export function useVirtualList<T>(
  items: Ref<readonly T[]> | ComputedRef<readonly T[]>,
  options: VirtualListOptions<T>,
): VirtualListResult<T> {
  const estimate = options.estimate ?? 108
  const overscan = options.overscan ?? 8
  const plainThreshold = options.plainThreshold ?? 80
  const rowGap = options.rowGap ?? 0
  const measureRow = options.measureRow ?? ((element: HTMLElement) => element.getBoundingClientRect().height)

  const container = ref<HTMLElement | null>(null)
  const listElement = ref<HTMLElement | null>(null)
  const scrollTop = ref(0)
  const viewportHeight = ref(0)
  const measured = reactive(new Map<string, number>())
  const isFollowing = ref(true)

  const plain = computed(() => items.value.length <= plainThreshold)

  /** Prefix sums for the current items: offsets[i] is row i's top; offsets[length] the total height. */
  const offsets = computed(() => buildOffsets(items.value))
  function buildOffsets(list: readonly T[]): number[] {
    // Rows that were never rendered keep an estimate — but one calibrated against everything
    // measured so far, not the initial guess: message rows are largely homogeneous, so the
    // measured median is close to the truth and anchors above large unmeasured spans stay exact.
    // (Median, not mean: one 500-line code block would drag the mean far off every normal row.)
    const fallback = estimatedRowHeight()
    const sums = new Array<number>(list.length + 1)
    sums[0] = 0
    let total = 0
    for (let index = 0; index < list.length; index += 1) {
      // A row's top counts every gap above it (the gap precedes the height), so offsets match the
      // plain fully-rendered layout; the total then ends at the last row's bottom with no tail gap.
      if (index > 0) total += rowGap
      sums[index] = total
      total += measured.get(options.keyOf(list[index])) ?? fallback
    }
    sums[list.length] = total
    return sums
  }

  /** Median of every measured row — far better than the initial guess for homogeneous lists. */
  function estimatedRowHeight(): number {
    if (measured.size === 0) return estimate
    const heights = [...measured.values()].sort((left, right) => left - right)
    return heights[Math.floor(heights.length / 2)]
  }

  const start = computed(() => {
    if (plain.value || viewportHeight.value <= 0) return 0
    return Math.max(0, firstVisible(offsets.value, scrollTop.value) - overscan)
  })
  const count = computed(() => {
    if (plain.value || viewportHeight.value <= 0) return items.value.length
    return Math.ceil(viewportHeight.value / minRowHeight()) + overscan * 2
  })
  const visibleItems = computed(() => items.value.slice(start.value, start.value + count.value))
  // Spacers live inside the same grid as the rows, so the row gap separates a spacer from the edge
  // row too: a spacer of `offsets[start]` would push that row down by one extra gap. Subtracting it
  // makes "row i's top = offsets[i]" hold in the real layout, which the anchor maths relies on.
  const topPadding = computed(() => (plain.value || start.value === 0 ? 0 : Math.max(0, offsets.value[start.value] - rowGap)))
  const bottomPadding = computed(() => {
    if (plain.value) return 0
    const windowEnd = Math.min(items.value.length, start.value + visibleItems.value.length)
    if (windowEnd >= items.value.length) return 0
    return Math.max(0, offsets.value[items.value.length] - offsets.value[windowEnd] - rowGap)
  })

  /** Smallest height seen (or estimated) — the window must cover worst case, or rows would be skipped. */
  function minRowHeight(): number {
    let smallest = estimate
    for (const height of measured.values()) {
      if (height < smallest) smallest = height
    }
    return Math.max(MIN_ROW_HEIGHT, smallest)
  }

  /** First row whose span reaches into the viewport at `offset`; clamped to the last row. */
  function firstVisible(sums: number[], offset: number): number {
    let low = 0
    let high = sums.length - 2
    while (low < high) {
      const middle = Math.floor((low + high) / 2)
      if (sums[middle + 1] <= offset) low = middle + 1
      else high = middle
    }
    return Math.max(0, low)
  }

  function onScroll(): void {
    const moved = container.value ? Math.abs(container.value.scrollTop - scrollTop.value) > 0.5 : false
    readMetrics()
    // The anchor describes where reading currently is, so it is re-derived on every real scroll —
    // but not on the scroll event a programmatic restore fires for free: that write lands on
    // estimated offsets, and re-deriving would adopt the landing spot (estimate error included)
    // as the official position. readMetrics has already absorbed the write into scrollTop.value,
    // so "no movement" is exactly how those echoes are told apart from member scrolling.
    // During a mutation (items already swapped, DOM not yet re-rendered) the old scrollTop must
    // not be applied to the new list — the captured anchor stays authoritative until the
    // post-flush restore consumes it. A window with no anchorable row keeps the previous anchor.
    if (!anchorLocked && moved) {
      const anchor = anchorFromViewport()
      if (anchor) pendingAnchor = anchor
    }
  }

  /** Reads the live scroll metrics; everything but anchor re-derivation shares this. */
  function readMetrics(): void {
    const element = container.value
    if (!element) return
    scrollTop.value = element.scrollTop
    viewportHeight.value = element.clientHeight
    isFollowing.value = element.scrollHeight - element.scrollTop - element.clientHeight <= FOLLOW_THRESHOLD
    if (!rowObserver) syncObservedRows()
  }

  /**
   * Anchor from the rendered rows themselves while real layout exists — exact even where the model
   * still estimates (unmeasured rows above shift every model offset). Null when no row intersects
   * the viewport (mid-reflow windows, empty lists); callers decide between keeping the previous
   * anchor and falling back to offset maths. jsdom's 0×0 rects never match, so specs stay on the
   * pure-maths path.
   */
  function anchorFromViewport(): VirtualAnchor | null {
    const element = container.value
    const list = listElement.value
    if (!element || !list) return null
    const viewportTop = element.getBoundingClientRect().top
    for (const row of list.querySelectorAll<HTMLElement>('[data-virtual-key]')) {
      const rect = row.getBoundingClientRect()
      if (rect.height <= 0) continue
      if (rect.bottom <= viewportTop) continue
      return { key: row.dataset.virtualKey ?? '', offsetWithin: rect.top - viewportTop }
    }
    return null
  }

  function currentAnchor(): VirtualAnchor | null {
    return anchorFromViewport() ?? anchorFromOffsets(items.value, scrollTop.value)
  }

  /** Anchor from pure maths on a given list, so jsdom (no layout) captures and restores identically. */
  function anchorFromOffsets(list: readonly T[], offset: number): VirtualAnchor | null {
    if (list.length === 0 || viewportHeight.value <= 0) return null
    const sums = buildOffsets(list)
    // Row positions are relative to the list's top, the scroll offset is not — align them first,
    // then both the row lookup and the anchor share the same coordinate system.
    const relative = offset - listContentOffset()
    const index = firstVisible(sums, relative)
    if (index >= list.length) return null
    // Same convention as anchorFromViewport: row top minus viewport top, negative = row above it.
    return { key: options.keyOf(list[index]), offsetWithin: sums[index] - relative }
  }

  /**
   * The list's own top inside the scroll content — the "load older" block and container padding
   * sit above it, and DOM-true anchors count them. The model path (anchors outside the window)
   * must add the same offset or a restore lands one preamble short. jsdom's 0×0 rects report 0,
   * which keeps specs on pure offset maths.
   */
  function listContentOffset(): number {
    const element = container.value
    const list = listElement.value
    if (!element || !list) return 0
    const listRect = list.getBoundingClientRect()
    // jsdom (and hidden hosts) report zero-width boxes while the scroll metrics may be mocked to
    // real numbers — without this guard a mock scrollTop alone would pose as a layout offset and
    // skew every model anchor. Real layouts always give the list a positive width.
    if (listRect.width <= 0) return 0
    const offset = listRect.top - element.getBoundingClientRect().top + element.scrollTop
    return offset > 0 ? offset : 0
  }

  function scrollToAnchor(anchor: VirtualAnchor): boolean {
    const element = container.value
    if (!element) return false
    const index = items.value.findIndex(item => options.keyOf(item) === anchor.key)
    if (index < 0) return false
    // A reading anchor must never land inside the follow zone: estimated offsets for rows that were
    // never rendered can overshoot the real bottom, and a bottom-clamped write would read as
    // "following" and stop every later compensation. Clamp one threshold below the maximum instead —
    // but only when a real scrollable layout exists (hidden or unmeasured containers report 0).
    const maxScroll = element.scrollHeight - element.clientHeight
    const overshootGuard = maxScroll > 0 ? maxScroll - FOLLOW_THRESHOLD - 1 : Number.POSITIVE_INFINITY
    // offsetWithin is "viewport top minus row top" (see anchorFromOffsets); restoring inverts it:
    // scrollTop = rowTop − offsetWithin. The preamble offset keeps DOM-true anchors (which count
    // the "load older" block and paddings above the list) and pure-maths anchors interchangeable.
    element.scrollTop = Math.min(Math.max(0, listContentOffset() + offsets.value[index] - anchor.offsetWithin), overshootGuard)
    // Anchoring is itself a reading position: measured-height corrections that land right after a
    // restore (rows above are still unmeasured at first paint) compensate around this anchor.
    // Metrics refresh, but the anchor is NOT re-derived here: the write landed on estimated
    // offsets, and re-deriving from that unmeasured layout would lock the estimate's error in
    // as the official reading position (the row RO pass then restores the *intent* exactly).
    pendingAnchor = anchor
    readMetrics()
    return true
  }

  let pendingAnchor: VirtualAnchor | null = null
  let anchorLocked = false

  function restoreAnchor(): void {
    const anchor = pendingAnchor
    if (!anchor) return
    const element = container.value
    const list = listElement.value
    if (!element || !list) return
    // When the anchor row is rendered, restore against its real DOM position: every model error
    // (unmeasured rows above, height changes after a resize, content that precedes the list)
    // collapses into one shift of scrollTop. The model path stays the fallback for anchors
    // outside the window (restore after reload) and for hosts without layout (specs: rect 0×0).
    const row = [...list.querySelectorAll<HTMLElement>('[data-virtual-key]')]
      .find(item => item.dataset.virtualKey === anchor.key)
    if (row) {
      const rect = row.getBoundingClientRect()
      if (rect.height > 0) {
        const offset = rect.top - element.getBoundingClientRect().top
        element.scrollTop += offset - anchor.offsetWithin
        onScroll()
        return
      }
    }
    scrollToAnchor(anchor)
  }

  /** Clear measurements of rows that left the list (conversation switch) and restart from the top. */
  function reset(): void {
    const live = new Set(items.value.map(options.keyOf))
    for (const key of measured.keys()) {
      if (!live.has(key)) measured.delete(key)
    }
    pendingAnchor = null
    anchorLocked = false
    scrollTop.value = 0
    isFollowing.value = true
    const element = container.value
    if (element) element.scrollTop = 0
    onScroll()
    // A switched-to conversation starts as "following" until the host's restore chain says otherwise.
    isFollowing.value = true
  }

  const rowObserver = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(entries => {
    let changed = false
    for (const entry of entries) {
      const element = entry.target as HTMLElement
      const key = element.dataset.virtualKey
      // A row that left the DOM reports 0×0 — recording that would poison every offset and
      // ping-pong the window forever. Unmounted rows are unobserved in syncObservedRows; the
      // guard covers the entry that races ahead of it.
      if (!key || !element.isConnected) continue
      const height = measureRow(element)
      if (height > 0 && measured.get(key) !== height) {
        measured.set(key, height)
        changed = true
      }
    }
    // A corrected height above the reading position would silently shift it; compensate through the anchor.
    if (changed && !isFollowing.value) restoreAnchor()
  }) : null
  const containerObserver = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(() => {
    // A resize is not a scroll: the anchor keeps describing where the member *was*, so it is
    // restored against the reflowed layout instead of re-derived from it — re-deriving would
    // adopt whatever drift the resize itself introduced and call that the reading position.
    readMetrics()
    if (!isFollowing.value) restoreAnchor()
  }) : null

  /** Observes rendered rows (browser) or measures them synchronously (jsdom fallback). */
  function syncObservedRows(): void {
    const list = listElement.value
    if (!list) return
    const rows = list.querySelectorAll<HTMLElement>('[data-virtual-key]')
    if (!rowObserver) {
      rows.forEach(row => {
        const key = row.dataset.virtualKey
        if (!key) return
        const height = measureRow(row)
        if (measured.get(key) !== height) measured.set(key, height)
      })
      return
    }
    const rendered = new Set<HTMLElement>()
    rows.forEach(row => {
      rendered.add(row)
      if (!observedRows.has(row)) {
        observedRows.add(row)
        rowObserver.observe(row)
      }
    })
    for (const row of observedRows) {
      if (!rendered.has(row)) {
        observedRows.delete(row)
        rowObserver.unobserve(row)
      }
    }
  }
  const observedRows = new Set<HTMLElement>()

  // Capture before the DOM re-renders: at flush 'sync' the scroll offset still describes the old
  // reading position and `previous` is still the old list, so the anchor records where reading was.
  // The lock keeps that anchor authoritative across the flush: any onScroll fired in between (scroll
  // events, observer callbacks) would otherwise re-derive it from the new list at the old offset.
  watch(items, (next, previous) => {
    if (isFollowing.value || previous === undefined) return
    // Capture from the live DOM (still the old list at flush 'sync'): the model's offsets for
    // rows above the viewport are estimates, and an estimated capture would survive into the
    // post-flush restore as a wrong anchor. Offset maths stays the jsdom fallback.
    pendingAnchor = anchorFromViewport() ?? anchorFromOffsets(previous, scrollTop.value)
    anchorLocked = true
  }, { flush: 'sync' })

  // After the DOM re-renders: a following reader stays glued to the end; anyone else returns to the
  // anchor — unless the mutation only appended rows below, where the reading position cannot have
  // moved and rewriting it through estimated offsets would only introduce error. Two scrollTop
  // writes in one flush render once, so a host applying its own restore after this does not flash.
  watch(items, (next, previous) => {
    const element = container.value
    if (!element) return
    try {
      if (isFollowing.value) {
        element.scrollTop = element.scrollHeight
        onScroll()
      } else if (!appendedOnly(next, previous)) {
        restoreAnchor()
      }
      syncObservedRows()
    } finally {
      anchorLocked = false
    }
  }, { flush: 'post' })

  /** True when `next` is `previous` plus rows at the end — nothing above the reading position changed. */
  function appendedOnly(next: readonly T[], previous: readonly T[] | undefined): boolean {
    if (!previous || previous.length === 0 || next.length <= previous.length) return false
    return options.keyOf(next[0]) === options.keyOf(previous[0])
      && options.keyOf(next[previous.length - 1]) === options.keyOf(previous[previous.length - 1])
  }

  // A scrollToAnchor write moves the virtual window; the rows it newly renders must be observed too,
  // or their real heights never feed the offsets and an estimated position becomes final. Neither a
  // visibleItems watcher (within one flush the computed already holds the post-scroll window while
  // the DOM still shows the old one — it would observe rows about to unmount) nor onUpdated fires
  // reliably for these window shifts, so observation is driven by the DOM itself: a MutationObserver
  // on the list element re-syncs whenever the rendered row set actually changes.
  const rowMutationObserver = typeof MutationObserver !== 'undefined' ? new MutationObserver(() => {
    syncObservedRows()
  }) : null
  watch(listElement, element => {
    rowMutationObserver?.disconnect()
    if (element) {
      rowMutationObserver?.observe(element, { childList: true })
      // Rows already in the DOM at observe time never fire the mutation observer — the host's
      // list mounts under async loading gates, long after this composable's own onMounted ran.
      syncObservedRows()
    }
  })

  // The container element arrives the same way (async gates), so it cannot be observed from
  // onMounted either; watching the ref keeps the resize compensation attached to the live element.
  watch(container, element => {
    containerObserver?.disconnect()
    if (element && containerObserver) containerObserver.observe(element)
  })

  // Same rule as containerObserver: a window resize restores the reading anchor, never re-derives.
  const onWindowResize = (): void => {
    readMetrics()
    if (!isFollowing.value) restoreAnchor()
  }

  onMounted(() => {
    onScroll()
    window.addEventListener('resize', onWindowResize)
    syncObservedRows()
  })
  onBeforeUnmount(() => {
    window.removeEventListener('resize', onWindowResize)
    rowObserver?.disconnect()
    containerObserver?.disconnect()
    rowMutationObserver?.disconnect()
    observedRows.clear()
  })

  return {
    container,
    listElement,
    visibleItems,
    topPadding,
    bottomPadding,
    isFollowing,
    onScroll,
    reset,
    currentAnchor,
    scrollToAnchor,
  }
}
