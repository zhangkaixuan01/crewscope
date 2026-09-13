import { readonly, ref } from 'vue'

export interface ToastAction { label: string; onClick: () => void | Promise<void> }
export interface ToastMessage { id: number; message: string; tone: 'info' | 'success' | 'warning' | 'danger'; duration: number; action?: ToastAction }

const messages = ref<ToastMessage[]>([])
let nextId = 1

/** Provides the single application-wide transient feedback queue. */
export function useToast() {
  function dismiss(id: number): void { messages.value = messages.value.filter(item => item.id !== id) }
  function show(message: string, options: Partial<Omit<ToastMessage, 'id' | 'message'>> = {}): number {
    const id = nextId++
    const toast: ToastMessage = { id, message, tone: 'info', duration: 4_000, ...options }
    messages.value = [...messages.value, toast]
    if (toast.duration > 0) window.setTimeout(() => dismiss(id), toast.duration)
    return id
  }
  return { toasts: readonly(messages), show, dismiss }
}
