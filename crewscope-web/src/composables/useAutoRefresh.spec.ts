import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAutoRefresh } from './useAutoRefresh'

/**
 * 每条都对应一个迁移前手工实现的坑：15 秒双胞胎的闸门/可见性舞步、GitHub 轮询的失败即停。
 * 宿主组件挂载是为了让 onUnmounted 的清理真正跑起来。
 */
const mounted: ReturnType<typeof mount>[] = []

function mountTimer(options: Parameters<typeof useAutoRefresh>[1], refresh: () => Promise<boolean | void> | boolean | void): { start: () => void; stop: () => void } {
  let api: { start: () => void; stop: () => void } | null = null
  const host = defineComponent({
    setup() {
      api = useAutoRefresh(refresh, options)
      return () => h('div')
    },
  })
  const wrapper = mount(host, { attachTo: document.body })
  mounted.push(wrapper)
  return api!
}

beforeEach(() => {
  vi.useFakeTimers()
  document.body.innerHTML = ''
})
afterEach(() => {
  mounted.splice(0).forEach(wrapper => wrapper.unmount())
  vi.useRealTimers()
})

describe('useAutoRefresh', () => {
  it('ticks at the interval and keeps rescheduling', async () => {
    const refresh = vi.fn()
    const timer = mountTimer({ interval: 15_000 }, refresh)
    timer.start()

    await vi.advanceTimersByTimeAsync(15_000)
    expect(refresh).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(15_000)
    expect(refresh).toHaveBeenCalledTimes(2)
  })

  it('stops rescheduling when active() turns false, and start() re-arms it', async () => {
    let gate = true
    const refresh = vi.fn()
    const timer = mountTimer({ interval: 1_000, active: () => gate }, refresh)
    timer.start()

    await vi.advanceTimersByTimeAsync(1_000)
    expect(refresh).toHaveBeenCalledTimes(1)
    gate = false
    await vi.advanceTimersByTimeAsync(5_000)
    // 闸门关掉：tick 到点不执行，之后不再重排。
    expect(refresh).toHaveBeenCalledTimes(1)

    gate = true
    timer.start()
    await vi.advanceTimersByTimeAsync(1_000)
    expect(refresh).toHaveBeenCalledTimes(2)
  })

  it('stops for good when refresh resolves false — the GitHub "stop on failure" contract', async () => {
    const refresh = vi.fn().mockResolvedValue(false)
    const timer = mountTimer({ interval: 1_200 }, refresh)
    timer.start()

    await vi.advanceTimersByTimeAsync(1_200)
    expect(refresh).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(10_000)
    expect(refresh).toHaveBeenCalledTimes(1)
  })

  it('stop() cancels the pending tick', async () => {
    const refresh = vi.fn()
    const timer = mountTimer({ interval: 1_000 }, refresh)
    timer.start()
    timer.stop()

    await vi.advanceTimersByTimeAsync(5_000)
    expect(refresh).not.toHaveBeenCalled()
  })

  it('pauses while hidden and refreshes immediately on return', async () => {
    const refresh = vi.fn()
    const timer = mountTimer({ interval: 15_000 }, refresh)
    timer.start()

    vi.spyOn(document, 'hidden', 'get').mockReturnValue(true)
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(60_000)
    // 隐藏期间一个 tick 都不该发。
    expect(refresh).not.toHaveBeenCalled()

    vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
    document.dispatchEvent(new Event('visibilitychange'))
    // 回前台立即刷一次（不等满一个间隔），随后恢复节奏。
    expect(refresh).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(15_000)
    expect(refresh).toHaveBeenCalledTimes(2)
  })

  it('doubles the delay per failure up to maxBackoff and resets on success', async () => {
    let calls = 0
    const refresh = vi.fn().mockImplementation(() => {
      calls += 1
      if (calls <= 2) throw new Error('network down')
      return undefined
    })
    const timer = mountTimer({ interval: 1_000, maxBackoff: 5_000 }, refresh)
    timer.start()

    await vi.advanceTimersByTimeAsync(1_000)
    expect(refresh).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1_000)
    // 第一次失败后间隔翻倍到 2s——1s 时不应触发。
    expect(refresh).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1_000)
    expect(refresh).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(4_000)
    // 第二次失败后间隔 4s。
    expect(refresh).toHaveBeenCalledTimes(3)
    await vi.advanceTimersByTimeAsync(1_000)
    // 成功复位回 1s 节奏。
    expect(refresh).toHaveBeenCalledTimes(4)
  })

  it('never re-arms after unmount even when a refresh was in flight', async () => {
    let release: (value: boolean | void) => void = () => {}
    const refresh = vi.fn().mockImplementation(() => new Promise<boolean | void>(resolve => { release = resolve }))
    let api: { start: () => void; stop: () => void } | null = null
    const host = defineComponent({
      setup() {
        api = useAutoRefresh(refresh, { interval: 1_000 })
        return () => h('div')
      },
    })
    const wrapper = mount(host, { attachTo: document.body })
    api!.start()
    await vi.advanceTimersByTimeAsync(1_000)
    expect(refresh).toHaveBeenCalledTimes(1)

    wrapper.unmount()
    release(undefined)
    await vi.advanceTimersByTimeAsync(10_000)
    // 在途 tick 完成后不得复活计时器（GitHub unmount 轮询契约）。
    expect(refresh).toHaveBeenCalledTimes(1)
  })

  it('clears the timer and the listener on unmount', async () => {
    const refresh = vi.fn()
    let api: { start: () => void; stop: () => void } | null = null
    const host = defineComponent({
      setup() {
        api = useAutoRefresh(refresh, { interval: 1_000 })
        return () => h('div')
      },
    })
    const wrapper = mount(host, { attachTo: document.body })
    api!.start()
    wrapper.unmount()

    await vi.advanceTimersByTimeAsync(10_000)
    expect(refresh).not.toHaveBeenCalled()

    // 监听器已移除：回前台不会再触发刷新。
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
    document.dispatchEvent(new Event('visibilitychange'))
    expect(refresh).not.toHaveBeenCalled()
  })
})
