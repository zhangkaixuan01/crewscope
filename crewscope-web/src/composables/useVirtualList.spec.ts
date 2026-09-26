import { computed, defineComponent, h, nextTick } from 'vue'
import { mount, type DOMWrapper, type VueWrapper } from '@vue/test-utils'
import { useVirtualList, type VirtualListResult } from './useVirtualList'

/**
 * M9b-F03 reading list: plain rendering below the threshold, measured virtualisation above it, an
 * anchor that keeps the reading position stable across prepends and mode switches, and the ≤80px
 * follow rule for appended rows. jsdom has no layout, so heights come from data-test-height and
 * scroll metrics from defineProperty mocks — the composable itself is pure maths over both.
 */
interface Row { id: string; height: number }

let active: VirtualListResult<Row>

const Host = defineComponent({
  props: {
    items: { type: Array as () => Row[], required: true },
    gap: { type: Number, default: 0 },
  },
  setup(props) {
    active = useVirtualList(computed(() => props.items), {
      keyOf: row => row.id,
      estimate: 100,
      rowGap: props.gap,
      measureRow: element => Number(element.dataset.testHeight ?? 100),
    })
    return () => h('div', {
      ref: (element: unknown) => { active.container.value = element instanceof HTMLElement ? element : null },
      onScroll: () => active.onScroll(),
    }, [
      h('ol', { ref: (element: unknown) => { active.listElement.value = element instanceof HTMLElement ? element : null } }, [
        ...(active.topPadding.value > 0
          ? [h('li', { class: 'spacer-top', style: { height: `${active.topPadding.value}px` } })]
          : []),
        ...active.visibleItems.value.map(row => h('li', {
          key: row.id,
          'data-virtual-key': row.id,
          'data-test-height': String(row.height),
        })),
        ...(active.bottomPadding.value > 0
          ? [h('li', { class: 'spacer-bottom', style: { height: `${active.bottomPadding.value}px` } })]
          : []),
      ]),
    ])
  },
})

function rows(count: number, height = 100): Row[] {
  return Array.from({ length: count }, (_, index) => ({ id: `m-${index}`, height }))
}

/** jsdom reports 0 for every layout box; the scroll maths read real properties, so mock them. */
function mockScrollMetrics(element: HTMLElement, metrics: { scrollTop: number; clientHeight: number; scrollHeight: number }): void {
  const state = { scrollTop: metrics.scrollTop, scrollHeight: metrics.scrollHeight }
  Object.defineProperty(element, 'scrollTop', {
    get: () => state.scrollTop,
    set: (value: number) => { state.scrollTop = value },
    configurable: true,
  })
  Object.defineProperty(element, 'clientHeight', { get: () => metrics.clientHeight, configurable: true })
  Object.defineProperty(element, 'scrollHeight', { get: () => state.scrollHeight, configurable: true })
}

async function mountHost(items: Row[], metrics: { scrollTop: number; clientHeight: number; scrollHeight: number }) {
  const wrapper = mount(Host, { props: { items } })
  await nextTick()
  const element = wrapper.element as HTMLElement
  mockScrollMetrics(element, metrics)
  element.dispatchEvent(new Event('scroll'))
  await nextTick()
  return { wrapper, element }
}

function renderedRows(wrapper: VueWrapper): DOMWrapper<Element>[] {
  return wrapper.findAll('li[data-virtual-key]')
}

