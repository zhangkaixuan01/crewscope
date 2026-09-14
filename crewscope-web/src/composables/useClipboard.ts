import { ref } from 'vue'

/** Copies identifiers while retaining a text-selection fallback for restricted browsers. */
export function useClipboard() {
  const copied = ref<string | null>(null)
  async function copy(value: string, key = value): Promise<boolean> {
    try {
      if (navigator.clipboard?.writeText) await navigator.clipboard.writeText(value)
      else {
        const input = document.createElement('textarea')
        input.value = value; input.style.position = 'fixed'; input.style.opacity = '0'
        document.body.appendChild(input); input.select()
        const success = document.execCommand('copy')
        input.remove()
        if (!success) return false
      }
      copied.value = key
      window.setTimeout(() => { if (copied.value === key) copied.value = null }, 1500)
      return true
    } catch { return false }
  }
  return { copied, copy }
}
