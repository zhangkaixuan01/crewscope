import { readonly, ref } from 'vue'

export interface ToastAction { label: string; onClick: () => void | Promise<void> }
export interface ToastMessage { id: number; message: string; tone: 'info' | 'success' | 'warning' | 'danger'; duration: number; action?: ToastAction }

interface ToastTimer { handle: number; remaining: number; startedAt: number }

const messages = ref<ToastMessage[]>([])
const timers = new Map<number, ToastTimer>()
let nextId = 1

/** Provides the single application-wide transient feedback queue. */
export function useToast() {
  function dismiss(id: number): void {
    const timer = timers.get(id)
    if (timer) window.clearTimeout(timer.handle)
    timers.delete(id)
    messages.value = messages.value.filter(item => item.id !== id)
  }

  function arm(id: number, remaining: number): void {
    timers.set(id, { handle: window.setTimeout(() => dismiss(id), remaining), remaining, startedAt: Date.now() })
  }

  function show(message: string, options: Partial<Omit<ToastMessage, 'id' | 'message'>> = {}): number {
    const id = nextId++
    const toast: ToastMessage = { id, message, tone: 'info', duration: 4_000, ...options }
    messages.value = [...messages.value, toast]
    if (toast.duration > 0) arm(id, toast.duration)
    return id
  }

  /**
   * M13b-F11（I09）：hover/focus 暂停倒计时。撤销只活在 toast 的自动消失窗口里，指针停留或键盘
   * 焦点进入时窗口不应继续走完；移开后从剩余时长续走。duration 0（不自动消失）无计时器，天然不暂停。
   */
  function pause(id: number): void {
    const timer = timers.get(id)
    if (!timer || timer.handle === 0) return
    window.clearTimeout(timer.handle)
    const remaining = Math.max(0, timer.remaining - (Date.now() - timer.startedAt))
    timers.set(id, { handle: 0, remaining, startedAt: 0 })
  }

  function resume(id: number): void {
    const timer = timers.get(id)
    if (!timer || timer.handle !== 0) return
    arm(id, timer.remaining)
  }

  return { toasts: readonly(messages), show, dismiss, pause, resume }
}
