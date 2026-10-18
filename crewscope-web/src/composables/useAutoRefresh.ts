import { onUnmounted } from 'vue'

export interface AutoRefreshOptions {
  /** 两次刷新的间隔 ms。 */
  interval: number
  /**
   * 排下一轮与 tick 触发前的总闸：返回 false 就不执行、不重排。
   * 页面可见性是内置闸（{@link AutoRefreshOptions.pauseWhenHidden}），不在这里重复。
   */
  active?: () => boolean
  /** 页面隐藏时暂停，回到前台立即刷一次并恢复节奏（默认 true）。 */
  pauseWhenHidden?: boolean
  /**
   * 连续失败退避的上限 ms：每次失败间隔翻倍、成功复位；不传则恒定间隔。
   * refresh 抛出才算失败——自己吞错的刷新（页面级刷新）保持恒速；
   * 要「失败即停」就让它返回 false。
   */
  maxBackoff?: number
}

/**
 * 周期刷新的唯一实现（M12b-F03 收敛）：此前 15 秒双胞胎手写 setInterval 各一份，
 * GitHub 导入轮询 1200ms 裸 setTimeout 链不感知页面可见性。
 *
 * 生命周期：start() 排下一轮（清掉旧计时）；tick 在闸门全开时执行 refresh，
 * 返回 false 或 {@link AutoRefreshOptions.active} 变 false 即自然停摆；
 * 组件卸载时自动清计时并移除可见性监听。
 */
export function useAutoRefresh(
  refresh: () => Promise<boolean | void> | boolean | void,
  options: AutoRefreshOptions,
): { start: () => void; stop: () => void } {
  const { interval, active = () => true, pauseWhenHidden = true, maxBackoff } = options
  let timer: ReturnType<typeof setTimeout> | null = null
  let delay = interval
  let failures = 0
  let disposed = false

  function clear(): void {
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
  }

  function schedule(): void {
    if (disposed) return
    clear()
    timer = setTimeout(tick, delay)
  }

  async function tick(): Promise<void> {
    timer = null
    // 卸载是终局：清理时可清掉「已排未触发」的计时，但挂起中的 tick 完成后也不得复活。
    if (disposed) return
    if (pauseWhenHidden && document.hidden) return
    if (!active()) return
    let keepGoing: boolean | void
    try {
      keepGoing = await refresh()
      failures = 0
      delay = interval
    } catch {
      failures += 1
      delay = maxBackoff ? Math.min(interval * 2 ** failures, maxBackoff) : interval
      keepGoing = true
    }
    if (keepGoing === false) return
    if (pauseWhenHidden && document.hidden) return
    if (!active()) return
    schedule()
  }

  function onVisibilityChange(): void {
    if (document.hidden) {
      // 已排的 tick 到点也会自查可见性；这里直接清掉省一次空转。
      clear()
    } else if (pauseWhenHidden) {
      // 回到前台：立即刷一次并恢复节奏。
      clear()
      void tick()
    }
  }

  document.addEventListener('visibilitychange', onVisibilityChange)
  onUnmounted(() => {
    disposed = true
    clear()
    document.removeEventListener('visibilitychange', onVisibilityChange)
  })

  return {
    start: schedule,
    stop: clear,
  }
}
