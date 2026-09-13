import { readonly, ref } from 'vue'

export interface ConfirmRequest { id: number; title: string; description?: string; confirmLabel: string; cancelLabel: string; danger: boolean; resolve: (value: boolean) => void }
const current = ref<ConfirmRequest | null>(null)
const queue: Array<{ options: Omit<ConfirmRequest, 'id' | 'resolve'>; resolve: (value: boolean) => void }> = []
let nextId = 1

/** Returns a Promise backed by the shared confirmation surface. */
export function useConfirm() {
  function confirm(options: Omit<ConfirmRequest, 'id' | 'resolve'>): Promise<boolean> {
    return new Promise(resolve => {
      const request = { ...options, id: nextId++, resolve }
      if (current.value) queue.push({ options, resolve })
      else current.value = request
    })
  }
  function settle(value: boolean): void {
    const request = current.value
    current.value = null
    request?.resolve(value)
    const next = queue.shift()
    if (next) current.value = { ...next.options, id: nextId++, resolve: next.resolve }
  }
  return { request: readonly(current), confirm, settle }
}
