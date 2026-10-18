import { ref, watch } from 'vue'
import { createFormCommandKeys } from '../api/formCommandKeys'

/**
 * Shared idempotent command lifecycle for the form-command dialogs (M12b-F04).
 *
 * The dialogs run the same protocol verbatim: editing any command input creates a new
 * logical request, an unchanged retry reuses its key, and a key-generation failure
 * surfaces as a spoken error without sending. The fingerprint getter declares what counts
 * as "the command inputs"; `onEdit` carries the per-dialog side effects (submitted-flag
 * reset, dirty marking) that stay domain territory.
 */
export function useIdempotentCommand(fingerprint: () => readonly unknown[], onEdit?: () => void) {
  const submissionError = ref<string | null>(null)
  const intentKeys = createFormCommandKeys()
  let attemptKey = ''

  watch(fingerprint, () => {
    attemptKey = ''
    onEdit?.()
  })

  /** Mint (or reuse) the attempt key for the given inputs; null means the command must not send. */
  function keyFor(inputs: readonly unknown[]): string | null {
    submissionError.value = null
    try {
      attemptKey = intentKeys.forInput(inputs)
      return attemptKey
    } catch (error) {
      submissionError.value = error instanceof Error ? error.message : '无法生成安全操作标识，本次操作尚未发送。'
      return null
    }
  }

  /** Drop the current attempt (close guard, post-receipt reset); the next send mints a fresh one. */
  function clearAttempt(): void {
    attemptKey = ''
    intentKeys.clear()
  }

  return { submissionError, keyFor, clearAttempt }
}
