import { mount, type VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import RelativeTime from './RelativeTime.vue'

/**
 * R32: the relative label must be alive — it rolls over on a bounded 60s tick, pauses while the
 * tab is hidden, and recomputes the instant the tab returns instead of thawing at a stale label.
 */

// jsdom keeps `document.hidden` false and read-only; flipping the property + dispatching the
// event is the standard way to exercise visibility-driven code.
function setHidden(hidden: boolean): void {
  Object.defineProperty(document, 'hidden', { value: hidden, configurable: true })
  document.dispatchEvent(new Event('visibilitychange'))
}

// Every mounted instance keeps a document-level visibilitychange listener; without unmounting
// them, earlier cases would answer later cases' visibility flips with stray 60s ticks.
const mounted: VueWrapper[] = []
function mountTime(options: { props: { value: string | number | Date } }): VueWrapper {
  const wrapper = mount(RelativeTime, options)
  mounted.push(wrapper)
  return wrapper
}

describe('RelativeTime', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => {
    mounted.splice(0).forEach(wrapper => wrapper.unmount())
    setHidden(false)
    vi.useRealTimers()
  })

  it('renders a machine-readable <time> with the absolute instant one hover away', async () => {
    vi.setSystemTime(new Date('2026-09-26T09:30:40'))
    const wrapper = mountTime({ props: { value: new Date('2026-09-26T09:30:10') } })

    const time = wrapper.get('time')
    expect(time.text()).toBe('刚刚')
    expect(time.attributes('datetime')).toBe(new Date('2026-09-26T09:30:10').toISOString())

    // The template opens with an HTML comment, so the component root is a fragment whose first
    // node is that comment — wrapper.element is the comment and trigger() on it goes nowhere.
    // The hover listener lives on the BaseTooltip root span; trigger there.
    await wrapper.get('.relative-time').trigger('mouseenter')
    expect(wrapper.get('[role="tooltip"]').text()).toContain('2026/09/26')
    expect(wrapper.get('[role="tooltip"]').text()).toContain('09:30:10')
  })

  it('accepts string, number and Date inputs the same way', () => {
    vi.setSystemTime(new Date('2026-09-26T09:30:00'))
    const iso = '2026-09-26T09:28:00Z'
    for (const value of [iso, new Date(iso).getTime(), new Date(iso)]) {
      const wrapper = mountTime({ props: { value } })
      expect(wrapper.get('time').attributes('datetime')).toBe(new Date(iso).toISOString())
    }
  })

  it('says 时间未知 for an unparsable value instead of inventing a date', () => {
    const wrapper = mountTime({ props: { value: 'not-a-date' } })
    expect(wrapper.get('time').text()).toBe('时间未知')
    expect(wrapper.get('time').attributes('datetime')).toBe('')
  })

  it('rolls the label over on the 60s tick while the tab is visible', async () => {
    vi.setSystemTime(new Date('2026-09-26T09:30:00'))
    const wrapper = mountTime({ props: { value: Date.now() } })
    expect(wrapper.get('time').text()).toBe('刚刚')

    // The clock only moves by advancing the (fake) timers — setSystemTime and the tick queue
    // are the same clock, so pushing both would double-count the elapsed time.
    vi.advanceTimersByTime(120_000)
    await nextTick()
    expect(wrapper.get('time').text()).toBe('2 分钟前')
  })

  it('freezes while hidden and recomputes immediately on return, even across midnight', async () => {
    vi.setSystemTime(new Date('2026-09-26T22:00:00'))
    const wrapper = mountTime({ props: { value: Date.now() } })
    expect(wrapper.get('time').text()).toBe('刚刚')

    setHidden(true)
    vi.setSystemTime(new Date('2026-09-27T01:30:00'))
    // No tick may fire while hidden — if the interval leaked, this advance would run it and
    // the label would silently catch up on its own.
    vi.advanceTimersByTime(1_000)
    expect(wrapper.get('time').text()).toBe('刚刚')

    // Becoming visible recomputes right away instead of waiting for the next tick boundary.
    setHidden(false)
    await nextTick()
    expect(wrapper.get('time').text()).toBe('3 小时前')
  })

  it('never starts a tick while hidden and cleans the timer up on unmount', () => {
    vi.setSystemTime(new Date('2026-09-26T09:30:00'))
    // Mounting a component tree registers timers of its own; the 60s interval signature is what
    // uniquely belongs to RelativeTime, so filter for it instead of counting raw calls.
    const startSpy = vi.spyOn(globalThis, 'setInterval')
    const stopSpy = vi.spyOn(globalThis, 'clearInterval')
    const sixtySecondTicks = () => startSpy.mock.calls.filter(([, interval]) => interval === 60_000).length

    setHidden(true)
    const hiddenWrapper = mount(RelativeTime, { props: { value: Date.now() } })
    expect(sixtySecondTicks()).toBe(0)

    // Returning to visible starts exactly one tick per live instance: the recovery of the
    // first one plus the fresh mount of the second.
    setHidden(false)
    const wrapper = mount(RelativeTime, { props: { value: Date.now() } })
    expect(sixtySecondTicks()).toBe(2)
    hiddenWrapper.unmount()
    wrapper.unmount()
    // Both live ticks are released on unmount by the same stop() the hidden path exercised.
    expect(stopSpy).toHaveBeenCalled()
  })
})
