import { nextTick, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { useVirtualList } from './useVirtualList'

describe('useVirtualList', () => {
  it('keeps the full list available before a viewport is measured', () => {
    const items = ref(Array.from({ length: 3 }, (_, index) => index))
    const virtual = useVirtualList(items, 100)
    expect(virtual.visibleItems.value).toEqual([0, 1, 2])
  })

  it('renders only a bounded window after scrolling', async () => {
    const items = ref(Array.from({ length: 5000 }, (_, index) => index))
    const virtual = useVirtualList(items, 100, 2)
    const host = mount({ setup: () => ({ virtual }), template: '<div style="height: 400px; overflow:auto" />' })
    const element = host.find('div').element as HTMLElement
    virtual.container.value = element
    Object.defineProperty(element, 'clientHeight', { configurable: true, value: 400 })
    element.scrollTop = 120000
    virtual.onScroll()
    await nextTick()
    expect(virtual.visibleItems.value.length).toBeLessThan(20)
    expect(virtual.visibleItems.value[0]).toBeGreaterThan(1000)
    expect(virtual.topPadding.value).toBeGreaterThan(100000)
  })
})
