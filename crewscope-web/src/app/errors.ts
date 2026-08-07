import { readonly, ref, type App } from 'vue'

const currentMessage = ref<string | null>(null)

export const globalErrorMessage = readonly(currentMessage)

export function reportGlobalError(error: unknown): void {
  currentMessage.value = error instanceof Error
    ? '页面遇到未预期问题，请重试；若问题持续，请联系团队管理员。'
    : '页面遇到未预期问题。'
}

export function clearGlobalError(): void {
  currentMessage.value = null
}

/** Normalizes unhandled Vue and Promise failures without displaying internal exception details. */
export function installGlobalErrorHandling(app: App): void {
  app.config.errorHandler = reportGlobalError
  window.addEventListener('unhandledrejection', (event) => {
    event.preventDefault()
    reportGlobalError(event.reason)
  })
}
