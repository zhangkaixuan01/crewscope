import { computed, ref, watch, type Ref } from 'vue'
import { createFormCommandKeys } from '../api/formCommandKeys'
import { useDirtyForm } from './useDirtyForm'

/**
 * Inline "edit → save → exit" editor lifecycle for settings cards (M12b-F04): the
 * editing/submitted state machine, dirty tracking against the server value, the
 * idempotent attempt key (reused for an unchanged retry, dropped when the store
 * rejects the send) and the success/reset watches. Field mapping stays at the page —
 * only the payload coordinate is shared.
 */
export function useInlineSettingEditor<T>(options: {
  /** Canonical payload coordinate; drives dirty tracking and the server-clean baseline. */
  payload: () => T
  /** Server-side value of the same coordinate (resets the fields while not editing). */
  serverValue: () => T | null | undefined
  /** Maps a server value into the page's input refs (domain field mapping). */
  applyServerValue: (value: T | null) => void
  /** Submit validation gate. */
  valid: () => boolean
  /** Source readiness gate (the read resource reached 'ready'). */
  ready: () => boolean
  /** Command phase from the store. */
  phase: () => string
  clearCommand: () => void
  /** Performs the store update with the attempt key; reports success. */
  save: (key: string) => Promise<boolean>
  /** Idempotency fingerprint — input changes that make a new logical request. */
  fingerprint?: () => readonly unknown[]
}) {
  const payloadRef: Ref<T> = computed(options.payload)
  const intentKeys = createFormCommandKeys()
  const attemptKey = ref('')
  const editing = ref(false)
  const submitted = ref(false)
  const dirtyForm = useDirtyForm(payloadRef)

  watch(payloadRef, value => {
    if (!editing.value) return
    dirtyForm.sync(value)
  })

  watch(options.phase, phase => {
    if (phase !== 'success') return
    editing.value = false
    submitted.value = false
    // Field reset rides the serverValue watch (the store just refreshed the value);
    // this watch only re-baselines the dirty coordinate.
    dirtyForm.markClean(options.serverValue() ?? null)
  })

  watch(options.serverValue, value => {
    if (editing.value || value == null) return
    options.applyServerValue(value)
    dirtyForm.markClean(value)
  })

  function startEditing(): void {
    editing.value = true
    submitted.value = false
    // Entering the editor retires the previous save's status line (F01 precedent).
    options.clearCommand()
    const current = options.serverValue() ?? null
    options.applyServerValue(current)
    dirtyForm.markClean(current)
  }

  function cancelEditing(): void {
    editing.value = false
    submitted.value = false
    const current = options.serverValue() ?? null
    options.applyServerValue(current)
    attemptKey.value = ''
    dirtyForm.markClean(current)
  }

  async function submit(): Promise<void> {
    submitted.value = true
    if (!options.valid()) return
    if (!options.ready()) return
    attemptKey.value = intentKeys.forInput(options.fingerprint ? options.fingerprint() : [payloadRef.value])
    const ok = await options.save(attemptKey.value)
    if (!ok) attemptKey.value = ''
  }

  return {
    editing,
    submitted,
    dirtyForm,
    startEditing,
    cancelEditing,
    submit,
  }
}
