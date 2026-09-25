import { readonly, ref, type App } from 'vue'
import { CrewScopeApiError } from '../api/client'

const currentMessage = ref<string | null>(null)

export const globalErrorMessage = readonly(currentMessage)

export function reportGlobalError(error: unknown): void {
  if (error instanceof CrewScopeApiError && error.envelope.code === 'secure_random_unavailable') {
    currentMessage.value = error.envelope.message
    return
  }
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