describe('useVirtualList', () => {
  it('renders every row plain below the threshold, without spacers', async () => {
    const { wrapper } = await mountHost(rows(80), { scrollTop: 0, clientHeight: 400, scrollHeight: 8000 })
    expect(renderedRows(wrapper)).toHaveLength(80)
    expect(wrapper.find('li.spacer-top').exists()).toBe(false)
    expect(wrapper.find('li.spacer-bottom').exists()).toBe(false)
  })

  it('switches to windowed rendering past the threshold', async () => {
    const { wrapper } = await mountHost(rows(81), { scrollTop: 0, clientHeight: 400, scrollHeight: 8100 })
    // ceil(400/100) + 2*8 = 20 rows in the window; the rest is bottom spacer.
    expect(renderedRows(wrapper)).toHaveLength(20)
    expect(wrapper.get('li.spacer-bottom').attributes('style')).toContain('height: 6100px')
    expect(wrapper.find('li.spacer-top').exists()).toBe(false)
  })

  it('measures rendered rows instead of trusting the estimate', async () => {
    // estimate 100 vs real 60: after the window renders and is measured, offsets use 60 per row.
    const wrapper = mount(Host, { props: { items: rows(200, 60) } })
    await nextTick()
    const element = wrapper.element as HTMLElement
    mockScrollMetrics(element, { scrollTop: 0, clientHeight: 400, scrollHeight: 12000 })
    element.dispatchEvent(new Event('scroll'))
    await nextTick()
    // minRow becomes 60 → count = ceil(400/60) + 16 = 23; bottom padding covers the rest at 60px.
    expect(renderedRows(wrapper)).toHaveLength(23)
    expect(wrapper.get('li.spacer-bottom').attributes('style')).toContain('height: 10620px')
  })

  it('keeps the reading anchor stable when older history is prepended', async () => {
    const items = rows(100)
    const { wrapper, element } = await mountHost(items, { scrollTop: 1000, clientHeight: 400, scrollHeight: 10000 })
    expect(active.isFollowing.value).toBe(false)
    const before = renderedRows(wrapper)[0].attributes('data-virtual-key')
    await wrapper.setProps({ items: [...rows(5), ...items] })
    await nextTick()
    // The first visible row was m-10 (top at 1000); five 100px rows above shift it to 1500.
    expect(element.scrollTop).toBe(1500)
    expect(renderedRows(wrapper)[0].attributes('data-virtual-key')).toBe(before)
  })

  it('keeps the anchor when the list crosses the plain threshold', async () => {
    const items = rows(80)
    const { wrapper, element } = await mountHost(items, { scrollTop: 3000, clientHeight: 400, scrollHeight: 8000 })
    await wrapper.setProps({ items: [...items, { id: 'm-new', height: 100 }] })
    await nextTick()
    expect(element.scrollTop).toBe(3000)
    expect(renderedRows(wrapper).length).toBeLessThanOrEqual(81)
  })

  it('follows appended messages only while within the 80px threshold', async () => {
    const items = rows(100)
    const nearBottom = await mountHost(items, { scrollTop: 9600, clientHeight: 400, scrollHeight: 10000 })
    expect(nearBottom.element.scrollTop).toBe(9600)
    expect(active.isFollowing.value).toBe(true)
    await nearBottom.wrapper.setProps({ items: [...items, { id: 'm-append', height: 100 }] })
    await nextTick()
    expect(nearBottom.element.scrollTop).toBe(10000)

    const readingHistory = await mountHost([...rows(100)], { scrollTop: 5000, clientHeight: 400, scrollHeight: 10000 })
    expect(active.isFollowing.value).toBe(false)
    await readingHistory.wrapper.setProps({ items: [...readingHistory.wrapper.props('items'), { id: 'm-append', height: 100 }] })
    await nextTick()
    // Reading history: appended rows arrive below — the reading position must not move at all.
    expect(readingHistory.element.scrollTop).toBe(5000)
    expect(active.isFollowing.value).toBe(false)
  })

  it('exposes the current anchor and restores to a persisted one', async () => {
    const items = rows(100)
    await mountHost(items, { scrollTop: 2300, clientHeight: 400, scrollHeight: 10000 })
    expect(active.currentAnchor()).toEqual({ key: 'm-23', offsetWithin: 0 })

    expect(active.scrollToAnchor({ key: 'm-40', offsetWithin: -50 })).toBe(true)
    // scrollTop = rowTop − offsetWithin: m-40's top is 4000 and −50 means the row starts 50px
    // above the viewport top, so the viewport top lands 50px past the row's own top.
    expect(active.container.value!.scrollTop).toBe(4050)
    expect(active.scrollToAnchor({ key: 'gone', offsetWithin: 0 })).toBe(false)
  })

  it('counts the layout row gap in offsets and spacers', async () => {
    const wrapper = mount(Host, { props: { items: rows(200, 60), gap: 16 } })
    await nextTick()
    const element = wrapper.element as HTMLElement
    mockScrollMetrics(element, { scrollTop: 1160, clientHeight: 400, scrollHeight: 15200 })
    element.dispatchEvent(new Event('scroll'))
    await nextTick()
    // Row i's top is i×(h+g): offset 1160 lands on row 15 (top 1140), so the window starts at
    // 15-8=7 and the top spacer covers 7 rows plus the gaps above the first row, minus the one
    // gap the grid puts between the spacer and the first row itself: 7×60+6×16.
    expect(wrapper.get('li.spacer-top').attributes('style')).toContain('height: 516px')
  })

  it('never writes a reading anchor into the follow zone', async () => {
    const items = rows(100)
    await mountHost(items, { scrollTop: 9600, clientHeight: 400, scrollHeight: 10000 })
    // m-99's top is 9900, but the follow zone starts at 9519 — the write is clamped one threshold
    // below the maximum so an estimate overshoot cannot silently flip the list into following.
    expect(active.scrollToAnchor({ key: 'm-99', offsetWithin: 0 })).toBe(true)
    expect(active.container.value!.scrollTop).toBe(9519)
    expect(active.isFollowing.value).toBe(false)
  })

  it('reset returns to the top and clears the pending anchor', async () => {
    const items = rows(100)
    const { element } = await mountHost(items, { scrollTop: 5000, clientHeight: 400, scrollHeight: 10000 })
    expect(active.isFollowing.value).toBe(false)
    active.reset()
    expect(element.scrollTop).toBe(0)
    expect(active.isFollowing.value).toBe(true)
    expect(active.currentAnchor()?.key).toBe('m-0')
  })
})
